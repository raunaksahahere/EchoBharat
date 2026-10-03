package com.echobharat.models

/**
 * Identifies one model file inside a language pack.
 *
 * A pack is described declaratively (see `assets/models/manifest.json`) so that new
 * exports can be published without touching Kotlin: the Model Manager resolves,
 * downloads and verifies whatever the manifest names.
 */
enum class ModelRole {
    /** IndicConformer acoustic model (ONNX). */
    STT_ACOUSTIC,

    /** Token/vocabulary list backing the STT decoder. */
    STT_TOKENS,

    /** FastPitch acoustic model (ONNX). */
    TTS_ACOUSTIC,

    /** HiFi-GAN vocoder (ONNX). */
    TTS_VOCODER,

    /** Symbol table backing TTS text normalisation. */
    TTS_TOKENS,

    /** IndicTrans2 encoder (ONNX). */
    MT_ENCODER,

    /** IndicTrans2 decoder (ONNX), cacheless: runs every step, and the first cached one. */
    MT_DECODER,

    /** IndicTrans2 decoder taking past key/values — optional, makes decoding linear. */
    MT_DECODER_PAST,

    /** BPE vocabulary for the translation source side. */
    MT_BPE_SRC,

    /** BPE vocabulary for the translation target side. */
    MT_BPE_TGT,

    /** Vocabulary sizes (and the hi/en tag ids) for one translation family. */
    MT_META
}

/**
 * One downloadable/bundled file.
 *
 * @param sha256 lowercase hex digest. Empty means "not yet published" — [ModelManager]
 *   refuses to install such a file rather than trusting an unverified download
 *   (Rules §19).
 */
data class ModelSpec(
    val role: ModelRole,
    val fileName: String,
    val url: String = "",
    val mirrorUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L
) {
    val isPublished: Boolean get() = url.isNotBlank() && sha256.isNotBlank()
}

/**
 * The full set of files one language needs to run STT and TTS offline.
 */
data class LanguageModelSpec(
    val lang: String,
    val displayName: String,
    val nativeName: String,
    val bundled: Boolean,
    val models: List<ModelSpec>,
    /** Monotonic voice-pack schema/version, independent of the APK version. */
    val packVersion: Int = 1,
    /** Historical, still trusted hashes for resolving and importing existing voice files. */
    val legacyModels: List<ModelSpec> = emptyList()
) {
    fun of(role: ModelRole): ModelSpec? = models.firstOrNull { it.role == role }

    val sttSpecs: List<ModelSpec> get() = models.filter { it.role == ModelRole.STT_ACOUSTIC || it.role == ModelRole.STT_TOKENS }
    val ttsSpecs: List<ModelSpec> get() = models.filter {
        it.role == ModelRole.TTS_ACOUSTIC || it.role == ModelRole.TTS_VOCODER || it.role == ModelRole.TTS_TOKENS
    }

    val totalBytes: Long get() = models.sumOf { it.sizeBytes }
}

/**
 * One IndicTrans2 model family — a single direction that covers every language.
 *
 * Translation is shared, not per language: `en-indic` turns English into any of the
 * Indian languages and `indic-en` does the reverse, so installing one serves every
 * language that needs it. Kept apart from [LanguageModelSpec] on purpose — it is optional
 * and larger than a voice pack, and the voice loop never depends on it.
 *
 * @param fast the KV-cache decoder: optional, installable separately, used when present.
 * @param tags FLORES tag (e.g. "tam_Taml") -> graph id in this family's source vocabulary.
 */
data class TranslationFamilySpec(
    val id: String,
    val title: String,
    val files: List<ModelSpec>,
    val fast: List<ModelSpec>,
    val tags: Map<String, Int>
) {
    fun of(role: ModelRole): ModelSpec? = (files + fast).firstOrNull { it.role == role }
    val bytes: Long get() = files.sumOf { it.sizeBytes }
    val fastBytes: Long get() = fast.sumOf { it.sizeBytes }
}
