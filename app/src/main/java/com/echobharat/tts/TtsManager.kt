package com.echobharat.tts

import android.content.Context
import android.util.Log
import com.echobharat.models.ModelCatalog
import com.echobharat.models.ModelRole
import com.echobharat.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TtsEngine? = null

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
        engine?.let { if (it.lang == lang) return@withLock it }

        engine?.let {
            Log.i(TAG, "Switching TTS ${it.lang} -> $lang; unloading previous")
            runCatching { it.close() }
            engine = null
        }

        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            Log.e(TAG, "TTS_UNAVAILABLE[$lang]: not in manifest")
            return@withLock null
        }

        val acoustic = spec.of(ModelRole.TTS_ACOUSTIC)?.let { store.resolve(lang, it) }
        val vocoder = spec.of(ModelRole.TTS_VOCODER)?.let { store.resolve(lang, it) }
        val tokens = spec.of(ModelRole.TTS_TOKENS)?.let { store.resolve(lang, it) }

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
            _state.value = State.Unavailable(TtsUnavailable.LoadFailed(lang, "ONNX session could not be created"))
        }
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
    suspend fun speak(text: String, lang: String, alert: Boolean = false): Spoken? {
        if (text.isBlank()) return null

        return try {
            _state.value = State.Synthesizing(lang)
            val started = android.os.SystemClock.elapsedRealtime()
            val (pcm, sampleRate) = useLock.withLock {
                val active = engineFor(lang) ?: return null
                withContext(Dispatchers.Default) { active.synthesize(text) } to active.sampleRate
            }
            val synthesisMs = android.os.SystemClock.elapsedRealtime() - started
            if (pcm == null || pcm.isEmpty()) {
                Log.e(TAG, "TTS_SYNTHESIS_EMPTY[$lang] for \"$text\"")
                return null
            }

            val audioMs = pcm.size * 1000L / sampleRate
            Log.i(TAG, "Synthesised ${audioMs}ms of [$lang] speech in ${synthesisMs}ms")
            _state.value = State.Speaking(lang, alert)
            if (output.play(pcm, sampleRate, alert)) Spoken(synthesisMs, audioMs) else null
        } catch (e: Throwable) {
            Log.e(TAG, "SPEAK_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } finally {
            _state.value = State.Idle
        }
    }

    /** Stops normal playback; an alert in flight continues. */
    fun stop() = output.stop()

    /**
     * Frees the loaded voice. Playback stops at once; a synthesis in flight finishes
     * before its session is closed. The next [speak] reloads lazily.
     */
    fun release() {
        output.stop()
        scope.launch {
            useLock.withLock {
                loadLock.withLock {
                    runCatching { engine?.close() }
                    engine = null
                }
            }
        }
    }
}
