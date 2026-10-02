package com.echobharat.mesh.transport

import com.echobharat.mesh.model.BitchatMessage

/**
 * Shared mesh delegate interface for transport-agnostic callbacks.
 */
interface MeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    /** A private message left this phone (possibly after waiting for a Noise session). */
    fun didSendPrivateMessage(messageID: String, recipientPeerID: String) {}
    /** A private message was given up on: no session came up before it expired. */
    fun didDropPrivateMessage(messageID: String, recipientPeerID: String) {}
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}
    /** Current Noise generation either proved peer state or exhausted its 5-second watchdog. */
    fun didResolvePrivateMediaPolicy(peerID: String) {}
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}
