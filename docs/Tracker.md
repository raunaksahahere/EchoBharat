# EchoBharat — Tracker

**Owner:** Team (single builder identity) · **Deadline:** 12 Sep
**Status keys:** ☐ todo · ◐ in progress · ☑ done · ⚠ blocked/at-risk
**Who:** 🤖 AI-assisted · 🧑 human-only (hardware/profiling/store)

> Update the status column as you go. Keep human-only (🧑) rows visible — they gate the demo.

---

## Phase 0 — Foundation
| # | Task | Who | Status |
|---|---|---|---|
| 0.1 | Scaffold Kotlin/Compose project, module structure, min SDK 26 | 🤖 | ☑ |
| 0.2 | Integrate bitchat-android transport core | 🤖 | ☑ |
| 0.3 | 2 phones discover each other over BLE | 🧑 | ◐ FGS crash fixed; needs hardware re-test |
| 0.4 | Define EchoBharatMessage payload + serialization | 🤖 | ☑ |
| 0.5 | Identity: name → keypair → Peer ID → device model | 🤖 | ☑ |
| 0.6 | Exit: plain text message across mesh w/ name + model | 🧑 | ☐ |

## Phase 1 — Voice loop (Hindi + English)
| # | Task | Who | Status |
|---|---|---|---|
| 1.1 | Integrate ONNX Runtime Mobile | 🤖 | ☑ 1.20.0, all 4 ABIs |
| 1.2 | STT pipeline: mic → Silero VAD → IndicConformer → CTC text | 🤖 | ☑ code complete; VAD bundled |
| 1.3 | TTS pipeline: text → FastPitch → HiFi-GAN → AudioTrack | 🤖 | ☑ code complete |
| 1.4 | End-to-end: speak → STT → mesh → TTS → hear | 🤖 | ◐ wired; two receive-side bugs that would have hidden it fixed 26 Sep; needs hardware run |
| 1.5 | Push-to-talk UI + peer list + chat view | 🤖 | ☑ PTT drives real capture |
| 1.6a | ⚠ **Export IndicConformer + Indic-TTS (hi/en) to ONNX, quantize int8** | 🤖 | ☑ **done 2 Sep** — STT needed no export; TTS exported + verified |
| 1.6b | ⚠ Profile on real phone: latency, RAM, idle CPU | 🧑 | ☐ |
| 1.7 | Exit: working voice demo on hardware (hi↔en) | 🧑 | ☐ |

## Phase 2 — Text, alerts, bypass
| # | Task | Who | Status |
|---|---|---|---|
| 2.1 | Typed-text send/receive | 🤖 | ☑ |
| 2.2 | Read-aloud received text (on-demand TTS) | 🤖 | ☑ speaker icon per message + auto-speak |
| 2.3 | Alert mode: max volume, non-interruptible | 🤖 | ☑ alarm stream, blocks interruption |
| 2.4 | Bypass/phone mode (raw audio, STT/TTS off) | 🧑 | ⚠ decision needed — contradicts Rules §3 "never put audio bytes in a mesh payload"; the dead toggle was removed |

## Phase 6 — Mesh, SOS and UI (locked 2 Sep)
| # | Task | Who | Status |
|---|---|---|---|
| 6.1 | Hybrid hop+GPS range policy (~20 hops, GPS only tightens) | 🤖 | ☑ `RangePolicy`, unit-tested |
| 6.2 | SOS message type: 1-hour expiry, 5-min re-announce, cancel | 🤖 | ☑ `SosManager` |
| 6.3 | SOS UI: raise, banner, tap-to-reveal location, resolve | 🤖 | ☑ |
| 6.4 | Multi-hop peer list with Direct/N hop badges | 🤖 | ☑ built on existing `MeshGraphService` gossip |
| 6.5 | Targeted ranged messaging (tap a peer to address it) | 🤖 | ☑ |
| 6.6 | Light theme, saffron accent | 🤖 | ☑ |
| 6.7 | Fix non-functional peer search | 🤖 | ☑ was disabled whenever the peer list was empty |
| 6.8 | ⚠ Hardware test: SOS propagation + multi-hop badges on 2-3 phones | 🧑 | ☐ |
| 2.5 | Cross-manufacturer alert-volume test | 🧑 | ☐ |

## Phase 3 — All 10 languages + on-demand packs
| # | Task | Who | Status |
|---|---|---|---|
| 3.1 | Model Manager: download + mirror, SHA-256 verify, index | 🤖 | ☑ refuses unverified files |
| 3.2 | Lazy load/unload models (RAM control) | 🤖 | ☑ one language resident at a time |
| 3.3 | Language selection UI — **multi-select**, live switching | 🤖 | ☑ no prompt on launch |
| 3.4 | ⚠ Export + quantize STT/TTS for remaining 8 langs | 🧑 | ☑ 7 Sep — all but Odia, which has no STT export |
| 3.7 | Publish exported models: set url + sha256 in manifest.json | 🧑 | ☑ 7 Sep — STT + TTS for 9 languages, MT both families |
| 3.5 | ⚠ Profile each language on device | 🧑 | ☐ |
| 3.6 | Airplane-mode offline proof after one-time download | 🧑 | ☐ |
| 3.8 | Phone-to-phone provisioning: share packs + app, import verified by SHA-256 | 🤖 | ☑ 26 Sep — needs a two-phone Quick Share / Bluetooth check |
| 3.9 | Resumable downloads (HTTP Range), retries, free-space check | 🤖 | ☑ 26 Sep |

## Phase 4 — Offline translation ⚠ highest risk
| # | Task | Who | Status |
|---|---|---|---|
| 4.1 | Integrate IndicTrans2 (distilled) behind `translate/` interface | 🤖 | ☑ |
| 4.2 | Auto-translate incoming → receiver language | 🤖 | ☑ |
| 4.3 | ~~Optional translate-before-send~~ — **dropped by design** | 🤖 | ✗ |
| 4.4 | ⚠ Profile translation footprint/latency on device | 🧑 | ☐ — the in-app *Show timings* switch reports it per message |
| 4.6 | Port IndicTrans2 text processing (IndicProcessor, Moses, indic-nlp) | 🤖 | ☑ 553 golden cases match the reference exactly |
| 4.7 | Translation for all 10 languages (Indian ↔ Indian via English) | 🤖 | ☑ desktop-verified: 270/270 translations match the reference |
| 4.8 | KV-cached decoding (`decoder_with_past`), optional download | 🤖 | ☑ 1.6–1.9× on long sentences; falls back to cacheless |
| 4.5 | Decision gate (by Day 11): keep as MVP or drop to stretch | 🧑 | ☑ keep |

**4.3 was deliberately dropped, not skipped.** Translating before sending forces the
sender to choose one target language, which destroys the multilingual broadcast: the whole
point is that one utterance reaches a Hindi speaker and an English speaker at once, each
hearing their own. Translation belongs on the receiving side and nowhere else.

Scope (updated 26 Sep): **all ten languages.** One IndicTrans2 family per direction covers
every language, so English ↔ Indian is one model pass and Indian ↔ Indian pivots through
English. Translation is shared, installed once into `models/mt/`.

## Phase 5 — Hardening + submission
| # | Task | Who | Status |
|---|---|---|---|
| 5.1 | ⚠ 3+ phone multi-hop mesh test | 🧑 | ☐ |
| 5.2 | On-screen latency/RTF readout for demo | 🤖 | ☑ per-message STT/RTF/MT/TTS timings behind a menu switch |
| 5.3 | Onboarding + permissions UX polish | 🤖 | ☐ |
| 5.4 | Build signed release APK | 🤖 | ☑ v1.1.0 released 26 Sep — per-ABI 22 / 17 MB, debug-key signed so it upgrades v1.0.0 in place; upgrade, launch, import and share smoke-tested on an Android 15 emulator. A real release key (`keystore.properties`) is still to be made |
| 5.7 | Trim APK: drop emulator ABIs (93.8 MB → 54.9 MB) | 🤖 | ☑ |
| 5.5 | Demo script + submission write-up (blind-review safe) | 🤖 | ☐ |
| 5.6 | Final dry-run of full demo | 🧑 | ☐ |

---

## Milestones
- **M1 (Day 2):** text across mesh on real phones.
- **M2 (Day 5):** ⭐ working voice loop hi↔en on hardware — *the protected milestone.*
- **M3 (Day 7):** text + alert + bypass done.
- **M4 (Day 10):** all viable languages usable.
- **M5 (Day 11):** translation keep/drop decision.
- **M6 (Day 12–13):** signed APK + rehearsed demo + submission.

## Blockers log
| Date | Blocker | Owner | Resolution |
|---|---|---|---|
| 31 Aug | App crashed on launch (FGS bad notification), killing BLE discovery | 🤖 | Fixed — `createChannel()` was never called |
| 31 Aug | No IndicConformer / Indic-TTS ONNX exports exist; upstream ships NeMo/PyTorch | 🤖 | **Resolved 2 Sep** — STT found pre-exported (Apache-2.0); TTS exported locally |
| 2 Sep | FastPitch ONNX baked the token length in, breaking any sentence length | 🤖 | Resolved — dynamo exporter keeps the axis symbolic |
| 7 Sep | PTT reported non-functional; no device attached to `adb`, so no trace could be captured | 🧑 | ◐ Likely cause found 26 Sep: the receiver discarded every ordinary message (peer-ID mismatch) and the sender dropped the first one (no session). Both fixed; confirm on hardware |
| 7 Sep | `EchoBharatMeshPayloadCodec` hardcodes `srcLang = "en"` for non-EchoBharat payloads; now mistranslates Hindi from bitchat-native nodes | 🤖 | Resolved 26 Sep — language inferred only from a script unique to one language, else `und` and shown as received |
| 7 Sep | Chat history is screen-local state, so leaving and re-entering a conversation loses it — far more visible under peer-first navigation | 🤖 | Resolved 26 Sep — app-wide `ConversationRepository`, encrypted on disk |
| 26 Sep | First private message to a peer dropped: bitchat starts the Noise handshake and discards the message, while the UI showed it as sent | 🤖 | Resolved — per-peer outbox, flushed on session; queued/sent/delivered/failed shown |
| 26 Sep | Incoming messages carried `IdentityManager`'s peer ID, the peer list the mesh's Noise-derived one; the chat screen discarded every ordinary incoming message | 🤖 | Resolved — one ID; messages filed under the transport-authenticated sender |
| 26 Sep | SOS re-broadcasts reuse their msgId; an open chat appended each copy, re-read it aloud, and crashed on the duplicate list key | 🤖 | Resolved — recorded once |
| 26 Sep | Any node could cancel someone else's SOS by naming its id | 🤖 | Resolved — only the originator's resolve is honoured |
| 26 Sep | Translation fed Indic text to IndicTrans2 without its preprocessing (no Devanagari transliteration, placeholders or tokenisation) | 🤖 | Resolved — IndicProcessor ported and golden-tested |
| 2 Oct | Long messages never left the sender: the private-message TLV stored text length in one byte, so anything over 255 bytes of JSON (~35 Hindi characters) failed to encode and was dropped | 🤖 | Resolved in code — 2-byte `CONTENT_LONG` field; unit-tested; needs hardware confirm |
| 2 Oct | BLE packets were sized for a 512-byte MTU and Noise frames padded to 256/512, but the negotiated MTU was never tracked; on a smaller link the radio truncates silently (long messages, handshakes and acks lost) | 🤖 | Resolved in code — per-link MTU tracked, fragments and padding follow the weakest link; needs hardware confirm |
| 2 Oct | Delivery ack was a single unrepeated packet; if lost the sender stayed on "sent" while the receiver had the message | 🤖 | Resolved in code — ack repeated at 0/2/6 s, sender resends unacked messages up to 4 times; needs hardware confirm |
| 2 Oct | First message stayed "waiting": a lost handshake was only retried after the 10 s timeout plus a 10 s tick | 🤖 | Resolved in code — 4 s retry tick, unanswered initiator handshake restarted after 5 s; needs hardware confirm |
| 2 Oct | Bluetooth off at launch (or toggled later) left the mesh dead until the app was reopened; nothing prompted the user | 🤖 | Resolved in code — system enable prompt (silent enable below Android 13), mesh restarts on adapter-on. Wi-Fi is not used by the mesh and cannot be toggled by apps on Android 10+ |
| 2 Sep | Odia has no published IndicConformer STT export | 🧑 | Open — 9 of 10 languages have STT |
| 31 Aug | Broadcast messages were signed but unencrypted, contradicting PRD §5 | 🤖 | Resolved — secure/broadcast now user-selectable |
| 31 Aug | Gradle daemon OOM-killed the build host (7GB RAM, -Xmx4g + parallel) | 🤖 | Resolved — heap right-sized; clean build peaks ~2.8GB |

## Status as of 26 Sep 2026

The deadline passed with every 🧑 hardware row still open. This session fixed what would
have made the first hardware run fail, and extended translation; nothing here has run on a
phone yet.

**Why the voice loop would not have worked on two phones**
- *Receiver:* messages carried `IdentityManager`'s peer ID while the peer list uses the
  mesh's Noise-derived ID, so the conversation screen filtered out every ordinary incoming
  message. Now one ID, and incoming traffic is attributed to the sender the transport
  authenticated (the payload's `senderId` is self-asserted and only trusted for relayed SOS).
- *Sender:* the first private message to a peer was dropped while the Noise handshake ran,
  and the UI showed it as sent. `PrivateOutbox` holds it (10 min, 32/peer) and flushes on
  session; a 10 s tick re-asks for lost handshakes.

**Conversations** (`conversation/`)
- App-wide `ConversationRepository`: messages are kept with no chat open, history survives
  (AES-GCM, Keystore key), alerts are spoken in the background, and translation + speech run
  through one queue so arrivals no longer talk over each other.
- Delivery state per message (waiting → sent → delivered / not delivered) from transport
  callbacks and acks. Home screen: last message, unread counts, earlier conversations.
- Models are shared app-wide (`VoiceEngines`), released on trim, and can no longer be closed
  while an inference is running.

**Translation** — now every language pair
- `IndicTransText` ports AI4Bharat's IndicProcessor: punctuation and digit normalisation,
  `<ID>` placeholders (phone numbers survive), Moses for English, indic-nlp normalise +
  tokenise + transliterate-to-Devanagari for Indic scripts. 553 golden cases from
  `model-export/make_mt_golden.py` match exactly. Two reference bugs deliberately not
  copied (double-escaped visarga rule).
- Routes: English ↔ Indian direct, Indian ↔ Indian via English, flagged "via EN" to the
  reader. Families install once into `models/mt/`; older `hi/`, `en/` installs still resolve.
- Optional KV-cache decoders (`decoder_with_past`, pinned upstream revision + SHA-256).
- `IndicTrans2DesktopTest` (opt-in, `-PmtModels=`): the Kotlin translator reproduces the
  reference on 270/270 translations, cacheless and cached, with ONNX Runtime pinned to the
  app's 1.20.0 (int8 kernels round differently across releases).

**Also**
- CI: `.github/workflows/android.yml` runs unit tests and builds the debug APK.
- Payloads missing fields or carrying an unknown type are rejected; untagged bitchat text is
  no longer labelled English; only an SOS's originator can cancel it.
- Removed: `MeshCore`/`MeshTransport` (dead duplicate transport), the no-op lock toggle,
  13 unused dependency-catalog entries.

**Light and offline (second pass)**
- A phone with packs can share them — and the APK itself — over Quick Share or Bluetooth;
  the receiver's Import accepts a file only if its SHA-256 is in the manifest, so the whole
  team can be provisioned from one download.
- Downloads resume by HTTP Range after a drop, retry with backoff, and refuse to start
  without room on the disk.
- Release APKs split per ABI (arm64 22 MB, armv7 17 MB, universal 35 MB); the unused Wi-Fi
  permissions are gone, INTERNET is declared explicitly.

**Still needs a human with phones** — 0.3, 0.6, 1.6b, 1.7, 2.5, 3.5, 3.6, 4.4, 5.1, 6.8.
Turn on *Show timings* for 1.6b / 3.5 / 4.4: every message reports its own STT, RTF, MT and
TTS cost.

## Status as of 7 Sep 2026

**Translation (Phase 4) — built, verified on desktop, unproven on hardware**
- No export was needed: MIT-licensed ONNX conversions of AI4Bharat IndicTrans2 distilled
  200M are published by `TigreGotico`. The graph contract was read off the models rather
  than taken from the README — encoder `input_ids`/`attention_mask` → `last_hidden_state
  [B,S,512]`; decoder + `encoder_hidden_states` → `logits [B,T,V]` plus 72 KV tensors the
  cacheless loop ignores.
- New `translate/` package: `SpmBpeTokenizer`, `TranslationEngine`,
  `IndicTrans2Translator` (greedy), `TranslationManager`.
- The tokenizer was the real risk and was **not** trusted: IndicTrans2 ships a
  SentencePiece model *and* a separate graph dictionary whose ids deliberately differ, and
  conflating them yields fluent nonsense rather than an error. A from-scratch BPE was
  diffed piece-for-piece against the reference `sentencepiece` library — 8/8 sentences
  exact, both scripts — before any Kotlin was written.
- The FLORES tag `hin_Deva` is id **8** going indic→en and **15** going en→indic. Hardcoding
  either silently corrupts the other direction, so tags ship as data (`mt-*-meta.json`).
- Each phone carries only the direction *into* its own language: en 226 MB, hi 270 MB.
- Desktop latency 114–258 ms/sentence. **No phone measurement exists** (4.4 still open).
- Known rough edges: greedy without KV cache (O(n²) in output length); NFKC only, no
  IndicNLP normalisation; one `<unk>` seen on an English comma.

**Navigation redesign (Phase 6 follow-on)**
- Peer-first home screen (`ui/home/HomeScreen.kt`): search on top, peer rows with hop
  badges, SOS separate and prominent, Language Packs in the top bar. No broadcast button.
- `TransceiverScreen` is now scoped to one peer and filters incoming traffic to that
  conversation — previously it collected *every* message, which the redesign would have
  turned into a bug.
- Removed an auto-clear that nulled the target when a peer left range: under peer-first
  navigation, null means broadcast, so that would silently widen a private message's
  audience.

**Open conflict (resolved 26 Sep):** general broadcast is reserved for SOS. The encrypted
"secure send" toggle was removed — every peer conversation was already Noise-encrypted, so
it switched nothing.

**Also fixed:** `ModelManager` no longer surfaces manifest paths and file names to the
screen; unavailable languages read "Not available yet" and the detail goes to logcat.
The Language Packs offline-explainer banner was removed.

## Status as of 31 Aug 2026

**Fixed this session**
- FGS startup crash: `MeshForegroundService.createChannel()` was defined but never
  called, so `startForeground()` posted against a non-existent channel and Android 14+
  killed the process (`CannotPostForegroundServiceNotificationException`). Now called in
  `onCreate()` before any promotion. This also explains "0 nearby peers" — BLE scanning
  died with the process a few ms after it registered.
- Launcher icon changed from bitchat's "BIT" to "EB"; notification channel id renamed
  `bitchat_mesh_service` → `echobharat_mesh_service`.

**Built this session (was absent, not stubbed — there was no `stt/`, `tts/` or `models/`
package, no ONNX/TFLite dependency, and no audio I/O anywhere in the app)**
- `stt/` — `AudioCapture` (16 kHz mono PCM), `SileroVad` (bundled, MIT),
  `MelSpectrogram` (NeMo-compatible, FFT unit-tested against a naive DFT),
  `IndicConformerStt` (ONNX + greedy CTC), `SttManager`.
- `tts/` — `FastPitchTts` (FastPitch → HiFi-GAN), `AudioOutput` (alert = alarm stream at
  max volume, non-interruptible), `TtsManager`.
- `models/` — `ModelCatalog` (reads `assets/models/manifest.json`), `ModelStore`,
  `ModelManager` (download → SHA-256 verify → install; mirror fallback).
- Push-to-talk previously transmitted a **hardcoded sample sentence**; it now opens the
  mic on press and transcribes on release.

**The one thing gating the voice demo:** no IndicConformer or Indic-TTS ONNX files exist
yet (Tracker 1.6a). Everything downstream of them is wired and reports "model missing"
rather than failing silently. Sideload path for testing:
`adb push <file> /sdcard/Android/data/com.echobharat/files/models/<lang>/<fileName>`

**Not present, contrary to earlier assumption:** Nostr was never ported (constants only),
and offline translation (Phase 4) has no code yet.

## Notes
- Any 🧑 row that slips is a red flag — those cannot be recovered by adding AI effort. Re-plan scope, don't cram.
- Keep the M2 voice loop demo-ready at all times after Day 5, even while adding features.
