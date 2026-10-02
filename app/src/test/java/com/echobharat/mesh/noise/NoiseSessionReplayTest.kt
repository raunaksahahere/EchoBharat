package com.echobharat.mesh.noise

import com.echobharat.mesh.noise.southernstorm.protocol.Noise
import org.junit.Assert.*
import org.junit.Test

class NoiseSessionReplayTest {
    private fun sessions(): Pair<NoiseSession, NoiseSession> {
        fun keyPair(): Pair<ByteArray, ByteArray> {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            dh.getPublicKey(publicKey, 0)
            dh.destroy()
            return privateKey to publicKey
        }
        val a = keyPair()
        val b = keyPair()
        val sender = NoiseSession(NoisePeerIdentity.derivePeerID(b.second)!!, true, a.first, a.second)
        val receiver = NoiseSession(NoisePeerIdentity.derivePeerID(a.second)!!, false, b.first, b.second)
        val response = receiver.processHandshakeMessage(sender.startHandshake())!!
        receiver.processHandshakeMessage(sender.processHandshakeMessage(response)!!)
        assertTrue(sender.isEstablished())
        assertTrue(receiver.isEstablished())
        return sender to receiver
    }

    @Test
    fun `advancing nonce must not permit replay of earlier ciphertext`() {
        val (sender, receiver) = sessions()
        val first = sender.encrypt(byteArrayOf(10))
        val second = sender.encrypt(byteArrayOf(20))
        assertArrayEquals(byteArrayOf(10), receiver.decrypt(first))
        assertArrayEquals(byteArrayOf(20), receiver.decrypt(second))
        assertThrows(Exception::class.java) { receiver.decrypt(first) }
    }

    @Test
    fun `out of order nonces remain marked across byte boundaries`() {
        val (sender, receiver) = sessions()
        val packets = (0..20).map { sender.encrypt(byteArrayOf(it.toByte())) }
        for (index in listOf(0, 7, 2, 8, 16, 4, 20, 19)) {
            assertArrayEquals(byteArrayOf(index.toByte()), receiver.decrypt(packets[index]))
        }
        for (index in listOf(0, 7, 2, 8, 16, 4, 20, 19)) {
            assertThrows("replayed nonce $index", Exception::class.java) { receiver.decrypt(packets[index]) }
        }
    }

    @Test
    fun `unauthenticated high nonce cannot evict valid packets`() {
        val (sender, receiver) = sessions()
        val first = sender.encrypt(byteArrayOf(10))
        val forged = first.clone().also { it[0] = 0x7f }
        assertThrows(Exception::class.java) { receiver.decrypt(forged) }
        assertArrayEquals(byteArrayOf(10), receiver.decrypt(first))
    }

    @Test
    fun `large unsigned nonce jump does not overflow window shift`() {
        val (sender, receiver) = sessions()
        val first = sender.encrypt(byteArrayOf(10))
        receiver.decrypt(first)
        NoiseSession::class.java.getDeclaredField("messagesSent").apply { isAccessible = true }
            .setLong(sender, 0x80000001L)
        val jumped = sender.encrypt(byteArrayOf(20))
        assertArrayEquals(byteArrayOf(20), receiver.decrypt(jumped))
        assertThrows(Exception::class.java) { receiver.decrypt(jumped) }
        assertThrows(Exception::class.java) { receiver.decrypt(first) }
    }
}
