package com.echobharat.mesh

import android.content.Context
import android.util.Log
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle of distress announcements (docs/PRD.md §SOS).
 *
 * An announcement is a broadcast with no recipient. Every phone that holds a live one
 * keeps re-announcing it to peers it has newly met, so someone who walks into range an
 * hour later still learns about it — this is what makes the mesh useful for search and
 * rescue rather than only for people already connected.
 *
 * Two costs are deliberately bounded:
 *  - **Time:** an announcement stops propagating [LIFETIME_MS] after it was created, by
 *    wall clock at the origin, so a stale distress call cannot circulate forever.
 *  - **Airtime:** re-announcement is on a [REANNOUNCE_INTERVAL_MS] tick and only fires
 *    when there are peers that have not already been told. Continuous rebroadcast would
 *    scale badly on a phone relaying several announcements at once.
 */
class SosManager(
    context: Context,
    private val mesh: EchoBharatMeshManager
) {

    companion object {
        private const val TAG = "SosManager"

        /** How long an announcement keeps propagating after creation. */
        const val LIFETIME_MS = 60 * 60 * 1000L // 1 hour

        /** Small sender clock skew accepted by the payload codec. */
        internal const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

        /** Cadence of re-announcement to newly-met peers. */
        const val REANNOUNCE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        /** A cached fix newer and tighter than this is used as is; anything else gets refreshed. */
        private const val GOOD_FIX_AGE_MS = 2 * 60 * 1000L
        private const val GOOD_FIX_ACCURACY_M = 100f

        /** Extra cancellation broadcasts after the first, in ms after it. */
        private val RESOLVE_REPEAT_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

        /** How often expiry is swept. */
        private const val SWEEP_INTERVAL_MS = 30 * 1000L
    }

    /** A distress announcement this device is currently holding and relaying. */
    data class Active(
        val message: EchoBharatMessage,
        /** True when this device raised it, which is what allows cancelling. */
        val isMine: Boolean,
        /** Non-null only for an origin-authenticated v2 event. */
        val envelope: SosEnvelope? = null,
        /** Peer IDs already told about it, so re-announcement targets only new ones. */
        val deliveredTo: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var lastAnnouncedAt: Long = System.currentTimeMillis()
    ) {
        val msgId: String get() = message.msgId
    }

    /** Our own cancelled announcement, kept so late joiners still hear that it is over. */
    private class Cancelled(val original: EchoBharatMessage, val envelope: SosEnvelope? = null) {
        val deliveredTo: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var lastAnnouncedAt: Long = System.currentTimeMillis()
    }

    private val cancelled = ConcurrentHashMap<String, Cancelled>()
    private val v2Ledger = SosEventLedger()
    private val statePrefs = lazy { context.getSharedPreferences("sos_v2_state", Context.MODE_PRIVATE) }
    private var ledgerLoaded = false

    private val location = LocationProvider(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val active = ConcurrentHashMap<String, Active>()
    private val _announcements = MutableStateFlow<List<Active>>(emptyList())
    val announcements: StateFlow<List<Active>> = _announcements.asStateFlow()

    /**
     * Cancellations are namespaced by their authenticated sender. A foreign cancellation
     * must never overwrite the originator's tombstone, including before the SOS arrives.
     */
    private val resolvedBy = ConcurrentHashMap<Pair<String, String>, Long>()

    private var ticker: Job? = null

    private fun ensureLedgerLoaded() {
        if (ledgerLoaded) return
        val records = statePrefs.value.getStringSet("records", emptySet()).orEmpty().mapNotNull { encoded ->
            val parts = encoded.split('|', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val wire = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrNull() ?: return@mapNotNull null
            val envelope = SosEnvelope.decodeWire(wire) ?: return@mapNotNull null
            SosEventLedger.Stored(envelope, parts[0] == "C")
        }
        v2Ledger.restore(SosEventLedger.Snapshot(records, emptyMap()), System.currentTimeMillis())
        records.filter { !it.cancelled && it.envelope.expiresAt > System.currentTimeMillis() }.forEach { stored ->
            active[stored.envelope.eventId.joinToString("") { "%02x".format(it) }] = Active(
                stored.envelope.toMessage(), isMine = stored.envelope.originPeerId == mesh.myPeerId,
                envelope = stored.envelope
            )
        }
        ledgerLoaded = true
        publish()
    }

    private fun persistLedger() {
        val values = v2Ledger.snapshot(System.currentTimeMillis()).records.mapNotNull { stored ->
            val wire = SosEnvelope.encodeWire(stored.envelope) ?: return@mapNotNull null
            (if (stored.cancelled) "C" else "A") + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
        }.toSet()
        statePrefs.value.edit().putStringSet("records", values).apply()
    }

    fun start() {
        ensureLedgerLoaded()
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS)
                sweepExpired()
                reannounceToNewPeers()
            }
        }
        Log.i(TAG, "SOS manager started")
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Raises a distress announcement from this device.
     *
     * Coordinates are attached when a fix is already cached; the announcement goes out
     * regardless, because waiting for GPS in an emergency is the wrong trade.
     */
    @Synchronized
    fun raise(text: String, srcLang: String): EchoBharatMessage? {
        val fix = location.lastKnown()
        val now = System.currentTimeMillis()
        val fixIsGood = fix != null && now - fix.time <= GOOD_FIX_AGE_MS && fix.accuracy <= GOOD_FIX_ACCURACY_M

        ensureLedgerLoaded()
        val envelope = mesh.sendSosV2(
            text = text,
            srcLang = srcLang,
            lat = fix?.latitude,
            lon = fix?.longitude,
            accuracy = fix?.accuracy,
            expiresAt = now + LIFETIME_MS
        ) ?: run {
            Log.e(TAG, "SOS_SEND_FAILED")
            return null
        }
        val message = envelope.toMessage()

        v2Ledger.accept(envelope, now) { envelope.originSigningKey to envelope.originNoiseKey }
        persistLedger()
        val entry = Active(message = message, isMine = true, envelope = envelope)
        entry.deliveredTo.addAll(mesh.connectedPeers.value.map { it.peerId })
        active[message.msgId] = entry
        publish()

        // Never wait for GPS before sending, but do not settle for a missing or stale position
        // either: get a real fix in the background and re-announce the same SOS with it.
        if (!fixIsGood) upgradeWithFreshFix(message.msgId)

        Log.i(
            TAG,
            "SOS raised: ${message.msgId} " +
                (if (message.hasLocation) "with location" else "without location") +
                ", expires in ${LIFETIME_MS / 60000} min"
        )
        return message
    }

    private fun upgradeWithFreshFix(msgId: String) {
        scope.launch {
            val fix = location.fresh() ?: return@launch
            synchronized(this@SosManager) {
                val entry = active[msgId] ?: return@launch // cancelled while we waited
                val updated = entry.envelope?.let {
                    mesh.updateSosV2(it, it.revision + 1, fix.latitude, fix.longitude, fix.accuracy)
                }
                if (updated != null) {
                    if (v2Ledger.accept(updated, System.currentTimeMillis()) { updated.originSigningKey to updated.originNoiseKey } == SosEventLedger.Result.ACCEPTED) {
                        persistLedger()
                        active[msgId] = entry.copy(message = updated.toMessage(), envelope = updated)
                        publish()
                    }
                } else if (entry.envelope == null) {
                    val legacy = entry.message.copy(
                        lat = fix.latitude,
                        lon = fix.longitude,
                        gpsAccuracyM = fix.accuracy
                    )
                    active[msgId] = entry.copy(message = legacy)
                    publish()
                    mesh.rebroadcastSos(legacy)
                }
                Log.i(TAG, "SOS $msgId re-announced with a fresh fix (±${fix.accuracy.toInt()} m)")
            }
        }
    }

    /**
     * Stops propagation of one of our own announcements early and tells the mesh.
     */
    @Synchronized
    fun resolve(msgId: String): Boolean {
        val entry = active[msgId]
        if (entry == null) {
            Log.w(TAG, "resolve: no active announcement $msgId")
            return false
        }
        if (!entry.isMine) {
            Log.w(TAG, "resolve refused: $msgId was raised by another device")
            return false
        }

        val v2Cancel = entry.envelope?.let { mesh.sendSosResolvedV2(it, it.revision + 1) }
        if (v2Cancel != null) {
            v2Ledger.accept(v2Cancel, System.currentTimeMillis()) { v2Cancel.originSigningKey to v2Cancel.originNoiseKey }
            persistLedger()
        } else if (entry.envelope == null) {
            mesh.sendSosResolved(entry.message)
        }
        active.remove(msgId)
        resolvedBy[msgId to entry.message.senderId] = System.currentTimeMillis() + LIFETIME_MS + MAX_CLOCK_SKEW_MS
        cancelled[msgId] = Cancelled(entry.message, entry.envelope).also { it.deliveredTo.addAll(entry.deliveredTo) }
        publish()
        Log.i(TAG, "SOS resolved by sender: $msgId")

        // One cancellation is one packet over a lossy mesh. Repeat it, or a relay that missed
        // it keeps telling newcomers about an emergency that is over.
        scope.launch {
            for ((index, waitMs) in RESOLVE_REPEAT_DELAYS_MS.withIndex()) {
                delay(waitMs - if (index == 0) 0L else RESOLVE_REPEAT_DELAYS_MS[index - 1])
                if (entry.envelope != null) {
                    mesh.sendSosResolvedV2(entry.envelope, entry.envelope.revision + 1)
                } else {
                    mesh.sendSosResolved(entry.message)
                }
            }
        }
        return true
    }

    /** Handles a verified v2 envelope after transport and origin-key validation. */
    @Synchronized
    fun onReceived(envelope: SosEnvelope): Boolean {
        ensureLedgerLoaded()
        val result = v2Ledger.accept(envelope, System.currentTimeMillis()) { mesh.expectedSosKeys(envelope) }
        if (result != SosEventLedger.Result.ACCEPTED) return false
        persistLedger()
        val message = envelope.toMessage()
        when (envelope.action) {
            SosEnvelope.Action.CANCEL -> {
                if (active.remove(message.msgId) != null) publish()
            }
            SosEnvelope.Action.RAISE, SosEnvelope.Action.UPDATE -> {
                val held = active[message.msgId]
                if (held == null || envelope.revision > (held.envelope?.revision ?: -1L)) {
                    active[message.msgId] = Active(message, isMine = false, envelope = envelope)
                    publish()
                }
            }
        }
        return true
    }

    /**
     * Records an announcement received from the mesh so this device relays it onward.
     *
     * A cancellation is honoured only from the phone that raised the announcement.
     * [message].senderId for a resolve is the transport-authenticated sender, so a third
     * party cannot silence someone else's distress call by naming its id.
     */
    @Synchronized
    fun onReceived(message: EchoBharatMessage) {
        when (message.type) {
            MessageType.SOS_RESOLVED -> {
                val ref = message.refMsgId ?: return
                val held = active[ref]
                if (held?.envelope != null) {
                    Log.w(TAG, "Ignoring legacy cancellation for authenticated SOS $ref")
                    return
                }
                if (held != null && held.message.senderId != message.senderId) {
                    Log.w(
                        TAG,
                        "Ignoring cancellation of SOS $ref from ${message.senderId.take(8)}: " +
                            "only its sender ${held.message.senderId.take(8)} can resolve it"
                    )
                    return
                }
                resolvedBy[ref to message.senderId] = System.currentTimeMillis() + LIFETIME_MS + MAX_CLOCK_SKEW_MS
                if (active.remove(ref) != null) {
                    Log.i(TAG, "SOS $ref cancelled by its sender")
                    publish()
                }
            }

            MessageType.SOS -> {
                if ((resolvedBy[message.msgId to message.senderId] ?: 0L) > System.currentTimeMillis()) {
                    Log.i(TAG, "Ignoring already-resolved SOS ${message.msgId}")
                    return
                }
                if (message.isExpired()) {
                    Log.i(TAG, "Ignoring expired SOS ${message.msgId}")
                    return
                }
                val held = active[message.msgId]
                if (held?.envelope != null) {
                    Log.w(TAG, "Ignoring legacy SOS collision with authenticated event ${message.msgId}")
                    return
                }
                if (held != null) {
                    // The sender re-announces the same SOS once it has a GPS fix. Take the
                    // position when we had none or the new one is tighter, never from a relay
                    // claiming a different sender.
                    val better = message.hasLocation && held.message.senderId == message.senderId &&
                        (!held.message.hasLocation ||
                            (message.gpsAccuracyM ?: Float.MAX_VALUE) < (held.message.gpsAccuracyM ?: Float.MAX_VALUE))
                    if (better) {
                        active[message.msgId] = held.copy(message = message)
                        publish()
                        Log.i(TAG, "SOS ${message.msgId} updated with the sender's position")
                    }
                    return
                }

                active[message.msgId] = Active(message = message, isMine = false)
                publish()
                Log.i(
                    TAG,
                    "Holding SOS ${message.msgId} from ${message.senderName}, " +
                        "${message.remainingMillis() / 60000} min left"
                )
            }

            else -> Unit
        }
    }

    @Synchronized
    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        resolvedBy.entries.removeIf { it.value <= now }
        val expired = active.values.filter { it.message.isExpired() }
        if (expired.isEmpty()) return
        expired.forEach {
            active.remove(it.msgId)
            Log.i(TAG, "SOS ${it.msgId} expired after ${LIFETIME_MS / 60000} min")
        }
        publish()
    }

    /**
     * Re-broadcasts live announcements, but only when peers have appeared that were not
     * present last time, and at most once per [REANNOUNCE_INTERVAL_MS] per announcement.
     */
    @Synchronized
    private fun reannounceToNewPeers() {
        reannounceCancellations()
        if (active.isEmpty()) return

        val peers = mesh.connectedPeers.value.map { it.peerId }.toSet()
        if (peers.isEmpty()) return

        val now = System.currentTimeMillis()
        for (entry in active.values) {
            if (now - entry.lastAnnouncedAt < REANNOUNCE_INTERVAL_MS) continue

            val unseen = peers - entry.deliveredTo
            if (unseen.isEmpty()) continue

            // One broadcast reaches all of them; the mesh handles the relaying.
            val sent = entry.envelope?.let { mesh.rebroadcastSos(it) } ?: mesh.rebroadcastSos(entry.message)
            if (sent) {
                entry.deliveredTo.addAll(unseen)
                entry.lastAnnouncedAt = now
                Log.i(TAG, "Re-announced SOS ${entry.msgId} for ${unseen.size} new peer(s)")
            }
        }
    }

    /** Tells newly met peers that one of our SOS calls is over, until it would have expired. */
    private fun reannounceCancellations() {
        if (cancelled.isEmpty()) return
        val now = System.currentTimeMillis()
        cancelled.entries.removeIf { it.value.original.isExpired() }
        val peers = mesh.connectedPeers.value.map { it.peerId }.toSet()
        for (c in cancelled.values) {
            if (now - c.lastAnnouncedAt < REANNOUNCE_INTERVAL_MS) continue
            val unseen = peers - c.deliveredTo
            if (unseen.isEmpty()) continue
            val sent = if (c.envelope != null) {
                mesh.sendSosResolvedV2(c.envelope, c.envelope.revision + 1) != null
            } else {
                mesh.sendSosResolved(c.original)
            }
            if (sent) {
                c.deliveredTo.addAll(unseen)
                c.lastAnnouncedAt = now
            }
        }
    }

    private fun publish() {
        _announcements.value = active.values.sortedByDescending { it.message.ts }
    }
}
