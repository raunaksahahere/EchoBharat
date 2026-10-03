package com.echobharat.models

import android.content.Context
import java.io.File

/** Verified downloads live internally; explicit sideloads live in app-specific external storage. */
class ModelStore(private val context: Context) {
    companion object {
        private const val TRANSLATION_DIR = "mt"
        private val LEGACY_TRANSLATION_DIRS = listOf("hi", "en")
    }

    val installRoot: File get() = File(context.filesDir, "models")
    val sideloadRoot: File? get() = context.getExternalFilesDir(null)?.let { File(it, "models") }
    val voices: VoicePackFiles get() = VoicePackFiles(installRoot, sideloadRoot)

    fun installDir(lang: String): File = File(installRoot, lang)

    fun resolve(lang: String, spec: ModelSpec): File? {
        if (spec.role in VoicePackFiles.roles) {
            val pack = ModelCatalog.byLang(context, lang) ?: return null
            return voices.resolve(pack)?.files?.get(spec.role)
        }
        return resolve(lang, spec.fileName)
    }

    fun resolve(lang: String, fileName: String): File? =
        listOfNotNull(File(installDir(lang), fileName), sideloadRoot?.let { File(File(it, lang), fileName) })
            .firstOrNull { it.isFile && it.length() > 0 }

    /** Availability is cheap; TTS verifies hashes on IO before opening native sessions. */
    fun isPresent(lang: String, spec: ModelSpec): Boolean {
        if (spec.role in VoicePackFiles.roles) {
            val pack = ModelCatalog.byLang(context, lang) ?: return false
            return voices.resolve(pack, verify = false) != null
        }
        return resolve(lang, spec.fileName) != null
    }

    fun hasStt(spec: LanguageModelSpec): Boolean =
        spec.sttSpecs.isNotEmpty() && spec.sttSpecs.all { isPresent(spec.lang, it) }

    fun hasTts(spec: LanguageModelSpec): Boolean = voices.resolve(spec, verify = false) != null

    fun installedBytes(lang: String): Long =
        installDir(lang).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Explicit user removal only; updates never delete previous voices. */
    fun delete(lang: String): Boolean = installDir(lang).deleteRecursively()

    fun missing(spec: LanguageModelSpec): List<ModelSpec> = spec.models.filterNot { isPresent(spec.lang, it) }

    val translationDir: File get() = installDir(TRANSLATION_DIR)

    fun resolveTranslation(fileName: String): File? =
        (listOf(TRANSLATION_DIR) + LEGACY_TRANSLATION_DIRS).firstNotNullOfOrNull { resolve(it, fileName) }

    fun hasFamily(spec: TranslationFamilySpec): Boolean =
        spec.files.isNotEmpty() && spec.files.all { resolveTranslation(it.fileName) != null }

    fun hasFastDecoder(spec: TranslationFamilySpec): Boolean =
        spec.fast.isNotEmpty() && spec.fast.all { resolveTranslation(it.fileName) != null }

    fun missingFamily(spec: TranslationFamilySpec, includeFast: Boolean): List<ModelSpec> =
        (if (includeFast) spec.files + spec.fast else spec.files)
            .filter { resolveTranslation(it.fileName) == null }

    fun deleteFamily(spec: TranslationFamilySpec) {
        for (dir in listOf(TRANSLATION_DIR) + LEGACY_TRANSLATION_DIRS) {
            for (f in spec.files + spec.fast) File(installDir(dir), f.fileName).delete()
        }
    }
}
