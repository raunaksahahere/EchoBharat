#!/usr/bin/env python3
"""
Locally checks an ONNX candidate pair and retains a WAV for human listening.

Uses longest-match tokenisation and the two-stage tensor contract, but fails on unmapped
characters instead of silently dropping them. Desktop CPU checks and RTF measurements
are not proof of Android kernel support, device speed, or correct pronunciation.
"""
from __future__ import annotations

import argparse
import struct
import sys
import time
from pathlib import Path

import numpy as np

from tts_validation import (create_session, load_symbols, validate_audio,
                            validate_dynamic_session, validate_graph, validate_mel)


def tokenize(text: str, symbols: list[str]) -> list[int]:
    """Longest-match over the symbol table — the same rule FastPitchTts.tokenize uses."""
    # First id wins on duplicates, matching the Kotlin putIfAbsent.
    lookup: dict[str, int] = {}
    for i, s in enumerate(symbols):
        lookup.setdefault(s, i)

    longest = max((len(s) for s in lookup), default=1)
    ids: list[int] = []
    i = 0
    skipped = []
    while i < len(text):
        for n in range(min(longest, len(text) - i), 0, -1):
            chunk = text[i:i + n]
            if chunk in lookup:
                ids.append(lookup[chunk])
                i += n
                break
        else:
            skipped.append(text[i])
            i += 1
    if skipped:
        raise ValueError(f"unmapped characters: {''.join(skipped)!r}")
    return ids


def write_wav(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    if sample_rate <= 0 or audio.ndim != 1 or not audio.size or not np.isfinite(audio).all():
        raise ValueError("refusing WAV: need positive sample rate and finite nonempty mono audio")
    pcm = np.clip(audio, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    data = pcm.tobytes()
    with open(path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + len(data)))
        f.write(b"WAVEfmt ")
        f.write(struct.pack("<IHHIIHH", 16, 1, 1, sample_rate, sample_rate * 2, 2, 16))
        f.write(b"data")
        f.write(struct.pack("<I", len(data)))
        f.write(data)


def spectral_report(audio: np.ndarray, sample_rate: int) -> dict:
    """
    Three cheap measurements that separate speech from a plausible-looking failure.

    A vocoder handed a mis-shaped or garbage mel still emits audio with a healthy RMS and
    a sensible duration, so level checks alone pass it. What it emits is almost always
    steady hiss or a constant buzz, and those differ from speech in ways that survive not
    knowing the language:

    * **envelope dynamic range** - speech alternates loud syllables with near-silent
      closures, so its frame energies span a wide range. Steady noise does not.
    * **active frame fraction** - real utterances contain pauses. Something active in
      every single frame is a drone; something active in almost none is silence.
    * **spectral tilt** - voiced speech loses energy with rising frequency. White-ish
      noise is flat or rising, so a non-negative tilt is a strong noise signal.

    None of these can judge whether the *pronunciation* is right - only a listener can do
    that, which is why a WAV is always written alongside.
    """
    frame = max(256, int(0.025 * sample_rate))
    hop = max(128, int(0.010 * sample_rate))
    if audio.size < frame * 4:
        return {"dyn_range_db": 0.0, "active_frac": 0.0, "tilt_db": 0.0}

    n_frames = 1 + (audio.size - frame) // hop
    idx = np.arange(frame)[None, :] + hop * np.arange(n_frames)[:, None]
    frames = audio[idx]

    energy = np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-12
    db = 20.0 * np.log10(energy)

    # Percentiles rather than min/max: one clipped sample or one dead frame should not
    # define the range.
    dyn_range_db = float(np.percentile(db, 95) - np.percentile(db, 5))

    # "Active" relative to this utterance's own peak, so the threshold does not depend on
    # absolute gain, which varies per language pack.
    active_frac = float(np.mean(db > (db.max() - 35.0)))

    window = np.hanning(frame)
    spectrum = np.abs(np.fft.rfft(frames * window, axis=1)) ** 2
    power = spectrum.mean(axis=0) + 1e-12
    freqs = np.fft.rfftfreq(frame, 1.0 / sample_rate)

    # Energy below 1 kHz against energy above 4 kHz — the coarse slope of the spectrum.
    low = power[(freqs >= 100) & (freqs < 1000)].mean()
    high = power[freqs >= 4000].mean() if (freqs >= 4000).any() else power[-1]
    tilt_db = float(10.0 * np.log10(high / low))

    return {"dyn_range_db": dyn_range_db, "active_frac": active_frac, "tilt_db": tilt_db}


def synthesize(fp, hg, tokens):
    started = time.perf_counter()
    mel = fp.run(None, {fp.get_inputs()[0].name: tokens})[0]
    validate_mel(mel)
    audio = hg.run(None, {hg.get_inputs()[0].name: mel})[0]
    validate_audio(audio)
    elapsed = time.perf_counter() - started
    return mel, audio.reshape(-1), elapsed


def benchmark(fp, hg, tokens, sample_rate: int, warmup: int, runs: int):
    if sample_rate <= 0 or warmup < 0 or runs < 1:
        raise ValueError("invalid benchmark sample rate, warmup or run count")
    for _ in range(warmup):
        synthesize(fp, hg, tokens)
    timings, rtfs = [], []
    for _ in range(runs):
        mel, audio, elapsed = synthesize(fp, hg, tokens)
        timings.append(elapsed)
        rtfs.append(elapsed / (audio.size / sample_rate))
    return mel, audio, timings, rtfs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True, help="directory of exported files")
    ap.add_argument("--lang", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--sample-rate", type=int, default=22050)
    ap.add_argument("--fastpitch", type=Path, help="candidate path, relative to --dir or absolute")
    ap.add_argument("--hifigan", type=Path, help="candidate path, relative to --dir or absolute")
    ap.add_argument("--tokens", type=Path, help="symbol table path, relative to --dir or absolute")
    ap.add_argument("--wav", type=Path, help="retained output WAV path (default: --dir/sample-<lang>.wav)")
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--runs", type=int, default=3, help="timed end-to-end CPU runs")
    args = ap.parse_args()
    if args.sample_rate <= 0 or args.warmup < 0 or args.runs < 1:
        ap.error("sample rate and runs must be positive; warmup must be nonnegative")

    d = args.dir
    fp_path = d / (args.fastpitch or f"fastpitch-{args.lang}.v2.onnx")
    hg_path = d / (args.hifigan or f"hifigan-{args.lang}.v2.onnx")
    symbols = load_symbols(d / (args.tokens or f"fastpitch-{args.lang}.v2.tokens.json"))
    print(f"symbols: {len(symbols)}")

    ids = tokenize(args.text, symbols)
    print(f"text   : {args.text!r}")
    print(f"tokens : {len(ids)} -> {ids[:20]}{'...' if len(ids) > 20 else ''}")
    if not ids:
        print("FAIL: no tokens produced")
        return 1

    validate_graph(fp_path, "fastpitch")
    validate_graph(hg_path, "hifigan")
    fp = create_session(fp_path)
    hg = create_session(hg_path)
    print(f"dynamic token probes: {validate_dynamic_session(fp, 'fastpitch', ids)}")
    print(f"dynamic frame probes: {validate_dynamic_session(hg, 'hifigan')}")
    print(f"fastpitch in : {[(i.name, i.shape) for i in fp.get_inputs()]}")
    print(f"fastpitch out: {[(o.name, o.shape) for o in fp.get_outputs()]}")
    print(f"hifigan   in : {[(i.name, i.shape) for i in hg.get_inputs()]}")
    print(f"hifigan   out: {[(o.name, o.shape) for o in hg.get_outputs()]}")

    tokens = np.array([ids], dtype=np.int64)
    mel, audio, timings, rtfs = benchmark(fp, hg, tokens, args.sample_rate, args.warmup, args.runs)
    print(f"mel    : {mel.shape}  range [{mel.min():.2f}, {mel.max():.2f}]")
    print(f"audio  : {audio.shape}  range [{audio.min():.3f}, {audio.max():.3f}]")
    print(f"desktop CPU benchmark ({args.runs} runs, {args.warmup} warmups, excludes load): "
          f"median {np.median(timings):.3f}s, RTF median {np.median(rtfs):.3f}, "
          f"RTF range {min(rtfs):.3f}–{max(rtfs):.3f}")

    seconds = audio.size / args.sample_rate
    rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
    peak = float(np.max(np.abs(audio)))
    print(f"duration: {seconds:.2f}s   rms {rms:.4f}   peak {peak:.4f}")

    out = args.wav or d / f"sample-{args.lang}.wav"
    write_wav(out, audio.astype(np.float32), args.sample_rate)
    print(f"wrote   : {out}")

    spec = spectral_report(audio.astype(np.float64), args.sample_rate)
    print(f"envelope: dynamic range {spec['dyn_range_db']:.1f} dB   "
          f"active frames {spec['active_frac']*100:.0f}%   "
          f"spectral tilt {spec['tilt_db']:+.1f} dB")

    # A working vocoder gives audible level and a plausible duration for the token count.
    problems = []
    if peak < 0.01:
        problems.append(f"near-silent output (peak {peak:.4f})")
    if rms < 0.001:
        problems.append(f"no energy (rms {rms:.5f})")
    if seconds < 0.2:
        problems.append(f"implausibly short ({seconds:.2f}s for {len(ids)} tokens)")

    # Spectral checks catch the failures that level alone does not. A vocoder fed a
    # mis-shaped or garbage mel still produces *something* with a healthy RMS — usually
    # broadband hiss or a constant buzz — and those are exactly what these three separate
    # from speech.
    if spec["dyn_range_db"] < 15.0:
        # Speech alternates loud syllables with near-silent closures; steady noise does not.
        problems.append(f"flat envelope ({spec['dyn_range_db']:.1f} dB range) — buzz, not speech")
    if spec["active_frac"] > 0.98:
        problems.append(f"no silence anywhere ({spec['active_frac']*100:.0f}% active) — likely hiss")
    if spec["active_frac"] < 0.15:
        problems.append(f"almost entirely silent ({spec['active_frac']*100:.0f}% active)")
    if spec["tilt_db"] > -3.0:
        # Voiced speech falls off with frequency; white-ish noise is flat or rising.
        problems.append(f"no spectral rolloff ({spec['tilt_db']:+.1f} dB) — noise-like")

    if problems:
        print("SUSPECT: " + "; ".join(problems))
        return 1
    print("LOCAL PASS: audio looks well-formed — listen to the retained WAV; Android tests still required")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"REJECTED: {exc}")
        sys.exit(1)
