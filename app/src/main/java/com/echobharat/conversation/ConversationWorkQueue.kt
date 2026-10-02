package com.echobharat.conversation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stored on the existing encrypted conversation record, not in a suspended producer. */
data class PendingConversationWork(
    val speak: Boolean = false,
    val urgent: Boolean = false,
    /** Failed model work stays visible and is retried only on an explicit new request. */
    val failed: Boolean = false
)

internal data class ConversationWork(val msgId: String, val speak: Boolean = false, val urgent: Boolean = false)

/** Aggregate admission telemetry; rejected admission is deferred in the repository's history. */
data class ConversationQueueStatus(
    val pending: Int = 0,
    val deferredNormal: Long = 0,
    val rejectedUrgent: Long = 0,
    val lastRejectedUrgent: String? = null,
    val failedUrgent: Long = 0,
    val lastFailedUrgent: String? = null
)

/**
 * Synchronous, bounded admission: no launch { send() } and no waiting producer coroutines.
 * Accepted urgent work is never evicted. Urgent overload is an explicit result/StateFlow,
 * and the repository retains the request in its encrypted history for its bounded pump.
 * Priority is between jobs, not preemption of a running model or alert.
 */
internal class ConversationWorkQueue(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    enum class Admission { ACCEPTED, COALESCED, DISPLACED_NORMAL, REJECTED }

    private val pending = LinkedHashMap<String, ConversationWork>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(ConversationQueueStatus())
    val status = mutableStatus.asStateFlow()

    @Synchronized fun contains(msgId: String): Boolean = msgId in pending
    @Synchronized fun hasRoom(): Boolean = pending.size < capacity

    @Synchronized fun offer(work: ConversationWork): Admission {
        val previous = pending[work.msgId]
        if (previous != null) {
            pending[work.msgId] = work.copy(speak = previous.speak || work.speak, urgent = previous.urgent || work.urgent)
            wake.trySend(Unit)
            return Admission.COALESCED
        }
        var admission = Admission.ACCEPTED
        if (pending.size == capacity) {
            val victim = if (work.urgent) pending.values.firstOrNull { !it.urgent } else null
            if (victim == null) {
                val s = mutableStatus.value
                mutableStatus.value = if (work.urgent) s.copy(
                    rejectedUrgent = s.rejectedUrgent + 1, lastRejectedUrgent = work.msgId
                ) else s.copy(deferredNormal = s.deferredNormal + 1)
                return Admission.REJECTED
            }
            pending.remove(victim.msgId)
            mutableStatus.value = mutableStatus.value.copy(deferredNormal = mutableStatus.value.deferredNormal + 1)
            admission = Admission.DISPLACED_NORMAL
        }
        pending[work.msgId] = work
        mutableStatus.value = mutableStatus.value.copy(pending = pending.size)
        wake.trySend(Unit)
        return admission
    }

    @Synchronized fun poll(): ConversationWork? {
        val work = pending.values.firstOrNull { it.urgent } ?: pending.values.firstOrNull() ?: return null
        pending.remove(work.msgId)
        mutableStatus.value = mutableStatus.value.copy(pending = pending.size)
        return work
    }

    @Synchronized fun failedUrgent(msgId: String) {
        mutableStatus.value = mutableStatus.value.copy(
            failedUrgent = mutableStatus.value.failedUrgent + 1, lastFailedUrgent = msgId
        )
    }

    suspend fun awaitSignal() { wake.receive() }

    suspend fun take(): ConversationWork {
        while (true) {
            poll()?.let { return it }
            awaitSignal()
        }
    }
}
