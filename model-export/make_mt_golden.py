#!/usr/bin/env python3
"""
Generates the golden cases that pin the app's Kotlin port of IndicTrans2's text processing
(app/.../translate/IndicTransText.kt) to AI4Bharat's reference implementation.

The model never sees raw text: IndicProcessor normalises it, swaps numbers and URLs for
placeholders, Moses-tokenises English and transliterates every Indic script to Devanagari.
A port that drifts from that produces fluent but wrong translations rather than errors, so
the port is diffed against the real thing on:

  * an English corpus with the awkward cases (contractions, abbreviations, phone numbers,
    times, e-mail, URLs, quotes, brackets, ellipses);
  * natural sentences in all nine Indic languages, obtained by translating that corpus
    with the reference pipeline, so the Indic side is tested on text the model writes;
  * the model's raw output for every translation, so post-processing is tested too;
  * the final translation from both decoders — cacheless, and KV-cached via
    decoder_with_past — which legitimately differ where two tokens are within int8
    rounding of each other, so each is pinned separately.

Needs the int8 ONNX exports (see README §7) and:
    pip install onnxruntime==1.20.0 sentencepiece numpy IndicTransToolkit
(the onnxruntime version must match the app's; see Family below).

Usage:
    python make_mt_golden.py --mt mt/ --out ../app/src/test/resources/mt-golden.tsv
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

FLORES = {
    "en": "eng_Latn", "hi": "hin_Deva", "bn": "ben_Beng", "gu": "guj_Gujr", "kn": "kan_Knda",
    "ml": "mal_Mlym", "mr": "mar_Deva", "or": "ory_Orya", "ta": "tam_Taml", "te": "tel_Telu",
}
INDIC = [l for l in FLORES if l != "en"]
EOS, UNK = 2, 3

ENGLISH = [
    "I need water and medicine.",
    "Two children are injured; please send a doctor quickly!",
    "Call me at 98765-43210 when you reach the school.",
    "Don't cross the bridge, it's broken.",
    "Dr. Rao said the camp opens at 10:30 tomorrow.",
    "We have food for 3 days... maybe less.",
    "Is anyone hurt? We're near the old temple (east side).",
    "Send the list to relief.team@example.org before noon.",
    "The water level rose 40% in two hours.",
    "My mother's leg is broken and she can't walk.",
    "Bring blankets, torches and rope.",
    "Mr. Singh and his family are safe.",
    "Check https://ndma.gov.in for updates.",
    'She said "stay inside" and left.',
    "there is no signal here",
]

# Edge cases the translated corpus is unlikely to contain.
INDIC_EXTRA = {
    "hi": ["मुझे पानी चाहिए|", "ज़मीन धँस गई है।", "राम १० बजे आएगा", "क्या आप ठीक हैं?"],
    "bn": ["আমাদের ১২ জন আছে।", "রাস্তা বন্ধ, অন্য পথে আসুন।"],
    "ml": ["അവൻൺ വന്നു", "എന്‍റെ വീട്"],
    "ta": ["எங்களுக்கு உணவு தேவை!", "பாலம் உடைந்துவிட்டது."],
    "te": ["మాకు సహాయం కావాలి", "౧౦ మంది ఉన్నారు"],
    "or": ["ଦୟାକରି ଶୀଘ୍ର ଆସନ୍ତୁ।"],
}


def load_processor():
    """Loads IndicProcessor straight from its compiled module: the package __init__ imports
    transformers, which is not needed here and whose current releases break that import."""
    pkg = Path(importlib.util.find_spec("IndicTransToolkit").submodule_search_locations[0])
    so = next(pkg.glob("processor*.so"))
    spec = importlib.util.spec_from_file_location("processor", so)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.IndicProcessor(inference=True)


class Family:
    def __init__(self, d: Path, threads: int):
        # Match the app's runtime: int8 kernels round differently across ONNX Runtime
        # releases and thread counts, and greedy decoding turns a 0.01 logit difference
        # into a different (equally valid) word. Pin onnxruntime to the version in
        # gradle/libs.versions.toml and pass the same thread count the test uses.
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sv = json.loads((d / "dict.SRC.json").read_text())
        self.tv = json.loads((d / "dict.TGT.json").read_text())
        self.inv = {v: k for k, v in self.tv.items()}
        self.sp = spm.SentencePieceProcessor(model_file=str(d / "model.SRC"))
        self.enc = ort.InferenceSession(str(d / "encoder_model.onnx"), opts)
        self.dec = ort.InferenceSession(str(d / "decoder_model.onnx"), opts)
        self.past = ort.InferenceSession(str(d / "decoder_with_past_model.onnx"), opts)

    def run(self, processed: str, s: str, t: str, cached: bool, max_new: int = 128) -> str:
        ids = [self.sv[FLORES[s]], self.sv[FLORES[t]]]
        ids += [self.sv.get(p, UNK) for p in self.sp.encode(processed, out_type=str)] + [EOS]
        x = np.array([ids], dtype=np.int64)
        mask = np.ones_like(x)
        h = self.enc.run(["last_hidden_state"], {"input_ids": x, "attention_mask": mask})[0]
        out = self._cached(h, mask, max_new) if cached else self._cacheless(h, mask, max_new)
        return "".join(self.inv.get(i, "<unk>") for i in out).replace("▁", " ").strip()

    def _cacheless(self, h, mask, max_new):
        out = [2]
        for _ in range(max_new):
            logits = self.dec.run(["logits"], {
                "input_ids": np.array([out], dtype=np.int64),
                "encoder_attention_mask": mask, "encoder_hidden_states": h})[0]
            n = int(logits[0, -1].argmax())
            if n == EOS:
                break
            out.append(n)
        return out[1:]

    def _cached(self, h, mask, max_new):
        """First step on the full decoder (it also yields the encoder KV), then one token
        at a time on decoder_with_past, feeding back the decoder KV."""
        names = [o.name for o in self.dec.get_outputs()]
        first = dict(zip(names, self.dec.run(None, {
            "input_ids": np.array([[2]], dtype=np.int64),
            "encoder_attention_mask": mask, "encoder_hidden_states": h})))
        past_names = [o.name for o in self.past.get_outputs()]
        enc_kv = {k.replace("present", "past_key_values"): v for k, v in first.items() if ".encoder." in k}
        dec_kv = {k.replace("present", "past_key_values"): v for k, v in first.items() if ".decoder." in k}
        n, out = int(first["logits"][0, -1].argmax()), []
        while n != EOS and len(out) < max_new:
            out.append(n)
            r = dict(zip(past_names, self.past.run(None, {
                "input_ids": np.array([[n]], dtype=np.int64),
                "encoder_attention_mask": mask, **enc_kv, **dec_kv})))
            n = int(r["logits"][0, -1].argmax())
            dec_kv = {k.replace("present", "past_key_values"): v for k, v in r.items() if ".decoder." in k}
        return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mt", type=Path, required=True, help="dir holding indic-en/ and en-indic/")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--threads", type=int, default=4, help="must match IndicTrans2DesktopTest")
    args = ap.parse_args()
    print(f"onnxruntime {ort.__version__}, {args.threads} threads")

    ip = load_processor()
    fams = {f: Family(args.mt / f, args.threads) for f in ("indic-en", "en-indic")}
    rows: list[tuple[str, ...]] = []

    def pre(text: str, s: str, t: str) -> str:
        out = ip.preprocess_batch([text], src_lang=FLORES[s], tgt_lang=FLORES[t])[0]
        return out.split(" ", 2)[2]

    def pre_only(text: str, s: str, t: str) -> str:
        processed = pre(text, s, t)
        ip.postprocess_batch([processed], lang=FLORES[t])  # pops the queued placeholder map
        return processed

    def translate(text: str, s: str, t: str) -> str:
        processed = pre(text, s, t)
        rows.append(("pre", s, t, text, "", processed, ""))
        fam = fams["en-indic" if s == "en" else "indic-en"]
        decoded = fam.run(processed, s, t, cached=False)
        # One preprocess queues one placeholder map, so both outputs go through a single
        # postprocess call as two "return sequences" of the same input.
        final, cached = ip.postprocess_batch(
            [decoded, fam.run(processed, s, t, cached=True)],
            lang=FLORES[t], num_return_sequences=2
        )
        rows.append(("post", s, t, text, decoded, final, cached))
        return final

    for lang in INDIC:
        for sentence in ENGLISH:
            native = translate(sentence, "en", lang)
            translate(native, lang, "en")
        for sentence in INDIC_EXTRA.get(lang, []):
            rows.append(("pre", lang, "en", sentence, "", pre_only(sentence, lang, "en"), ""))

    clean = lambda v: v.replace("\t", " ").replace("\n", " ")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as f:
        f.write("# kind\tsrc\ttgt\tinput\tdecoded\texpected\texpected_cached — generated by model-export/make_mt_golden.py\n")
        for r in rows:
            f.write("\t".join(clean(v) for v in r) + "\n")
    print(f"wrote {len(rows)} cases to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
