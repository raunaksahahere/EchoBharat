package com.echobharat.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePackUpgradeTest {
    private val files = mapOf("fastpitch-hi.v2.onnx" to "new-sha")

    @Test
    fun `higher pack version is stale`() {
        assertTrue(VoicePackUpgrade.needsUpdate(2, files, VoicePackUpgrade.Installed(1, files), false))
    }

    @Test
    fun `same version and hashes are current`() {
        assertFalse(VoicePackUpgrade.needsUpdate(2, files, VoicePackUpgrade.Installed(2, files), false))
    }

    @Test
    fun `renamed or changed file is stale while legacy remains readable`() {
        assertTrue(VoicePackUpgrade.needsUpdate(
            2,
            files,
            VoicePackUpgrade.Installed(1, mapOf("fastpitch-hi.int8.onnx" to "old-sha")),
            hasReadableLegacyFiles = true
        ))
    }

    @Test
    fun `load failure forces a retry without deleting the old pack`() {
        assertTrue(VoicePackUpgrade.needsUpdate(2, files, VoicePackUpgrade.Installed(2, files, true), true))
    }

    @Test
    fun `a brand new pack is not reported as an update`() {
        assertFalse(VoicePackUpgrade.needsUpdate(2, files, null, false))
    }

    @Test
    fun `metered data requires explicit consent`() {
        assertFalse(VoicePackUpgrade.mayUseNetwork(true, metered = true, allowMetered = false))
        assertTrue(VoicePackUpgrade.mayUseNetwork(true, metered = true, allowMetered = true))
        assertTrue(VoicePackUpgrade.mayUseNetwork(true, metered = false, allowMetered = false))
        assertFalse(VoicePackUpgrade.mayUseNetwork(false, metered = false, allowMetered = true))
    }
}
