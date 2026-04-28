"""Translate the user's long abstract using transformers FP32 reference.

Splits by sentence so nothing exceeds opus-mt-en-zh's max_length (512).
"""
import re
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM

MODEL_ID = "Helsinki-NLP/opus-mt-en-zh"

TEXT = """Computational functionalism dominates current debates on AI consciousness. This is the hypothesis that subjective experience emerges entirely from abstract causal topology, regardless of the underlying physical substrate. We argue this view fundamentally mischaracterizes how physics relates to information. We call this mistake the Abstraction Fallacy. Tracing the causal origins of abstraction reveals that symbolic computation is not an intrinsic physical process. Instead, it is a mapmaker-dependent description. It requires an active, experiencing cognitive agent to alphabetize continuous physics into a finite set of meaningful states. Consequently, we do not need a complete, finalized theory of consciousness to assess AI sentience—a demand that simply pushes the question beyond near-term resolution and deepens the AI welfare trap. What we actually need is a rigorous ontology of computation. The framework proposed here explicitly separates simulation (behavioral mimicry driven by vehicle causality) from instantiation (intrinsic physical constitution driven by content causality). Establishing this ontological boundary shows why algorithmic symbol manipulation is structurally incapable of instantiating experience. Crucially, this argument does not rely on biological exclusivity. If an artificial system were ever conscious, it would be because of its specific physical constitution, never its syntactic architecture. Ultimately, this framework offers a physically grounded refutation of computational functionalism to resolve the current uncertainty surrounding AI consciousness."""

# Sentence-level split — keep `—` and `(...)` intact.
sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", TEXT) if s.strip()]
print(f"Loaded {len(sentences)} sentences")

tok = AutoTokenizer.from_pretrained(MODEL_ID)
model = AutoModelForSeq2SeqLM.from_pretrained(MODEL_ID)

translations = []
for i, s in enumerate(sentences, 1):
    inp = tok(s, return_tensors="pt", truncation=True, max_length=512)
    out = model.generate(**inp, num_beams=4, max_length=512, early_stopping=True)
    zh = tok.batch_decode(out, skip_special_tokens=True)[0]
    print(f"[{i:2d}] {s}")
    print(f"     → {zh}")
    print()
    translations.append(zh)

print("=" * 80)
print("Final concatenated translation:")
print("=" * 80)
print("".join(translations))
