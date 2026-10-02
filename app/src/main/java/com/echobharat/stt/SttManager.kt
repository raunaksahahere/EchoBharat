package com.echobharat.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.echobharat.models.ModelCatalog
import com.echobharat.models.ModelRole
import com.echobharat.models.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Owns the microphone → VAD → IndicConformer path.
 *
 * Only one language model is resident at a time; switching language unloads the previous
 * one so RAM stays bounded (Rules §9). Everything runs off the main thread (Rules §10).
 */
class SttManager(private val context: Context) {

    companion object {
        private const val TAG = "SttManager"

        /** Silero probability above which a frame counts as speech. */
        private const val SPEECH_THRESHOLD = 0.5f

        /** Frames of leading audio kept before speech onset, so word starts survive. */
        private const val PREROLL_FRAMES = 10 // ~320 ms

        /** Guards against a runaway press pinning memory. 30 s at 16 kHz. */
        private const val MAX_UTTERANCE_SAMPLES = AudioCapture.SAMPLE_RATE * 30
    }

    /**
     * One recognised utterance, with what it cost: [audioMs] of speech took
     * [inferenceMs] to transcribe on this phone.
     */
    data class Transcript(val text: String, val audioMs: Long, val inferenceMs: Long) {
        /** Real-time factor: below 1 means faster than the speech itself. */
        val realTimeFactor: Float get() = if (audioMs > 0) inferenceMs.toFloat() / audioMs else 0f
    }

    sealed interface State {
        data object Idle : State
        /** [speech] is true while the VAD believes the user is talking. */
        data class Listening(val speech: Boolean, val level: Float) : State
        data object Transcribing : State
        data class Unavailable(val reason: SttUnavailable) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ModelStore(context)
    private val capture = AudioCapture()
    private val loadLock = Mutex()

    /** Held for the whole of an inference, so [release] cannot close a session mid-run. */
    private val useLock = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var vad: SileroVad? = null
    private var engine: SttEngine? = null

    private var captureJob: Job? = null
    private val buffer = ArrayList<Float>(MAX_UTTERANCE_SAMPLES / 4)
    private val preroll = ArrayDeque<FloatArray>(PREROLL_FRAMES)
    private var sawSpeech = false

    /** True when [lang] has every STT file it needs on disk. */
    fun isAvailable(lang: String): Boolean {
        val spec = ModelCatalog.byLang(context, lang) ?: return false
        return store.hasStt(spec)
    }

    fun missingFiles(lang: String): List<String> {
        val spec = ModelCatalog.byLang(context, lang) ?: return emptyList()
        return spec.sttSpecs.filterNot { store.isPresent(lang, it) }.map { it.fileName }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Loads (or reuses) the engine for [lang], unloading any other language first.
     */
    private suspend fun engineFor(lang: String): SttEngine? = loadLock.withLock {
        engine?.let { if (it.lang == lang) return@withLock it }

        engine?.let {
            Log.i(TAG, "Switching STT ${it.lang} -> $lang; unloading previous")
            runCatching { it.close() }
            engine = null
        }

        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            Log.e(TAG, "STT_UNAVAILABLE[$lang]: not in manifest")
            return@withLock null
        }

        val modelSpec = spec.of(ModelRole.STT_ACOUSTIC)
        val tokensSpec = spec.of(ModelRole.STT_TOKENS)
        val modelFile = modelSpec?.let { store.resolve(lang, it) }
        val tokensFile = tokensSpec?.let { store.resolve(lang, it) }

        if (modelFile == null || tokensFile == null) {
            val missing = missingFiles(lang)
            Log.e(TAG, "STT_UNAVAILABLE[$lang]: missing ${missing.joinToString()}")
            _state.value = State.Unavailable(SttUnavailable.ModelMissing(lang, missing))
            return@withLock null
        }

        val loaded = withContext(Dispatchers.IO) {
            IndicConformerStt.load(lang, modelFile, tokensFile)
        }
        if (loaded == null) {
            _state.value = State.Unavailable(SttUnavailable.LoadFailed(lang, "ONNX session could not be created"))
        }
        engine = loaded
        loaded
    }

    private suspend fun vadOrLoad(): SileroVad? = loadLock.withLock {
        vad?.let { return@withLock it }
        val loaded = withContext(Dispatchers.IO) { SileroVad.load(context) }
        vad = loaded
        loaded
    }

    /**
     * Begins capturing. Call [stopAndTranscribe] to finish the utterance.
     *
     * Safe to call when the model is missing — it reports [State.Unavailable] rather
     * than throwing, so push-to-talk never crashes the UI.
     */
    fun startListening(lang: String) {
        if (captureJob != null) {
            Log.w(TAG, "startListening ignored: already capturing")
            return
        }
        if (!hasMicPermission()) {
            Log.e(TAG, "STT_UNAVAILABLE: RECORD_AUDIO not granted")
            _state.value = State.Unavailable(SttUnavailable.PermissionDenied)
            return
        }

        buffer.clear()
        preroll.clear()
        sawSpeech = false

        captureJob = scope.launch {
            // Warm the models before opening the mic so the first words are not lost.
            val detector = vadOrLoad()
            if (engineFor(lang) == null) return@launch

            detector?.reset()
            _state.value = State.Listening(speech = false, level = 0f)
            Log.i(TAG, "Listening [$lang] (vad=${if (detector != null) "on" else "off"})")

            try {
                capture.frames().collect { frame ->
                    if (buffer.size >= MAX_UTTERANCE_SAMPLES) return@collect

                    var level = 0f
                    for (s in frame) level = maxOf(level, abs(s))

                    // Without a VAD we still work — we simply keep everything.
                    val prob = detector?.speechProbability(frame)
                    val isSpeech = prob == null || prob >= SPEECH_THRESHOLD

                    if (isSpeech) {
                        if (!sawSpeech) {
                            // Flush the pre-roll so the utterance does not start clipped.
                            preroll.forEach { pre -> pre.forEach { buffer.add(it) } }
                            preroll.clear()
                            sawSpeech = true
                        }
                        frame.forEach { buffer.add(it) }
                    } else if (sawSpeech) {
                        // Keep trailing silence: it carries the final phoneme's release.
                        frame.forEach { buffer.add(it) }
                    } else {
                        if (preroll.size == PREROLL_FRAMES) preroll.removeFirst()
                        preroll.addLast(frame)
                    }

                    _state.value = State.Listening(speech = isSpeech, level = level)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "CAPTURE_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                _state.value = State.Unavailable(SttUnavailable.LoadFailed(lang, e.message ?: "capture failed"))
            }
        }
    }

    /**
     * Ends the utterance and transcribes what was captured.
     *
     * @return the recognised utterance, or null when nothing usable was heard.
     */
    suspend fun stopAndTranscribe(lang: String): Transcript? {
        captureJob?.cancelAndJoin()
        captureJob = null

        val samples = buffer.toFloatArray()
        buffer.clear()
        preroll.clear()

        if (!sawSpeech || samples.isEmpty()) {
            Log.i(TAG, "No speech detected in utterance (${samples.size} samples)")
            settle()
            return null
        }

        // Anything under ~200 ms is a button tap, not an utterance.
        if (samples.size < AudioCapture.SAMPLE_RATE / 5) {
            Log.i(TAG, "Utterance too short: ${samples.size} samples")
            settle()
            return null
        }

        _state.value = State.Transcribing
        val audioMs = samples.size * 1000L / AudioCapture.SAMPLE_RATE
        val started = android.os.SystemClock.elapsedRealtime()
        val text = try {
            useLock.withLock {
                val active = engineFor(lang) ?: return null
                withContext(Dispatchers.Default) { active.transcribe(samples) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TRANSCRIBE_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } finally {
            settle()
        }
        val inferenceMs = android.os.SystemClock.elapsedRealtime() - started

        val heard = text?.takeIf { it.isNotBlank() } ?: return null
        Log.i(TAG, "Transcribed ${audioMs}ms of [$lang] speech in ${inferenceMs}ms")
        return Transcript(heard, audioMs, inferenceMs)
    }

    /** Aborts the current utterance without transcribing. */
    fun cancel() {
        scope.launch {
            captureJob?.cancelAndJoin()
            captureJob = null
            buffer.clear()
            preroll.clear()
            settle()
        }
    }

    /**
     * Returns to idle, but leaves an [State.Unavailable] explanation on screen — a
     * missing model or denied mic is why nothing happened, and clearing it here would
     * make the failure look like silence.
     */
    private fun settle() {
        if (_state.value !is State.Unavailable) _state.value = State.Idle
    }

    /**
     * Frees model memory. Safe at any moment: capture is stopped first, and a
     * transcription in flight finishes before its session is closed. The next utterance
     * reloads lazily.
     */
    fun release() {
        scope.launch {
            captureJob?.cancelAndJoin()
            captureJob = null
            useLock.withLock { loadLock.withLock {
                runCatching { engine?.close() }
                engine = null
                runCatching { vad?.close() }
                vad = null
            } }
            settle()
        }
    }
}
