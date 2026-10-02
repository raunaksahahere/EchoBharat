package com.echobharat.mesh

import android.location.Location
import android.util.Log

/**
 * Decides how far a message or a peer is allowed to be considered "in range".
 *
 * Hybrid by design (see docs/PRD.md §Range):
 *  - **Hop count is the floor and always applies.** It works with no GPS at all, which
 *    is the case that matters most — collapsed buildings, basements, indoor structures.
 *  - **GPS only tightens the boundary**, never widens it, and only when the receiver has
 *    a live fix *and* the message carried origin coordinates. A missing fix silently
 *    falls back to hop count rather than dropping traffic.
 *
 * Hop count is an approximation of distance, not a guaranteed kilometre figure: a BLE
 * hop can be 10 m indoors or 100 m across open ground. Anything user-facing should say
 * "about N hops away", not a distance.
 */
object RangePolicy {

    private const val TAG = "RangePolicy"

    /** Maximum hops at which a peer or message is still considered in range. */
    const val MAX_HOPS = 20

    /**
     * Distance ceiling used when GPS is available on both ends. Generous on purpose —
     * it exists to stop a message crossing a whole state via a chain of relays, not to
     * draw a tight perimeter.
     */
    const val MAX_DISTANCE_METERS = 5_000.0

    /** A coordinate pair carried by a message, if the sender had a fix. */
    data class Origin(val lat: Double, val lon: Double)

    sealed interface Verdict {
        data object InRange : Verdict
        data class OutOfRange(val reason: String) : Verdict
    }

    /**
     * @param hops hops travelled from the origin, or null when unknown (treated as in
     *   range on the hop axis — we do not drop traffic just because we cannot count).
     * @param origin sender coordinates from the payload, if any.
     * @param here the receiver's current fix, if any.
     */
    fun evaluate(hops: Int?, origin: Origin?, here: Location?): Verdict {
        if (hops != null && hops > MAX_HOPS) {
            return Verdict.OutOfRange("$hops hops exceeds $MAX_HOPS")
        }

        // GPS can only narrow the boundary, and only when both ends have a position.
        if (origin != null && here != null) {
            val metres = distanceMeters(origin.lat, origin.lon, here.latitude, here.longitude)
            if (metres > MAX_DISTANCE_METERS) {
                return Verdict.OutOfRange("${metres.toInt()} m exceeds ${MAX_DISTANCE_METERS.toInt()} m")
            }
        }

        return Verdict.InRange
    }

    fun isInRange(hops: Int?, origin: Origin?, here: Location?): Boolean =
        evaluate(hops, origin, here) is Verdict.InRange

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Human-readable distance for the announcement detail view. */
    fun formatDistance(metres: Double): String = when {
        metres < 1_000 -> "${metres.toInt()} m"
        else -> String.format("%.1f km", metres / 1_000)
    }

    fun logDrop(what: String, verdict: Verdict.OutOfRange) {
        Log.i(TAG, "Out of range, dropping $what: ${verdict.reason}")
    }
}
