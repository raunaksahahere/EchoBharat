package com.echobharat.models

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class VoicePackFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = File(temp.root, "internal")
    private val external get() = File(temp.root, "external")
    private val store get() = VoicePackFiles(root, external)
    private fun bytes(version: Int, role: ModelRole) = "weights-$version-$role"
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun models(version: Int) = VoicePackFiles.roles.map { role ->
        val content = bytes(version, role)
        ModelSpec(role, "${role.name}.v$version.onnx", "https://example.invalid/model", sha256 = sha(content), sizeBytes = content.length.toLong())
    }
    private fun spec(version: Int = 2) = LanguageModelSpec(
        "hi", "Hindi", "हिन्दी", false, models(version), packVersion = version, legacyModels = models(1)
    )
    private fun populate(dir: File, version: Int) {
        dir.mkdirs()
        models(version).forEach { File(dir, it.fileName).writeText(bytes(version, it.role)) }
    }
    private fun old() { populate(File(root, "hi"), 1) }
    private fun candidates(version: Int = 2) { populate(store.candidateDir(spec(version)), version) }

    @Test fun `fresh phone does not download every voice automatically`() {
        assertFalse(store.needsUpdate(spec()))
        assertFalse(store.hasExistingVoice(spec()))
    }

    @Test fun `old internal and sideloaded voices are detected without metadata`() {
        old()
        assertTrue(store.needsUpdate(spec()))
        assertEquals(3, store.resolve(spec())!!.files.size)
        File(root, "hi").deleteRecursively()
        populate(File(external, "hi"), 1)
        assertTrue(store.needsUpdate(spec()))
        assertNotNull(store.resolve(spec()))
    }

    @Test fun `candidate download never changes active voice until verification and load succeed`() {
        old()
        val before = store.resolve(spec())!!
        candidates()
        assertEquals(before, store.resolve(spec()))
        assertTrue(store.activate(spec(), 5) { selection ->
            assertEquals(before, store.resolve(spec()))
            assertTrue(before.files.values.all { it.exists() })
            selection.files.values.all { it.readText().startsWith("weights-2") }
        })
        assertTrue(store.isCurrent(spec()))
        assertNotEquals(before.fingerprint, store.resolve(spec())!!.fingerprint)
        assertTrue(before.files.values.all { it.exists() })
    }

    @Test fun `native load failure leaves old generation byte for byte unchanged`() {
        old(); candidates()
        val before = store.resolve(spec())!!
        assertFalse(store.activate(spec(), 5) { false })
        assertEquals(before, store.resolve(spec()))
        before.files.forEach { (role, file) -> assertEquals(bytes(1, role), file.readText()) }
    }

    @Test fun `invalid hash never reaches loader and old files are not deleted`() {
        old(); candidates()
        val model = spec().ttsSpecs.first()
        File(store.candidateDir(spec()), model.fileName).writeText("x".repeat(model.sizeBytes.toInt()))
        var invoked = false
        assertFalse(store.activate(spec(), 5) { invoked = true; true })
        assertFalse(invoked)
        assertEquals(bytes(1, model.role), store.resolve(spec())!!.files.getValue(model.role).readText())
    }

    @Test fun `partial candidate and interrupted transfer leave old voice resolvable`() {
        old()
        val before = store.resolve(spec())!!
        val dir = store.candidateDir(spec()).apply { mkdirs() }
        File(dir, "partial.onnx.part").writeText("partial")
        assertFalse(store.candidatesReady(spec()))
        assertFalse(store.activate(spec(), 5) { fail("must not load partial"); true })
        assertEquals(before, store.resolve(spec()))
    }

    @Test fun `exception during validation preserves published metadata`() {
        old(); candidates()
        assertTrue(store.activate(spec(), 5) { true })
        val before = store.resolve(spec())!!
        val next = spec(3).copy(legacyModels = models(2))
        populate(store.candidateDir(next), 3)
        assertThrows(IllegalStateException::class.java) { store.activate(next, 6) { error("failed load") } }
        assertEquals(before, store.resolve(next))
    }

    @Test fun `version and per-file hashes survive storage recreation and manifest upgrade`() {
        old(); candidates()
        assertTrue(store.activate(spec(), 5) { true })
        val reloaded = VoicePackFiles(root, external)
        assertTrue(reloaded.isCurrent(spec()))
        val next = spec(3).copy(legacyModels = models(2))
        assertTrue(reloaded.needsUpdate(next))
        assertNotNull(reloaded.resolve(next))
        assertFalse(reloaded.isCurrent(spec().copy(packVersion = 3)))
        assertFalse(reloaded.isCurrent(spec().copy(models = models(3))))
    }

    @Test fun `same-size corruption is detected even with installed metadata`() {
        old(); candidates()
        assertTrue(store.activate(spec(), 5) { true })
        val model = spec().ttsSpecs.first()
        File(store.candidateDir(spec()), model.fileName).writeText("x".repeat(model.sizeBytes.toInt()))
        assertFalse(store.isCurrent(spec()))
        // A still-trusted old generation is retained as fallback.
        assertTrue(store.resolve(spec())!!.files.values.all { it.readText().startsWith("weights-1") })
    }

    @Test fun `unverified old named files are never used`() {
        old()
        File(root, "hi/${models(1).first().fileName}").writeText("tampered")
        assertNull(store.resolve(spec()))
    }

    @Test fun `seeding keeps existing install and crash leaves candidate inactive`() {
        populate(File(external, "hi"), 2)
        val before = store.resolve(spec())!!
        store.seedCandidates(spec())
        assertTrue(store.candidatesReady(spec()))
        assertEquals(before, store.resolve(spec()))
        assertTrue(before.files.values.all { it.exists() })
    }

    @Test fun `failure status persists and successful activation clears it`() {
        old()
        store.recordLoadFailure(spec())
        assertTrue(store.needsVoiceRepair(spec()))
        candidates()
        assertTrue(store.activate(spec(), 5) { true })
        assertFalse(store.needsVoiceRepair(spec()))
    }

    @Test fun `no model generation is assembled across internal and external roots`() {
        old()
        val model = models(1).first()
        val file = File(root, "hi/${model.fileName}")
        val target = File(external, "hi/${model.fileName}").apply { parentFile!!.mkdirs() }
        file.copyTo(target); file.delete()
        assertNull(store.resolve(spec()))
    }
}
