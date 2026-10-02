package com.echobharat.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ConversationWorkQueueTest {
    @Test fun `backlog is bounded and urgent displaces only ordinary work`() {
        val queue = ConversationWorkQueue(2)
        queue.offer(ConversationWork("a", speak = false))
        queue.offer(ConversationWork("b", speak = true))
        assertEquals(ConversationWorkQueue.Admission.REJECTED, queue.offer(ConversationWork("c")))
        assertEquals(ConversationWorkQueue.Admission.DISPLACED_NORMAL,
            queue.offer(ConversationWork("sos", speak = true, urgent = true)))
        assertEquals(2, queue.status.value.pending)
        assertEquals("sos", queue.poll()?.msgId)
        assertEquals("b", queue.poll()?.msgId)
        assertNull(queue.poll())
        assertEquals(2L, queue.status.value.deferredNormal)
    }

    @Test fun `all urgent overload is explicit and never evicts accepted urgent work`() {
        val queue = ConversationWorkQueue(2)
        queue.offer(ConversationWork("alert1", urgent = true))
        queue.offer(ConversationWork("alert2", urgent = true))
        repeat(10_000) { queue.offer(ConversationWork("excess$it", urgent = true)) }
        assertEquals(2, queue.status.value.pending)
        assertEquals(10_000L, queue.status.value.rejectedUrgent)
        assertEquals("excess9999", queue.status.value.lastRejectedUrgent)
        assertEquals("alert1", queue.poll()?.msgId)
        assertEquals("alert2", queue.poll()?.msgId)
    }

    @Test fun `duplicate requests merge and promote without consuming capacity`() {
        val queue = ConversationWorkQueue(2)
        queue.offer(ConversationWork("a"))
        queue.offer(ConversationWork("b"))
        assertEquals(ConversationWorkQueue.Admission.COALESCED,
            queue.offer(ConversationWork("b", speak = true, urgent = true)))
        assertEquals(ConversationWork("b", speak = true, urgent = true), queue.poll())
        assertEquals(1, queue.status.value.pending)
    }

    @Test fun `waiting consumer is cancellable and does not remove later work`() = runBlocking {
        val queue = ConversationWorkQueue(1)
        val consumer = launch { queue.take(); fail("Unexpected work") }
        yield()
        consumer.cancelAndJoin()
        queue.offer(ConversationWork("later", urgent = true))
        assertEquals("later", queue.take().msgId)
    }
}
