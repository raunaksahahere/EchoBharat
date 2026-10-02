package com.echobharat.mesh.transport

import com.echobharat.mesh.protocol.BitchatPacket
import com.echobharat.mesh.protocol.MessageType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A BLE write longer than the link's MTU - 3 is cut off with no error, so fragments have to be
 * sized to the weakest link rather than to the 512 bytes we asked for.
 */
class FragmentSizingTest {

    private fun packet(bytes: Int) = BitchatPacket(
        version = 1u,
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = ByteArray(8) { 1 },
        recipientID = ByteArray(8) { 2 },
        timestamp = 1_700_000_000_000UL,
        payload = Random(7).nextBytes(bytes),
        ttl = 7u
    )

    private fun biggestFragment(bytes: Int, limit: Int): Int {
        val fragments = FragmentManager().createFragments(packet(bytes), 256, limit)
        assertTrue("expected fragments", fragments.isNotEmpty())
        return fragments.maxOf { it.toBinaryData(padding = false)!!.size }
    }

    @Test
    fun `fragments fit a 185-byte MTU link`() {
        assertTrue(biggestFragment(1_500, 182) <= 182)
    }

    @Test
    fun `fragments fit a 247-byte MTU link`() {
        assertTrue(biggestFragment(1_500, 244) <= 244)
    }

    @Test
    fun `fragments still fit the default 512`() {
        assertTrue(biggestFragment(3_000, 512) <= 512)
    }

    @Test
    fun `a packet that fits the link is not fragmented`() {
        val whole = FragmentManager().createFragments(packet(100), 256, 182)
        assertTrue(whole.size == 1 && whole[0].type == MessageType.NOISE_ENCRYPTED.value)
    }

    @Test
    fun `a packet over a small link limit is fragmented even though 512 would carry it`() {
        val fragments = FragmentManager().createFragments(packet(300), 256, 182)
        assertTrue(fragments.size > 1)
    }
}
