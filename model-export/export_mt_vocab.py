#!/usr/bin/env python3
"""
Flattens an IndicTrans2 SentencePiece pair into two files the Kotlin tokeniser can read
without a SentencePiece dependency on the phone.

The app needs two different things that live in two different places:

  * merge order   - the piece scores inside model.SRC / model.TGT (a BPE model: encoding
                    is "repeatedly merge the adjacent pair with the best score")
  * token ids     - dict.SRC.json / dict.TGT.json, whose ids are what the ONNX graph
                    actually consumes, and which do NOT match SentencePiece's internal ids

Emitting them pre-joined as one TSV means the app parses a single flat file instead of a
3 MB JSON plus a protobuf, and means any piece/id mismatch shows up here rather than as
silent <unk> noise on a phone during a demo.

Output per direction:
    bpe-src.tsv    piece <TAB> score <TAB> graph_id     (encoder side)
    bpe-tgt.tsv    piece <TAB> score <TAB> graph_id     (decoder side)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import sentencepiece.sentencepiece_model_pb2 as sp_pb2


def dump(model_path: Path, dict_path: Path, out_path: Path) -> tuple[int, int]:
    proto = sp_pb2.ModelProto()
    proto.ParseFromString(model_path.read_bytes())
    vocab: dict[str, int] = json.loads(dict_path.read_text(encoding="utf-8"))

    lines: list[str] = []
    missing = 0
    for piece in proto.pieces:
        gid = vocab.get(piece.piece)
        if gid is None:
            # Present in the SentencePiece model but absent from the graph vocabulary.
            # Kept with id -1 so it can still take part in a merge, then resolve to <unk>.
            gid = -1
            missing += 1
        lines.append(f"{piece.piece}\t{piece.score:.6g}\t{gid}")

    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(lines), missing


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True,
                    help="a downloaded direction, e.g. mt/indic-en")
    args = ap.parse_args()
    d = args.dir

    for side, model, dic in (("src", "model.SRC", "dict.SRC.json"),
                             ("tgt", "model.TGT", "dict.TGT.json")):
        out = d / f"bpe-{side}.tsv"
        n, missing = dump(d / model, d / dic, out)
        size = out.stat().st_size / 1e6
        note = f", {missing} not in graph vocab" if missing else ""
        print(f"  {out.name:14s} {n:>7} pieces  {size:5.1f} MB{note}")

    # The FLORES language tags are graph vocabulary entries, not SentencePiece pieces, and
    # their ids differ between directions (hin_Deva is 8 going indic->en and 15 going
    # en->indic). Hardcoding either value silently mistranslates the other direction, so
    # they ship as data alongside the vocab sizes the tokeniser needs to size its tables.
    src_vocab = json.loads((d / "dict.SRC.json").read_text(encoding="utf-8"))
    tgt_vocab = json.loads((d / "dict.TGT.json").read_text(encoding="utf-8"))
    meta = {
        "srcVocabSize": len(src_vocab),
        "tgtVocabSize": len(tgt_vocab),
        # Every language the app speaks. The app ships the same table in its manifest and
        # refuses a meta.json that disagrees with it.
        "langTagIds": {
            tag: src_vocab[tag] for tag in (
                "eng_Latn", "hin_Deva", "ben_Beng", "guj_Gujr", "kan_Knda", "mal_Mlym",
                "mar_Deva", "ory_Orya", "tam_Taml", "tel_Telu",
            ) if tag in src_vocab
        },
        "bos": 0, "pad": 1, "eos": 2, "unk": 3,
        "decoderStartTokenId": 2,
        "maxSourceTokens": 256,
    }
    meta_path = d / "mt-meta.json"
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(f"  {meta_path.name:14s} {meta['langTagIds']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
