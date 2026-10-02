package com.echobharat.mesh.transport

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for "why is discovery not working", under one logcat tag.
 *
 * Discovery can fail at three points that look identical from the outside — the peer list is
 * empty either way — so each is recorded separately:
 *
 *  1. **Advertising never started.** `startAdvertising` is fire-and-forget; the only truth is
 *     the [AdvertiseCallback], and several guard conditions return before the call is even
 *     made. Both are recorded here, with the reason.
 *  2. **Nothing was heard.** Raw scan results are counted *before* any service-UUID filtering,
 *     so "the radio heard nothing" is distinguishable from "we heard things and threw them
 *     away".
 *  3. **The scan callback is silently dead.** Android can stop delivering results with no
 *     `onScanFailed` at all. The heartbeat prints the scanner's believed state next to the
 *     time since the last raw result, which is what exposes that case.
 *
 * Filter logcat on the tag [TAG] to get all three in one stream.
 */
object BleDiagnostics {

    const val TAG = "EchoBharatBLE"

    private const val HEARTBEAT_INTERVAL_MS = 10_000L

    enum class AdvertiseState { IDLE, BLOCKED, STARTING, ADVERTISING, FAILED }
    enum class ScanState { IDLE, BLOCKED, STARTING, SCANNING, STOPPED, FAILED }

    data class Snapshot(
        val advertiseState: AdvertiseState = AdvertiseState.IDLE,
        val advertiseDetail: String = "not started",
        val scanState: ScanState = ScanState.IDLE,
        val scanDetail: String = "not started",
        val rawResultsTotal: Long = 0,
        val matchedResultsTotal: Long = 0,
        val lastRawResultAt: Long = 0,
        val lastMatchedResultAt: Long = 0
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val rawResults = AtomicLong(0)
    private val matchedResults = AtomicLong(0)
    private val rawSinceHeartbeat = AtomicLong(0)
    private val matchedSinceHeartbeat = AtomicLong(0)

    @Volatile private var loggedFirstRawResult = false
    @Volatile private var heartbeatJob: Job? = null

    // ---------------------------------------------------------------- advertising

    /** A guard condition stopped us before `startAdvertising` was ever called. */
    fun advertiseBlocked(reason: String) {
        _snapshot.value = _snapshot.value.copy(
            advertiseState = AdvertiseState.BLOCKED,
            advertiseDetail = reason
        )
        Log.w(TAG, "ADVERTISE blocked before start: $reason")
    }

    /** `startAdvertising` was called. This is not yet success — the callback decides that. */
    fun advertiseRequested(serviceUuid: Any, mode: String) {
        _snapshot.value = _snapshot.value.copy(
            advertiseState = AdvertiseState.STARTING,
            advertiseDetail = "awaiting callback"
        )
        Log.i(TAG, "ADVERTISE startAdvertising() called (mode=$mode, serviceUuid=$serviceUuid) — awaiting callback")
    }

    /** The real [AdvertiseCallback.onStartSuccess]. */
    fun advertiseStarted(mode: String) {
        _snapshot.value = _snapshot.value.copy(
            advertiseState = AdvertiseState.ADVERTISING,
            advertiseDetail = "onStartSuccess (mode=$mode)"
        )
        Log.i(TAG, "ADVERTISE onStartSuccess — this phone is now discoverable (mode=$mode)")
    }

    /** The real [AdvertiseCallback.onStartFailure]. */
    fun advertiseFailed(errorCode: Int) {
        val name = advertiseErrorName(errorCode)
        _snapshot.value = _snapshot.value.copy(
            advertiseState = AdvertiseState.FAILED,
            advertiseDetail = "onStartFailure $name"
        )
        Log.e(TAG, "ADVERTISE onStartFailure: $name ($errorCode) — this phone is NOT discoverable")
    }

    fun advertiseStopped(reason: String) {
        _snapshot.value = _snapshot.value.copy(
            advertiseState = AdvertiseState.IDLE,
            advertiseDetail = "stopped: $reason"
        )
        Log.i(TAG, "ADVERTISE stopped ($reason)")
    }

    // ---------------------------------------------------------------- scanning

    /** A guard condition stopped us before `startScan` was ever called. */
    fun scanBlocked(reason: String) {
        _snapshot.value = _snapshot.value.copy(
            scanState = ScanState.BLOCKED,
            scanDetail = reason
        )
        Log.w(TAG, "SCAN blocked before start: $reason")
    }

    /** `startScan` was called with a [ScanCallback] registered. */
    fun scanRequested(filtered: Boolean, scanMode: String) {
        _snapshot.value = _snapshot.value.copy(
            scanState = ScanState.SCANNING,
            scanDetail = "callback registered (mode=$scanMode, filters=${if (filtered) "yes" else "none"})"
        )
        loggedFirstRawResult = false
        Log.i(
            TAG,
            "SCAN startScan() called, callback registered (mode=$scanMode, " +
                "filters=${if (filtered) "yes" else "none — filtering in handleScanResult"})"
        )
    }

    fun scanStopped(reason: String) {
        _snapshot.value = _snapshot.value.copy(
            scanState = ScanState.STOPPED,
            scanDetail = "stopped: $reason"
        )
        Log.i(TAG, "SCAN stopped ($reason)")
    }

    /** The real [ScanCallback.onScanFailed]. */
    fun scanFailed(errorCode: Int) {
        val name = scanErrorName(errorCode)
        _snapshot.value = _snapshot.value.copy(
            scanState = ScanState.FAILED,
            scanDetail = "onScanFailed $name"
        )
        Log.e(TAG, "SCAN onScanFailed: $name ($errorCode) — no results will arrive")
    }

    fun scanStartThrew(message: String?) {
        _snapshot.value = _snapshot.value.copy(
            scanState = ScanState.FAILED,
            scanDetail = "startScan threw: $message"
        )
        Log.e(TAG, "SCAN startScan() threw: $message")
    }

    /**
     * Every scan result, logged *before* any service-UUID filtering. This is the line that
     * separates "nothing nearby was heard" from "something was heard and our filter dropped it".
     */
    fun rawScanResult(address: String, name: String?, rssi: Int, advertisedUuids: String, matched: Boolean) {
        val now = System.currentTimeMillis()
        rawResults.incrementAndGet()
        rawSinceHeartbeat.incrementAndGet()
        if (matched) {
            matchedResults.incrementAndGet()
            matchedSinceHeartbeat.incrementAndGet()
        }

        _snapshot.value = _snapshot.value.copy(
            rawResultsTotal = rawResults.get(),
            matchedResultsTotal = matchedResults.get(),
            lastRawResultAt = now,
            lastMatchedResultAt = if (matched) now else _snapshot.value.lastMatchedResultAt
        )

        // The first result after a (re)start proves the callback is live; after that the
        // per-result detail is verbose-only and the heartbeat carries the counts, so a busy
        // room does not bury the lines that matter.
        if (!loggedFirstRawResult) {
            loggedFirstRawResult = true
            Log.i(
                TAG,
                "SCAN first raw result since start — callback IS alive: $address " +
                    "rssi=$rssi name=${name ?: "-"} uuids=$advertisedUuids matchesOurService=$matched"
            )
        } else {
            Log.v(
                TAG,
                "SCAN raw: $address rssi=$rssi name=${name ?: "-"} uuids=$advertisedUuids matchesOurService=$matched"
            )
        }
    }

    // ---------------------------------------------------------------- heartbeat

    /**
     * Periodic one-line health report. Idempotent: repeated calls keep the single existing job.
     */
    fun startHeartbeat(scope: CoroutineScope, context: Context) {
        if (heartbeatJob?.isActive == true) return
        val appContext = context.applicationContext
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    logHeartbeat(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat error: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Diagnostics heartbeat started (every ${HEARTBEAT_INTERVAL_MS / 1000}s)")
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun logHeartbeat(context: Context) {
        val s = _snapshot.value
        val raw = rawSinceHeartbeat.getAndSet(0)
        val matched = matchedSinceHeartbeat.getAndSet(0)
        val now = System.currentTimeMillis()
        val sinceRaw = if (s.lastRawResultAt == 0L) "never" else "${(now - s.lastRawResultAt) / 1000}s"
        val sinceMatched = if (s.lastMatchedResultAt == 0L) "never" else "${(now - s.lastMatchedResultAt) / 1000}s"
        val readiness = BleReadiness.check(context)

        Log.i(
            TAG,
            "HEARTBEAT advertise=${s.advertiseState}(${s.advertiseDetail}) " +
                "scan=${s.scanState}(${s.scanDetail}) " +
                "raw=${raw}/10s (total ${s.rawResultsTotal}, last $sinceRaw) " +
                "matched=${matched}/10s (total ${s.matchedResultsTotal}, last $sinceMatched) | " +
                readiness.summary()
        )

        // Name the conclusion rather than leaving it to be inferred from the counters.
        when {
            !readiness.canScan ->
                Log.e(TAG, "DIAGNOSIS: scanning cannot return results — ${readiness.blockers.joinToString { it.name }}")
            s.scanState == ScanState.FAILED || s.scanState == ScanState.BLOCKED ->
                Log.e(TAG, "DIAGNOSIS: scanner is not running (${s.scanDetail})")
            s.scanState == ScanState.SCANNING && raw == 0L ->
                Log.w(TAG, "DIAGNOSIS: scanner believes it is running but heard NOTHING at all in the last 10s — radio, Location toggle, or a silently dead callback")
            raw > 0L && matched == 0L ->
                Log.w(TAG, "DIAGNOSIS: heard $raw advertisements but none carried our service UUID — peers are not advertising it, or are not advertising at all")
            s.advertiseState != AdvertiseState.ADVERTISING ->
                Log.w(TAG, "DIAGNOSIS: scanning is healthy but this phone is not advertising (${s.advertiseDetail}) — peers cannot find us")
        }
    }

    // ---------------------------------------------------------------- decoding

    private fun advertiseErrorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        else -> "UNKNOWN"
    }

    private fun scanErrorName(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        5 -> "OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCANNING_TOO_FREQUENTLY"
        else -> "UNKNOWN"
    }
}
