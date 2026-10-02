#!/usr/bin/env python3
"""
Runs the proven single-language TTS export across a batch of languages.

This adds no new export logic — it drives `export_tts.py` and `verify_tts.py`, which are
the scripts that produced the Hindi and English packs. What it adds is the things a long
batch needs and a single export does not:

* **one language resident at a time.** Each checkpoint is ~1.5 GB zipped and ~1.6 GB
  extracted; keeping seven of them would need ~22 GB and, worse, would invite the OOM
  killer on a 7 GB machine. Download, export, verify, delete, next.
* **progress that never goes quiet.** curl's own meter during the download, and a running
  `[k/N]` counter across the whole batch so it is clear both which language is active and
  how far through the run it is. Silence during a job this long is indistinguishable from
  a hang.
* **failure isolation.** A language that fails to export or fails verification is recorded
  and skipped, not allowed to abort the batch. Shipping a broken voice is worse than
  shipping six voices and saying which one is missing.

Nothing here judges pronunciation. Each language writes a WAV that a human still has to
listen to — see the note printed at the end.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

RELEASE = ("https://github.com/AI4Bharat/Indic-TTS/releases/download/"
           "v1-checkpoints-release/{lang}.zip")

# Disaster-relevant sentence per language, so verification exercises the vocabulary the
# app actually cares about rather than a generic greeting.
SENTENCE = {
    "gu": "અહીં ભૂકંપ આવ્યો છે, ત્રણ લોકો ઘાયલ છે",
    "mr": "इथे भूकंप झाला आहे, तीन लोक जखमी आहेत",
    "kn": "ಇಲ್ಲಿ ಭೂಕಂಪ ಸಂಭವಿಸಿದೆ, ಮೂರು ಜನ ಗಾಯಗೊಂಡಿದ್ದಾರೆ",
    "ml": "ഇവിടെ ഭൂകമ്പം ഉണ്ടായി, മൂന്ന് പേർക്ക് പരിക്കേറ്റു",
    "ta": "இங்கு நிலநடுக்கம் ஏற்பட்டது, மூன்று பேர் காயமடைந்தனர்",
    "te": "ఇక్కడ భూకంపం సంభవించింది, ముగ్గురు గాయపడ్డారు",
    "bn": "এখানে ভূমিকম্প হয়েছে, তিনজন আহত হয়েছেন",
}

NAME = {"gu": "Gujarati", "mr": "Marathi", "kn": "Kannada", "ml": "Malayalam",
        "ta": "Tamil", "te": "Telugu", "bn": "Bengali"}

PY = Path(__file__).resolve().parent / "ttsenv" / "bin" / "python"
HERE = Path(__file__).resolve().parent


def banner(msg: str) -> None:
    print(f"\n{'=' * 78}\n{msg}\n{'=' * 78}", flush=True)


def run(cmd: list[str], **kw) -> int:
    """Runs a child process with its output passed straight through, unbuffered."""
    return subprocess.call(cmd, **kw)


def download(lang: str, dest: Path) -> bool:
    if dest.is_file() and dest.stat().st_size > 1_000_000_000:
        print(f"  checkpoint already present ({dest.stat().st_size/1e9:.2f} GB), skipping download",
              flush=True)
        return True
    url = RELEASE.format(lang=lang)
    print(f"  GET {url}", flush=True)
    # --progress-bar writes to stderr and is inherited, so curl's own meter is visible.
    # -C - resumes a partial file rather than restarting ~1.5 GB.
    return run(["curl", "-4", "-L", "--fail", "--retry", "5", "--retry-delay", "3",
                "-C", "-", "--progress-bar", "-o", str(dest), url]) == 0


def export_one(lang: str, extracted: Path, out: Path,
               pos: list[int], total_models: int) -> bool:
    """
    Streams export_tts.py, annotating its stage lines with a batch-wide model counter.

    The counter is what makes a long run legible: export_tts.py knows it is on FastPitch,
    but only this driver knows that is model 5 of 14.
    """
    cmd = [str(PY), "-u", str(HERE / "export_tts.py"),
           "--lang", lang, "--ckpt-root", str(extracted), "--out", str(out)]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1)
    stage_started: float | None = None
    stage_name = ""
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip("\n")
        m = re.search(r"=== \w+: (FastPitch|HiFi-GAN) ===", line)
        if m:
            stage_name = m.group(1)
            stage_started = time.time()
            pos[0] += 1
            print(f"[{pos[0]}/{total_models}] {NAME[lang]} {stage_name} — starting", flush=True)
        elif line.strip().startswith("quantised") and stage_started is not None:
            took = time.time() - stage_started
            print(f"    {line.strip()}", flush=True)
            print(f"[{pos[0]}/{total_models}] {NAME[lang]} {stage_name} — done ({took:.0f}s)",
                  flush=True)
            stage_started = None
        else:
            print(f"    {line}", flush=True)
    return proc.wait() == 0


def verify_one(lang: str, out: Path) -> bool:
    cmd = [str(PY), "-u", str(HERE / "verify_tts.py"),
           "--dir", str(out / lang), "--lang", lang, "--text", SENTENCE[lang]]
    return run(cmd) == 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", nargs="+", required=True)
    ap.add_argument("--out", type=Path, default=HERE / "out")
    ap.add_argument("--work", type=Path, default=HERE / "checkpoints")
    ap.add_argument("--keep", action="store_true",
                    help="keep the checkpoint archive and extraction (uses ~3 GB per language)")
    args = ap.parse_args()

    args.work.mkdir(parents=True, exist_ok=True)
    args.out.mkdir(parents=True, exist_ok=True)
    extracted_root = HERE / "extracted"

    total_models = 2 * len(args.langs)
    pos = [0]
    results: dict[str, str] = {}
    batch_started = time.time()

    for i, lang in enumerate(args.langs, 1):
        banner(f"[language {i}/{len(args.langs)}] {NAME.get(lang, lang)} ({lang})"
               f"   — model {pos[0]}/{total_models} done so far")
        t0 = time.time()
        zip_path = args.work / f"{lang}.zip"

        try:
            if not download(lang, zip_path):
                results[lang] = "FAILED: checkpoint download"
                pos[0] += 2
                continue

            print("  extracting…", flush=True)
            extracted_root.mkdir(parents=True, exist_ok=True)
            if run(["unzip", "-q", "-o", str(zip_path), "-d", str(extracted_root)]) != 0:
                results[lang] = "FAILED: unzip"
                pos[0] += 2
                continue

            if not export_one(lang, extracted_root, args.out, pos, total_models):
                results[lang] = "FAILED: export"
                continue

            print(f"  verifying {NAME.get(lang, lang)}…", flush=True)
            ok = verify_one(lang, args.out)
            results[lang] = "ok" if ok else "FAILED: verification"

        finally:
            # Reclaim before the next language regardless of outcome — this is what keeps
            # peak disk near one language rather than the whole batch.
            if not args.keep:
                shutil.rmtree(extracted_root / lang, ignore_errors=True)
                zip_path.unlink(missing_ok=True)

        print(f"  {NAME.get(lang, lang)}: {results[lang]}  "
              f"({time.time() - t0:.0f}s)", flush=True)

    banner(f"batch finished in {(time.time() - batch_started)/60:.1f} min")
    for lang in args.langs:
        print(f"  {NAME.get(lang, lang):<10} {lang:<3} {results.get(lang, 'not reached')}")

    bad = [l for l, r in results.items() if r != "ok"]
    print("\nSpectral checks passed for the languages marked ok. They cannot judge "
          "pronunciation —\nlisten to out/<lang>/sample-<lang>.wav before shipping any of "
          "them.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
