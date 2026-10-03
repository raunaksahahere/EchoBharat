package com.echobharat.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Phone-to-phone provisioning must be exactly as strict as a download: content that does
 * not hash to a published file is refused, whatever it is called.
 */
class ModelImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val tokens = "shared tokens"
    private val acoustic = "tamil acoustic model"
    private val encoder = "translation encoder"

    private fun importer(): ModelImporter {
        val root = File(temp.root, "models")
        val languages = listOf(
            LanguageModelSpec("ta", "Tamil", "தமிழ்", false, listOf(
                ModelSpec(ModelRole.STT_ACOUSTIC, "model.int8.onnx", "u", sha256 = sha(acoustic)),
                ModelSpec(ModelRole.STT_TOKENS, "tokens.txt", "u", sha256 = sha(tokens))
            )),
            LanguageModelSpec("te", "Telugu", "తెలుగు", false, listOf(
                ModelSpec(ModelRole.STT_TOKENS, "tokens.txt", "u", sha256 = sha(tokens))
            ))
        )
        val families = listOf(
            TranslationFamilySpec("en-indic", "EN→IN", listOf(
                ModelSpec(ModelRole.MT_ENCODER, "mt-en-hi-encoder.int8.onnx", "u", sha256 = sha(encoder))
            ), emptyList(), emptyMap())
        )
        return ModelImporter(
            ModelImporter.index(languages, families, { File(root, it) }, File(root, "mt")),
            File(temp.root, "scratch")
        )
    }

    private val models get() = File(temp.root, "models")

    @Test
    fun `a file is recognised by content, not by the name it arrived with`() {
        val report = importer().import("IMG_2041.jpg", acoustic.byteInputStream())
        assertEquals(listOf("model.int8.onnx"), report.installed)
        assertEquals(acoustic, File(models, "ta/model.int8.onnx").readText())
    }

    @Test
    fun `a file shared by several packs lands in each of them`() {
        importer().import("tokens (1).txt", tokens.byteInputStream())
        assertTrue(File(models, "ta/tokens.txt").isFile)
        assertTrue(File(models, "te/tokens.txt").isFile)
    }

    @Test
    fun `translation files go to the shared directory`() {
        importer().import("enc", encoder.byteInputStream())
        assertTrue(File(models, "mt/mt-en-hi-encoder.int8.onnx").isFile)
    }

    @Test
    fun `unknown or tampered content is refused and nothing is written`() {
        val report = importer().import("model.int8.onnx", "tampered".byteInputStream())
        assertEquals(listOf("model.int8.onnx"), report.rejected)
        assertFalse(File(models, "ta/model.int8.onnx").exists())
        assertTrue(File(temp.root, "scratch").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `reimport repairs corrupt installed content even when its size matches`() {
        val imp = importer()
        imp.import("a", acoustic.byteInputStream())
        val target = File(models, "ta/model.int8.onnx")
        target.writeText("x".repeat(acoustic.length))

        val report = imp.import("a", acoustic.byteInputStream())

        assertEquals(listOf("model.int8.onnx"), report.installed)
        assertTrue(report.alreadyPresent.isEmpty())
        assertEquals(acoustic, target.readText())
    }

    @Test
    fun `old and new voice hashes remain allowed without replacing the active old file`() {
        val old = ModelSpec(ModelRole.TTS_ACOUSTIC, "voice.v1.onnx", "u", sha256 = sha("old voice"))
        val new = ModelSpec(ModelRole.TTS_ACOUSTIC, "voice.v2.onnx", "u", sha256 = sha("new voice"))
        val pack = LanguageModelSpec("hi", "Hindi", "hi", false, listOf(new), 2, listOf(old))
        val candidates = File(temp.root, "candidates")
        val importer = ModelImporter(
            ModelImporter.index(listOf(pack), emptyList(), { File(models, it) }, File(models, "mt")) { candidates },
            File(temp.root, "scratch")
        )
        assertEquals(listOf("voice.v1.onnx"), importer.import("shared", "old voice".byteInputStream()).installed)
        assertEquals(listOf("voice.v2.onnx"), importer.import("shared", "new voice".byteInputStream()).installed)
        assertEquals("old voice", File(models, "hi/voice.v1.onnx").readText())
        assertEquals("new voice", File(candidates, "voice.v2.onnx").readText())
        assertFalse(File(models, "hi/voice.v2.onnx").exists())
        assertEquals(listOf("shared"), importer.import("shared", "unknown voice".byteInputStream()).rejected)
    }

    @Test
    fun `importing twice reports the file as already present`() {
        val imp = importer()
        imp.import("a", acoustic.byteInputStream())
        val again = imp.import("a", acoustic.byteInputStream())
        assertEquals(listOf("model.int8.onnx"), again.alreadyPresent)
        assertTrue(again.installed.isEmpty())
    }
}
