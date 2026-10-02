package com.echobharat.models

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands installed language packs — and the app itself — to the system share sheet, so a
 * phone that downloaded them once can pass them to others over Quick Share, Bluetooth or a
 * cable, with no internet anywhere.
 *
 * That is the provisioning path the disaster case actually needs: one coordinator with a
 * connection, a room of volunteers without one. The receiving phone imports the files
 * through [ModelImporter], which checks each against the manifest's SHA-256 exactly as a
 * download would be checked, so sharing never weakens verification.
 */
class PackSharing(private val context: Context) {

    companion object {
        private const val TAG = "PackSharing"
    }

    private val store = ModelStore(context)
    private val authority = "${context.packageName}.share"

    /** Installed files of a voice pack; empty when nothing is installed. */
    fun voicePackFiles(lang: String): List<File> {
        val spec = ModelCatalog.byLang(context, lang) ?: return emptyList()
        return spec.models.mapNotNull { store.resolve(lang, it) }
    }

    /** Installed files of a translation family, including its fast decoder if present. */
    fun translationFiles(familyId: String): List<File> {
        val spec = ModelCatalog.translationFamily(context, familyId) ?: return emptyList()
        return (spec.files + spec.fast).mapNotNull { store.resolveTranslation(it.fileName) }
    }

    /** A share-sheet intent for [files], or null when none can be shared. */
    fun shareIntent(files: List<File>, title: String): Intent? {
        val uris = files.mapNotNull { f ->
            runCatching { FileProvider.getUriForFile(context, authority, f) }
                .onFailure { Log.w(TAG, "Cannot share ${f.name}: ${it.message}") }
                .getOrNull()
        }
        if (uris.isEmpty()) return null
        return send(ArrayList(uris), "application/octet-stream", title)
    }

    /**
     * Shares this app's own APK. Android keeps it where FileProvider cannot reach, so it is
     * copied into the cache first (and reused while the version is unchanged).
     */
    suspend fun appIntent(): Intent? = withContext(Dispatchers.IO) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val source = File(context.applicationInfo.sourceDir)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val copy = File(dir, "EchoBharat-${info.versionName}.apk")
            if (!copy.isFile || copy.length() != source.length()) {
                dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
                source.copyTo(copy, overwrite = true)
            }
            val uri = FileProvider.getUriForFile(context, authority, copy)
            send(arrayListOf(uri), "application/vnd.android.package-archive", "Share EchoBharat")
        }.onFailure { Log.e(TAG, "Could not stage the APK: ${it.message}", it) }.getOrNull()
    }

    private fun send(uris: ArrayList<Uri>, mime: String, title: String): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        // ClipData carries the read grant to whichever app the user picks.
        send.clipData = ClipData.newRawUri(title, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }
}
