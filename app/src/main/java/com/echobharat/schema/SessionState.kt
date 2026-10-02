package com.echobharat.schema

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Connected mesh peer model.
 */
@Parcelize
data class Peer(
    val peerId: String,
    val name: String,
    val deviceModel: String = "",
    val hops: Int = 1,
    val lastSeen: Long = System.currentTimeMillis(),
    val rssi: Int? = null
) : Parcelable

/**
 * Current transceiver role during a session.
 */
enum class TransmitRole {
    IDLE,
    SENDING,
    RECEIVING
}

/**
 * Ephemeral session state for UI and active mesh coordination.
 */
data class SessionState(
    val peers: List<Peer> = emptyList(),
    val isTransmitting: Boolean = false,
    val currentRole: TransmitRole = TransmitRole.IDLE,
    val messageLog: List<EchoBharatMessage> = emptyList(),
    val activeSpeaker: Peer? = null,
    val isBypassMode: Boolean = false
)
