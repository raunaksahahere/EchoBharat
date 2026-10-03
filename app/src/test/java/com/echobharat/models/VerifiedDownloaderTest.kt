package com.echobharat.models

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class VerifiedDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val downloader = VerifiedDownloader(OkHttpClient(), attemptsPerSource = 2, backoffMs = 1)

    private val payload = "model weights ".repeat(5_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.close()

    private fun target() = File(temp.root, "model.onnx")
    private fun url(path: String = "/m") = server.url(path).toString()
    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun `a verified file is installed and the partial file is gone`() = runBlocking {
        server.enqueue(ok(payload))
        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals(payload, target().readText())
        assertFalse(File(temp.root, "model.onnx.part").exists())
    }

    @Test
    fun `an interrupted download resumes with a range request`() = runBlocking {
        val half = payload.length / 2
        File(temp.root, "model.onnx.part").writeText(payload.substring(0, half))
        server.enqueue(
            MockResponse.Builder().code(206)
                .headers(Headers.headersOf("Content-Range", "bytes $half-${payload.length - 1}/${payload.length}"))
                .body(payload.substring(half)).build()
        )

        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
        assertEquals(payload, target().readText())
    }

    @Test
    fun `a server that ignores the range starts over rather than duplicating bytes`() = runBlocking {
        File(temp.root, "model.onnx.part").writeText(payload.substring(0, 100))
        server.enqueue(ok(payload))

        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals(payload, target().readText())
    }

    @Test
    fun `wrong bytes are never installed and the mirror is tried`() = runBlocking {
        server.enqueue(ok("tampered"))
        server.enqueue(ok(payload))

        val result = downloader.download(listOf(url("/cdn"), url("/mirror")), target(), sha, 0)

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals("/cdn", server.takeRequest().url.encodedPath)
        assertEquals("/mirror", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `when every source is wrong nothing is left behind`() = runBlocking {
        server.enqueue(ok("tampered"))
        val result = downloader.download(listOf(url()), target(), sha, 0)

        assertTrue(result is VerifiedDownloader.Result.Failed)
        assertFalse(target().exists())
        assertFalse(File(temp.root, "model.onnx.part").exists())
    }

    @Test
    fun `a stale partial rejected with 416 retries the same source from scratch`() = runBlocking {
        File(temp.root, "model.onnx.part").writeText("x".repeat(payload.length + 1))
        server.enqueue(MockResponse.Builder().code(416)
            .headers(Headers.headersOf("Content-Range", "bytes */${payload.length}")).build())
        server.enqueue(ok(payload))

        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals(payload, target().readText())
        assertEquals("bytes=${payload.length + 1}-", server.takeRequest().headers["Range"])
        assertEquals(null, server.takeRequest().headers["Range"])
    }

    @Test
    fun `a corrupt resumed prefix retries the same source from scratch`() = runBlocking {
        val half = payload.length / 2
        File(temp.root, "model.onnx.part").writeText("x".repeat(half))
        server.enqueue(MockResponse.Builder().code(206)
            .headers(Headers.headersOf("Content-Range", "bytes $half-${payload.length - 1}/${payload.length}"))
            .body(payload.substring(half)).build())
        server.enqueue(ok(payload))

        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals(payload, target().readText())
        assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
        assertEquals(null, server.takeRequest().headers["Range"])
    }

    @Test
    fun `a complete verified partial is installed when range returns 416`() = runBlocking {
        File(temp.root, "model.onnx.part").writeText(payload)
        server.enqueue(MockResponse.Builder().code(416)
            .headers(Headers.headersOf("Content-Range", "bytes */${payload.length}")).build())

        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong())

        assertTrue(result is VerifiedDownloader.Result.Installed)
        assertEquals(payload, target().readText())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `metered gating prevents even opening a download request`() = runBlocking {
        target().writeText("working old model")
        val result = downloader.download(listOf(url()), target(), sha, 0, mayDownload = { false })
        assertTrue(result is VerifiedDownloader.Result.Failed)
        assertEquals(0, server.requestCount)
        assertEquals("working old model", target().readText())
    }

    @Test
    fun `network permission loss pauses a transfer and preserves the active file`() = runBlocking {
        target().writeText("working old model")
        server.enqueue(ok(payload))
        var permitted = true
        val result = downloader.download(listOf(url()), target(), sha, payload.length.toLong(),
            mayDownload = { permitted }, onProgress = { permitted = false })
        assertTrue(result is VerifiedDownloader.Result.Failed)
        assertEquals(1, server.requestCount)
        assertEquals("working old model", target().readText())
        assertTrue(File(temp.root, "model.onnx.part").isFile)
    }

    @Test
    fun `wrong hash on replacement never overwrites or deletes the working model`() = runBlocking {
        target().writeText("working old model")
        server.enqueue(ok("tampered"))
        assertTrue(downloader.download(listOf(url()), target(), sha, 0) is VerifiedDownloader.Result.Failed)
        assertEquals("working old model", target().readText())
    }

    @Test
    fun `server errors are retried and a partial file is kept for next time`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        File(temp.root, "model.onnx.part").writeText("partial")

        val result = downloader.download(listOf(url()), target(), sha, 0)

        assertTrue(result is VerifiedDownloader.Result.Failed)
        assertEquals(2, server.requestCount)
        assertTrue(File(temp.root, "model.onnx.part").exists())
    }
}
