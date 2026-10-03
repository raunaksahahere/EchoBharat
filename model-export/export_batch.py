#!/usr/bin/env python3
"""Run guarded TTS exports against existing local checkpoints, one language at a time.

Never downloads or deletes checkpoints. Retains fp32 intermediates and verification WAVs.
A successful batch is local validation only, not publication or Android device evidence.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

SENTENCE = {
    "hi": "यहाँ भूकंप आया है, तीन लोग घायल हैं",
    "en": "There was an earthquake here, three people are injured",
    "gu": "અહીં ભૂકંપ આવ્યો છે, ત્રણ લોકો ઘાયલ છે",
    "mr": "इथे भूकंप झाला आहे, तीन लोक जखमी आहेत",
    "kn": "ಇಲ್ಲಿ ಭೂಕಂಪ ಸಂಭವಿಸಿದೆ, ಮೂರು ಜನ ಗಾಯಗೊಂಡಿದ್ದಾರೆ",
    "ml": "ഇവിടെ ഭൂകമ്പം ഉണ്ടായി, മൂന്ന് പേർക്ക് പരിക്കേറ്റു",
    "ta": "இங்கு நிலநடுக்கம் ஏற்பட்டது, மூன்று பேர் காயமடைந்தனர்",
    "te": "ఇక్కడ భూకంపం సంభవించింది, ముగ్గురు గాయపడ్డారు",
    "bn": "এখানে ভূমিকম্প হয়েছে, তিনজন আহত হয়েছেন",
}
HERE = Path(__file__).resolve().parent


def run(cmd: list[str]) -> int:
    return subprocess.call(cmd)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--langs", nargs="+", required=True, choices=sorted(SENTENCE))
    ap.add_argument("--ckpt-root", type=Path, required=True,
                    help="existing local <lang>/{fastpitch,hifigan} directories")
    ap.add_argument("--out", type=Path, default=HERE / "out")
    ap.add_argument("--python", default=sys.executable, help="interpreter with export dependencies")
    args = ap.parse_args()
    if len(set(args.langs)) != len(args.langs):
        ap.error("duplicate languages are not supported")

    results = {}
    started = time.perf_counter()
    for index, lang in enumerate(args.langs, 1):
        print(f"[{index}/{len(args.langs)}] {lang}: local export", flush=True)
        try:
            code = run([args.python, "-u", str(HERE / "export_tts.py"), "--lang", lang,
                        "--ckpt-root", str(args.ckpt_root), "--out", str(args.out)])
            if code:
                results[lang] = "FAILED: export"
                continue
            code = run([args.python, "-u", str(HERE / "verify_tts.py"),
                        "--dir", str(args.out / lang), "--lang", lang, "--text", SENTENCE[lang]])
            results[lang] = "LOCAL PASS" if code == 0 else "FAILED: verification"
        except OSError as exc:
            results[lang] = f"FAILED: {exc}"
        print(f"  {lang}: {results[lang]}", flush=True)

    print(f"Batch finished in {(time.perf_counter() - started) / 60:.1f} min")
    for lang, result in results.items():
        print(f"  {lang}: {result}")
    print("No downloads or publication performed. Listen to out/<lang>/sample-<lang>.wav "
          "and test ORT 1.20.0 on Android before release; fp32 files remain local.")
    return int(any(result != "LOCAL PASS" for result in results.values()))


if __name__ == "__main__":
    sys.exit(main())
