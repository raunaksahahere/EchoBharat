package com.echobharat.stt

import android.content.Context
import android.content.res.AssetManager
import com.echobharat.models.ModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SttManagerTest {
    private fun manager(engine: SttEngine): SttManager {
        val assets = mock<AssetManager> {
            on { open("models/manifest.json") } doAnswer { File("src/main/assets/models/manifest.json").inputStream() }
        }
        val context = mock<Context> { on { getAssets() } doReturn assets }
        val manager = SttManager(context)
        val store = mock<ModelStore> { on { hasStt(any()) } doReturn true }
        set(manager, "store", store)
        set(manager, "engine", engine)
        set(manager, "sawSpeech", true)
        set(manager, "buffer", ArrayList(List(4000) { 0.1f }))
        return manager
    }

    private fun set(target: Any, field: String, value: Any) {
        target.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(target, value)
    }

    @Test fun `prefetch never closes a model during transcription`() = runBlocking {
        val main = newSingleThreadContext("stt-test-main")
        Dispatchers.setMain(main)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val engine = mock<SttEngine> {
            on { lang } doReturn "hi"
            on { transcribe(any()) } doAnswer {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                "hello"
            }
            on { close() } doAnswer { closed.countDown(); Unit }
        }
        val manager = manager(engine)
        try {
            val transcription = async(Dispatchers.Default) { manager.stopAndTranscribe("hi") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            manager.prepare("en")
            assertFalse("Prefetch closed a session still executing native inference", closed.await(500, TimeUnit.MILLISECONDS))
            finish.countDown()
            assertEquals("hello", transcription.await()?.text)
        } finally {
            finish.countDown()
            manager.release()
            delay(100)
            Dispatchers.resetMain()
            main.close()
        }
    }

    @Test fun `release discards a captured utterance rather than sending stale audio`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = mock<SttEngine> {
            on { lang } doReturn "hi"
            on { transcribe(any()) } doReturn "stale speech"
        }
        val manager = manager(engine)
        try {
            manager.release()
            advanceUntilIdle()
            @Suppress("UNCHECKED_CAST")
            val buffer = manager.javaClass.getDeclaredField("buffer").apply { isAccessible = true }.get(manager) as List<Float>
            assertTrue("Trim left microphone samples available for a later release", buffer.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
