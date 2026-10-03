package com.echobharat.mesh

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class SosEnvelopeTest {
    private data class Keys(val privateKey: ByteArray, val publicKey: ByteArray, val noise: ByteArray)

    private fun keys(seed: Int): Keys {
        val privateKey = ByteArray(32) { (it + seed).toByte() }
        val pair = Ed25519PrivateKeyParameters(privateKey, 0)
        return Keys(privateKey, pair.generatePublicKey().encoded, ByteArray(32) { (it + seed + 50).toByte() })
    }

    private fun raise(keys: Keys, now: Long, event: ByteArray = ByteArray(16) { it.toByte() }) =
        SosEnvelope.create(
            action = SosEnvelope.Action.RAISE,
            eventId = event,
            revision = 0,
            createdAt = now,
            expiresAt = now + SosEnvelope.LIFETIME_MS,
            originSigningKey = keys.publicKey,
            originNoiseKey = keys.noise,
            srcLang = "en",
            text = "Help at the shelter",
            senderName = "Origin",
            deviceModel = "test",
            latitude = 12.3,
            longitude = 45.6,
            gpsAccuracyM = 10f,
            privateKey = keys.privateKey
        )!!

    private fun signed(
        action: SosEnvelope.Action,
        keys: Keys,
        base: SosEnvelope,
        revision: Long
    ) = SosEnvelope.create(
        action, base.eventId, revision, base.createdAt, base.expiresAt,
        keys.publicKey, keys.noise, base.srcLang, base.text, base.senderName, base.deviceModel,
        base.latitude, base.longitude, base.gpsAccuracyM, keys.privateKey
    )!!

    private fun verifiedLedger(keys: Keys) = SosEventLedger().also {
        it.accept(raise(keys, NOW), NOW) { keys.publicKey to keys.noise }
    }

    @Test
    fun `valid origin envelope round trips and verifies`() {
        val envelope = raise(keys(1), NOW)
        val wire = SosEnvelope.encodeWire(envelope)!!
        val decoded = SosEnvelope.decodeWire(wire)!!
        assertTrue(SosEnvelope.verify(decoded, decoded.originSigningKey, decoded.originNoiseKey, NOW))
        assertEquals("Help at the shelter", decoded.text)
        assertEquals(16, decoded.eventId.size)
    }

    @Test
    fun `altered signed fields and trailing bytes fail`() {
        val keys = keys(2)
        val envelope = raise(keys, NOW)
        val cases = listOf(
            envelope.copy(text = "Forged"),
            envelope.copy(latitude = 99.0),
            envelope.copy(expiresAt = envelope.expiresAt - 1),
            envelope.copy(originSigningKey = keys(3).publicKey),
            envelope.copy(signature = envelope.signature + byteArrayOf(1))
        )
        cases.forEach { altered ->
            assertFalse(SosEnvelope.verify(altered, keys.publicKey, keys.noise, NOW))
        }
        val trailing = SosEnvelope.encode(envelope)!! + byteArrayOf(0)
        assertNull(SosEnvelope.decode(trailing))
    }

    @Test
    fun `wrong origin key and relay key cannot verify`() {
        val origin = keys(4)
        val relay = keys(5)
        val envelope = raise(origin, NOW)
        assertFalse(SosEnvelope.verify(envelope, relay.publicKey, origin.noise, NOW))
        assertFalse(SosEnvelope.verify(envelope, origin.publicKey, relay.noise, NOW))
    }

    @Test
    fun `ledger rejects replay, forged cancel, and post-cancel replay across restore`() {
        val origin = keys(6)
        val attacker = keys(7)
        val event = raise(origin, NOW)
        val ledger = SosEventLedger()
        val resolver: (SosEnvelope) -> Pair<ByteArray, ByteArray>? = { origin.publicKey to origin.noise }
        assertEquals(SosEventLedger.Result.ACCEPTED, ledger.accept(event, NOW, resolver))
        assertEquals(SosEventLedger.Result.DUPLICATE, ledger.accept(event, NOW, resolver))
        val forgedCancel = signed(SosEnvelope.Action.CANCEL, attacker, event, 1)
        assertEquals(SosEventLedger.Result.REJECTED, ledger.accept(forgedCancel, NOW, resolver))
        val cancel = signed(SosEnvelope.Action.CANCEL, origin, event, 1)
        assertTrue(SosEnvelope.verify(cancel, origin.publicKey, origin.noise, NOW))
        assertEquals(SosEventLedger.Result.ACCEPTED, ledger.accept(cancel, NOW, resolver))
        assertEquals(SosEventLedger.Result.REJECTED, ledger.accept(event, NOW, resolver))

        val restored = SosEventLedger().also { it.restore(ledger.snapshot(NOW), NOW) }
        assertFalse(restored.containsActive(event))
        assertEquals(SosEventLedger.Result.DUPLICATE, restored.accept(cancel, NOW, resolver))
    }

    @Test
    fun `cancel before raise leaves a tombstone and malformed oversized input is rejected`() {
        val origin = keys(8)
        val event = raise(origin, NOW)
        val cancel = signed(SosEnvelope.Action.CANCEL, origin, event, 1)
        val ledger = SosEventLedger()
        val resolver: (SosEnvelope) -> Pair<ByteArray, ByteArray>? = { origin.publicKey to origin.noise }
        assertEquals(SosEventLedger.Result.ACCEPTED, ledger.accept(cancel, NOW, resolver))
        assertEquals(SosEventLedger.Result.REJECTED, ledger.accept(event, NOW, resolver))
        val oversized = event.copy(text = "x".repeat(SosEnvelope.MAX_TEXT_BYTES + 1))
        assertNull(SosEnvelope.encodeWire(oversized))
    }

    @Test
    fun `clock skew and expiry are bounded from signed creation`() {
        val origin = keys(9)
        val future = SosEnvelope.create(
            SosEnvelope.Action.RAISE, ByteArray(16), 0,
            NOW + SosEnvelope.MAX_CLOCK_SKEW_MS + 1,
            NOW + SosEnvelope.MAX_CLOCK_SKEW_MS + 1 + SosEnvelope.LIFETIME_MS,
            origin.publicKey, origin.noise, "en", "Help", "Origin", "test", privateKey = origin.privateKey
        )!!
        assertFalse(SosEnvelope.verify(future, origin.publicKey, origin.noise, NOW))
        val tooLong = raise(origin, NOW).copy(expiresAt = NOW + SosEnvelope.LIFETIME_MS + 1)
        assertFalse(SosEnvelope.verify(tooLong, origin.publicKey, origin.noise, NOW))
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
    }
}
