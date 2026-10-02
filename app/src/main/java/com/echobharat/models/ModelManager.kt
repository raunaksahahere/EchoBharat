package com.echobharat.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies and installs language packs.
 *
 * This is the ONLY component in EchoBharat permitted to touch the network, and only
 * for one-time provisioning (Rules §1). Nothing in the speak → STT → mesh → TTS → hear
 * loop calls into it.
 *
 * Every file is SHA-256 verified before it is moved into place; a mismatch deletes the
 * download and fails the install (Rules §19).
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val FREE_SPACE_MARGIN = 200L * 1024 * 1024

        /** Progress key for an import in flight. */
        const val IMPORT = "import"
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val lang: String, val fileName: String, val fraction: Float) : Progress
        data class Verifying(val lang: String, val fileName: String) : Progress
        data class Done(val lang: String) : Progress
        data class Failed(val lang: String, val reason: String) : Progress
    }

    private val store = ModelStore(context)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Installs every missing file for [lang].
     *
     * @return true when the pack is fully present afterwards.
     */
    suspend fun install(lang: String): Boolean = withContext(Dispatchers.IO) {
        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            fail(lang, "Unknown language '$lang'")
            return@withContext false
        }

        val missing = store.missing(spec)
        if (missing.isEmpty()) {
            Log.i(TAG, "Pack '$lang' already complete")
            _progress.value = Progress.Done(lang)
            return@withContext true
        }

        val unpublished = missing.filterNot { it.isPublished }
        if (unpublished.isNotEmpty()) {
            // Refusing here is deliberate: installing a file we cannot checksum would
            // silently break the offline-integrity guarantee.
            // File names, manifest keys and sideload paths are developer detail and go to
            // logcat. What reaches the screen stays plain: this is the ordinary state of
            // every language except Hindi and English, not an error the user can act on.
            Log.w(
                TAG,
                "UNPUBLISHED $lang: no verified download for " +
                    unpublished.joinToString { it.fileName } +
                    " — set url + sha256 in assets/models/manifest.json, or sideload into " +
                    "${store.sideloadRoot?.absolutePath}/$lang/"
            )
            fail(lang, "Not available yet")
            return@withContext false
        }

        val dir = store.installDir(lang).apply { mkdirs() }
        if (!hasRoomFor(lang, dir, missing)) return@withContext false

        for (model in missing) {
            currentCoroutineContext().ensureActive()
            val ok = downloadAndVerify(lang, model, dir)
            if (!ok) return@withContext false
        }

        val complete = store.missing(spec).isEmpty()
        if (complete) {
            Log.i(TAG, "Pack '$lang' installed (${store.installedBytes(lang)} bytes)")
            _progress.value = Progress.Done(lang)
        } else {
            fail(lang, "Install finished but files are still missing")
        }
        complete
    }

    private val downloader by lazy { VerifiedDownloader(http) }

    private suspend fun downloadAndVerify(lang: String, model: ModelSpec, dir: File): Boolean {
        val sources = listOfNotNull(
            model.url.takeIf { it.isNotBlank() },
            model.mirrorUrl.takeIf { it.isNotBlank() }
        )
        _progress.value = Progress.Downloading(lang, model.fileName, 0f)
        val result = downloader.download(
            sources = sources,
            target = File(dir, model.fileName),
            sha256 = model.sha256,
            expectedBytes = model.sizeBytes
        ) { fraction -> _progress.value = Progress.Downloading(lang, model.fileName, fraction) }

        return when (result) {
            is VerifiedDownloader.Result.Installed -> {
                Log.i(TAG, "Installed ${model.fileName} (${result.file.length()} bytes)")
                true
            }
            is VerifiedDownloader.Result.Failed -> {
                fail(lang, result.reason)
                false
            }
        }
    }

    /**
     * Refuses to start a download the phone has no room for. Filling the disk halfway
     * through a 200 MB file helps nobody, and on a cheap phone it can take the mesh's own
     * storage down with it. Partial files already on disk count towards what is needed.
     */
    private fun hasRoomFor(lang: String, dir: File, files: List<ModelSpec>): Boolean {
        val needed = files.sumOf { spec ->
            val part = File(dir, "${spec.fileName}.part")
            (spec.sizeBytes - (if (part.isFile) part.length() else 0L)).coerceAtLeast(0L)
        }
        val free = dir.usableSpace
        // Keep a margin so the rest of the phone keeps working afterwards.
        if (needed + FREE_SPACE_MARGIN > free) {
            fail(
                lang,
                "Not enough storage: needs ${needed / 1_000_000} MB, " +
                    "${free / 1_000_000} MB free"
            )
            return false
        }
        return true
    }

    /** Removes an installed pack to reclaim space. */
    fun uninstall(lang: String): Boolean {
        val ok = store.delete(lang)
        Log.i(TAG, "Uninstall '$lang': $ok")
        return ok
    }

    /**
     * Installs a translation family into the shared `mt/` directory — once for every
     * language that uses it. [includeFast] adds the optional KV-cache decoder.
     *
     * Separate from [install] because it is larger than a voice pack and the voice loop
     * never depends on it: a failure here must not make a language that speaks and listens
     * correctly report itself as broken. Progress is reported under the family id.
     */
    suspend fun installTranslation(familyId: String, includeFast: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val spec = ModelCatalog.translationFamily(context, familyId)
            if (spec == null) {
                fail(familyId, "Unknown translation model '$familyId'")
                return@withContext false
            }

            val missing = store.missingFamily(spec, includeFast)
            if (missing.isEmpty()) {
                _progress.value = Progress.Done(familyId)
                return@withContext true
            }

            val unpublished = missing.filterNot { it.isPublished }
            if (unpublished.isNotEmpty()) {
                Log.w(TAG, "UNPUBLISHED translation $familyId: ${unpublished.joinToString { it.fileName }}")
                fail(familyId, "Not available yet")
                return@withContext false
            }

            val dir = store.translationDir.apply { mkdirs() }
            if (!hasRoomFor(familyId, dir, missing)) return@withContext false
            for (model in missing) {
                currentCoroutineContext().ensureActive()
                if (!downloadAndVerify(familyId, model, dir)) return@withContext false
            }

            val complete = store.missingFamily(spec, includeFast).isEmpty()
            _progress.value = if (complete) Progress.Done(familyId) else Progress.Idle
            Log.i(TAG, "Translation install for '$familyId' complete=$complete (fast=$includeFast)")
            return@withContext complete
        }

    /**
     * Installs model files the user picked — typically ones another phone shared over
     * Quick Share or Bluetooth. Each is accepted only if its SHA-256 is one the manifest
     * publishes; see [ModelImporter].
     */
    suspend fun importFiles(uris: List<android.net.Uri>): ModelImporter.Report = withContext(Dispatchers.IO) {
        val importer = ModelImporter(
            index = ModelImporter.index(
                ModelCatalog.languages(context),
                ModelCatalog.translationFamilies(context),
                store::installDir,
                store.translationDir
            ),
            scratch = java.io.File(context.cacheDir, "import")
        )
        var report = ModelImporter.Report()
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(uri) ?: "file ${i + 1}"
            _progress.value = Progress.Verifying(IMPORT, name)
            report += try {
                context.contentResolver.openInputStream(uri)?.let { importer.import(name, it) }
                    ?: ModelImporter.Report(rejected = listOf(name))
            } catch (e: Exception) {
                Log.e(TAG, "Import of $name failed: ${e.message}", e)
                ModelImporter.Report(rejected = listOf(name))
            }
        }
        Log.i(TAG, "Import: ${report.installed.size} installed, ${report.alreadyPresent.size} present, ${report.rejected.size} rejected")
        _progress.value = Progress.Idle
        report
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun hasTranslation(familyId: String): Boolean =
        ModelCatalog.translationFamily(context, familyId)?.let { store.hasFamily(it) } ?: false

    fun hasFastTranslation(familyId: String): Boolean =
        ModelCatalog.translationFamily(context, familyId)?.let { store.hasFastDecoder(it) } ?: false

    fun uninstallTranslation(familyId: String) {
        ModelCatalog.translationFamily(context, familyId)?.let { store.deleteFamily(it) }
        Log.i(TAG, "Uninstalled translation '$familyId'")
    }

    fun status(lang: String): PackStatus {
        val spec = ModelCatalog.byLang(context, lang) ?: return PackStatus.UNKNOWN
        val missing = store.missing(spec)
        return when {
            missing.isEmpty() -> PackStatus.INSTALLED
            missing.size < spec.models.size -> PackStatus.PARTIAL
            spec.models.none { it.isPublished } -> PackStatus.UNPUBLISHED
            else -> PackStatus.NOT_INSTALLED
        }
    }

    enum class PackStatus { INSTALLED, PARTIAL, NOT_INSTALLED, UNPUBLISHED, UNKNOWN }

    private fun fail(lang: String, reason: String) {
        Log.e(TAG, "INSTALL_FAILED[$lang]: $reason")
        _progress.value = Progress.Failed(lang, reason)
    }
}
