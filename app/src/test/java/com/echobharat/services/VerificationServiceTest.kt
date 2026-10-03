package com.echobharat.services

import org.junit.Assert.*
import org.junit.Test

class VerificationServiceTest {
    private val qr = VerificationService.VerificationQR(
        v = 1,
        noiseKeyHex = "11".repeat(32),
        signKeyHex = "22".repeat(32),
        npub = null,
        nickname = "Ravi",
        ts = 1_700_000_000,
        nonceB64 = "AQIDBA",
        sigHex = "33".repeat(64)
    )

    @Test
    fun `signed verification payload has stable canonical bytes`() {
        assertTrue(qr.canonicalBytes().isNotEmpty())
        assertEquals(qr.canonicalBytes().toList(), qr.canonicalBytes().toList())
    }

    @Test
    fun `changing an identity field changes the signed canonical bytes`() {
        val changed = qr.copy(signKeyHex = "44".repeat(32))
        assertFalse(qr.canonicalBytes().contentEquals(changed.canonicalBytes()))
    }

    @Test
    fun `malformed verification URI is rejected`() {
        assertNull(VerificationService.VerificationQR.fromUrlString("bitchat://verify?noise=bad"))
        assertNull(VerificationService.VerificationQR.fromUrlString("https://verify.example/?v=1"))
    }
}
