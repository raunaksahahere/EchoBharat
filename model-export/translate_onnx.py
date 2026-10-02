#!/usr/bin/env python3
"""
Drives the IndicTrans2 ONNX encoder/decoder pair with greedy decoding, exactly as the
Kotlin IndicTrans2Translator will.

This exists to verify the published export before any of it is trusted on a phone: the
graph I/O contract, the token layout, and whether real sentences actually come out. A
wrong translation in a disaster message is worse than an untranslated one, so the point
here is to look at the output, not to see the script exit 0.

Greedy, not beam: pick the highest-probability token each step. Beam search would score
a little better but is far more code to get right, and correctness under time pressure
beats a marginal BLEU gain.

The decoder is the cacheless `decoder_model.onnx`, so every step re-runs the full prefix.
That is O(n^2) in tokens and is the obvious thing to optimise later by switching to
`decoder_with_past_model.onnx`; it is not a correctness problem.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

# Reuse the reference pipeline's language table and its compatible processor loader.
# Importing IndicTransToolkit directly also imports an unnecessary transformers stack.
from make_mt_golden import FLORES, load_processor

LANG_TAG = FLORES

BOS, PAD, EOS, UNK = 0, 1, 2, 3
DECODER_START = EOS  # decoder_start_token_id == 2, per generation_config.json


def load_dir(d: Path):
    src_vocab = json.loads((d / "dict.SRC.json").read_text())
    tgt_vocab = json.loads((d / "dict.TGT.json").read_text())
    sp_src = spm.SentencePieceProcessor(model_file=str(d / "model.SRC"))
    sp_tgt = spm.SentencePieceProcessor(model_file=str(d / "model.TGT"))

    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    enc = ort.InferenceSession(str(d / "encoder_model.onnx"), opts,
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(d / "decoder_model.onnx"), opts,
                               providers=["CPUExecutionProvider"])
    return src_vocab, tgt_vocab, sp_src, sp_tgt, enc, dec


def encode_source(text: str, src: str, tgt: str, sp_src, src_vocab) -> list[int]:
    """[src_tag, tgt_tag, *pieces, </s>] -> ids. Mirrors _src_tokenize + eos append."""
    pieces = [LANG_TAG[src], LANG_TAG[tgt]] + sp_src.encode(text, out_type=str)
    ids = [src_vocab.get(p, UNK) for p in pieces]
    ids.append(EOS)
    return ids


def greedy_decode(enc, dec, src_ids: list[int], tgt_inv: dict[int, str],
                  max_new: int = 128, verbose: bool = False):
    input_ids = np.array([src_ids], dtype=np.int64)
    attn = np.ones_like(input_ids, dtype=np.int64)

    t0 = time.perf_counter()
    hidden = enc.run(["last_hidden_state"],
                     {"input_ids": input_ids, "attention_mask": attn})[0]
    t_enc = time.perf_counter() - t0

    out = [DECODER_START]
    t0 = time.perf_counter()
    for _ in range(max_new):
        logits = dec.run(
            ["logits"],
            {
                "input_ids": np.array([out], dtype=np.int64),
                "encoder_attention_mask": attn,
                "encoder_hidden_states": hidden,
            },
        )[0]
        nxt = int(np.argmax(logits[0, -1]))
        if nxt == EOS:
            break
        out.append(nxt)
    t_dec = time.perf_counter() - t0

    # Drop the priming token; map ids back through the target vocab.
    pieces = [tgt_inv.get(i, "<unk>") for i in out[1:]]
    text = "".join(pieces).replace("▁", " ").strip()
    if verbose:
        print(f"    tokens_out={len(pieces)} enc={t_enc*1000:.0f}ms dec={t_dec*1000:.0f}ms")
    return text, t_enc, t_dec, len(pieces)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True)
    ap.add_argument("--src", required=True, choices=sorted(LANG_TAG))
    ap.add_argument("--tgt", required=True, choices=sorted(LANG_TAG))
    ap.add_argument("--text", action="append", required=True,
                    help="repeatable; one sentence per flag")
    ap.add_argument("--max-new", type=int, default=128)
    args = ap.parse_args()

    src_vocab, tgt_vocab, sp_src, sp_tgt, enc, dec = load_dir(args.dir)
    tgt_inv = {v: k for k, v in tgt_vocab.items()}
    processor = load_processor()

    print(f"encoder in : {[(i.name, i.shape) for i in enc.get_inputs()]}")
    print(f"decoder in : {[(i.name, i.shape) for i in dec.get_inputs()]}")
    print(f"src vocab {len(src_vocab)}  tgt vocab {len(tgt_vocab)}")
    print(f"direction  {args.src} -> {args.tgt} "
          f"({LANG_TAG[args.src]} -> {LANG_TAG[args.tgt]})\n")

    total = 0.0
    for text in args.text:
        # The reference processor prepends language tags and queues this sentence's
        # placeholder map. encode_source adds its own tags, so pass only the body.
        processed = processor.preprocess_batch(
            [text], src_lang=LANG_TAG[args.src], tgt_lang=LANG_TAG[args.tgt]
        )[0].split(" ", 2)[2]
        ids = encode_source(processed, args.src, args.tgt, sp_src, src_vocab)
        unk = sum(1 for i in ids if i == UNK)
        t0 = time.perf_counter()
        got, t_enc, t_dec, n_out = greedy_decode(enc, dec, ids, tgt_inv, args.max_new)
        got = processor.postprocess_batch([got], lang=LANG_TAG[args.tgt])[0]
        wall = time.perf_counter() - t0
        total += wall
        print(f"  in  : {text}")
        print(f"  out : {got}")
        print(f"        {len(ids)} src tokens ({unk} unk) -> {n_out} out tokens, "
              f"{wall*1000:.0f}ms (enc {t_enc*1000:.0f} / dec {t_dec*1000:.0f})")
        if not got.strip():
            print("        SUSPECT: empty output")
        print()

    print(f"mean {total/len(args.text)*1000:.0f}ms per sentence on this CPU")
    return 0


if __name__ == "__main__":
    sys.exit(main())
