package com.echobharat.models

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs model files that arrived by any route other than a download — Quick Share,
 * Bluetooth, a USB stick, a file manager.
 *
 * A file is identified by its SHA-256 alone. Its name is whatever the sending app chose, so
 * it is ignored: the content either hashes to a file the bundled manifest publishes, and is
 * installed under that file's name in the directory it belongs to, or it is rejected. The
 * trust model is therefore exactly the download path's (Rules §19) — the bytes must be the
 * published bytes — which is what makes phone-to-phone provisioning safe to offer.
 *
 * Plain JVM over [File]s and streams; the Android side only opens content URIs.
 */
class ModelImporter(
    /** sha256 -> every place that file is installed (a file can serve several packs). */
    private val index: Map<String, List<File>>,
    private val scratch: File
) {

    data class Report(
        val installed: List<String> = emptyList(),
        val alreadyPresent: List<String> = emptyList(),
        val rejected: List<String> = emptyList()
    ) {
        operator fun plus(o: Report) = Report(
            installed + o.installed, alreadyPresent + o.alreadyPresent, rejected + o.rejected
        )
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** Builds the lookup from the manifest: voice-pack files and translation files. */
        fun index(
            languages: List<LanguageModelSpec>,
            families: List<TranslationFamilySpec>,
            installDir: (String) -> File,
            translationDir: File
        ): Map<String, List<File>> {
            val map = HashMap<String, MutableList<File>>()
            fun add(spec: ModelSpec, dir: File) {
                if (spec.sha256.isBlank()) return
                map.getOrPut(spec.sha256.lowercase()) { mutableListOf() }.add(File(dir, spec.fileName))
            }
            languages.forEach { l -> l.models.forEach { add(it, installDir(l.lang)) } }
            families.forEach { f -> (f.files + f.fast).forEach { add(it, translationDir) } }
            return map
        }
    }

    /**
     * Hashes [input] into a scratch file, then installs it everywhere the manifest wants
     * that content. [name] is used for the report only.
     */
    fun import(name: String, input: InputStream): Report {
        scratch.mkdirs()
        val tmp = File.createTempFile("import-", ".part", scratch)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val targets = index[sha] ?: return Report(rejected = listOf(name))

            val missing = targets.filter { !(it.isFile && it.length() == tmp.length()) }
            if (missing.isEmpty()) return Report(alreadyPresent = listOf(targets.first().name))
            for (target in missing) {
                target.parentFile?.mkdirs()
                tmp.copyTo(target, overwrite = true)
            }
            return Report(installed = missing.map { it.name }.distinct())
        } finally {
            tmp.delete()
        }
    }
}
