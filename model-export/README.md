# EchoBharat model export

How the on-device speech models are produced and installed.

STT needs no export — verified int8 ONNX already exists (see §3). Only TTS is built here.

---

## 1. What is already done

TTS is exported, verified and published for **nine languages** — Hindi, English, Bengali,
Gujarati, Kannada, Malayalam, Marathi, Tamil and Telugu. Odia is deliberately absent: it
has no published speech-to-text export, so a voice pack could speak but never listen.

Re-run the whole batch with `export_batch.py`, which drives the scripts below one language
at a time and prints a running counter:

```
./ttsenv/bin/python export_batch.py --langs gu mr kn ml ta te bn
```

The files land in `out/<lang>/`:

| File | Size |
|---|---|
| `fastpitch-<lang>.int8.onnx` | ~63 MB |
| `hifigan-<lang>.int8.onnx` | ~22 MB |
| `fastpitch-<lang>.tokens.json` | ~1 KB |

Listen to `out/hi/sample-hi.wav` and `out/en/sample-en.wav` to judge voice quality
before putting these on a phone.

**SHA-256** (recorded so the Model Manager can verify a published download):

```
878c2359fa9ed88dbf3d7895f380ee9ca025247c722032220970b004b0e0a476  hi/fastpitch-hi.int8.onnx
2f07c88e30de4594eae9da54cf349262d0185e50172646ca20fd73f7d68383e4  hi/hifigan-hi.int8.onnx
315cd28bbbad1aa7119f59d778f28b35666cbbfc85c2f748e2146bbf8edafb35  hi/fastpitch-hi.tokens.json
8ae4c26e5d03ef1b72fba184f9460d2eca1e81362dd4df90839b449ca9faa805  en/fastpitch-en.int8.onnx
2de17f389fee6b0bcb18b445ee317833530aa86c6720e6ff49eb5b694aa27ff3  en/hifigan-en.int8.onnx
721ef293934a5f7554f366c57aa48742065cf98349becb0ea66e31b5525e0989  en/fastpitch-en.tokens.json
```

## 2. Put the models on a phone

Nothing needs rebuilding — the app picks these up at runtime.

1. Connect the phone and confirm it is visible:
   ```
   adb devices
   ```
2. Install the app once, so its storage directory exists:
   ```
   adb install -r ../app/build/outputs/apk/debug/app-debug.apk
   ```
3. Create the model folders:
   ```
   adb shell mkdir -p /sdcard/Android/data/com.echobharat/files/models/hi
   adb shell mkdir -p /sdcard/Android/data/com.echobharat/files/models/en
   ```
4. Push the STT models (download them first — see §3) and the TTS models:
   ```
   adb push out/hi/. /sdcard/Android/data/com.echobharat/files/models/hi/
   adb push out/en/. /sdcard/Android/data/com.echobharat/files/models/en/
   ```
5. Confirm what landed:
   ```
   adb shell ls -l /sdcard/Android/data/com.echobharat/files/models/hi
   ```
6. Open the app → **Language Packs**. Hindi and English should read "Ready".
7. Watch the pipeline while testing:
   ```
   adb logcat -s SttManager IndicConformerStt TtsManager FastPitchTts AudioOutput SileroVad
   ```

The `.wav` files in `out/` are samples only — do not push them.

## 3. STT models (no export needed)

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
curl -4 -L -o out/en/indicconformer-en.tokens.txt $B/en/tokens.txt
```

> Use `curl -4`. This machine resolved `huggingface.co` to IPv6-only addresses that stall.

Available: `as bn en gu hi kn ml mr pa ta te`. **Odia has no published STT export** — it is
the one gap in the ten languages.

## 4. Exporting another language

Each language pack is a ~1.45 GB download.

1. Fetch the checkpoint (`or`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`, `bn`):
   ```
   L=ta
   curl -4 -L --retry 5 -o checkpoints/$L.zip \
     https://github.com/AI4Bharat/Indic-TTS/releases/download/v1-checkpoints-release/$L.zip
   ```
2. Extract and export:
   ```
   unzip -q -o checkpoints/$L.zip -d extracted/
   ./ttsenv/bin/python export_tts.py --lang $L --ckpt-root extracted --out out
   ```
3. Verify it makes sound, in that language's script:
   ```
   ./ttsenv/bin/python verify_tts.py --dir out/$L --lang $L --text "<a sentence>"
   ```
4. Record the checksums for the manifest:
   ```
   sha256sum out/$L/*
   ```
5. Reclaim ~1.6 GB:
   ```
   rm -rf extracted/$L checkpoints/$L.zip
   ```

`--speaker-id` selects the voice: **0 = female, 1 = male** (both are trained in every
pack). The default is 0.

## 5. Environment

Built once, already present in `ttsenv/`. To recreate:

```
python3 -m venv ttsenv
./ttsenv/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
./ttsenv/bin/pip install "coqui-tts[codec]" "transformers<5" numpy onnx onnxruntime onnxscript
```

Three pins matter, each learned the hard way:

- **`transformers<5`** — coqui-tts imports XTTS → tortoise → `isin_mps_friendly`, which
  transformers 5.x removed.
- **`coqui-tts[codec]`** — torch ≥ 2.9 needs torchcodec for audio IO.
- **CPU torch** — this machine has an AMD integrated GPU, so the CUDA wheels are ~2 GB of
  dead weight. Export is a one-shot graph trace; it takes about a minute per model on CPU.

## 6. Notes on the export itself

Three things in `export_tts.py` exist for non-obvious reasons; leave them in place.

- **FastPitch must use the dynamo exporter** (`dynamo=True`). The `fftransformer` encoder
  is built on `torch.nn.MultiheadAttention`, which bakes the traced sequence length into a
  Reshape under the legacy TorchScript tracer. The result loads fine and then fails at
  runtime on any sentence that is not exactly the dummy length. Symptom:
  `input_shape_size == requested_shape_size was false ... requested shape:{24,1,512}`.
- **`PositionalEncoding.forward` is patched** to drop its `max_len` guard. That guard is a
  data-dependent comparison which aborts `torch.export`, and it cannot fire for any real
  utterance.
- **Mel orientation is probed eagerly**, not branched on inside `forward`. Coqui emits
  `[B, T, 80]` and HiFi-GAN wants `[B, 80, T]`; testing `mel.shape[1] != 80` inside the
  traced function is another data-dependent guard.

HiFi-GAN stays on the legacy tracer, which handles it without complaint.

The two stages are exported separately rather than fused, matching the app's two-stage
`TtsEngine`: the vocoder is the expensive half and the most likely thing to be swapped or
requantised later.

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
