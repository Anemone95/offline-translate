"""Build tokenizer + detokenizer ONNX graphs for Helsinki-NLP/opus-mt-en-zh.

MarianTokenizer uses source.spm/target.spm to split text into pieces, then maps
each piece via vocab.json (the model's *actual* vocab). The bare SentencePiece
ONNX op only produces SP-internal IDs, not Marian vocab IDs, so we add a
`Gather` remap node on each side.
"""
import json
from pathlib import Path
import numpy as np
import onnx
import sentencepiece as spm
from onnx import helper, TensorProto, numpy_helper

MODEL_DIR = Path("models/opus-mt-en-zh-onnx-int8")
SRC_SPM_PATH = MODEL_DIR / "source.spm"
TGT_SPM_PATH = MODEL_DIR / "target.spm"
VOCAB_JSON = MODEL_DIR / "vocab.json"

SRC_SPM = SRC_SPM_PATH.read_bytes()
TGT_SPM = TGT_SPM_PATH.read_bytes()

vocab: dict[str, int] = json.loads(VOCAB_JSON.read_text())
unk_id = vocab.get("<unk>", 0)

# Build remap tables.
src_sp = spm.SentencePieceProcessor(model_file=str(SRC_SPM_PATH))
sp_to_marian = np.full(src_sp.vocab_size(), unk_id, dtype=np.int64)
for i in range(src_sp.vocab_size()):
    piece = src_sp.id_to_piece(i)
    sp_to_marian[i] = vocab.get(piece, unk_id)

tgt_sp = spm.SentencePieceProcessor(model_file=str(TGT_SPM_PATH))
marian_to_sp_tgt = np.zeros(len(vocab), dtype=np.int64)
for piece, marian_id in vocab.items():
    marian_to_sp_tgt[marian_id] = tgt_sp.piece_to_id(piece)

CONTRIB_DOMAIN = "ai.onnx.contrib"
OPSET_IMPORTS = [
    helper.make_opsetid("", 18),
    helper.make_opsetid(CONTRIB_DOMAIN, 1),
]


def build_tokenizer_onnx(out_path: Path) -> None:
    """text (string[batch]) -> Marian token IDs (int64[num_tokens])."""
    input_text = helper.make_tensor_value_info("input_text", TensorProto.STRING, ["batch"])
    output_ids = helper.make_tensor_value_info("token_ids", TensorProto.INT64, ["num_tokens"])

    inits = [
        helper.make_tensor("nbest_size", TensorProto.INT64, [1], [0]),
        helper.make_tensor("alpha", TensorProto.FLOAT, [1], [0.0]),
        helper.make_tensor("add_bos", TensorProto.BOOL, [1], [False]),
        helper.make_tensor("add_eos", TensorProto.BOOL, [1], [True]),
        helper.make_tensor("reverse", TensorProto.BOOL, [1], [False]),
        # Remap table baked in.
        numpy_helper.from_array(sp_to_marian, name="sp_to_marian"),
    ]

    sp_node = helper.make_node(
        "SentencepieceTokenizer",
        inputs=["input_text", "nbest_size", "alpha", "add_bos", "add_eos", "reverse"],
        outputs=["sp_ids", "indices"],
        name="sptokenizer",
        domain=CONTRIB_DOMAIN,
        model=SRC_SPM,
    )
    cast_node = helper.make_node("Cast", ["sp_ids"], ["sp_ids_i64"], to=TensorProto.INT64)
    gather_node = helper.make_node("Gather", ["sp_to_marian", "sp_ids_i64"], ["token_ids"])

    graph = helper.make_graph(
        [sp_node, cast_node, gather_node],
        "tokenizer",
        [input_text],
        [output_ids],
        inits,
    )
    model = helper.make_model(graph, opset_imports=OPSET_IMPORTS, ir_version=9)
    onnx.save(model, str(out_path))


def build_detokenizer_onnx(out_path: Path) -> None:
    """Marian token IDs (int64[num_tokens]) -> text (string[1])."""
    tokens_in = helper.make_tensor_value_info("token_ids", TensorProto.INT64, ["num_tokens"])
    text_out = helper.make_tensor_value_info("text", TensorProto.STRING, ["1"])

    inits = [
        numpy_helper.from_array(marian_to_sp_tgt, name="marian_to_sp_tgt"),
    ]

    gather_node = helper.make_node("Gather", ["marian_to_sp_tgt", "token_ids"], ["sp_ids"])
    sp_node = helper.make_node(
        "SentencepieceDecoder",
        inputs=["sp_ids"],
        outputs=["text"],
        name="spdetokenizer",
        domain=CONTRIB_DOMAIN,
        model=TGT_SPM,
    )

    graph = helper.make_graph([gather_node, sp_node], "detokenizer", [tokens_in], [text_out], inits)
    model = helper.make_model(graph, opset_imports=OPSET_IMPORTS, ir_version=9)
    onnx.save(model, str(out_path))


build_tokenizer_onnx(MODEL_DIR / "tokenizer.onnx")
build_detokenizer_onnx(MODEL_DIR / "detokenizer.onnx")

for f in ["tokenizer.onnx", "detokenizer.onnx"]:
    p = MODEL_DIR / f
    print(f"{f:25s} {p.stat().st_size/1024:.1f} KB")
