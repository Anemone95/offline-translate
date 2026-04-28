"""Inspect ONNX input/output schemas for opus-mt-en-zh."""
from pathlib import Path
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

MODEL_DIR = Path("models/opus-mt-en-zh-onnx-int8")

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())

for name in [
    "tokenizer.onnx",
    "encoder_model.onnx",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx",
    "detokenizer.onnx",
]:
    print(f"\n=== {name} ===")
    sess = ort.InferenceSession(str(MODEL_DIR / name), sess_options=so, providers=["CPUExecutionProvider"])
    print("Inputs:")
    for x in sess.get_inputs():
        print(f"  {x.name:40s} {x.type:20s} {x.shape}")
    print("Outputs:")
    for x in sess.get_outputs():
        print(f"  {x.name:40s} {x.type:20s} {x.shape}")
