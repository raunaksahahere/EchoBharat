package com.echobharat.mesh

import com.echobharat.mesh.model.PrivateMessagePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A private message's text used to be capped at 255 bytes, which silently dropped long ones. */
class PrivateMessagePacketTest {

    private fun roundTrip(content: String): PrivateMessagePacket? =
        PrivateMessagePacket("id-1", content).encode()?.let { PrivateMessagePacket.decode(it) }

    @Test
    fun `short content round-trips`() {
        assertEquals("hello", roundTrip("hello")?.content)
    }

    @Test
    fun `content past 255 bytes round-trips`() {
        val hindi = "नमस्ते दुनिया ".repeat(60) // ~1.5 KB of UTF-8
        assertEquals(hindi, roundTrip(hindi)?.content)
    }

    @Test
    fun `content of exactly 255 and 256 bytes both work`() {
        assertEquals("a".repeat(255), roundTrip("a".repeat(255))?.content)
        assertEquals("a".repeat(256), roundTrip("a".repeat(256))?.content)
    }

    @Test
    fun `short content keeps the original wire format`() {
        val bytes = PrivateMessagePacket("m", "hi").encode()!!
        // [0][1]'m' [1][2]'h''i' — readable by any bitchat client.
        assertEquals(listOf<Byte>(0, 1, 'm'.code.toByte(), 1, 2, 'h'.code.toByte(), 'i'.code.toByte()), bytes.toList())
    }

    @Test
    fun `absurdly large content is refused rather than truncated`() {
        assertNull(PrivateMessagePacket("m", "a".repeat(70_000)).encode())
    }

    @Test
    fun `a truncated long field is rejected`() {
        val bytes = PrivateMessagePacket("m", "a".repeat(400)).encode()!!
        assertNotNull(PrivateMessagePacket.decode(bytes))
        assertNull(PrivateMessagePacket.decode(bytes.copyOf(bytes.size - 10)))
    }
}
