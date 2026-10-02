package com.echobharat.services

import com.echobharat.mesh.model.BitchatMessage
import com.echobharat.mesh.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory state store for EchoBharat mesh transport.
 * Keeps track of connected peers, direct (1-hop) neighbors, and transmission delivery states.
 */
object AppStateStore {
    private val peerIdsByTransport = mutableMapOf<String, Set<String>>()
    private val directPeerIdsByTransport = mutableMapOf<String, Set<String>>()

    private val _directPeers = MutableStateFlow<Set<String>>(emptySet())
    val directPeers: StateFlow<Set<String>> = _directPeers.asStateFlow()

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BitchatMessage>>> = _privateMessages.asStateFlow()

    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    fun setNickname(name: String) {
        _nickname.value = name
    }

    @Synchronized
    fun setTransportPeers(transportId: String, peerIds: Collection<String>) {
        peerIdsByTransport[transportId] = peerIds.toSet()
        val allPeers = peerIdsByTransport.values.flatten().distinct()
        _peers.value = allPeers
    }

    @Synchronized
    fun clearTransportPeers(transportId: String) {
        peerIdsByTransport.remove(transportId)
        val allPeers = peerIdsByTransport.values.flatten().distinct()
        _peers.value = allPeers
    }

    @Synchronized
    fun setTransportDirectPeers(transportId: String, directPeers: Collection<String>) {
        directPeerIdsByTransport[transportId] = directPeers.toSet()
        _directPeers.value = directPeerIdsByTransport.values.flatten().toSet()
    }

    @Synchronized
    fun clearTransportDirectPeers(transportId: String) {
        directPeerIdsByTransport.remove(transportId)
        _directPeers.value = directPeerIdsByTransport.values.flatten().toSet()
    }

    fun getDirectPeers(): List<String> {
        return _directPeers.value.toList()
    }

    fun addPublicMessage(message: BitchatMessage) {
        _publicMessages.value = _publicMessages.value + message
    }

    fun addPrivateMessage(peerId: String, message: BitchatMessage) {
        val current = _privateMessages.value.toMutableMap()
        val list = current[peerId]?.toMutableList() ?: mutableListOf()
        list.add(message)
        current[peerId] = list
        _privateMessages.value = current
    }

    fun updatePrivateMessageStatus(messageId: String, status: DeliveryStatus) {
        val current = _privateMessages.value.toMutableMap()
        for ((peerId, list) in current) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val updated = list.toMutableList()
                updated[idx] = updated[idx].copy(deliveryStatus = status)
                current[peerId] = updated
                _privateMessages.value = current
                break
            }
        }
    }

    fun clear() {
        peerIdsByTransport.clear()
        directPeerIdsByTransport.clear()
        _directPeers.value = emptySet()
        _peers.value = emptyList()
        _publicMessages.value = emptyList()
        _privateMessages.value = emptyMap()
    }
}
