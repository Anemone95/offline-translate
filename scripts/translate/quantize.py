"""Dynamic int8 quantization of opus-mt encoder/decoder ONNX models."""
from pathlib import Path
from onnxruntime.quantization import quantize_dynamic, QuantType

SRC = Path("models/opus-mt-en-zh-onnx")
DST = Path("models/opus-mt-en-zh-onnx-int8")
DST.mkdir(parents=True, exist_ok=True)

ONNX_FILES = [
    "encoder_model.onnx",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx",
]

for name in ONNX_FILES:
    src = SRC / name
    dst = DST / name
    print(f"Quantizing {src} → {dst}")
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        per_channel=False,
        reduce_range=False,
    )

# Copy tokenizer/config files alongside.
import shutil
for f in SRC.iterdir():
    if f.is_file() and not f.name.endswith(".onnx"):
        shutil.copy(f, DST / f.name)

print(f"\nFiles in {DST}:")
for f in sorted(DST.iterdir()):
    print(f"    {f.name:50s} {f.stat().st_size / 1024 / 1024:>8.2f} MB")
