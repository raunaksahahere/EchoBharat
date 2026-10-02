package com.echobharat.mesh.transport

import android.content.Context
import android.util.Log
import com.echobharat.mesh.model.BitchatFilePacket
import com.echobharat.mesh.model.BitchatMessage
import com.echobharat.mesh.noise.NoiseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feature-facing mesh service that wraps BluetoothMeshService for EchoBharat.
 */
class UnifiedMeshService(
    private val context: Context,
    private val bluetooth: BluetoothMeshService
) : MeshService, BluetoothMeshDelegate {

    companion object {
        private const val TAG = "UnifiedMeshService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val powerManager = PowerManager.getInstance(context.applicationContext)
    private var announcementJob: Job? = null

    override val myPeerID: String
        get() = bluetooth.myPeerID

    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            refreshDelegates()
        }

    fun refreshDelegates() {
        try { bluetooth.delegate = if (delegate != null) this else null } catch (_: Exception) { }
    }

    override fun startServices() {
        if (isBleEnabled()) {
            try { bluetooth.startServices() } catch (e: Exception) {
                Log.w(TAG, "Failed to start BLE transport: ${e.message}")
            }
        } else {
            try { bluetooth.setBleTransportEnabled(false) } catch (_: Exception) { }
        }
        startAnnouncementScheduler()
        refreshDelegates()
    }

    /** Restarts BLE after the adapter was switched on; see [BluetoothMeshService.restartTransport]. */
    fun restartBluetooth() {
        try { bluetooth.restartTransport() } catch (e: Exception) {
            Log.w(TAG, "Failed to restart BLE transport: ${e.message}")
        }
    }

    override fun stopServices() {
        announcementJob?.cancel()
        announcementJob = null
        try { bluetooth.stopServices() } catch (_: Exception) { }
    }

    private fun startAnnouncementScheduler() {
        if (announcementJob?.isActive == true) return
        announcementJob = serviceScope.launch {
            powerManager.profile
                .map { profile ->
                    profile.meshAnnouncementIntervalMs to profile.hasDirectPeers
                }
                .distinctUntilChanged()
                .collectLatest { (intervalMs, hasRecipients) ->
                    if (!hasRecipients) return@collectLatest
                    while (isActive) {
                        delay(intervalMs)
                        if (powerManager.profile.value.hasDirectPeers) sendBroadcastAnnounce()
                    }
                }
        }
    }

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        bluetooth.sendMessage(content, mentions, channel)
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        bluetooth.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        bluetooth.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }

    override fun sendDeliveryAck(messageID: String, recipientPeerID: String) {
        // Handled via Noise encrypted delivery ack in BluetoothMeshService
    }

    override fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {
        // No-op in EchoBharat
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        bluetooth.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        bluetooth.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        bluetooth.sendFileBroadcast(file)
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        bluetooth.sendFilePrivate(recipientPeerID, file)
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        bluetooth.sendVoiceFrame(recipientPeerID, payload)
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation {
        return bluetooth.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback)
    }

    override fun cancelFileTransfer(transferId: String): Boolean {
        return bluetooth.cancelFileTransfer(transferId)
    }

    override fun sendBroadcastAnnounce() {
        bluetooth.sendBroadcastAnnounce()
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        bluetooth.sendAnnouncementToPeer(peerID)
    }

    override fun getPeerNicknames(): Map<String, String> = bluetooth.getPeerNicknames()

    override fun getPeerRSSI(): Map<String, Int> = bluetooth.getPeerRSSI()

    override fun getActivePeerCount(): Int = bluetooth.getActivePeerCount()

    override fun hasEstablishedSession(peerID: String): Boolean = bluetooth.hasEstablishedSession(peerID)

    override fun getSessionState(peerID: String): NoiseSession.NoiseSessionState = bluetooth.getSessionState(peerID)

    override fun initiateNoiseHandshake(peerID: String) {
        bluetooth.initiateNoiseHandshake(peerID)
    }

    override fun getPeerFingerprint(peerID: String): String? = bluetooth.getPeerFingerprint(peerID)

    override fun getPeerInfo(peerID: String): PeerInfo? = bluetooth.getPeerInfo(peerID)

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return bluetooth.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    }

    override fun getIdentityFingerprint(): String = bluetooth.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? = bluetooth.getStaticNoisePublicKey()

    override fun shouldShowEncryptionIcon(peerID: String): Boolean = bluetooth.shouldShowEncryptionIcon(peerID)

    override fun getEncryptedPeers(): List<String> = bluetooth.getEncryptedPeers()

    override fun getDeviceAddressForPeer(peerID: String): String? = bluetooth.getDeviceAddressForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> = bluetooth.getDeviceAddressToPeerMapping()

    override fun printDeviceAddressesForPeers(): String = bluetooth.printDeviceAddressesForPeers()

    override fun getDebugStatus(): String = bluetooth.getDebugStatus()

    override fun clearAllInternalData() {
        bluetooth.clearAllInternalData()
    }

    override fun clearAllEncryptionData() {
        bluetooth.clearAllEncryptionData()
    }

    // BluetoothMeshDelegate implementation
    override fun didReceiveMessage(message: BitchatMessage) {
        delegate?.didReceiveMessage(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        delegate?.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        delegate?.didReceiveChannelLeave(channel, fromPeer)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveDeliveryAck(messageID, recipientPeerID)
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        delegate?.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didSendPrivateMessage(messageID: String, recipientPeerID: String) {
        delegate?.didSendPrivateMessage(messageID, recipientPeerID)
    }

    override fun didDropPrivateMessage(messageID: String, recipientPeerID: String) {
        delegate?.didDropPrivateMessage(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
    }

    override fun didResolvePrivateMediaPolicy(peerID: String) {
        delegate?.didResolvePrivateMediaPolicy(peerID)
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return delegate?.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? = delegate?.getNickname()

    override fun isFavorite(peerID: String): Boolean = delegate?.isFavorite(peerID) ?: false

    private fun isBleEnabled(): Boolean {
        return try {
            com.echobharat.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.echobharat.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }
}
