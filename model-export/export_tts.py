#!/usr/bin/env python3
"""
Exports AI4Bharat Indic-TTS (Coqui FastPitch + HiFi-GAN V1) to int8 ONNX for EchoBharat.

Produces, per language:
    fastpitch-<lang>.int8.onnx    tokens -> mel      [1,T] int64 -> [1,80,F]
    hifigan-<lang>.int8.onnx      mel    -> audio    [1,80,F]    -> [1,1,S] or [1,S]
    fastpitch-<lang>.tokens.json  symbol table indexed by token id

The two models are exported separately, matching the app's two-stage TtsEngine: the
vocoder is the expensive half and is the piece most likely to be swapped or requantised
later, so it must not be fused into the acoustic model.

The checkpoints carry optimizer state (~1.6 GB per language); only the weights are
loaded, and each model is released before the next is built so peak RSS stays near the
size of a single model rather than the whole pack.
"""
from __future__ import annotations

import argparse
import gc
import json
import sys
from pathlib import Path

import torch


def log(msg: str) -> None:
    print(f"[export] {msg}", flush=True)


# --------------------------------------------------------------------------- FastPitch

class FastPitchWrapper(torch.nn.Module):
    """
    Traceable wrapper exposing only tokens -> mel.

    Coqui's inference() returns a dict and takes an aux_input mapping; neither traces
    cleanly, so the speaker id is bound at export time and the mel tensor is unwrapped.

    [transpose] is decided eagerly by the caller rather than tested inside forward:
    branching on `mel.shape[1] != 80` is a data-dependent guard that aborts torch.export,
    and the orientation is a property of the architecture, not of the input.
    """

    def __init__(self, model: torch.nn.Module, speaker_id: int | None, transpose: bool):
        super().__init__()
        self.model = model
        self.speaker_id = speaker_id
        self.transpose = transpose

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        aux = {"d_vectors": None, "speaker_ids": None}
        if self.speaker_id is not None:
            aux["speaker_ids"] = torch.tensor([self.speaker_id], dtype=torch.long)

        out = self.model.inference(x, aux_input=aux)
        mel = out["model_outputs"] if isinstance(out, dict) else out
        # Coqui emits [B, T, 80]; HiFi-GAN wants [B, 80, T].
        if self.transpose:
            mel = mel.transpose(1, 2)
        return mel


def _patch_positional_encoding() -> None:
    """
    Removes the length guard in Coqui's PositionalEncoding.

    The guard raises when the sequence exceeds max_len (5000). Under torch.export that
    comparison becomes a data-dependent symbolic guard and aborts the export, even though
    it can never fire for our inputs — a 5000-token utterance is far past anything the
    app will synthesise. Dropping it is what allows a genuinely dynamic graph, which is
    the difference between paying for the real token count and padding every utterance to
    a fixed bucket.
    """
    import math
    from TTS.tts.layers.generic.pos_encoding import PositionalEncoding

    def forward(self, x, mask=None, first_idx=None, last_idx=None):
        x = x * math.sqrt(self.channels)
        if first_idx is None:
            pos_enc = self.pe[:, :, : x.size(2)]
            if mask is not None:
                pos_enc = pos_enc * mask
            x = x + (self.scale * pos_enc if self.use_scale else pos_enc)
        else:
            sliced = self.pe[:, :, first_idx:last_idx]
            x = x + (self.scale * sliced if self.use_scale else sliced)
        if hasattr(self, "dropout"):
            x = self.dropout(x)
        return x

    PositionalEncoding.forward = forward
    log("  patched PositionalEncoding (dropped static length guard)")


def export_fastpitch(ckpt_dir: Path, out_path: Path, speaker_id: int | None) -> None:
    from TTS.tts.configs.fast_pitch_config import FastPitchConfig
    from TTS.tts.models.forward_tts import ForwardTTS

    _patch_positional_encoding()

    config = FastPitchConfig()
    config.load_json(str(ckpt_dir / "config.json"))

    # The published configs carry the training machine's relative path for the speaker
    # map ("models/v1/<lang>/fastpitch/speakers.pth"), which does not exist here. Point
    # both copies of the setting at the file that actually shipped in the archive.
    speakers = ckpt_dir / "speakers.pth"
    if speakers.is_file():
        config.speakers_file = str(speakers)
        if hasattr(config, "model_args"):
            config.model_args.speakers_file = str(speakers)
        log(f"  speakers: {speakers}")

    model = ForwardTTS.init_from_config(config)
    model.load_checkpoint(config, str(ckpt_dir / "best_model.pth"), eval=True)
    model.eval()

    dummy = torch.randint(low=4, high=40, size=(1, 24), dtype=torch.long)

    # Probe the mel orientation once, eagerly, then bake the answer into the wrapper.
    probe = FastPitchWrapper(model, speaker_id, transpose=False).eval()
    with torch.no_grad():
        raw = probe(dummy)
    needs_transpose = raw.dim() == 3 and raw.shape[1] != 80 and raw.shape[2] == 80
    log(f"  raw mel {tuple(raw.shape)} -> transpose={needs_transpose}")

    wrapper = FastPitchWrapper(model, speaker_id, transpose=needs_transpose).eval()
    with torch.no_grad():
        mel = wrapper(dummy)
    if mel.shape[1] != 80:
        raise SystemExit(f"expected 80 mel bins, got shape {tuple(mel.shape)}")
    log(f"  fastpitch smoke test: tokens {tuple(dummy.shape)} -> mel {tuple(mel.shape)}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy,),
            str(out_path),
            input_names=["text"],
            output_names=["mel"],
            dynamic_axes={"text": {1: "tokens"}, "mel": {2: "frames"}},
            opset_version=17,
            do_constant_folding=True,
            # The dynamo exporter is required here: torch.nn.MultiheadAttention inside
            # the fftransformer encoder bakes the traced sequence length into a Reshape
            # under the legacy TorchScript tracer, producing a graph that only accepts
            # the dummy length. dynamo keeps the axis symbolic.
            dynamo=True,
        )
    log(f"  wrote {out_path.name} ({out_path.stat().st_size / 1e6:.1f} MB fp32)")

    del wrapper, probe, model
    gc.collect()


# ---------------------------------------------------------------------------- HiFi-GAN

class HifiganWrapper(torch.nn.Module):
    """Mel -> waveform. Coqui wraps the generator in a GAN container."""

    def __init__(self, generator: torch.nn.Module):
        super().__init__()
        self.generator = generator

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        return self.generator(mel)


def export_hifigan(ckpt_dir: Path, out_path: Path) -> None:
    from TTS.vocoder.configs.hifigan_config import HifiganConfig
    from TTS.vocoder.models.gan import GAN

    config = HifiganConfig()
    config.load_json(str(ckpt_dir / "config.json"))

    model = GAN.init_from_config(config)
    model.load_checkpoint(config, str(ckpt_dir / "best_model.pth"), eval=True)

    generator = getattr(model, "model_g", model)
    # Weight norm is a training-time reparameterisation that does not trace, so it must
    # be folded away before export. Coqui's load_checkpoint(eval=True) already does this
    # for most builds, and calling it twice raises on the un-parametrised modules — so a
    # failure here means it was already removed, which is the state we want anyway.
    if hasattr(generator, "remove_weight_norm"):
        try:
            generator.remove_weight_norm()
            log("  removed weight norm")
        except (ValueError, RuntimeError) as e:
            log(f"  weight norm already folded ({type(e).__name__})")
    generator.eval()

    wrapper = HifiganWrapper(generator).eval()
    dummy = torch.randn(1, 80, 40)

    with torch.no_grad():
        audio = wrapper(dummy)
    log(f"  hifigan smoke test: mel {tuple(dummy.shape)} -> audio {tuple(audio.shape)}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy,),
            str(out_path),
            input_names=["mel"],
            output_names=["audio"],
            dynamic_axes={"mel": {2: "frames"}, "audio": {2: "samples"}},
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )
    log(f"  wrote {out_path.name} ({out_path.stat().st_size / 1e6:.1f} MB fp32)")

    del wrapper, generator, model
    gc.collect()


# -------------------------------------------------------------------------- quantise

def quantize(src: Path, dst: Path) -> None:
    """Dynamic int8 quantisation — weights only, so no calibration set is needed."""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
    )
    before = src.stat().st_size / 1e6
    after = dst.stat().st_size / 1e6
    log(f"  quantised {dst.name}: {before:.1f} MB -> {after:.1f} MB ({after / before:.0%})")


# ------------------------------------------------------------------------------ main

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--ckpt-root", type=Path, required=True,
                    help="directory holding <lang>/fastpitch and <lang>/hifigan")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--speaker-id", type=int, default=0,
                    help="these packs are trained on a male and a female speaker")
    ap.add_argument("--keep-fp32", action="store_true")
    args = ap.parse_args()

    lang = args.lang
    fp_dir = args.ckpt_root / lang / "fastpitch"
    hg_dir = args.ckpt_root / lang / "hifigan"
    for d in (fp_dir, hg_dir):
        if not (d / "best_model.pth").is_file():
            log(f"missing checkpoint: {d / 'best_model.pth'}")
            return 1

    out = args.out / lang
    out.mkdir(parents=True, exist_ok=True)
    torch.set_grad_enabled(False)

    log(f"=== {lang}: symbol table ===")
    sys.argv = ["make_tokens", str(fp_dir / "config.json"), str(out / f"fastpitch-{lang}.tokens.json")]
    import make_tokens
    make_tokens.main()

    log(f"=== {lang}: FastPitch ===")
    fp32_fp = out / f"fastpitch-{lang}.fp32.onnx"
    export_fastpitch(fp_dir, fp32_fp, args.speaker_id)
    quantize(fp32_fp, out / f"fastpitch-{lang}.int8.onnx")

    log(f"=== {lang}: HiFi-GAN ===")
    fp32_hg = out / f"hifigan-{lang}.fp32.onnx"
    export_hifigan(hg_dir, fp32_hg)
    quantize(fp32_hg, out / f"hifigan-{lang}.int8.onnx")

    if not args.keep_fp32:
        for f in (fp32_fp, fp32_hg):
            f.unlink(missing_ok=True)
            # The dynamo exporter writes weights to a sidecar rather than inlining them,
            # so removing the .onnx alone leaves a few hundred MB behind.
            Path(str(f) + ".data").unlink(missing_ok=True)

    log(f"=== {lang}: done ===")
    for f in sorted(out.iterdir()):
        log(f"  {f.name:34s} {f.stat().st_size / 1e6:8.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
