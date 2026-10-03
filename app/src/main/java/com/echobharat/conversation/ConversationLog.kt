package com.echobharat.conversation

import com.echobharat.mesh.Delivery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every conversation this phone holds, and the rules for changing them.
 *
 * Pure state — no Android, no disk, no mesh — so the rules are unit-testable:
 *  - **A message is recorded once.** Distress announcements are re-broadcast unchanged
 *    every few minutes and can reach a phone by several routes; recording each copy
 *    duplicated the transcript, re-read it aloud, and crashed the list, which keys rows by
 *    message id.
 *  - **Delivery only moves forward.** Acks and send confirmations race each other across
 *    threads; a late "sent" must not overwrite "delivered".
 *  - **History is bounded** at [maxEntries] per conversation.
 */
class ConversationLog(private val maxEntries: Int = MAX_ENTRIES) {

    companion object {
        const val MAX_ENTRIES = 500

        private fun rank(d: Delivery?): Int = when (d) {
            null -> -1
            Delivery.QUEUED -> 0
            Delivery.SENT -> 1
            Delivery.FAILED -> 2
            Delivery.DELIVERED -> 3
        }
    }

    private val _state = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val state: StateFlow<Map<String, Conversation>> = _state.asStateFlow()

    /** msgId -> peerId, so updates can find their conversation without a scan. */
    private val index = HashMap<String, String>()

    /**
     * Loads saved history underneath whatever is already in memory. Restoring is
     * asynchronous, so a message sent or received in the first moments after launch must
     * survive it rather than be overwritten by the older snapshot.
     */
    @Synchronized
    fun restore(conversations: Collection<Conversation>) {
        val map = LinkedHashMap<String, Conversation>()
        for (c in conversations) map[c.peerId] = c
        for ((peerId, live) in _state.value) {
            val saved = map[peerId]
            map[peerId] = if (saved == null) live else live.copy(
                // Retain chronological position, but live state wins for duplicate ids.
                entries = (saved.entries + live.entries).associateBy { it.msgId }.values.toList(),
                unread = saved.unread + live.unread
            )
        }
        index.clear()
        for ((peerId, c) in map) {
            val entries = c.entries.distinctBy { it.msgId }.takeLast(maxEntries)
            map[peerId] = c.copy(entries = entries)
            entries.forEach { index[it.msgId] = peerId }
        }
        _state.value = map
    }

    /**
     * Adds [entry] to the conversation with [peerId], creating it if needed.
     *
     * @param countUnread true when nobody is looking at this conversation right now.
     * @return false when the message was already recorded — the caller must then skip
     *   everything it would do for a new message, including reading it aloud.
     */
    @Synchronized
    fun record(
        peerId: String,
        peerName: String,
        deviceModel: String,
        entry: ChatEntry,
        countUnread: Boolean
    ): Boolean {
        if (index.containsKey(entry.msgId)) return false

        val existing = _state.value[peerId]
        var entries = (existing?.entries ?: emptyList()) + entry
        if (entries.size > maxEntries) {
            val dropped = entries.size - maxEntries
            entries.take(dropped).forEach { index.remove(it.msgId) }
            entries = entries.drop(dropped)
        }
        index[entry.msgId] = peerId

        val updated = Conversation(
            peerId = peerId,
            peerName = peerName.ifBlank { existing?.peerName.orEmpty() },
            deviceModel = deviceModel.ifBlank { existing?.deviceModel.orEmpty() },
            entries = entries,
            unread = (existing?.unread ?: 0) + if (countUnread && !entry.outgoing) 1 else 0,
            updatedAt = entry.recordedAt
        )
        _state.value = _state.value + (peerId to updated)
        return true
    }

    fun entry(msgId: String): ChatEntry? {
        val peerId = synchronized(this) { index[msgId] } ?: return null
        return _state.value[peerId]?.entries?.firstOrNull { it.msgId == msgId }
    }

    /** Applies [change] to the entry with [msgId]. False when no such entry exists. */
    @Synchronized
    fun update(msgId: String, change: (ChatEntry) -> ChatEntry): Boolean {
        val peerId = index[msgId] ?: return false
        val conversation = _state.value[peerId] ?: return false
        var hit = false
        val entries = conversation.entries.map {
            if (it.msgId == msgId) {
                hit = true
                change(it)
            } else it
        }
        if (!hit) return false
        _state.value = _state.value + (peerId to conversation.copy(entries = entries))
        return true
    }

    /** Advances an outgoing message's delivery state; never moves it backwards. */
    fun updateDelivery(msgId: String, delivery: Delivery): Boolean {
        var advanced = false
        update(msgId) {
            if (rank(delivery) > rank(it.delivery)) {
                advanced = true
                it.copy(delivery = delivery)
            } else it
        }
        return advanced
    }

    /** Keeps the conversation's name and device fresh from the live peer list. */
    @Synchronized
    fun touchPeer(peerId: String, peerName: String, deviceModel: String) {
        val c = _state.value[peerId] ?: return
        val name = peerName.ifBlank { c.peerName }
        val model = deviceModel.ifBlank { c.deviceModel }
        if (name == c.peerName && model == c.deviceModel) return
        _state.value = _state.value + (peerId to c.copy(peerName = name, deviceModel = model))
    }

    @Synchronized
    fun markRead(peerId: String) {
        val c = _state.value[peerId] ?: return
        if (c.unread == 0) return
        _state.value = _state.value + (peerId to c.copy(unread = 0))
    }

    @Synchronized
    fun clear(peerId: String) {
        val c = _state.value[peerId] ?: return
        c.entries.forEach { index.remove(it.msgId) }
        _state.value = _state.value - peerId
    }

    @Synchronized
    fun clearAll() {
        index.clear()
        _state.value = emptyMap()
    }
}
