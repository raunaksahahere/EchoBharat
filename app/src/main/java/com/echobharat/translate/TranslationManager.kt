package com.echobharat.translate

import android.content.Context
import android.util.Log
import com.echobharat.models.ModelCatalog
import com.echobharat.models.ModelRole
import com.echobharat.models.ModelStore
import com.echobharat.models.TranslationFamilySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the received-text → translated-text step that sits between the mesh and TTS.
 *
 * The architecture this serves: a sender transmits text **in its own language**, tagged
 * with that language code, and never translates. Every receiver independently translates
 * into whatever language *it* has selected. One broadcast therefore reaches speakers of
 * different languages at once, each hearing their own — which is the point of a
 * multilingual mesh, and is only possible because translation happens on receive.
 *
 * Any of the ten languages can be translated into any other: English ↔ Indian directly,
 * Indian ↔ Indian through English (see [TranslationRoutes]).
 *
 * Translating never throws. A failure surfaces as [Outcome.Failed] and callers fall back
 * to showing the original text *marked as untranslated* — presenting an untranslated
 * distress message as though it had been translated is the dangerous failure here.
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"

        fun isSupported(source: String, target: String) = TranslationRoutes.route(source, target) != null
    }

    sealed interface State {
        data object Idle : State
        data class Loading(val family: String) : State
        data class Translating(val direction: String) : State
        data class Unavailable(val reason: TranslationUnavailable) : State
    }

    /**
     * Result of asking for a translation.
     *
     * [NotNeeded] and [Failed] are deliberately distinct: both end up displaying the
     * original string, but only one of them is a problem, and the UI must be able to tell
     * the reader which case they are looking at.
     */
    sealed interface Outcome {
        /** Source language already matches the reader's language. */
        data class NotNeeded(val text: String) : Outcome

        /** [via] names the pivot language when two models were chained, else null. */
        data class Translated(
            val text: String,
            val original: String,
            val millis: Long,
            val via: String? = null
        ) : Outcome

        data class Failed(val original: String, val reason: TranslationUnavailable) : Outcome
    }

    private val store = ModelStore(context)
    private val loadLock = Mutex()

    /** Held while an engine is translating, so [release] cannot close it mid-run. */
    private val useLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TranslationEngine? = null

    private fun familySpec(id: String): TranslationFamilySpec? = ModelCatalog.translationFamily(context, id)

    /** Files still needed to translate [source] into [target]; empty when ready. */
    fun missingFiles(source: String, target: String): List<String> {
        val route = TranslationRoutes.route(source, target) ?: return emptyList()
        return route.map { it.family }.distinct().flatMap { id ->
            val spec = familySpec(id) ?: return@flatMap listOf("<$id not in manifest>")
            store.missingFamily(spec, includeFast = false).map { it.fileName }
        }
    }

    /** True when every model on the route is already on disk. */
    fun isAvailable(source: String, target: String): Boolean =
        isSupported(source, target) && missingFiles(source, target).isEmpty()

    /**
     * Translates [text] from [source] into [target].
     *
     * Same-language input short-circuits before any model is touched, which is both the
     * common case on a single-language mesh and the reason a phone never needs a model
     * for its own language.
     */
    suspend fun translate(text: String, source: String, target: String): Outcome {
        if (source == target || text.isBlank()) return Outcome.NotNeeded(text)

        val route = TranslationRoutes.route(source, target)
        if (route == null) {
            Log.i(TAG, "no model for $source->$target; passing text through untranslated")
            return Outcome.Failed(text, TranslationUnavailable.UnsupportedPair(source, target))
        }

        val missing = missingFiles(source, target)
        if (missing.isNotEmpty()) {
            val direction = "$source->$target"
            Log.w(TAG, "TRANSLATION_UNAVAILABLE[$direction]: missing ${missing.joinToString()}")
            val reason = TranslationUnavailable.ModelMissing(direction, missing)
            _state.value = State.Unavailable(reason)
            return Outcome.Failed(text, reason)
        }

        val started = System.currentTimeMillis()
        var current = text
        for (hop in route) {
            val translated = useLock.withLock {
                val active = engineFor(hop.family)
                    ?: return Outcome.Failed(
                        text,
                        TranslationUnavailable.LoadFailed("${hop.source}->${hop.target}", "engine unavailable")
                    )
                _state.value = State.Translating("${hop.source}->${hop.target}")
                withContext(Dispatchers.Default) { active.translate(current, hop.source, hop.target) }
            }
            if (translated.isNullOrBlank()) {
                _state.value = State.Idle
                Log.e(TAG, "TRANSLATION_FAILED[${hop.source}->${hop.target}]")
                return Outcome.Failed(
                    text,
                    TranslationUnavailable.LoadFailed("${hop.source}->${hop.target}", "inference returned nothing")
                )
            }
            current = translated
        }
        _state.value = State.Idle

        val via = route.takeIf { it.size > 1 }?.first()?.target
        return Outcome.Translated(current, text, System.currentTimeMillis() - started, via)
    }

    /**
     * One family is resident at a time, mirroring TtsManager's single-voice rule — both
     * would be roughly 500 MB on a phone that also holds STT and TTS. A pivoted
     * translation therefore swaps models mid-way; that is the price of fitting in memory.
     */
    private suspend fun engineFor(family: String): TranslationEngine? = loadLock.withLock {
        engine?.let {
            if (it.family == family) return@withLock it
            Log.i(TAG, "Switching MT ${it.family} -> $family")
            runCatching { it.close() }
            engine = null
        }

        val spec = familySpec(family) ?: return@withLock null
        _state.value = State.Loading(family)
        val opened = withContext(Dispatchers.IO) {
            val files = (spec.files + spec.fast).mapNotNull { m ->
                store.resolveTranslation(m.fileName)?.let { m.role to it }
            }.toMap<ModelRole, java.io.File>()
            IndicTrans2Translator.open(family, files, spec.tags)
        }
        _state.value = State.Idle

        opened.fold(
            onSuccess = {
                Log.i(TAG, "Loaded MT $family (${if (it.cached) "KV-cached" else "cacheless"} decoding)")
                engine = it
                it
            },
            onFailure = {
                Log.e(TAG, "MT load failed for $family: ${it.message}", it)
                _state.value = State.Unavailable(
                    TranslationUnavailable.LoadFailed(family, it.message ?: it.javaClass.simpleName)
                )
                null
            }
        )
    }

    /** Frees the loaded family once any translation in flight has finished. */
    fun release() {
        scope.launch {
            useLock.withLock {
                loadLock.withLock {
                    engine?.let { runCatching { it.close() } }
                    engine = null
                }
            }
            _state.value = State.Idle
        }
    }
}
