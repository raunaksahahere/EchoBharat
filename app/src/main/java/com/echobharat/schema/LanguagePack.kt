package com.echobharat.schema

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class LanguagePackStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    ERROR
}

enum class ModelRuntime {
    ONNX,
    TFLITE,
    CTRANSLATE2
}

enum class QuantizationType {
    INT8,
    FP16,
    FP32
}

@Parcelize
data class ModelRef(
    val name: String,
    val runtime: ModelRuntime,
    val fileUri: String = "",
    val sha256: String = "",
    val quantization: QuantizationType = QuantizationType.INT8,
    val sourceUrl: String = "",
    val mirrorUrl: String = ""
) : Parcelable

@Parcelize
data class LanguagePack(
    val lang: String,
    val displayName: String,
    val nativeName: String,
    val sttModel: ModelRef,
    val ttsModels: List<ModelRef> = emptyList(),
    val bundled: Boolean = false,
    val status: LanguagePackStatus = if (bundled) LanguagePackStatus.INSTALLED else LanguagePackStatus.NOT_INSTALLED,
    val sizeBytes: Long = 0L,
    val downloadProgress: Float = 0f
) : Parcelable {
    companion object {
        val ALL_LANGUAGES = listOf(
            LanguagePack(
                lang = "hi",
                displayName = "Hindi",
                nativeName = "हिन्दी",
                sttModel = ModelRef("indicconformer-hi", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-hi", ModelRuntime.ONNX), ModelRef("hifigan-hi", ModelRuntime.ONNX)),
                bundled = true,
                status = LanguagePackStatus.INSTALLED,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "en",
                displayName = "English",
                nativeName = "English (Indian)",
                sttModel = ModelRef("indicconformer-en", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-en", ModelRuntime.ONNX), ModelRef("hifigan-en", ModelRuntime.ONNX)),
                bundled = true,
                status = LanguagePackStatus.INSTALLED,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "gu",
                displayName = "Gujarati",
                nativeName = "ગુજરાતી",
                sttModel = ModelRef("indicconformer-gu", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-gu", ModelRuntime.ONNX), ModelRef("hifigan-gu", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "mr",
                displayName = "Marathi",
                nativeName = "मराठी",
                sttModel = ModelRef("indicconformer-mr", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-mr", ModelRuntime.ONNX), ModelRef("hifigan-mr", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "kn",
                displayName = "Kannada",
                nativeName = "ಕನ್ನಡ",
                sttModel = ModelRef("indicconformer-kn", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-kn", ModelRuntime.ONNX), ModelRef("hifigan-kn", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "ml",
                displayName = "Malayalam",
                nativeName = "മലയാളം",
                sttModel = ModelRef("indicconformer-ml", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-ml", ModelRuntime.ONNX), ModelRef("hifigan-ml", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "ta",
                displayName = "Tamil",
                nativeName = "தமிழ்",
                sttModel = ModelRef("indicconformer-ta", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-ta", ModelRuntime.ONNX), ModelRef("hifigan-ta", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "te",
                displayName = "Telugu",
                nativeName = "తెలుగు",
                sttModel = ModelRef("indicconformer-te", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-te", ModelRuntime.ONNX), ModelRef("hifigan-te", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "or",
                displayName = "Odia",
                nativeName = "ଓଡ଼ିଆ",
                sttModel = ModelRef("indicconformer-or", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-or", ModelRuntime.ONNX), ModelRef("hifigan-or", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            ),
            LanguagePack(
                lang = "bn",
                displayName = "Bengali",
                nativeName = "বাংলা",
                sttModel = ModelRef("indicconformer-bn", ModelRuntime.ONNX),
                ttsModels = listOf(ModelRef("fastpitch-bn", ModelRuntime.ONNX), ModelRef("hifigan-bn", ModelRuntime.ONNX)),
                bundled = false,
                sizeBytes = 45_000_000L
            )
        )
    }
}

@Parcelize
data class TranslationModel(
    val name: String = "indictrans2-distilled",
    val runtime: ModelRuntime = ModelRuntime.ONNX,
    val status: LanguagePackStatus = LanguagePackStatus.NOT_INSTALLED,
    val sha256: String = "",
    val sourceUrl: String = "",
    val mirrorUrl: String = ""
) : Parcelable
