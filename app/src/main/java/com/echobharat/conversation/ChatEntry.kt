package com.echobharat.conversation

import com.echobharat.mesh.Delivery
import com.echobharat.schema.EchoBharatMessage

/**
 * One line of a conversation as this phone sees it: the message that crossed the mesh,
 * plus everything that is this reader's own business and never goes on the wire —
 * delivery progress, the local translation, and what each step cost.
 *
 * Fields are nullable rather than defaulted because the history is restored with Gson,
 * which does not run Kotlin default initialisers.
 */
data class ChatEntry(
    val message: EchoBharatMessage,
    val outgoing: Boolean,
    /** Only for [outgoing] entries. */
    val delivery: Delivery? = null,
    /** Only for incoming entries; resolved on this phone into its own language. */
    val translation: StoredTranslation? = null,
    val timings: Timings? = null,
    val recordedAt: Long = System.currentTimeMillis()
) {
    val msgId: String get() = message.msgId
}

/**
 * The outcome of translating an incoming message into [target].
 *
 * Kept distinct from "no translation attempted yet" (a null [ChatEntry.translation]) and
 * split by reason, because the reader must be told *why* a line is shown as received:
 * a foreign line with no model and a line whose language nobody knows are different
 * problems.
 */
data class StoredTranslation(
    val target: String,
    val status: Status,
    val text: String? = null,
    val millis: Long? = null,
    /** The pivot language when two models were chained (Tamil → English → Hindi). */
    val via: String? = null
) {
    enum class Status {
        /** [text] is the message in [target]. */
        TRANSLATED,
        /** Already in [target]; the original is the translation. */
        NOT_NEEDED,
        /** A translation was needed but no model could produce one. */
        UNAVAILABLE,
        /** The sender did not say what language this is, so nothing was attempted. */
        UNKNOWN_SOURCE
    }
}

/**
 * Measured on this phone, per message. Every field is optional: a typed message has no
 * recognition time, a message nobody read aloud has no synthesis time.
 */
data class Timings(
    /** Speech recognition, for messages spoken on this phone. */
    val sttMs: Long? = null,
    /** Length of the recorded utterance. */
    val audioMs: Long? = null,
    val translateMs: Long? = null,
    val synthesisMs: Long? = null,
    /** Length of the synthesised speech. */
    val spokenMs: Long? = null
) {
    val isEmpty: Boolean
        get() = sttMs == null && translateMs == null && synthesisMs == null
}

/** Everything this phone has exchanged with one peer. */
data class Conversation(
    val peerId: String,
    val peerName: String,
    val deviceModel: String,
    val entries: List<ChatEntry>,
    val unread: Int,
    val updatedAt: Long
) {
    val last: ChatEntry? get() = entries.lastOrNull()
}
