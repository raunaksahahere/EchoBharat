package com.echobharat.tts

import android.content.Context
import android.util.Log
import com.echobharat.models.ModelCatalog
import com.echobharat.models.ModelRole
import com.echobharat.models.LanguageModelSpec
import com.echobharat.BuildConfig
import com.echobharat.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the text → FastPitch + HiFi-GAN → speaker path.
 *
 * One voice is resident at a time; switching language unloads the previous one
 * (Rules §9). Speaking never throws — a missing model surfaces as [State.Unavailable]
 * so a failed read-aloud cannot take down the message list.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    /** What one utterance cost: [synthesisMs] to produce [audioMs] of speech. */
    data class Spoken(val synthesisMs: Long, val audioMs: Long)

    sealed interface State {
        data object Idle : State
        data class Synthesizing(val lang: String) : State
        data class Speaking(val lang: String, val alert: Boolean) : State
        data class Unavailable(val reason: TtsUnavailable) : State
    }

    private val store = ModelStore(context)
    private val output = AudioOutput(context)
    private val loadLock = Mutex()

    /** Held while an engine is synthesising, so [release] cannot close it mid-run. */
    private val useLock = Mutex()
    /** Includes playback and every sentence: another caller cannot interleave an alert. */
    private val utteranceLock = Mutex()
    private val stopGeneration = AtomicLong()
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TtsEngine? = null
    private var engineFingerprint: String? = null

    /** True when [lang] has every TTS file it needs on disk. */
    fun isAvailable(lang: String): Boolean {
        val spec = ModelCatalog.byLang(context, lang) ?: return false
        return store.hasTts(spec)
    }

    fun missingFiles(lang: String): List<String> {
        val spec = ModelCatalog.byLang(context, lang) ?: return emptyList()
        return spec.ttsSpecs.filterNot { store.isPresent(lang, it) }.map { it.fileName }
    }

    private suspend fun engineFor(lang: String): TtsEngine? = loadLock.withLock {
        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            Log.e(TAG, "TTS_UNAVAILABLE[$lang]: not in manifest")
            return@withLock null
        }

        // Resolve a whole generation once, on IO. A new manifest cannot mix an old
        // acoustic model with a partly installed new vocoder or bypass the hash check.
        val selected = withContext(Dispatchers.IO) { store.voices.resolve(spec) }
        engine?.let {
            if (it.lang == lang && selected?.fingerprint == engineFingerprint) return@withLock it
            runCatching { it.close() }
            engine = null
            engineFingerprint = null
        }
        val acoustic = selected?.files?.get(ModelRole.TTS_ACOUSTIC)
        val vocoder = selected?.files?.get(ModelRole.TTS_VOCODER)
        val tokens = selected?.files?.get(ModelRole.TTS_TOKENS)

        if (acoustic == null || vocoder == null || tokens == null) {
            val missing = missingFiles(lang)
            Log.e(TAG, "TTS_UNAVAILABLE[$lang]: missing ${missing.joinToString()}")
            _state.value = State.Unavailable(TtsUnavailable.ModelMissing(lang, missing))
            return@withLock null
        }

        val loaded = withContext(Dispatchers.IO) {
            FastPitchTts.load(lang, acoustic, vocoder, tokens)
        }
        if (loaded == null) {
            withContext(Dispatchers.IO) { store.voices.recordLoadFailure(spec) }
            _state.value = State.Unavailable(TtsUnavailable.LoadFailed(lang, "Voice needs updating"))
        } else {
            withContext(Dispatchers.IO) { store.voices.clearLoadFailure(spec) }
        }
        engineFingerprint = selected?.fingerprint
        engine = loaded
        loaded
    }

    /**
     * Synthesises and plays [text] in [lang], suspending until playback ends.
     *
     * @param alert plays at max volume, non-interruptible.
     * @return timings for the utterance, or null when the voice is unavailable, synthesis
     *   failed, or playback did not complete.
     */
    suspend fun speak(text: String, lang: String, alert: Boolean = false): Spoken? = utteranceLock.withLock {
        if (text.isBlank()) return@withLock null
        val generation = stopGeneration.get()
        try {
            // Keep the voice resident through the complete utterance, including playback.
            useLock.withLock {
                val active = engineFor(lang) ?: return@withLock null
                var synthesisMs = 0L
                var audioMs = 0L
                val complete = playSpeechChunks(
                    text,
                    shouldStop = { !alert && generation != stopGeneration.get() },
                    synthesize = { chunk ->
                        _state.value = State.Synthesizing(lang)
                        val started = android.os.SystemClock.elapsedRealtime()
                        val pcm = withContext(Dispatchers.Default) { active.synthesize(chunk) }
                        synthesisMs += android.os.SystemClock.elapsedRealtime() - started
                        pcm
                    },
                    play = { pcm ->
                        _state.value = State.Speaking(lang, alert)
                        val played = output.play(pcm, active.sampleRate, alert)
                        if (played) audioMs += pcm.size * 1000L / active.sampleRate
                        played
                    }
                )
                if (complete) {
                    Log.i(TAG, "Synthesised ${audioMs}ms of [$lang] chunked speech in ${synthesisMs}ms")
                    Spoken(synthesisMs, audioMs)
                } else {
                    Log.w(TAG, "TTS_INCOMPLETE[$lang]: stopped or a chunk failed; original text retained")
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "SPEAK_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } finally {
            if (_state.value !is State.Unavailable) _state.value = State.Idle
        }
    }

    /** Native-load proof before the candidate pointer changes; never interrupts an utterance. */
    suspend fun activateVoicePack(spec: LanguageModelSpec): Boolean = utteranceLock.withLock {
        useLock.withLock {
            loadLock.withLock {
                withContext(Dispatchers.IO) {
                    // Bound resident voices to one. The old files/pointer remain available
                    // for the next utterance if validation fails.
                    engine?.close()
                    engine = null
                    engineFingerprint = null
                    val activated = store.voices.activate(spec, BuildConfig.VERSION_CODE) { candidate ->
                        FastPitchTts.load(
                            spec.lang,
                            candidate.files.getValue(ModelRole.TTS_ACOUSTIC),
                            candidate.files.getValue(ModelRole.TTS_VOCODER),
                            candidate.files.getValue(ModelRole.TTS_TOKENS)
                        )?.use { true } ?: false
                    }
                    if (!activated) {
                        store.voices.recordLoadFailure(spec)
                        _state.value = State.Unavailable(TtsUnavailable.LoadFailed(spec.lang, "Voice needs updating"))
                    } else _state.value = State.Idle
                    activated
                }
            }
        }
    }

    /** Stops all remaining normal chunks; an alert in flight continues between chunks too. */
    fun stop() {
        stopGeneration.incrementAndGet()
        output.stop()
    }

    /**
     * Frees the loaded voice. Playback stops at once; a synthesis in flight finishes
     * before its session is closed. The next [speak] reloads lazily.
     */
    fun release() {
        stop()
        scope.launch {
            useLock.withLock {
                loadLock.withLock {
                    runCatching { engine?.close() }
                    engine = null
                    engineFingerprint = null
                }
            }
        }
    }
}
