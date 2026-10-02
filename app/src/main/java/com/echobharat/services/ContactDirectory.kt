package com.echobharat.services

/**
 * Helper to canonicalize peer/conversation IDs for EchoBharat.
 */
object ContactDirectory {
    fun canonicalConversationId(peerId: String): String = peerId.trim()
}
