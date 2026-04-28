"""Translate the abstract with M2M100-418M and NLLB-600M (FP32, beam=4) for quality comparison."""
import re
import time
import torch
from transformers import (
    AutoTokenizer, AutoModelForSeq2SeqLM,
    NllbTokenizer, M2M100ForConditionalGeneration, M2M100Tokenizer,
)

TEXT = """Computational functionalism dominates current debates on AI consciousness. This is the hypothesis that subjective experience emerges entirely from abstract causal topology, regardless of the underlying physical substrate. We argue this view fundamentally mischaracterizes how physics relates to information. We call this mistake the Abstraction Fallacy. Tracing the causal origins of abstraction reveals that symbolic computation is not an intrinsic physical process. Instead, it is a mapmaker-dependent description. It requires an active, experiencing cognitive agent to alphabetize continuous physics into a finite set of meaningful states. Consequently, we do not need a complete, finalized theory of consciousness to assess AI sentience—a demand that simply pushes the question beyond near-term resolution and deepens the AI welfare trap. What we actually need is a rigorous ontology of computation. The framework proposed here explicitly separates simulation (behavioral mimicry driven by vehicle causality) from instantiation (intrinsic physical constitution driven by content causality). Establishing this ontological boundary shows why algorithmic symbol manipulation is structurally incapable of instantiating experience. Crucially, this argument does not rely on biological exclusivity. If an artificial system were ever conscious, it would be because of its specific physical constitution, never its syntactic architecture. Ultimately, this framework offers a physically grounded refutation of computational functionalism to resolve the current uncertainty surrounding AI consciousness."""

sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", TEXT) if s.strip()]


def run_m2m100():
    print("\n" + "=" * 80 + "\nM2M100-418M (FP32, beam=4)\n" + "=" * 80)
    t0 = time.time()
    tok = M2M100Tokenizer.from_pretrained("facebook/m2m100_418M")
    model = M2M100ForConditionalGeneration.from_pretrained("facebook/m2m100_418M")
    print(f"  loaded in {time.time()-t0:.1f}s")
    tok.src_lang = "en"
    out_chunks = []
    for i, s in enumerate(sentences, 1):
        inp = tok(s, return_tensors="pt", truncation=True, max_length=512)
        ids = model.generate(**inp, forced_bos_token_id=tok.get_lang_id("zh"),
                             num_beams=4, max_length=512, early_stopping=True)
        zh = tok.batch_decode(ids, skip_special_tokens=True)[0]
        print(f"[{i:2d}] {zh}")
        out_chunks.append(zh)
    print(f"\n[FULL]\n{''.join(out_chunks)}")


def run_nllb_600m():
    print("\n" + "=" * 80 + "\nNLLB-200-distilled-600M (FP32, beam=4)\n" + "=" * 80)
    t0 = time.time()
    tok = AutoTokenizer.from_pretrained("facebook/nllb-200-distilled-600M", src_lang="eng_Latn")
    model = AutoModelForSeq2SeqLM.from_pretrained("facebook/nllb-200-distilled-600M")
    print(f"  loaded in {time.time()-t0:.1f}s")
    out_chunks = []
    zh_id = tok.convert_tokens_to_ids("zho_Hans")
    for i, s in enumerate(sentences, 1):
        inp = tok(s, return_tensors="pt", truncation=True, max_length=512)
        ids = model.generate(**inp, forced_bos_token_id=zh_id,
                             num_beams=4, max_length=512, early_stopping=True)
        zh = tok.batch_decode(ids, skip_special_tokens=True)[0]
        print(f"[{i:2d}] {zh}")
        out_chunks.append(zh)
    print(f"\n[FULL]\n{''.join(out_chunks)}")


run_m2m100()
run_nllb_600m()
