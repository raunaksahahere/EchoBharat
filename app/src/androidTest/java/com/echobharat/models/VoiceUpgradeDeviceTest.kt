package com.echobharat.models

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.echobharat.tts.FastPitchTts
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in tests for real, manifest-verified models. No downloads and no production pack
 * deletion. These exercise native compatibility/migration, NOT mesh reception or listening.
 */
class VoiceUpgradeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled(key: String) = InstrumentationRegistry.getArguments().getString(key) == "true"

    @Test fun inspectBothPublishedStagesIndependently() {
        assumeTrue(enabled("ttsLegacyProbe"))
        val pack = ModelCatalog.byLang(context, "hi")!!
        val specs = if (pack.legacyModels.isEmpty()) pack.ttsSpecs else pack.legacyModels
        val root = File(context.getExternalFilesDir(null), "models/hi")
        for (role in listOf(ModelRole.TTS_ACOUSTIC, ModelRole.TTS_VOCODER)) {
            val spec = specs.first { it.role == role }
            val file = File(root, spec.fileName)
            assertTrue("Missing or wrong published bytes: ${spec.fileName}", VoicePackFiles.matches(file, spec))
            try {
                OrtSession.SessionOptions().use { options ->
                    OrtEnvironment.getEnvironment().createSession(file.absolutePath, options).use {
                        Log.i("TtsRuntimeTest", "LEGACY_LOAD_OK role=$role file=${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("TtsRuntimeTest", "LEGACY_LOAD_FAILED role=$role file=${file.name}: ${e.message}")
                // Record either result, not an assumption that both stages have the same kernel gap.
            }
        }
    }

    @Test fun verifiedOldInstallMigratesOnlyAfterNativeLoadAndSynthesizes() {
        assumeTrue(enabled("ttsUpgrade"))
        for ((lang, text) in listOf("hi" to "मुझे पानी और दवा चाहिए.", "en" to "I need water and medicine.")) {
            val spec = ModelCatalog.byLang(context, lang)!!
            assertTrue("Publish and bundle v2 metadata before running this test", spec.packVersion >= 2)
            val external = File(context.getExternalFilesDir(null), "models/$lang")
            val scratch = File(context.cacheDir, "voice-upgrade-test-${System.nanoTime()}")
            try {
                val files = VoicePackFiles(scratch)
                val oldDir = File(scratch, lang).apply { mkdirs() }
                val old = VoicePackFiles.roles.map { role ->
                    spec.legacyModels.firstOrNull { it.role == role } ?: spec.ttsSpecs.first { it.role == role }
                }
                for (model in old) {
                    val source = File(external, model.fileName)
                    assertTrue("Sideload exact legacy ${model.fileName}", VoicePackFiles.matches(source, model))
                    source.copyTo(File(oldDir, model.fileName))
                }
                assertTrue(files.needsUpdate(spec))
                val previous = files.resolve(spec)!!
                val candidate = files.candidateDir(spec).apply { mkdirs() }
                for (model in spec.ttsSpecs) {
                    val source = File(external, model.fileName)
                    assertTrue("Sideload exact replacement ${model.fileName}", VoicePackFiles.matches(source, model))
                    source.copyTo(File(candidate, model.fileName))
                }
                assertEquals(previous, files.resolve(spec))
                assertTrue(files.activate(spec, com.echobharat.BuildConfig.VERSION_CODE) { selection ->
                    FastPitchTts.load(lang, selection.files.getValue(ModelRole.TTS_ACOUSTIC),
                        selection.files.getValue(ModelRole.TTS_VOCODER), selection.files.getValue(ModelRole.TTS_TOKENS)
                    )?.use { engine ->
                        val start = SystemClock.elapsedRealtime()
                        val pcm = engine.synthesize(text)
                        val elapsed = SystemClock.elapsedRealtime() - start
                        assertNotNull(pcm)
                        assertTrue(pcm!!.isNotEmpty() && pcm.all { it.isFinite() })
                        val seconds = pcm.size.toDouble() / engine.sampleRate
                        Log.i("TtsRuntimeTest", "lang=$lang synthesisMs=$elapsed audioSeconds=$seconds RTF=${elapsed / 1000.0 / seconds} sampledPssKb=${Debug.getPss()}")
                        true
                    } ?: false
                })
                assertTrue(files.isCurrent(spec))
                assertTrue(previous.files.values.all { it.exists() })
                assertTrue(VoicePackFiles(scratch).isCurrent(spec))
            } finally { scratch.deleteRecursively() }
        }
    }
}
