package com.echobharat.mesh.transport

/**
 * Private messages waiting for a Noise session with their recipient.
 *
 * A private message can only be encrypted once a session exists, and the first message to
 * a peer is exactly the one that never has one. bitchat's answer was to start the
 * handshake and drop the message ("fire and forget"), which under peer-first navigation
 * meant the opening line of every conversation vanished while the sender's screen showed
 * it as sent. This holds those messages instead, until the session comes up or they are
 * too old to still be worth delivering.
 *
 * Bounded twice over, so a peer that never answers cannot pin memory:
 *  - **age:** anything older than [ttlMs] is handed back by [expire] as undeliverable;
 *  - **count:** past [maxPerPeer] the oldest message for that peer is evicted.
 *
 * Pure bookkeeping — no Android, no I/O, no clock of its own — so the queueing rules are
 * unit-testable without a radio.
 */
internal class PrivateOutbox(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxPerPeer: Int = DEFAULT_MAX_PER_PEER,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** Long enough to ride out a peer walking briefly out of range. */
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** A conversation's worth of backlog; beyond this the oldest line goes. */
        const val DEFAULT_MAX_PER_PEER = 32
    }

    data class Pending(
        val peerID: String,
        val messageID: String,
        val content: String,
        val queuedAt: Long
    )

    private val queues = LinkedHashMap<String, ArrayDeque<Pending>>()

    /**
     * Queues [content] for [peerID].
     *
     * Re-queuing a message id already waiting is a no-op, so a retry cannot duplicate it.
     *
     * @return messages evicted to make room, which the caller must report as undeliverable.
     */
    fun enqueue(peerID: String, messageID: String, content: String): List<Pending> =
        requeue(Pending(peerID, messageID, content, clock()))

    /** A failed transmission retains its original deadline, even across repeated retries. */
    @Synchronized
    fun requeue(pending: Pending): List<Pending> {
        val queue = queues.getOrPut(pending.peerID) { ArrayDeque() }
        if (queue.any { it.messageID == pending.messageID }) return emptyList()

        val insertionIndex = queue.indexOfFirst { it.queuedAt > pending.queuedAt }
        if (insertionIndex < 0) queue.addLast(pending) else queue.add(insertionIndex, pending)
        val evicted = ArrayList<Pending>()
        while (queue.size > maxPerPeer) evicted.add(queue.removeFirst())
        return evicted
    }

    /** Removes and returns everything waiting for [peerID], oldest first. */
    @Synchronized
    fun drain(peerID: String): List<Pending> = queues.remove(peerID)?.toList().orEmpty()

    /** Removes and returns every message that has waited longer than the TTL. */
    @Synchronized
    fun expire(): List<Pending> {
        val cutoff = clock() - ttlMs
        val expired = ArrayList<Pending>()
        val emptied = ArrayList<String>()
        for ((peer, queue) in queues) {
            while (queue.isNotEmpty() && queue.first().queuedAt <= cutoff) {
                expired.add(queue.removeFirst())
            }
            if (queue.isEmpty()) emptied.add(peer)
        }
        emptied.forEach { queues.remove(it) }
        return expired
    }

    /** Peers with at least one message waiting on a session. */
    @Synchronized
    fun waitingPeers(): Set<String> = queues.keys.toSet()

    @Synchronized
    fun size(peerID: String): Int = queues[peerID]?.size ?: 0

    @Synchronized
    fun clear() = queues.clear()
}
