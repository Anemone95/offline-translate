"""Debug: compare tokenizer / encoder / first-step decoder between transformers and our manual ORT pipeline."""
from pathlib import Path
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path
from transformers import AutoTokenizer
from optimum.onnxruntime import ORTModelForSeq2SeqLM

MODEL_DIR = Path("models/opus-mt-en-zh-onnx-int8")
TEXT = "Hello, how are you today?"

# Reference path (transformers / optimum)
ref_tok = AutoTokenizer.from_pretrained(MODEL_DIR)
ref_inputs = ref_tok(TEXT, return_tensors="np")
print("REF input_ids:    ", ref_inputs["input_ids"].tolist())
print("REF attn_mask:    ", ref_inputs["attention_mask"].tolist())

# Our path: tokenizer.onnx
so = ort.SessionOptions(); so.register_custom_ops_library(get_library_path())
tok_sess = ort.InferenceSession(str(MODEL_DIR / "tokenizer.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
ours = tok_sess.run(None, {"input_text": np.array([TEXT], dtype=object)})
print("OURS tokens:      ", ours[0].tolist())
print("OURS indices:     ", ours[1].tolist())

# Encoder check
ref_model = ORTModelForSeq2SeqLM.from_pretrained(MODEL_DIR, use_cache=True)
ref_enc = ref_model.encoder(input_ids=ref_inputs["input_ids"], attention_mask=ref_inputs["attention_mask"])
print("\nREF encoder hidden state mean/std:", float(ref_enc.last_hidden_state.mean()), float(ref_enc.last_hidden_state.std()))

enc_sess = ort.InferenceSession(str(MODEL_DIR / "encoder_model.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
ours_input_ids = ours[0].astype(np.int64).reshape(1, -1)
ours_attn = np.ones_like(ours_input_ids, dtype=np.int64)
ours_enc = enc_sess.run(None, {"input_ids": ours_input_ids, "attention_mask": ours_attn})[0]
print("OURS encoder hidden state mean/std:", float(ours_enc.mean()), float(ours_enc.std()))
print("OURS encoder shape:", ours_enc.shape, "REF encoder shape:", ref_enc.last_hidden_state.shape)

# Decoder step 1
dec_sess = ort.InferenceSession(str(MODEL_DIR / "decoder_model.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
ours_dec = dec_sess.run(None, {
    "encoder_attention_mask": ours_attn,
    "input_ids": np.array([[65000]], dtype=np.int64),
    "encoder_hidden_states": ours_enc,
})
print("\nOURS step1 logits shape:", ours_dec[0].shape)
ours_top5 = np.argsort(-ours_dec[0][0, -1])[:10]
print("OURS step1 top10 token ids:", ours_top5.tolist())
print("OURS step1 decoded top10:")
detok_sess = ort.InferenceSession(str(MODEL_DIR / "detokenizer.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
for tid in ours_top5:
    s = detok_sess.run(None, {"tokens": np.array([int(tid)], dtype=np.int64)})[0][0]
    print(f"  {int(tid):6d}  {s!r}")
