package com.echobharat.mesh

import android.util.Log
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.Languages
import com.echobharat.schema.MessageType
import java.nio.charset.StandardCharsets

/**
 * Codec for packing and unpacking EchoBharatMessage payloads across the mesh transport.
 *
 * Payload wire framing:
 * - Magic header: "EB1:" prefix to distinguish EchoBharat payloads from raw legacy text.
 * - JSON payload serialized as UTF-8 bytes.
 *
 * Fallback:
 * If an incoming payload does not have the "EB1:" prefix (e.g. from a plain bitchat client),
 * it is parsed as typed text whose language is inferred from its script only where the
 * script names exactly one language, and is otherwise [Languages.UNDETERMINED]. It used to
 * be stamped "en" unconditionally, which fed Hindi from bitchat nodes to the English→Hindi
 * translator and presented the output as a translation.
 */
object EchoBharatMeshPayloadCodec {

    private const val TAG = "EchoBharatPayloadCodec"
    private const val MAGIC_PREFIX = "EB1:"

    /**
     * Encodes an EchoBharatMessage into wire bytes for transmission.
     */
    fun encode(message: EchoBharatMessage): ByteArray {
        val jsonString = message.toJson()
        val wireString = MAGIC_PREFIX + jsonString
        return wireString.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Decodes wire bytes received over mesh into an EchoBharatMessage.
     */
    fun decode(
        payloadBytes: ByteArray,
        fallbackSenderId: String = "",
        fallbackSenderName: String = "Peer"
    ): EchoBharatMessage? {
        if (payloadBytes.isEmpty()) return null

        return try {
            val textContent = String(payloadBytes, StandardCharsets.UTF_8)
            if (textContent.startsWith(MAGIC_PREFIX)) {
                val json = textContent.removePrefix(MAGIC_PREFIX)
                EchoBharatMessage.fromJson(json)?.takeIf { message ->
                    // Distress traffic must expire even when a peer supplies malformed JSON
                    // metadata. Otherwise an absent expiry or future timestamp pins relay state.
                    if (message.type != MessageType.SOS) true else {
                        val expiry = message.expiresAt
                        expiry != null && message.ts > 0 && expiry > message.ts &&
                            expiry - message.ts <= SosManager.LIFETIME_MS &&
                            message.ts <= System.currentTimeMillis() + SosManager.MAX_CLOCK_SKEW_MS
                    }
                }
            } else {
                // Plain bitchat text: no language tag, so none is invented.
                EchoBharatMessage(
                    v = 1,
                    type = MessageType.TYPED_TEXT,
                    srcLang = Languages.guessFromScript(textContent),
                    text = textContent,
                    senderName = fallbackSenderName,
                    senderId = fallbackSenderId,
                    deviceModel = "Mesh Node",
                    isAlert = false,
                    ts = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding EchoBharat payload: ${e.message}")
            null
        }
    }

    /**
     * Checks if given byte payload is an EchoBharat framed message.
     */
    fun isEchoBharatPayload(payloadBytes: ByteArray): Boolean {
        if (payloadBytes.size < MAGIC_PREFIX.length) return false
        val prefixBytes = MAGIC_PREFIX.toByteArray(StandardCharsets.UTF_8)
        for (i in prefixBytes.indices) {
            if (payloadBytes[i] != prefixBytes[i]) return false
        }
        return true
    }
}
