package com.echobharat.mesh.transport

import com.echobharat.mesh.model.FragmentPayload
import com.echobharat.mesh.protocol.BitchatPacket
import com.echobharat.mesh.protocol.MessageType
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FragmentReassemblyTest {
    private fun packet(sender: Byte = 1) = BitchatPacket(
        version = 1u, type = MessageType.NOISE_ENCRYPTED.value,
        senderID = ByteArray(8) { sender }, recipientID = ByteArray(8) { 2 },
        timestamp = 1_700_000_000_000UL, payload = Random(7).nextBytes(900), ttl = 7u
    )

    @Test
    fun `same fragment id from another sender cannot overwrite an in flight transfer`() {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(packet(), 256, 182)
            assertNull(manager.handleFragment(fragments.first()))
            val original = FragmentPayload.decode(fragments.first().payload)!!
            val foreign = fragments.first().copy(
                senderID = ByteArray(8) { 3 },
                payload = original.copy(data = ByteArray(original.data.size) { 99 }).encode()
            )
            assertNull(manager.handleFragment(foreign))
            var result: BitchatPacket? = null
            fragments.drop(1).forEach { result = manager.handleFragment(it) ?: result }
            assertNotNull("other senders must have isolated fragment state", result)
            assertArrayEquals(packet().payload, result!!.payload)
        } finally { manager.shutdown() }
    }

    @Test
    fun `reassembled sender must match the outer fragment sender`() {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(packet(), 256, 182)
            fragments.forEach {
                assertNull(manager.handleFragment(it.copy(senderID = ByteArray(8) { 3 })))
            }
        } finally { manager.shutdown() }
    }

    @Test
    fun `reassembled recipient must match the outer fragment recipient`() {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(packet(), 256, 182)
            fragments.forEach {
                assertNull(manager.handleFragment(it.copy(recipientID = ByteArray(8) { 4 })))
            }
        } finally { manager.shutdown() }
    }

    @Test
    fun `wire limits below minimum overhead fail closed instead of producing oversized frames`() {
        val manager = FragmentManager()
        try {
            assertTrue(manager.createFragments(packet(), 256, 20).isEmpty())
        } finally { manager.shutdown() }
    }
}
