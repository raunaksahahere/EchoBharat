package com.echobharat.models

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves where a language pack's files live on disk.
 *
 * Layout: `<root>/models/<lang>/<fileName>`
 *
 * Two roots are consulted, in order:
 *  1. `filesDir` — app-private internal storage, where the Model Manager installs
 *     verified downloads (Rules §5).
 *  2. `getExternalFilesDir(null)` — app-specific external storage. Nothing writes here
 *     at runtime; it exists so exported models can be sideloaded during development
 *     with `adb push` (which cannot reach internal storage on a non-rooted device)
 *     without weakening the install path.
 */
class ModelStore(private val context: Context) {

    companion object {
        private const val TAG = "ModelStore"
        private const val DIR = "models"
        private const val TRANSLATION_DIR = "mt"
        private val LEGACY_TRANSLATION_DIRS = listOf("hi", "en")
    }

    /** Canonical install root — verified downloads land here. */
    val installRoot: File get() = File(context.filesDir, DIR)

    /** Sideload root, or null when external storage is unavailable. */
    val sideloadRoot: File? get() = context.getExternalFilesDir(null)?.let { File(it, DIR) }

    fun installDir(lang: String): File = File(installRoot, lang)

    /**
     * Returns the readable file for [spec] in [lang], or null when it is not present.
     */
    fun resolve(lang: String, spec: ModelSpec): File? = resolve(lang, spec.fileName)

    fun resolve(lang: String, fileName: String): File? {
        val installed = File(installDir(lang), fileName)
        if (installed.isFile && installed.length() > 0) return installed

        val sideloaded = sideloadRoot?.let { File(File(it, lang), fileName) }
        if (sideloaded != null && sideloaded.isFile && sideloaded.length() > 0) {
            Log.i(TAG, "Using sideloaded model: ${sideloaded.absolutePath}")
            return sideloaded
        }
        return null
    }

    fun isPresent(lang: String, spec: ModelSpec): Boolean = resolve(lang, spec) != null

    /** True when every file the language needs for speech-to-text is on disk. */
    fun hasStt(spec: LanguageModelSpec): Boolean =
        spec.sttSpecs.isNotEmpty() && spec.sttSpecs.all { isPresent(spec.lang, it) }

    /** True when every file the language needs for text-to-speech is on disk. */
    fun hasTts(spec: LanguageModelSpec): Boolean =
        spec.ttsSpecs.isNotEmpty() && spec.ttsSpecs.all { isPresent(spec.lang, it) }

    /** Bytes currently occupied by an installed pack. */
    fun installedBytes(lang: String): Long =
        installDir(lang).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes an installed pack. Sideloaded files are left alone. */
    fun delete(lang: String): Boolean = installDir(lang).deleteRecursively()

    /** Lists the missing files for a language, for surfacing in the UI. */
    fun missing(spec: LanguageModelSpec): List<ModelSpec> =
        spec.models.filterNot { isPresent(spec.lang, it) }

    // ---- translation --------------------------------------------------------------

    /** Translation families install here, once, for every language that uses them. */
    val translationDir: File get() = installDir(TRANSLATION_DIR)

    /**
     * Finds a translation file. Besides the shared `mt/` directory this also looks in the
     * per-language directories translation used to install into (`hi/` held en->hi,
     * `en/` held hi->en), so phones provisioned before translation became shared keep
     * working without a re-download.
     */
    fun resolveTranslation(fileName: String): File? =
        (listOf(TRANSLATION_DIR) + LEGACY_TRANSLATION_DIRS).firstNotNullOfOrNull { resolve(it, fileName) }

    fun hasFamily(spec: TranslationFamilySpec): Boolean =
        spec.files.isNotEmpty() && spec.files.all { resolveTranslation(it.fileName) != null }

    fun hasFastDecoder(spec: TranslationFamilySpec): Boolean =
        spec.fast.isNotEmpty() && spec.fast.all { resolveTranslation(it.fileName) != null }

    fun missingFamily(spec: TranslationFamilySpec, includeFast: Boolean): List<ModelSpec> =
        (if (includeFast) spec.files + spec.fast else spec.files)
            .filter { resolveTranslation(it.fileName) == null }

    /** Removes a family's installed files, wherever an install put them. */
    fun deleteFamily(spec: TranslationFamilySpec) {
        for (dir in listOf(TRANSLATION_DIR) + LEGACY_TRANSLATION_DIRS) {
            for (f in spec.files + spec.fast) File(installDir(dir), f.fileName).delete()
        }
    }
}
