package com.echobharat.mesh

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.echobharat.identity.IdentityManager
import com.echobharat.mesh.model.BitchatMessage
import com.echobharat.mesh.service.MeshServiceHolder
import com.echobharat.mesh.transport.MeshDelegate
import com.echobharat.mesh.transport.MeshService
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.MessageType
import com.echobharat.schema.Peer
import com.echobharat.services.meshgraph.MeshGraphService
import com.echobharat.services.meshgraph.RoutePlanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Where an outgoing private message is on its way to the recipient. */
enum class Delivery {
    /** Accepted locally; waiting for a Noise session with the recipient. */
    QUEUED,
    /** Encrypted and handed to the radio. */
    SENT,
    /** The recipient's phone acknowledged it. */
    DELIVERED,
    /** Given up on: no session with the recipient came up in time. */
    FAILED
}

data class DeliveryUpdate(val msgId: String, val peerId: String, val delivery: Delivery)

/**
 * Primary high-level coordinator for EchoBharat mesh communication.
 *
 * Connects the UI / App layer to the underlying BLE mesh transport core.
 * Transmits ONLY text representations (STT output, typed text, alert messages), NEVER audio.
 */
class EchoBharatMeshManager(private val context: Context) : MeshDelegate {

    companion object {
        private const val TAG = "EchoBharatMeshManager"

        @Volatile
        private var instance: EchoBharatMeshManager? = null

        fun getInstance(context: Context): EchoBharatMeshManager {
            return instance ?: synchronized(this) {
                instance ?: EchoBharatMeshManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val identityManager = IdentityManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Low-level BLE mesh service from bitchat core
    private var meshService: MeshService? = null

    // Reactive peer list state
    private val _connectedPeers = MutableStateFlow<List<Peer>>(emptyList())
    val connectedPeers: StateFlow<List<Peer>> = _connectedPeers.asStateFlow()

    // Reactive incoming message stream
    private val _incomingMessages = MutableSharedFlow<EchoBharatMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<EchoBharatMessage> = _incomingMessages.asSharedFlow()

    // Mesh running status
    private val _isMeshRunning = MutableStateFlow(false)
    val isMeshRunning: StateFlow<Boolean> = _isMeshRunning.asStateFlow()

    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
    val deliveryUpdates: SharedFlow<DeliveryUpdate> = _deliveryUpdates.asSharedFlow()

    private val locationProvider = LocationProvider(context)

    /**
     * This phone's mesh peer ID — the identity every other phone sees in its peer list and
     * that Noise sessions, routing and delivery acks are bound to.
     *
     * Outgoing messages carry this, not [IdentityManager]'s separate peer ID. The two were
     * derived from different keys, so a receiver comparing a message's sender against the
     * peer it had open never found a match and discarded every ordinary incoming message.
     */
    val myPeerId: String get() = meshService?.myPeerID.orEmpty()

    /**
     * Distress announcements live here rather than in a screen: a phone must keep
     * relaying an SOS it is holding even with the UI closed.
     */
    val sos: SosManager by lazy { SosManager(context, this) }

    init {
        initMeshService()
    }

    private fun initMeshService() {
        try {
            val service = MeshServiceHolder.getUnifiedOrCreate(context)
            service.delegate = this
            meshService = service
            Log.i(TAG, "Shared UnifiedMeshService initialized with peerId=${service.myPeerID}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BluetoothMeshService: ${e.message}", e)
        }
    }

    /**
     * Starts advertising and scanning on the BLE mesh.
     */
    fun startMesh() {
        if (_isMeshRunning.value) return

        try {
            val service = meshService ?: MeshServiceHolder.getUnifiedOrCreate(context).also {
                it.delegate = this
                meshService = it
            }

            service.startServices()
            registerBluetoothStateReceiver()
            _isMeshRunning.value = true
            sos.start()
            Log.i(TAG, "EchoBharat mesh started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}", e)
        }
    }

    private var bluetoothReceiverRegistered = false

    /**
     * Brings the mesh back when Bluetooth is switched on. If the adapter was off at launch the
     * first start failed, and if it was toggled while running the GATT server died with it;
     * either way nothing else retries, so the phone sat with a dead mesh until the app was
     * killed and reopened.
     */
    @Synchronized
    private fun registerBluetoothStateReceiver() {
        if (bluetoothReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && _isMeshRunning.value) {
                    Log.i(TAG, "Bluetooth switched on; restarting the mesh transport")
                    (meshService as? com.echobharat.mesh.transport.UnifiedMeshService)?.restartBluetooth()
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            bluetoothReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch Bluetooth state: ${e.message}")
        }
    }

    /**
     * Stops the BLE mesh operations.
     */
    fun stopMesh() {
        try {
            meshService?.stopServices()
            sos.stop()
            _isMeshRunning.value = false
            _connectedPeers.value = emptyList()
            Log.i(TAG, "EchoBharat mesh stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop mesh: ${e.message}", e)
        }
    }

    /**
     * Builds a message from this phone. It is not sent: callers record it first and then
     * hand it to [sendPrivate], so a delivery update can never outrun the record of the
     * message it is about.
     */
    fun compose(
        type: MessageType,
        text: String,
        srcLang: String,
        isAlert: Boolean = false
    ): EchoBharatMessage {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")
        return EchoBharatMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = if (isAlert) MessageType.ALERT else type,
            srcLang = srcLang,
            text = text,
            senderName = identity.displayName,
            senderId = myPeerId,
            deviceModel = identity.deviceModel,
            isAlert = isAlert,
            ts = System.currentTimeMillis()
        )
    }

    /**
     * Sends [message] to one peer, Noise-encrypted end to end.
     *
     * Every conversation is private; there is no "encrypt this one" switch, because a
     * message addressed to a person was always meant for that person. If no session exists
     * yet the transport holds the message until one does, and [deliveryUpdates] reports it
     * as SENT, then DELIVERED — or FAILED if the recipient never answers.
     *
     * @return false only when the mesh is not running at all.
     */
    fun sendPrivate(message: EchoBharatMessage, recipientPeerId: String): Boolean {
        val service = meshService ?: return false
        return try {
            val wire = String(EchoBharatMeshPayloadCodec.encode(message), Charsets.UTF_8)
            // The payload's own msgId doubles as the transport message id, so the
            // recipient's delivery ack names the message it acknowledges.
            service.sendPrivateMessage(
                content = wire,
                recipientPeerID = recipientPeerId,
                recipientNickname = "peer",
                messageID = message.msgId
            )
            Log.d(TAG, "Queued private ${message.type} ${message.msgId} for $recipientPeerId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send private message: ${e.message}", e)
            false
        }
    }

    /**
     * Public, signed, unencrypted broadcast. Only distress traffic goes this way: an SOS
     * that only phones holding a session with the sender could read would defeat itself.
     */
    private fun broadcast(message: EchoBharatMessage): Boolean {
        val service = meshService ?: return false
        return try {
            service.sendMessage(content = String(EchoBharatMeshPayloadCodec.encode(message), Charsets.UTF_8))
            Log.d(TAG, "Broadcast ${message.type} ${message.msgId} (signed, not encrypted)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast: ${e.message}", e)
            false
        }
    }

    /**
     * Raises a distress announcement: broadcast, no recipient, carrying coordinates when
     * a fix was available. Always sent in the clear on the public mesh — a distress call
     * that only reachable peers with an established Noise session could read would defeat
     * its own purpose.
     */
    fun sendSos(
        text: String,
        srcLang: String,
        lat: Double?,
        lon: Double?,
        accuracy: Float?,
        expiresAt: Long
    ): EchoBharatMessage? {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = EchoBharatMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = MessageType.SOS,
            srcLang = srcLang,
            text = text,
            senderName = identity.displayName,
            senderId = myPeerId,
            deviceModel = identity.deviceModel,
            isAlert = true,
            ts = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            gpsAccuracyM = accuracy,
            expiresAt = expiresAt
        )

        return if (broadcast(message)) message else null
    }

    /** Re-broadcasts an announcement this device is holding, unchanged. */
    fun rebroadcastSos(message: EchoBharatMessage): Boolean {
        if (message.isExpired()) return false
        return broadcast(message)
    }

    /** Tells the mesh that [original] has been resolved and must stop propagating. */
    fun sendSosResolved(original: EchoBharatMessage): Boolean {
        val identity = identityManager.getCurrentIdentity()
            ?: identityManager.getOrCreateIdentity("User")

        val message = EchoBharatMessage(
            v = 1,
            msgId = UUID.randomUUID().toString(),
            type = MessageType.SOS_RESOLVED,
            srcLang = original.srcLang,
            text = "Resolved",
            senderName = identity.displayName,
            senderId = myPeerId,
            deviceModel = identity.deviceModel,
            ts = System.currentTimeMillis(),
            refMsgId = original.msgId
        )
        return broadcast(message)
    }

    // --- BluetoothMeshDelegate implementation ---

    override fun didReceiveMessage(message: BitchatMessage) {
        val payloadBytes = message.content.toByteArray(Charsets.UTF_8)
        val parsed = EchoBharatMeshPayloadCodec.decode(
            payloadBytes = payloadBytes,
            fallbackSenderId = message.senderPeerID ?: message.sender,
            fallbackSenderName = message.sender
        )

            ?: return
        val decoded = attribute(parsed, message.senderPeerID)

        Log.d(TAG, "Received ${decoded.type} ${decoded.msgId} from ${decoded.senderName} (${decoded.senderId.take(8)})")

        // Expired distress announcements are dropped rather than shown or relayed.
        if (decoded.isExpired()) {
            Log.i(TAG, "Dropping expired message ${decoded.msgId} (type=${decoded.type})")
            return
        }

        // Range gate: hop count always applies, GPS narrows it when both ends have a fix.
        val hops = senderHops(decoded.senderId)
        val verdict = RangePolicy.evaluate(hops, decoded.origin(), locationProvider.lastKnown())
        if (verdict is RangePolicy.Verdict.OutOfRange) {
            RangePolicy.logDrop("message ${decoded.msgId} from ${decoded.senderName}", verdict)
            return
        }

        // Distress announcements are held and relayed by this device, not just displayed.
        if (decoded.type == MessageType.SOS || decoded.type == MessageType.SOS_RESOLVED) {
            sos.onReceived(decoded)
        }

        scope.launch {
            _incomingMessages.emit(decoded)
        }
    }

    /**
     * Files a message under the peer the transport authenticated, not the one the payload
     * claims. The payload's `senderId` is self-asserted JSON; the transport's sender is
     * bound to a Noise session or a verified announcement signature.
     *
     * Distress announcements are the exception: a phone relaying someone else's SOS
     * re-broadcasts it unchanged, so the transport sender is the relay, and the payload is
     * the only record of who is actually in trouble.
     */
    private fun attribute(message: EchoBharatMessage, transportSender: String?): EchoBharatMessage {
        if (transportSender.isNullOrBlank() || message.type == MessageType.SOS) return message
        if (message.senderId != transportSender) {
            Log.d(TAG, "Attributing ${message.msgId} to transport sender ${transportSender.take(8)}")
        }
        return message.copy(senderId = transportSender)
    }

    /** Hops to [peerId] from the gossip graph, or null when it cannot be determined. */
    private fun senderHops(peerId: String): Int? = try {
        _connectedPeers.value.firstOrNull { it.peerId == peerId }?.hops
    } catch (e: Exception) {
        null
    }

    override fun didUpdatePeerList(peers: List<String>) {
        updatePeerList()
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        Log.d(TAG, "Peer $fromPeer left channel $channel")
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        Log.d(TAG, "Delivery ack received for message $messageID from $recipientPeerID")
        _deliveryUpdates.tryEmit(DeliveryUpdate(messageID, recipientPeerID, Delivery.DELIVERED))
    }

    override fun didSendPrivateMessage(messageID: String, recipientPeerID: String) {
        _deliveryUpdates.tryEmit(DeliveryUpdate(messageID, recipientPeerID, Delivery.SENT))
    }

    override fun didDropPrivateMessage(messageID: String, recipientPeerID: String) {
        _deliveryUpdates.tryEmit(DeliveryUpdate(messageID, recipientPeerID, Delivery.FAILED))
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        Log.d(TAG, "Read receipt received for message $messageID from $recipientPeerID")
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        Log.d(TAG, "Verify challenge from $peerID")
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        Log.d(TAG, "Verify response from $peerID")
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return null
    }

    override fun getNickname(): String? {
        return identityManager.getCurrentIdentity()?.displayName
    }

    override fun isFavorite(peerID: String): Boolean {
        return false
    }

    /**
     * Builds the peer list from two sources:
     *  - **Direct peers** we hold a BLE link to, reported by the transport (hops = 1).
     *  - **Multi-hop peers** learned from bitchat's announcement gossip, with the hop
     *    count taken from the shortest path through [MeshGraphService]'s graph.
     *
     * Anything beyond [RangePolicy.MAX_HOPS] is dropped, so the list stays bounded to
     * the incident area rather than growing with the whole mesh.
     *
     * Note the graph's reach is itself limited by how far announcements propagate, so
     * observed hop counts will not exceed the announcement TTL regardless of the policy
     * ceiling.
     */
    private fun updatePeerList() {
        val service = meshService ?: return
        val nicknames = service.getPeerNicknames()
        val rssiMap = service.getPeerRSSI()

        val direct = nicknames.map { (peerId, nickname) ->
            val peerInfo = service.getPeerInfo(peerId)
            Peer(
                peerId = peerId,
                name = nickname.ifEmpty { "Peer ${peerId.take(4)}" },
                deviceModel = peerInfo?.deviceModel.orEmpty(),
                hops = 1,
                lastSeen = peerInfo?.lastSeen ?: System.currentTimeMillis(),
                rssi = rssiMap[peerId]
            )
        }

        val directIds = direct.map { it.peerId }.toSet()
        val myId = service.myPeerID

        val remote = try {
            MeshGraphService.getInstance().graphState.value.nodes
                .asSequence()
                .filter { it.peerID != myId && it.peerID !in directIds }
                .mapNotNull { node ->
                    // shortestPath includes both endpoints, so hops = edges = size - 1.
                    val path = RoutePlanner.shortestPath(myId, node.peerID) ?: return@mapNotNull null
                    val hops = path.size - 1
                    if (hops <= 1 || hops > RangePolicy.MAX_HOPS) return@mapNotNull null
                    Peer(
                        peerId = node.peerID,
                        name = node.nickname?.takeIf { it.isNotBlank() }
                            ?: "Peer ${node.peerID.take(4)}",
                        deviceModel = "",
                        hops = hops,
                        lastSeen = System.currentTimeMillis(),
                        rssi = null
                    )
                }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Mesh graph unavailable, showing direct peers only: ${e.message}")
            emptyList()
        }

        val all = (direct + remote).sortedWith(compareBy({ it.hops }, { it.name.lowercase() }))
        if (remote.isNotEmpty()) {
            Log.d(TAG, "Peers: ${direct.size} direct, ${remote.size} multi-hop")
        }
        _connectedPeers.value = all
    }
}
