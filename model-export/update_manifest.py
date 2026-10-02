#!/usr/bin/env python3
"""
Fills the TTS url/sha256/sizeBytes fields in the app manifest from locally exported files.

The manifest already names every TTS file for every language; what is missing until a
model is published is the URL and the digest. This computes the digest from the file that
was actually exported, so the manifest can never claim a hash that no local artifact
matches.

It refuses to write an entry whose file is absent, rather than emitting a plausible URL for
something that was never uploaded — a manifest entry pointing at a missing file turns into
a download failure on a phone, which is a far more expensive place to discover it.

Verification that the published URL really serves these bytes is a separate step; see
--verify.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

BASE = "https://huggingface.co/RaunakSaha/echobharat-models/resolve/main"

ROLE_FILE = {
    "TTS_ACOUSTIC": "fastpitch-{L}.int8.onnx",
    "TTS_VOCODER": "hifigan-{L}.int8.onnx",
    "TTS_TOKENS": "fastpitch-{L}.tokens.json",
}


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_url(url: str, want_sha: str, want_size: int) -> tuple[bool, str]:
    """Re-downloads and hashes. Large files are streamed rather than held in memory."""
    try:
        h = hashlib.sha256()
        total = 0
        with urllib.request.urlopen(url, timeout=300) as r:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                h.update(chunk)
        if total != want_size:
            return False, f"size {total} != manifest {want_size}"
        if h.hexdigest() != want_sha:
            return False, f"sha256 {h.hexdigest()[:16]}… != manifest {want_sha[:16]}…"
        return True, "ok"
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path,
                    default=Path("../app/src/main/assets/models/manifest.json"))
    ap.add_argument("--out", type=Path, default=Path("out"),
                    help="directory holding <lang>/ export output")
    ap.add_argument("--langs", nargs="+", required=True)
    ap.add_argument("--verify", action="store_true",
                    help="re-download every touched URL and check it against the manifest")
    args = ap.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    by_lang = {l["lang"]: l for l in manifest["languages"]}

    touched: list[tuple[str, str, int]] = []
    skipped: list[str] = []

    for lang in args.langs:
        entry = by_lang.get(lang)
        if entry is None:
            print(f"  {lang}: not in manifest, skipping")
            skipped.append(lang)
            continue

        missing = [
            tmpl.format(L=lang) for tmpl in ROLE_FILE.values()
            if not (args.out / lang / tmpl.format(L=lang)).is_file()
        ]
        if missing:
            print(f"  {lang}: NOT published — missing locally: {', '.join(missing)}")
            skipped.append(lang)
            continue

        for m in entry["models"]:
            tmpl = ROLE_FILE.get(m["role"])
            if tmpl is None:
                continue
            fn = tmpl.format(L=lang)
            local = args.out / lang / fn
            digest = sha256_of(local)
            size = local.stat().st_size
            m["fileName"] = fn
            m["url"] = f"{BASE}/{lang}/{fn}"
            m["mirrorUrl"] = ""
            m["sha256"] = digest
            m["sizeBytes"] = size
            touched.append((m["url"], digest, size))
        total = sum(
            (args.out / lang / t.format(L=lang)).stat().st_size for t in ROLE_FILE.values()
        )
        print(f"  {lang}: published 3 TTS files ({total/1e6:.1f} MB)")

    args.manifest.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"\nwrote {args.manifest}")
    if skipped:
        print(f"left unpublished: {', '.join(skipped)}")

    if args.verify and touched:
        print(f"\nverifying {len(touched)} published URLs against the manifest…")
        bad = 0
        for url, digest, size in touched:
            ok, why = verify_url(url, digest, size)
            name = url.rsplit("/", 1)[-1]
            print(f"  {'OK  ' if ok else 'FAIL'} {name:<34} {why if not ok else ''}")
            bad += 0 if ok else 1
        print(f"  -> {len(touched) - bad} verified, {bad} failed")
        return 1 if bad else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
