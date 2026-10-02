package com.echobharat.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Fetches one model file and proves it is the file the manifest names.
 *
 * Built for the network a relief worker actually has before heading out: slow, and prone
 * to dropping halfway through a 200 MB file. So a download is **resumable** — the partial
 * `.part` file survives a failure and the next attempt asks the server for the rest with an
 * HTTP Range request, re-hashing what is already on disk rather than trusting it — and each
 * source is **retried** with backoff before the mirror is tried.
 *
 * Nothing becomes the real file until its SHA-256 matches (Rules §19). A mismatch deletes
 * the partial file, since resuming a corrupt prefix could never succeed.
 *
 * No Android dependencies, so the rules above are unit-tested against a mock server.
 */
class VerifiedDownloader(
    private val http: OkHttpClient,
    private val attemptsPerSource: Int = 3,
    private val backoffMs: Long = 2_000L
) {

    sealed interface Result {
        data class Installed(val file: File) : Result
        data class Failed(val reason: String) : Result
    }

    private class ChecksumMismatch(val actual: String) : Exception("checksum mismatch")

    /**
     * Downloads the first source that yields [sha256] into [target].
     *
     * @param onProgress fraction of [expectedBytes] (or of the server's length) on disk.
     */
    suspend fun download(
        sources: List<String>,
        target: File,
        sha256: String,
        expectedBytes: Long,
        onProgress: (Float) -> Unit = {}
    ): Result {
        val part = File(target.parentFile, "${target.name}.part")
        var lastError = "no source URL"

        for (url in sources) {
            for (attempt in 1..attemptsPerSource) {
                currentCoroutineContext().ensureActive()
                try {
                    val digest = fetch(url, part, expectedBytes, onProgress)
                    if (!digest.equals(sha256, ignoreCase = true)) throw ChecksumMismatch(digest)
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    return Result.Installed(target)
                } catch (e: ChecksumMismatch) {
                    // A bad copy on this source; its prefix is worthless, and retrying the
                    // same URL would fetch the same bytes. Move to the next source.
                    part.delete()
                    lastError = "checksum mismatch for ${target.name}: expected $sha256, got ${e.actual}"
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // keep the .part: cancelling is not a reason to lose progress
                } catch (e: Exception) {
                    lastError = "download failed for ${target.name}: ${e.message ?: e.javaClass.simpleName}"
                    if (attempt < attemptsPerSource) delay(backoffMs * attempt)
                }
            }
        }
        return Result.Failed(lastError)
    }

    /** Streams [url] onto the end of [part], returning the SHA-256 of the whole file. */
    private suspend fun fetch(url: String, part: File, expectedBytes: Long, onProgress: (Float) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        var have = if (part.isFile) part.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        http.newCall(request).execute().use { response ->
            val append = when {
                have > 0 && response.code == 206 -> true
                response.code == 416 && have > 0 -> {
                    // Already have every byte; the server has nothing left to send.
                    return hashFile(part, md)
                }
                response.isSuccessful -> false // 200: the server ignored Range; start over
                else -> throw IllegalStateException("HTTP ${response.code}")
            }
            if (append) hashFile(part, md) else have = 0L

            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }?.let { it + have } ?: expectedBytes
            var written = have
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        written += n
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            return md.digest().toHex()
        }
    }

    private fun hashFile(file: File, md: MessageDigest): String {
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return (md.clone() as MessageDigest).digest().toHex()
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** SHA-256 of a whole file, lowercase hex. */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }
    }
}
