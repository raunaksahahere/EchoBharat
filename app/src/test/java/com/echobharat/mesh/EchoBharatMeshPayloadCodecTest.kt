package com.echobharat.mesh

import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.Languages
import com.echobharat.schema.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EchoBharatMeshPayloadCodecTest {

    private val sample = EchoBharatMessage(
        msgId = "abc",
        type = MessageType.VOICE_TEXT,
        srcLang = "hi",
        text = "मुझे पानी चाहिए",
        senderName = "Asha",
        senderId = "0123456789abcdef",
        deviceModel = "Pixel 7",
        lat = 12.5,
        lon = 77.25
    )

    @Test
    fun `framed payloads round trip unchanged`() {
        val decoded = EchoBharatMeshPayloadCodec.decode(EchoBharatMeshPayloadCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun `plain bitchat text is not stamped english`() {
        val hindi = EchoBharatMeshPayloadCodec.decode("मदद चाहिए".toByteArray(), "peer1", "Ravi")!!
        assertEquals(Languages.UNDETERMINED, hindi.srcLang)
        assertEquals("peer1", hindi.senderId)
        assertEquals(MessageType.TYPED_TEXT, hindi.type)

        val tamil = EchoBharatMeshPayloadCodec.decode("உதவி தேவை".toByteArray(), "peer2", "Kavin")!!
        assertEquals("ta", tamil.srcLang)
    }

    @Test
    fun `payloads missing required fields are rejected, not half-built`() {
        assertNull(EchoBharatMeshPayloadCodec.decode("EB1:{\"msgId\":\"x\",\"senderId\":\"p\"}".toByteArray()))
        assertNull(EchoBharatMeshPayloadCodec.decode("EB1:not json".toByteArray()))
    }

    @Test
    fun `a message type this build does not know is rejected`() {
        val future = String(EchoBharatMeshPayloadCodec.encode(sample), Charsets.UTF_8)
            .replace("\"VOICE_TEXT\"", "\"HOLOGRAM\"")
        assertNull(EchoBharatMeshPayloadCodec.decode(future.toByteArray()))
    }

    @Test
    fun `SOS cannot omit expiry or extend beyond its bounded lifetime`() {
        val now = System.currentTimeMillis()
        val sos = sample.copy(type = MessageType.SOS, ts = now)
        assertNull(EchoBharatMeshPayloadCodec.decode(EchoBharatMeshPayloadCodec.encode(sos)))
        assertNull(EchoBharatMeshPayloadCodec.decode(EchoBharatMeshPayloadCodec.encode(
            sos.copy(expiresAt = now + SosManager.LIFETIME_MS + 1)
        )))
        assertNotNull(EchoBharatMeshPayloadCodec.decode(EchoBharatMeshPayloadCodec.encode(
            sos.copy(expiresAt = now + SosManager.LIFETIME_MS)
        )))
    }

    @Test
    fun `far future SOS timestamps cannot pin distress state indefinitely`() {
        val future = sample.copy(type = MessageType.SOS, ts = Long.MAX_VALUE - 60_000, expiresAt = Long.MAX_VALUE)
        assertNull(EchoBharatMeshPayloadCodec.decode(EchoBharatMeshPayloadCodec.encode(future)))
    }

    @Test
    fun `optional fields may be absent`() {
        val minimal = "EB1:{\"msgId\":\"m\",\"type\":\"TYPED_TEXT\",\"srcLang\":\"en\",\"text\":\"hi\"," +
            "\"senderName\":\"A\",\"senderId\":\"p\",\"deviceModel\":\"X\"}"
        val decoded = EchoBharatMeshPayloadCodec.decode(minimal.toByteArray())
        assertNotNull(decoded)
        assertNull(decoded!!.lat)
    }
}
