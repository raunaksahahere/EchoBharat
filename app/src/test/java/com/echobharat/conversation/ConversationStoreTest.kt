package com.echobharat.conversation

import com.echobharat.mesh.Delivery
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.services.ConversationStorageCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Stands in for the Keystore cipher: reversible, and checks the associated data. */
    private class FakeCipher : ConversationStorageCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) =
            associatedData + byteArrayOf(0) + plaintext.map { (it.toInt() xor 0x5A).toByte() }

        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            val prefix = associatedData + byteArrayOf(0)
            require(envelope.take(prefix.size) == prefix.toList()) { "wrong associated data" }
            return envelope.drop(prefix.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        override fun destroyKey() = Unit
    }

    private val conversation = Conversation(
        peerId = "peerA",
        peerName = "Asha",
        deviceModel = "Pixel 7",
        entries = listOf(
            ChatEntry(
                EchoBharatMessage(msgId = "m1", text = "पानी", senderName = "Asha", senderId = "peerA", deviceModel = "Pixel 7"),
                outgoing = false,
                translation = StoredTranslation("en", StoredTranslation.Status.TRANSLATED, "water", 90),
                timings = Timings(translateMs = 90)
            ),
            ChatEntry(
                EchoBharatMessage(msgId = "m2", text = "on my way", srcLang = "en", senderName = "Me", senderId = "me", deviceModel = "X"),
                outgoing = true,
                delivery = Delivery.DELIVERED
            )
        ),
        unread = 1,
        updatedAt = 42
    )

    @Test
    fun `history round trips through the encrypted file`() {
        val file = File(temp.root, "c/history.bin")
        ConversationStore(file, FakeCipher()).save(listOf(conversation))

        assertFalse("plaintext must not reach disk", String(file.readBytes()).contains("on my way"))
        assertEquals(listOf(conversation), ConversationStore(file, FakeCipher()).load())
    }

    @Test
    fun `an unreadable file is set aside and history starts empty`() {
        val file = File(temp.root, "history.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(emptyList<Conversation>(), ConversationStore(file, FakeCipher()).load())
        assertFalse(file.exists())
        assertTrue(File(temp.root, "history.bin.unreadable").exists())
    }

    @Test
    fun `no file means no history`() {
        assertEquals(emptyList<Conversation>(), ConversationStore(File(temp.root, "none"), FakeCipher()).load())
    }
}
