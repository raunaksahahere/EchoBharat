#!/usr/bin/env python3
"""
Generates the TTS symbol table the app loads, straight from a Coqui config.json.

Emitted as a JSON array indexed by token id rather than a text file, because the
vocabulary contains a space (id 13), a non-breaking space, and zero-width joiners.
Those survive JSON escaping exactly; in a line-per-symbol text file they are either
invisible, ambiguous, or silently dropped by a blank-line filter.

Ordering mirrors Coqui's VitsCharacters._create_vocab:
    [pad] + list(punctuations) + list(characters) + [blank]
which is verified against config's model_args.num_chars before writing.
"""
import argparse
import json
import sys
from pathlib import Path


def build_vocab(config_path: Path) -> list[str]:
    cfg = json.loads(config_path.read_text(encoding="utf-8"))
    ch = cfg["characters"]

    # A vocab_dict, when present, is authoritative — prefer it over reconstruction.
    vocab_dict = ch.get("vocab_dict")
    if vocab_dict:
        vocab = [""] * (max(vocab_dict.values()) + 1)
        for sym, idx in vocab_dict.items():
            vocab[idx] = sym
        return vocab

    vocab = [ch["pad"]] + list(ch["punctuations"]) + list(ch["characters"]) + [ch["blank"]]

    expected = (cfg.get("model_args") or {}).get("num_chars")
    if expected is not None and len(vocab) != expected:
        raise SystemExit(
            f"vocab size {len(vocab)} != num_chars {expected} in {config_path}; "
            "the character-class ordering has changed and must be re-derived"
        )
    return vocab


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("config", type=Path, help="Coqui FastPitch config.json")
    ap.add_argument("out", type=Path, help="destination .tokens.json")
    args = ap.parse_args()

    vocab = build_vocab(args.config)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")

    print(f"wrote {args.out} ({len(vocab)} symbols)")
    preview = [(i, s) for i, s in enumerate(vocab[:15])]
    print(f"  ids 0-14: {preview}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
