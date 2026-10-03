# EchoBharat model export

## 1. Local TTS v2 candidates — not published or device-verified

The tooling targets **Android ONNX Runtime 1.20.0**, with a deliberately conservative
local gate. Actual speech checkpoints, exported ONNX files, and the export virtual
environment are absent from this checkout. No real speech graph inspection, model
hashes, phone tests, pronunciation evidence, or RTF results were produced by this change.
Existing legacy filenames are not proof that those artifacts are Android-compatible.

The new default is **float16 internal weights/operations with `keep_io_types=True`**:
FastPitch still accepts int64 `[1,T]` tokens and returns float32 `[1,80,F]` mel;
HiFi-GAN accepts float32 mel and returns float32 `[1,1,S]` or `[1,S]` audio.
Float16 conversion is a candidate, not a guarantee of smaller latency or CPU kernel support.

Per language, the exporter retains:

- `fastpitch-<lang>.fp32.onnx` and any exporter sidecar data.
- `hifigan-<lang>.fp32.onnx` and any exporter sidecar data.
- Only after local validation: `fastpitch-<lang>.v2.onnx`, `hifigan-<lang>.v2.onnx`,
  and `fastpitch-<lang>.v2.tokens.json`.
- Verification retains `sample-<lang>.wav` for listening, including finite audio flagged
  as suspect by the spectral heuristics. Invalid/nonfinite data never replaces a WAV.

Legacy `.int8.onnx` models and unversioned token tables are never overwritten. The
exporter refuses an output directory containing any v2 artifact; use a fresh output
root for another experiment. `--keep-fp32` remains accepted for compatibility but is now
always in effect. No int8 option is offered: dynamic MatMul-only quantization commonly
introduces `MatMulInteger` and `DynamicQuantizeLinear`, which conflict with the denylist.

## 2. Fail-closed local gates

`tts_validation.py` is shared by export, verification, and manifest preparation:

- Recursively rejects **`ConvInteger`, `MatMulInteger`, `DynamicQuantizeLinear`** in the
  main graph, graph-valued and graph-list attributes (including If/Loop bodies), and local
  function bodies. Relabeling a quantized model `.v2.onnx` cannot bypass this check.
- Runs the ONNX checker; rejects IR > 10 and standard ONNX opset > 21, outside the
  ORT 1.20 support ceiling. This is not an exhaustive static kernel allowlist.
- Requires the app's tensor ranks, static batch/channel sizes, float32 boundary types,
  and symbolic token/frame/sample axes. Candidates must embed all weights, not depend
  on a sidecar that the app will not install. Retained fp32 intermediates may use sidecars.
- Requires **desktop `onnxruntime==1.20.0`**, CPU provider, and actual inference at token
  lengths **8, 24, 41** and independent vocoder frame lengths **17, 40, 63**. Output
  lengths must change, all output arrays must be nonempty and finite, and a two-stage
  probe must run. This catches many baked trace lengths, but does not prove every length.
- The WAV verifier rejects *any* unmapped input characters (including punctuation),
  checks mel before the vocoder, checks audio before writing, and reports median/range
  **RTF = synthesis wall time / produced audio duration**. Timing excludes session loading
  and warmups; it includes both inference stages and boundary checks, not tokenization.

Only successfully validated candidates are moved out of the staging directory. A rejected
candidate returns a nonzero exit status; completed fp32 intermediates remain for diagnosis.
No gate is a replacement for comparison against fp32, listening, or Android execution.
The old positional-encoding and dynamo tracing workarounds remain in place; runtime probes
must establish that a particular exporter/checkpoint combination really preserves lengths.
HiFi-GAN continues using the legacy tracer; FastPitch uses the dynamo exporter.

## 3. Reproduce with locally available dependencies and checkpoints

Run commands from `model-export/`. The lightweight tests need only Python's standard
library; graph tests explicitly skip if their native dependencies are absent:

```sh
python3 -m unittest discover -s tests -v
python3 -m compileall -q export_tts.py verify_tts.py tts_validation.py update_manifest.py export_batch.py tests
```

For real exports, provision an isolated Python environment with a mutually compatible
CPU PyTorch/torchaudio, Coqui TTS, NumPy, ONNX, onnxscript, onnxconverter-common, and
**onnxruntime==1.20.0**. The last version is the requested runtime target, not an inferred
export-environment lock. No newly validated version pins or lockfile are provided: the
full environment must be resolved and recorded when actual checkpoints are available.
Do not silently downgrade model IR or opset numbers to pass a gate; re-export with a
compatible exporter and validate the resulting graph.

The scripts do not fetch checkpoints. Supply existing local directories:

```text
extracted/<lang>/fastpitch/config.json
extracted/<lang>/fastpitch/best_model.pth
extracted/<lang>/fastpitch/speakers.pth    (when shipped with the checkpoint)
extracted/<lang>/hifigan/config.json
extracted/<lang>/hifigan/best_model.pth
```

In that provisioned environment:

```sh
python export_tts.py --lang hi --ckpt-root extracted --out out-v2
python verify_tts.py --dir out-v2/hi --lang hi --text 'यहाँ भूकंप आया है' --warmup 1 --runs 5
```

Use a sentence fully represented by the actual symbol table. Unmapped characters are
an error, not permission to silently omit words. The selected speaker defaults to ID 0;
confirm the checkpoint's speaker mapping before drawing voice/gender conclusions.

To compare fp32 or another candidate, give explicit filenames (relative to `--dir` or
absolute), and distinct WAV paths. The verifier requires a standalone model; if a retained
fp32 export has sidecars, first create a separate inlined copy using ONNX in the provisioned
environment, without deleting the original or its sidecars.

```sh
python verify_tts.py --dir out-v2/hi --lang hi --text 'यहाँ भूकंप आया है' \
  --fastpitch fastpitch-hi.v2.onnx --hifigan hifigan-hi.v2.onnx \
  --tokens fastpitch-hi.v2.tokens.json --wav out-v2/hi/candidate-listen.wav --runs 5
python export_batch.py --langs hi en gu mr kn ml ta te bn --ckpt-root extracted --out out-v2-batch
```

The batch is local-only, isolates failures by language, keeps checkpoints/intermediates,
uses its current Python interpreter unless `--python` is supplied, and retains every
verification WAV. It never downloads, uploads, or deletes source checkpoints. Missing
checkpoints/dependencies are failures, not successful exports. Odia is not offered by
the batch and is explicitly blocked by manifest preparation.

## 4. Transactional manifest preparation (not publication)

Work on a **local manifest copy** while inspecting the migration. Nothing below uploads
artifacts or establishes that URLs exist:

```sh
python update_manifest.py --manifest manifest-candidate.json --out out-v2 --langs hi en
```

The copy must already contain the language entries and exactly one ModelSpec for each
TTS role. Every requested language must have all three nonempty v2 files. Before replacing
any manifest bytes, the helper validates token tables, both graphs, exact desktop runtime,
dynamic execution and end-to-end finite data for every requested pack. Missing languages,
missing/invalid artifacts, and Odia abort the entire operation; there is no partial update.
The replacement is a same-directory atomic file rename.

For each requested language it:

- Sets language-level `packVersion` to **2** (refuses newer/unknown versions).
- Changes TTS `fileName` and URL paths to the new v2 names, clears the old mirror URL,
  and computes SHA-256/size only from the actual local files.
- Appends each replaced **complete old ModelSpec dictionary** to language-level
  `legacyModels`, retaining unknown fields, old hashes/URLs/filenames, and prior legacy
  entries for backward import. Other models and unrequested languages are untouched.
- Is idempotent for identical artifacts and refuses different bytes under already
  recorded v2 names. Changed released artifacts need a future version, not replacement.

`--verify` is a separate explicit **network opt-in**: it downloads each touched URL and
compares bytes *before* the atomic replacement; any failure leaves the manifest unchanged.
Do not use it during an offline/local-only task. Without it, URL availability is explicitly
unverified. Local metadata preparation is not authorization to publish.

## 5. Evidence still required before release

1. Obtain authorized checkpoints and record the actual dependency versions/environment.
2. Export and inspect the real fp32/candidate graphs; retain gate logs and compare audio
   against fp32 across short/long, script-specific, and disaster-relevant sentences.
3. Listen to retained WAVs for each language/speaker; spectral heuristics cannot establish
   pronunciation or even reliably classify all valid speech.
4. Run both stages on the target Android build with ORT 1.20.0 and actual device/provider,
   record dynamic-length results, RTF, memory and failures. Desktop CPU passes do not prove
   Android kernel availability, mobile performance, or audio playback.
5. Review legacy pack import and new v2 pack behavior before any separately authorized
   upload or manifest release. Do not publish Odia.

The current checkout lacks torch, NumPy, ONNX, ONNX Runtime, onnxconverter-common and Coqui
TTS in the available Python environment. Synthetic/mocked tests are tooling evidence only;
real-model exports, Android tests, listening and publication remain outstanding.

## 6. STT models (no export needed)

Per-language int8 ONNX exports of the AI4Bharat IndicConformer weights are already
published under Apache-2.0 at
`parismitaglobalsolutions/indicconformer-sherpa-onnx`, and their URLs and checksums are
already filled into `app/src/main/assets/models/manifest.json`. Download them from inside
the app (Language Packs → Download), or fetch manually:

```
B=https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main
curl -4 -L -o out/hi/indicconformer-hi.int8.onnx  $B/hi/model.int8.onnx
curl -4 -L -o out/hi/indicconformer-hi.tokens.txt $B/tokens.txt
curl -4 -L -o out/en/indicconformer-en.int8.onnx  $B/en/model.int8.onnx
curl -4 -L -o out/en/indicconformer-en.tokens.txt $B/tokens.txt
```

> Use `curl -4`. This machine resolved `huggingface.co` to IPv6-only addresses that stall.

Available: `as bn en gu hi kn ml mr pa ta te`. **Odia has no published STT export** — it is
the one gap in the ten languages.

## 7. Translation (IndicTrans2)

All ten languages, in any direction: English ↔ Indian directly, Indian ↔ Indian through
English.

**No export was needed.** MIT-licensed ONNX conversions of AI4Bharat's distilled 200M
IndicTrans2 are published by `TigreGotico`, and the graph contract was read off the models
themselves rather than taken from the README:

```
encoder       input_ids [B,S] i64, attention_mask [B,S] i64  ->  last_hidden_state [B,S,512]
decoder       input_ids [B,T] i64, encoder_attention_mask [B,S] i64,
              encoder_hidden_states [B,S,512]  ->  logits [B,T,V] + present.{0..17}.*
decoder-past  input_ids [B,1], encoder_attention_mask, past_key_values.{0..17}.*
                                               ->  logits [B,1,V] + present.*.decoder.*
```

`decoder_start_token_id=2`, `eos=2`, `pad=1`, max source 256 tokens.

### Two families, shared by every language

IndicTrans2 comes as one model per *direction*, each covering all 22 scheduled languages.
They install once into `models/mt/` and serve every language on the phone:

| Family | Files (published names) | Size | Needed by |
|---|---|---|---|
| `indic-en` | `mt-hi-en-*` | 236 MB (+101 MB fast decoder) | English phones; any phone reading one Indian language from another |
| `en-indic` | `mt-en-hi-*` | 283 MB (+194 MB fast decoder) | Every Indian-language phone |

The files keep their original `hi-en` / `en-hi` names so phones provisioned before this
change still resolve them from `models/en/` and `models/hi/`. The language-tag ids for all
ten languages ship in the app's `manifest.json`; `export_mt_vocab.py` now writes the same
table into `mt-meta.json`, and the app refuses a meta file that disagrees.

### The text processing is part of the model

IndicTrans2 was trained on text run through AI4Bharat's `IndicProcessor`: punctuation
normalisation, `<ID1>` placeholders for numbers, URLs and e-mail, Moses tokenisation for
English, and — for every Indic script — tokenisation plus **transliteration into
Devanagari**. The app carries a Kotlin port (`translate/IndicTransText.kt`). It is pinned
to the reference by 553 golden cases:

```
pip install onnxruntime==1.20.0 sentencepiece numpy IndicTransToolkit
python make_mt_golden.py --mt mt/ --out ../app/src/test/resources/mt-golden.tsv
```

`onnxruntime` must match the app's version: int8 kernels round differently between
releases, and greedy decoding turns a 0.01 logit difference into a different word.

### Check the whole translator on real models

```
./gradlew testDebugUnitTest --tests '*IndicTrans2DesktopTest*' -PmtModels=$PWD/model-export/mt
```

Runs the Kotlin translator on the desktop JVM against the int8 models and requires every one
of 270 translations (nine languages, both directions) to match the reference exactly, for
both the cacheless and the KV-cached decoder. `mt/<family>/` needs the upstream
`encoder_model.onnx`, `decoder_model.onnx`, `decoder_with_past_model.onnx` plus the files
below.

### Check an individual translation

With the same dependencies and downloaded family files as the golden-data generator:

```
python translate_onnx.py --dir mt/en-indic --src en --tgt ta --text "I need water and medicine."
python -m unittest discover -s tests -v
```

The helper applies the reference IndicProcessor before encoding and after decoding,
including script conversion and number/URL placeholders. Use `en-indic` for English →
Indian and `indic-en` for Indian → English; Indian → Indian requires two invocations
through English. The lightweight Python tests check this wiring without model downloads;
they do not replace the opt-in real-model JVM test above.

### Rebuild the vocab files

The ONNX repos ship a SentencePiece model plus a separate graph dictionary whose ids do
**not** match SentencePiece's internal ids. `export_mt_vocab.py` joins them into one flat
TSV the Kotlin tokeniser reads without a native SentencePiece dependency, and writes the
FLORES language-tag ids — which differ between directions (`hin_Deva` is 8 one way and 15
the other) and must never be hardcoded.

```
./ttsenv/bin/python export_mt_vocab.py --dir mt/indic-en
./ttsenv/bin/python export_mt_vocab.py --dir mt/en-indic
```

### Put them on a phone

```
adb shell mkdir -p /sdcard/Android/data/com.echobharat/files/models/mt
adb push mt/staged/. /sdcard/Android/data/com.echobharat/files/models/mt/
adb logcat -s TranslationManager IndicTrans2
```

### Decoding

- **KV cache when available.** With `decoder-past` installed, the first step runs the full
  decoder (which also yields the cross-attention cache) and every later step feeds one
  token, so decoding is linear. Desktop CPU: equal on 6-token sentences, 1.6–1.9× faster on
  25–30 tokens. Without it the app falls back to the cacheless loop.
- The two decoders are quantised separately, so where the top two tokens are within int8
  rounding they can choose different, equally valid words — about one sentence in eight on
  the golden corpus. Each path is tested against the reference's own version of it.
- **Greedy, not beam.** A subtly wrong beam is worse than an honest greedy pass.
- **Pivoting costs a model swap.** One family is resident at a time, so Tamil → Hindi loads
  `indic-en`, then `en-indic`. That keeps memory bounded on low-end phones.
