"""Sanity check the int8-quantized opus-mt-en-zh model on a few English sentences."""
import time
from pathlib import Path
from optimum.onnxruntime import ORTModelForSeq2SeqLM
from transformers import AutoTokenizer

MODEL_DIR = Path("models/opus-mt-en-zh-onnx-int8")

tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
model = ORTModelForSeq2SeqLM.from_pretrained(MODEL_DIR, use_cache=True)

samples = [
    "Hello, how are you today?",
    "The quick brown fox jumps over the lazy dog.",
    "Speech recognition technology has improved dramatically in recent years.",
    "I would like to order a cup of coffee, please.",
    "The conference will start at 9 AM tomorrow morning.",
]

for s in samples:
    t0 = time.perf_counter()
    inputs = tokenizer(s, return_tensors="pt")
    out = model.generate(**inputs, max_length=128, num_beams=1)
    dt = time.perf_counter() - t0
    text = tokenizer.batch_decode(out, skip_special_tokens=True)[0]
    print(f"[{dt*1000:6.0f} ms] {s}")
    print(f"          → {text}")
    print()
