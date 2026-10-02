package com.echobharat.services

import com.echobharat.mesh.model.BitchatMessage

/**
 * Reflects an incoming transport message into process-wide state.
 */
internal object IncomingMessageAdmission {
    fun admitToAppState(message: BitchatMessage): Boolean = try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                AppStateStore.addPrivateMessage(peerID, message)
                true
            }
            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        true
    }
}
