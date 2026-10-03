#!/usr/bin/env python3
"""Stage v2 TTS metadata locally, atomically, only after all requested packs validate.

This does not publish files. Hashes and sizes come only from local validated artifacts.
Optional --verify checks remote bytes BEFORE writing; without it URLs are unverified.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

from tts_validation import ROLE_FILE, load_symbols, validate_pair

BASE = "https://huggingface.co/RaunakSaha/echobharat-models/resolve/main"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_url(url: str, want_sha: str, want_size: int) -> tuple[bool, str]:
    """Explicit opt-in remote check; never called by the default local-only flow."""
    try:
        h = hashlib.sha256()
        total = 0
        with urllib.request.urlopen(url, timeout=300) as response:
            while chunk := response.read(1 << 20):
                total += len(chunk)
                h.update(chunk)
        if total != want_size:
            return False, f"size {total} != manifest {want_size}"
        if h.hexdigest() != want_sha:
            return False, "sha256 mismatch"
        return True, "ok"
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"


def prepare_manifest(manifest: dict, out: Path, langs: list[str], *, verify=False) -> dict:
    """No caller-owned mutation on success or failure; caller commits the returned copy."""
    if not langs or len(set(langs)) != len(langs):
        raise ValueError("request a nonempty list of distinct languages")
    if any(lang.lower() in {"or", "od", "ori", "ory", "odia"} for lang in langs):
        raise ValueError("Odia publishing is disabled")
    updated = copy.deepcopy(manifest)
    entries = updated["languages"]
    by_lang = {entry["lang"]: entry for entry in entries}
    if len(by_lang) != len(entries):
        raise ValueError("duplicate manifest languages")
    for lang in langs:
        if lang not in by_lang or not lang.isascii() or not lang.isalpha():
            raise ValueError(f"unknown or invalid language: {lang}")
        entry = by_lang[lang]
        version = entry.get("packVersion", 1)
        if type(version) is not int or version not in (1, 2):
            raise ValueError(f"{lang}: cannot migrate packVersion {version!r} to 2")
        models = entry["models"]
        for role in ROLE_FILE:
            if sum(model.get("role") == role for model in models) != 1:
                raise ValueError(f"{lang}: expected exactly one {role} ModelSpec")
        paths = {role: out / lang / template.format(L=lang)
                 for role, template in ROLE_FILE.items()}
        for path in paths.values():
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"missing or empty artifact: {path}")
        # All local graph, runtime, boundary and token checks precede any disk mutation.
        symbols = load_symbols(paths["TTS_TOKENS"])
        validate_pair(paths["TTS_ACOUSTIC"], paths["TTS_VOCODER"], symbols)
        legacy = entry.setdefault("legacyModels", [])
        if not isinstance(legacy, list) or not all(isinstance(m, dict) for m in legacy):
            raise ValueError(f"{lang}: legacyModels must contain complete ModelSpec objects")
        for model in models:
            role = model.get("role")
            if role not in paths:
                continue
            path = paths[role]
            digest, size = sha256_of(path), path.stat().st_size
            # Published versioned names are immutable, including on repeated invocations.
            if model.get("fileName") == path.name:
                if model.get("sha256") != digest or model.get("sizeBytes") != size:
                    raise ValueError(f"{lang}: refusing to replace bytes under existing {path.name}")
            elif model not in legacy:
                legacy.append(copy.deepcopy(model))
            model.update(fileName=path.name, url=f"{BASE}/{lang}/{path.name}",
                         mirrorUrl="", sha256=digest, sizeBytes=size)
            if verify:
                ok, why = verify_url(model["url"], digest, size)
                if not ok:
                    raise ValueError(f"{lang}/{path.name}: remote verification failed: {why}")
        entry["packVersion"] = 2
    return updated


def atomic_write(path: Path, manifest: dict) -> None:
    payload = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=f".{path.name}.", delete=False) as f:
            temporary = Path(f.name)
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path,
                    default=Path("../app/src/main/assets/models/manifest.json"))
    ap.add_argument("--out", type=Path, default=Path("out"))
    ap.add_argument("--langs", nargs="+", required=True)
    ap.add_argument("--verify", action="store_true",
                    help="opt in to re-downloading touched URLs before the atomic local update")
    args = ap.parse_args()
    try:
        original = json.loads(args.manifest.read_text(encoding="utf-8"))
        updated = prepare_manifest(original, args.out, args.langs, verify=args.verify)
        atomic_write(args.manifest, updated)
    except Exception as exc:
        print(f"REJECTED; manifest unchanged: {exc}", file=sys.stderr)
        return 1
    print(f"Updated local v2 metadata in {args.manifest}; no files uploaded.")
    if not args.verify:
        print("Remote URLs NOT verified. Android execution and human listening are still required.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
