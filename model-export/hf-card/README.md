---
license: other
license_name: mixed-mit-and-apache-2.0
license_link: LICENSE
language:
  - hi
  - en
  - bn
  - gu
  - kn
  - ml
  - mr
  - ta
  - te
library_name: onnx
pipeline_tag: text-to-speech
tags:
  - onnx
  - onnxruntime
  - text-to-speech
  - translation
  - on-device
  - android
  - indic
---

# EchoBharat models

Quantized ONNX models for [EchoBharat](https://github.com/raunaksahahere/EchoBharat), an
offline multilingual voice transceiver for Android. Phones turn speech into text locally,
send only the text across a Bluetooth LE mesh, and turn it back into speech on the other
side.

Everything here is a **derived redistribution** of work by other people. Nothing in this
repository was trained here. See [Licensing](#licensing) before you use any of it — the
files are not all under the same terms.

## What is in this repository

| Path | Contents | Derived from |
|---|---|---|
| `<lang>/fastpitch-<lang>.int8.onnx` | FastPitch acoustic model | AI4Bharat Indic-TTS |
| `<lang>/hifigan-<lang>.int8.onnx` | HiFi-GAN vocoder | AI4Bharat Indic-TTS |
| `<lang>/fastpitch-<lang>.tokens.json` | symbol table, indexed by token id | AI4Bharat Indic-TTS |
| `<lang>/sample-<lang>.wav` | a sample utterance, for listening | generated here |
| `mt/<lang>/mt-*-encoder.int8.onnx` | IndicTrans2 encoder | AI4Bharat IndicTrans2 |
| `mt/<lang>/mt-*-decoder.int8.onnx` | IndicTrans2 decoder | AI4Bharat IndicTrans2 |
| `mt/<lang>/mt-*-bpe-{src,tgt}.tsv` | flattened BPE vocabulary | AI4Bharat IndicTrans2 |
| `mt/<lang>/mt-*-meta.json` | language-tag ids and vocab sizes | AI4Bharat IndicTrans2 |

**Speech recognition is not hosted here.** EchoBharat uses the Apache-2.0 IndicConformer
conversions published at
[`parismitaglobalsolutions/indicconformer-sherpa-onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)
and links to them directly. They are documented below because anyone assembling a full
voice pack needs all three pieces.

## Graph contracts

Read off the exported graphs, not copied from upstream documentation.

### Speech-to-text — IndicConformer (hosted elsewhere, listed for completeness)

```
input   audio_signal  [B, 80, T]  float32     log-mel features
        length        [B]         int64       valid frames per item
output  logprobs      [B, T/4, V] float32     V = 5633 for the released vocabularies
```

Features are NeMo-compatible: 25 ms window, 10 ms hop, 512-point FFT, 80 Slaney mel bins,
0.97 pre-emphasis, log, then `per_feature` normalisation. Subsampling factor is 4.
Decoding is greedy CTC, and the blank token `<blk>` lives **inside** the vocabulary at id
5632 rather than being appended after it — assuming the usual "blank is index V" produces
plausible-looking but wrong transcripts.

### Text-to-speech — FastPitch + HiFi-GAN

```
fastpitch  input   text   [1, T]      int64     token ids from the .tokens.json table
           output  mel    [1, 80, F]  float32
hifigan    input   mel    [1, 80, F]  float32
           output  audio  [1, 1, S]   float32   22050 Hz, mono, roughly [-1, 1]
```

Both are int8 dynamically quantized, weights only. The token axis and frame axis are
dynamic. The two stages are exported separately rather than fused, so the vocoder — the
expensive half — can be swapped or requantized on its own.

Tokenisation is longest-match against the symbol table in `fastpitch-<lang>.tokens.json`,
where the first id wins on duplicates.

> **Exporter note.** FastPitch must be exported with PyTorch's **dynamo** exporter. Its
> `fftransformer` encoder is built on `torch.nn.MultiheadAttention`, which bakes the traced
> sequence length into a `Reshape` under the legacy TorchScript tracer. The result loads
> without complaint and then fails at runtime on any sentence that is not exactly the dummy
> length: `input_shape_size == requested_shape_size was false … requested shape:{24,1,512}`.

### Translation — IndicTrans2 (distilled 200M)

```
encoder  input   input_ids              [B, S]       int64
                 attention_mask         [B, S]       int64
         output  last_hidden_state      [B, S, 512]  float32

decoder  input   input_ids              [B, T]       int64
                 encoder_attention_mask [B, S]       int64
                 encoder_hidden_states  [B, S, 512]  float32
         output  logits                 [B, T, V]    float32
                 present.*              (72 KV-cache tensors)
```

`decoder_start_token_id` = 2, which is also `</s>`. `pad` = 1, `bos` = 0, `unk` = 3.
Maximum source length is 256 tokens. The decoder here is the **cacheless** variant, so a
greedy loop re-runs the whole prefix each step; the 72 `present.*` outputs are ignored.
Using `decoder_with_past_model.onnx` instead is the obvious speed-up.

The source sequence is `[<src_lang_tag>, <tgt_lang_tag>, …bpe pieces, </s>]`.

**Two traps worth stating plainly:**

1. The SentencePiece piece ids and the ONNX graph vocabulary ids are **different**.
   Upstream ships a `.model` file *and* a separate `dict.*.json`; conflating the two
   produces fluent nonsense rather than an error. The `bpe-*.tsv` files here are the two
   joined together — `piece <TAB> score <TAB> graph_id` — so this cannot be got wrong. A
   `graph_id` of `-1` means the piece exists in SentencePiece but not in the graph
   vocabulary and must resolve to `<unk>`.
2. The FLORES language-tag ids **differ per direction**: `hin_Deva` is `8` going
   Indic→English and `15` going English→Indic. Hardcoding either value silently corrupts
   the other direction. They ship in `mt-*-meta.json`.

## Which translation direction goes on which phone

EchoBharat translates **on receive, never on send**. A sender transmits in its own
language and tags the packet with that language; each receiving phone translates into
whatever language *it* is set to. That is what lets one broadcast reach a Hindi speaker
and an English speaker simultaneously, each hearing their own — a sender that translated
first would have to pick one target and the broadcast would stop being multilingual.

The consequence for packaging: **a phone needs only the direction *into* its own
language.**

| Phone's language | Needs | Files | Size |
|---|---|---|---|
| English | Hindi → English | `mt/en/mt-hi-en-*` | 226 MB |
| Hindi | English → Hindi | `mt/hi/mt-en-hi-*` | 270 MB |

So an English handset carries `mt/en/` and nothing else; it never needs `mt/hi/`.

Translation is available for **Hindi ↔ English only**. The other languages have
speech-to-text and text-to-speech but no translation pair, and the app reports that
honestly rather than passing text through as though it had been translated.

## Licensing

These files are **not all under one license.** Redistributing them means honouring each
upstream's terms separately.

| Component | Upstream | Upstream license | Notes |
|---|---|---|---|
| FastPitch / HiFi-GAN weights | [AI4Bharat/Indic-TTS](https://github.com/AI4Bharat/Indic-TTS) | **MIT** | checkpoints from the `v1-checkpoints-release` GitHub release |
| IndicTrans2 weights | [ai4bharat/indictrans2-*](https://huggingface.co/ai4bharat) | **MIT** | via the ONNX conversion below |
| IndicTrans2 ONNX conversion | [TigreGotico/indictrans2-*-dist-200M-onnx](https://huggingface.co/TigreGotico) | **MIT** | conversion this repo re-quantized/repacked |
| IndicConformer weights *(linked, not hosted)* | [ai4bharat/indicconformer_stt_*](https://huggingface.co/ai4bharat) | **MIT** | |
| IndicConformer ONNX conversion *(linked, not hosted)* | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) | **Apache-2.0** | the conversion declares Apache-2.0 even though the upstream weights are MIT; honour both |
| Silero VAD *(shipped in the app, not here)* | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | **MIT** | |
| Export and packaging scripts | this project | **MIT** | |

The repository is marked `license: other` because a single SPDX identifier would
misrepresent at least one of the components above.

**No new training data was used, and no new model was trained.** Every weight here is a
quantized re-export of a published checkpoint. Attribution belongs to AI4Bharat and to the
authors of the conversions named above.

## Known limitations

- **Quantization is lossy.** All models are int8. Quality is below the fp32 originals; how
  far below has not been measured against a reference.
- **Odia is absent.** No IndicConformer speech-to-text export exists for it, so a voice
  pack would be incomplete in the one direction that matters most.
- **Translation has no KV cache**, so decoding cost grows with the square of the output
  length.
- **No IndicNLP normalisation** in the translation path — NFKC only, unlike the reference
  IndicTrans2 pipeline. Unusual orthography is untested.
- **Voice quality has not been formally evaluated.** Each language ships a
  `sample-<lang>.wav` precisely so it can be listened to rather than taken on trust.

## Citation

Please cite the upstream work rather than this repository.

```bibtex
@article{ai4bharat-indictrans2,
  title   = {IndicTrans2: Towards High-Quality and Accessible Machine Translation
             Models for all 22 Scheduled Indian Languages},
  author  = {AI4Bharat},
  journal = {Transactions on Machine Learning Research},
  year    = {2023}
}
```
