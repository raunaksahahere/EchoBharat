package com.echobharat.mesh

/**
 * Pure state machine for origin-authenticated SOS events. Verification is deliberately the
 * first operation in [accept], before event lookup or tombstone mutation.
 */
class SosEventLedger {
    data class EventKey(val originFingerprint: String, val eventId: String)
    data class Stored(val envelope: SosEnvelope, val cancelled: Boolean = false)
    data class Snapshot(val records: List<Stored>, val tombstones: Map<EventKey, Long>)

    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    private val events = LinkedHashMap<EventKey, Stored>()
    private val tombstones = LinkedHashMap<EventKey, Long>()

    @Synchronized
    fun accept(
        envelope: SosEnvelope,
        now: Long,
        expectedKeys: (SosEnvelope) -> Pair<ByteArray, ByteArray>?
    ): Result {
        val expected = expectedKeys(envelope) ?: return Result.REJECTED
        if (!SosEnvelope.verify(envelope, expected.first, expected.second, now)) return Result.REJECTED

        val key = keyOf(envelope)
        val tombstoneExpiry = tombstones[key]
        val current = events[key]
        if (tombstoneExpiry != null && envelope.action == SosEnvelope.Action.RAISE) return Result.REJECTED
        val currentRevision = current?.envelope?.revision ?: -1L
        if (envelope.revision <= currentRevision) return Result.DUPLICATE

        return when (envelope.action) {
            SosEnvelope.Action.RAISE -> {
                if (tombstoneExpiry != null) Result.REJECTED
                else if (current != null) Result.REJECTED
                else {
                    events[key] = Stored(envelope)
                    Result.ACCEPTED
                }
            }
            SosEnvelope.Action.UPDATE -> {
                if (current == null || current.cancelled || !sameOriginAndLifetime(current.envelope, envelope)) {
                    Result.REJECTED
                } else {
                    events[key] = Stored(envelope)
                    Result.ACCEPTED
                }
            }
            SosEnvelope.Action.CANCEL -> {
                if (current != null && !sameOriginAndLifetime(current.envelope, envelope)) {
                    Result.REJECTED
                } else {
                    tombstones[key] = envelope.expiresAt + SosEnvelope.MAX_CLOCK_SKEW_MS
                    events[key] = Stored(envelope, cancelled = true)
                    Result.ACCEPTED
                }
            }
        }
    }

    @Synchronized
    fun snapshot(now: Long): Snapshot {
        events.entries.removeIf { (_, stored) -> stored.envelope.expiresAt <= now }
        tombstones.entries.removeIf { (_, expiry) -> expiry <= now }
        return Snapshot(
            records = events.values.filter { it.envelope.expiresAt > now },
            tombstones = tombstones.toMap()
        )
    }

    @Synchronized
    fun restore(snapshot: Snapshot, now: Long) {
        events.clear()
        tombstones.clear()
        snapshot.tombstones.filterValues { it > now }.forEach { (key, expiry) -> tombstones[key] = expiry }
        snapshot.records.filter { it.envelope.expiresAt > now }.forEach { stored ->
            events[keyOf(stored.envelope)] = stored
        }
    }

    @Synchronized
    fun containsActive(envelope: SosEnvelope): Boolean =
        events[keyOf(envelope)]?.let { !it.cancelled } == true

    private fun keyOf(envelope: SosEnvelope): EventKey = EventKey(envelope.originFingerprint, envelope.eventId.toHex())

    private fun sameOriginAndLifetime(a: SosEnvelope, b: SosEnvelope): Boolean =
        a.originSigningKey.contentEquals(b.originSigningKey) &&
            a.originNoiseKey.contentEquals(b.originNoiseKey) &&
            a.createdAt == b.createdAt && a.expiresAt == b.expiresAt

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
