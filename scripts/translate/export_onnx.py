"""Export Helsinki-NLP/opus-mt-en-zh to ONNX (encoder + decoder), then int8-quantize."""
import os
import sys
from pathlib import Path

from optimum.onnxruntime import ORTModelForSeq2SeqLM
from transformers import AutoTokenizer

MODEL_ID = "Helsinki-NLP/opus-mt-en-zh"
OUT_DIR = Path("models/opus-mt-en-zh-onnx")

OUT_DIR.mkdir(parents=True, exist_ok=True)

print(f"[1/2] Exporting {MODEL_ID} to ONNX → {OUT_DIR}")
model = ORTModelForSeq2SeqLM.from_pretrained(
    MODEL_ID,
    export=True,
    use_cache=True,
)
model.save_pretrained(OUT_DIR)

tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
tokenizer.save_pretrained(OUT_DIR)

print(f"[2/2] Files in {OUT_DIR}:")
for f in sorted(OUT_DIR.iterdir()):
    print(f"    {f.name:50s} {f.stat().st_size / 1024 / 1024:>8.2f} MB")
