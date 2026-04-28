"""End-to-end greedy translation using only ORT + ORT-extensions ops.

This is the reference for the Kotlin port: every step here corresponds to a
step we'll do in Translator.kt on Android.
"""
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

MODEL_DIR = Path("models/opus-mt-en-zh-onnx-int8")
DECODER_START_TOKEN_ID = 65000
EOS_TOKEN_ID = 0
NUM_LAYERS = 6
MAX_NEW_TOKENS = 128


def make_session(name: str) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())
    return ort.InferenceSession(
        str(MODEL_DIR / name),
        sess_options=so,
        providers=["CPUExecutionProvider"],
    )


tok_sess = make_session("tokenizer.onnx")
enc_sess = make_session("encoder_model.onnx")
dec_sess = make_session("decoder_model.onnx")
dec_past_sess = make_session("decoder_with_past_model.onnx")
detok_sess = make_session("detokenizer.onnx")


def translate(text: str) -> str:
    # 1. Tokenize source.
    tok_out = tok_sess.run(None, {"input_text": np.array([text], dtype=object)})
    input_ids = tok_out[0].reshape(1, -1)                              # [1, src_len], already int64
    attention_mask = np.ones_like(input_ids, dtype=np.int64)          # [1, src_len]

    # 2. Encode.
    encoder_hidden_states = enc_sess.run(None, {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
    })[0]                                                              # [1, src_len, 512]

    # 3. First decode step uses the full decoder.
    dec_out = dec_sess.run(None, {
        "encoder_attention_mask": attention_mask,
        "input_ids": np.array([[DECODER_START_TOKEN_ID]], dtype=np.int64),
        "encoder_hidden_states": encoder_hidden_states,
    })
    # Output order matches the schema we inspected:
    # [logits, present.0.dec.k, present.0.dec.v, present.0.enc.k, present.0.enc.v, ..., layer 5]
    logits = dec_out[0]                                                # [1, 1, 65001]
    next_token = int(np.argmax(logits[0, -1]))
    generated = [next_token]
    if next_token == EOS_TOKEN_ID:
        return ""

    # Reorganize caches per layer.
    # decoder_model output: 1 + 6*4 = 25 tensors.
    # past_key_values feed for decoder_with_past expected ordering:
    #   for L in 0..5: dec.k, dec.v, enc.k, enc.v
    past_kv = dec_out[1:1 + NUM_LAYERS * 4]  # already in this exact ordering

    # 4. Subsequent decode steps use decoder_with_past.
    for _ in range(MAX_NEW_TOKENS - 1):
        feed = {
            "encoder_attention_mask": attention_mask,
            "input_ids": np.array([[next_token]], dtype=np.int64),
        }
        for i in range(NUM_LAYERS):
            feed[f"past_key_values.{i}.decoder.key"]   = past_kv[i*4 + 0]
            feed[f"past_key_values.{i}.decoder.value"] = past_kv[i*4 + 1]
            feed[f"past_key_values.{i}.encoder.key"]   = past_kv[i*4 + 2]
            feed[f"past_key_values.{i}.encoder.value"] = past_kv[i*4 + 3]

        out = dec_past_sess.run(None, feed)
        logits = out[0]
        next_token = int(np.argmax(logits[0, -1]))
        if next_token == EOS_TOKEN_ID:
            break
        generated.append(next_token)

        # Update only decoder cache (encoder cache stays constant).
        new_dec = out[1:]  # 12 tensors: present.{0..5}.decoder.{key,value}
        for i in range(NUM_LAYERS):
            past_kv[i*4 + 0] = new_dec[i*2 + 0]
            past_kv[i*4 + 1] = new_dec[i*2 + 1]

    # 5. Detokenize.
    text_out = detok_sess.run(None, {"token_ids": np.array(generated, dtype=np.int64)})[0]
    return str(text_out[0])


samples = [
    "Hello, how are you today?",
    "The quick brown fox jumps over the lazy dog.",
    "Speech recognition technology has improved dramatically in recent years.",
    "I would like to order a cup of coffee, please.",
    "The conference will start at 9 AM tomorrow morning.",
]

for s in samples:
    t0 = time.perf_counter()
    out = translate(s)
    dt = (time.perf_counter() - t0) * 1000
    print(f"[{dt:6.0f} ms] {s}")
    print(f"          → {out}")
    print()
