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
        /** Peer IDs already told about it, so re-announcement targets only new ones. */
        val deliveredTo: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var lastAnnouncedAt: Long = System.currentTimeMillis()
    ) {
        val msgId: String get() = message.msgId
    }

    /** Our own cancelled announcement, kept so late joiners still hear that it is over. */
    private class Cancelled(val original: EchoBharatMessage) {
        val deliveredTo: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var lastAnnouncedAt: Long = System.currentTimeMillis()
    }

    private val cancelled = ConcurrentHashMap<String, Cancelled>()

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

    fun start() {
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

        val message = mesh.sendSos(
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

        val entry = Active(message = message, isMine = true)
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
                val updated = entry.message.copy(
                    lat = fix.latitude,
                    lon = fix.longitude,
                    gpsAccuracyM = fix.accuracy
                )
                active[msgId] = entry.copy(message = updated)
                publish()
                mesh.rebroadcastSos(updated)
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

        mesh.sendSosResolved(entry.message)
        active.remove(msgId)
        resolvedBy[msgId to entry.message.senderId] = System.currentTimeMillis() + LIFETIME_MS + MAX_CLOCK_SKEW_MS
        cancelled[msgId] = Cancelled(entry.message).also { it.deliveredTo.addAll(entry.deliveredTo) }
        publish()
        Log.i(TAG, "SOS resolved by sender: $msgId")

        // One cancellation is one packet over a lossy mesh. Repeat it, or a relay that missed
        // it keeps telling newcomers about an emergency that is over.
        scope.launch {
            for ((index, waitMs) in RESOLVE_REPEAT_DELAYS_MS.withIndex()) {
                delay(waitMs - if (index == 0) 0L else RESOLVE_REPEAT_DELAYS_MS[index - 1])
                mesh.sendSosResolved(entry.message)
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
            val sent = mesh.rebroadcastSos(entry.message)
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
            if (mesh.sendSosResolved(c.original)) {
                c.deliveredTo.addAll(unseen)
                c.lastAnnouncedAt = now
            }
        }
    }

    private fun publish() {
        _announcements.value = active.values.sortedByDescending { it.message.ts }
    }
}
