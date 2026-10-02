package com.echobharat.mesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox is what keeps the first line of a conversation from vanishing while the
 * Noise handshake is still in flight, so its rules are pinned here.
 */
class PrivateOutboxTest {

    private var now = 1_000L
    private fun outbox(ttl: Long = 60_000L, max: Int = 3) = PrivateOutbox(ttl, max) { now }

    @Test
    fun `held messages come back in the order they were sent`() {
        val box = outbox()
        box.enqueue("peerA", "m1", "one")
        box.enqueue("peerA", "m2", "two")
        box.enqueue("peerB", "m3", "three")

        assertEquals(listOf("m1", "m2"), box.drain("peerA").map { it.messageID })
        assertEquals(emptyList<String>(), box.drain("peerA").map { it.messageID })
        assertEquals(setOf("peerB"), box.waitingPeers())
    }

    @Test
    fun `re-queuing the same message id does not duplicate it`() {
        val box = outbox()
        box.enqueue("peerA", "m1", "one")
        box.enqueue("peerA", "m1", "one")
        assertEquals(1, box.size("peerA"))
    }

    @Test
    fun `past the per-peer cap the oldest message is evicted and reported`() {
        val box = outbox(max = 2)
        box.enqueue("peerA", "m1", "one")
        box.enqueue("peerA", "m2", "two")
        val evicted = box.enqueue("peerA", "m3", "three")

        assertEquals(listOf("m1"), evicted.map { it.messageID })
        assertEquals(listOf("m2", "m3"), box.drain("peerA").map { it.messageID })
    }

    @Test
    fun `failed send requeue must not reset the original expiry deadline`() {
        val box = outbox(ttl = 10_000L)
        box.enqueue("peerA", "old", "text")
        now += 9_000L
        val pending = box.drain("peerA").single()
        box.requeue(pending)
        now += 1_000L
        assertEquals(listOf("old"), box.expire().map { it.messageID })
        assertTrue(box.waitingPeers().isEmpty())
    }

    @Test
    fun `old retry is expired even when newer messages arrived during transmission`() {
        val box = outbox(ttl = 10_000L)
        box.enqueue("peerA", "old", "text")
        val pending = box.drain("peerA").single()
        now += 5_000L
        box.enqueue("peerA", "new", "text")
        box.requeue(pending)
        now += 5_000L
        assertEquals(listOf("old"), box.expire().map { it.messageID })
        assertEquals(listOf("new"), box.drain("peerA").map { it.messageID })
    }

    @Test
    fun `messages older than the ttl expire and empty queues are forgotten`() {
        val box = outbox(ttl = 10_000L)
        box.enqueue("peerA", "old", "x")
        now += 6_000L
        box.enqueue("peerA", "new", "y")
        now += 5_000L

        assertEquals(listOf("old"), box.expire().map { it.messageID })
        assertEquals(listOf("new"), box.drain("peerA").map { it.messageID })

        box.enqueue("peerB", "b1", "z")
        now += 10_000L
        box.expire()
        assertTrue(box.waitingPeers().isEmpty())
    }
}
