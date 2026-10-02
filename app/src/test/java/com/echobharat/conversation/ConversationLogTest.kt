package com.echobharat.conversation

import com.echobharat.mesh.Delivery
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLogTest {

    private fun msg(id: String, from: String = "peerA", type: MessageType = MessageType.TYPED_TEXT) =
        EchoBharatMessage(
            msgId = id, type = type, srcLang = "hi", text = "text $id",
            senderName = "Name $from", senderId = from, deviceModel = "Model"
        )

    private fun ConversationLog.incoming(id: String, from: String = "peerA", unread: Boolean = true) =
        record(from, "Name $from", "Model", ChatEntry(msg(id, from), outgoing = false), unread)

    @Test
    fun `a message is recorded once however many copies arrive`() {
        val log = ConversationLog()
        assertTrue(log.incoming("sos-1"))
        assertFalse(log.incoming("sos-1"))
        assertFalse(log.incoming("sos-1"))

        assertEquals(1, log.state.value["peerA"]!!.entries.size)
        assertEquals(1, log.state.value["peerA"]!!.unread)
    }

    @Test
    fun `unread counts only incoming lines nobody was looking at`() {
        val log = ConversationLog()
        log.incoming("1", unread = true)
        log.incoming("2", unread = false)
        log.record("peerA", "A", "M", ChatEntry(msg("3", "me"), outgoing = true), countUnread = true)
        assertEquals(1, log.state.value["peerA"]!!.unread)

        log.markRead("peerA")
        assertEquals(0, log.state.value["peerA"]!!.unread)
    }

    @Test
    fun `delivery only moves forward`() {
        val log = ConversationLog()
        log.record("peerA", "A", "M", ChatEntry(msg("m"), outgoing = true, delivery = Delivery.QUEUED), false)

        assertTrue(log.updateDelivery("m", Delivery.DELIVERED))
        assertFalse(log.updateDelivery("m", Delivery.SENT))
        assertFalse(log.updateDelivery("m", Delivery.FAILED))
        assertEquals(Delivery.DELIVERED, log.entry("m")!!.delivery)
    }

    @Test
    fun `a failed message can still be acknowledged but not re-sent`() {
        val log = ConversationLog()
        log.record("peerA", "A", "M", ChatEntry(msg("m"), outgoing = true, delivery = Delivery.QUEUED), false)
        log.updateDelivery("m", Delivery.FAILED)
        assertFalse(log.updateDelivery("m", Delivery.SENT))
        assertTrue(log.updateDelivery("m", Delivery.DELIVERED))
    }

    @Test
    fun `history is capped per conversation and evicted ids can be recorded again`() {
        val log = ConversationLog(maxEntries = 3)
        (1..5).forEach { log.incoming("$it") }
        assertEquals(listOf("3", "4", "5"), log.state.value["peerA"]!!.entries.map { it.msgId })
        assertNull(log.entry("1"))
    }

    @Test
    fun `restore keeps anything recorded before it finished`() {
        val log = ConversationLog()
        log.incoming("live", from = "peerA")
        val saved = Conversation(
            peerId = "peerA", peerName = "A", deviceModel = "M",
            entries = listOf(ChatEntry(msg("old"), outgoing = false)), unread = 2, updatedAt = 0
        )
        log.restore(listOf(saved))

        val c = log.state.value["peerA"]!!
        assertEquals(listOf("old", "live"), c.entries.map { it.msgId })
        assertEquals(3, c.unread)
        assertFalse("restored ids are indexed", log.incoming("old"))
    }

    @Test
    fun `restore preserves a newer live delivery and translation for the same id`() {
        val log = ConversationLog()
        val saved = ChatEntry(msg("same", "me"), outgoing = true, delivery = Delivery.SENT)
        val current = saved.copy(delivery = Delivery.DELIVERED)
        log.record("peerA", "A", "M", current, false)
        log.restore(listOf(Conversation("peerA", "A", "M", listOf(saved), 0, 0)))
        assertEquals(Delivery.DELIVERED, log.entry("same")!!.delivery)
        assertEquals(1, log.state.value["peerA"]!!.entries.size)
    }

    @Test
    fun `updates find their message across conversations`() {
        val log = ConversationLog()
        log.incoming("a1", from = "peerA")
        log.incoming("b1", from = "peerB")
        val t = StoredTranslation("en", StoredTranslation.Status.TRANSLATED, "hello", 120)
        assertTrue(log.update("b1") { it.copy(translation = t) })
        assertEquals(t, log.entry("b1")!!.translation)
        assertNull(log.entry("a1")!!.translation)
        assertFalse(log.update("missing") { it })
    }
}
