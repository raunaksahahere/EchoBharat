package com.echobharat.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Best-effort device position for distress announcements.
 *
 * Deliberately built on the platform [LocationManager] rather than Play Services: the app
 * must work on devices with no Google services, and a distress feature cannot depend on a
 * proprietary component (Rules §2).
 *
 * Every call can return null, and callers must treat that as normal — GPS is unavailable
 * exactly where this app matters most (indoors, underground, collapsed structures). It
 * only ever enriches an announcement; it never gates one.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"

        /** A fix older than this is reported but flagged as stale. */
        private const val MAX_FIX_AGE_MS = 10 * 60 * 1000L
    }

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Most recent usable fix across providers, or null when there is none.
     *
     * Reads cached fixes only — it never blocks waiting for a satellite lock, because a
     * distress message must go out immediately whether or not a position is available.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) {
            Log.i(TAG, "No location permission; announcements will carry no coordinates")
            return null
        }
        val mgr = manager ?: return null

        val providers = try {
            mgr.getProviders(true)
        } catch (e: Exception) {
            Log.e(TAG, "GET_PROVIDERS_FAILED: ${e.message}")
            return null
        }

        var best: Location? = null
        for (provider in providers) {
            val fix = try {
                mgr.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                Log.e(TAG, "LAST_KNOWN_FAILED[$provider]: ${e.message}")
                null
            } ?: continue

            // Prefer the more accurate fix, breaking ties on recency.
            if (best == null ||
                fix.accuracy < best!!.accuracy ||
                (fix.accuracy == best!!.accuracy && fix.time > best!!.time)
            ) {
                best = fix
            }
        }

        if (best != null) {
            val ageMs = System.currentTimeMillis() - best!!.time
            Log.i(
                TAG,
                "Fix from ${best!!.provider}: ±${best!!.accuracy}m, ${ageMs / 1000}s old" +
                    if (ageMs > MAX_FIX_AGE_MS) " (STALE)" else ""
            )
        } else {
            Log.i(TAG, "No cached fix available")
        }
        return best
    }

    /**
     * Asks the radios for a position right now instead of reading whatever was cached.
     *
     * [lastKnown] is empty on a phone that has not had a fix recently, which is exactly the
     * phone in the field, so the SOS carried no coordinates and the distance on the other
     * side read "no position". GPS is tried first, then the network provider; null only when
     * neither answers in time or location is off.
     */
    @SuppressLint("MissingPermission")
    suspend fun fresh(timeoutMs: Long = 15_000L): Location? {
        if (!hasPermission()) return null
        val mgr = manager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { mgr.isProviderEnabled(it) }.getOrDefault(false) }
        for ((i, provider) in providers.withIndex()) {
            // GPS gets most of the budget; the network fallback only needs a moment.
            val budget = when {
                providers.size == 1 -> timeoutMs
                i == 0 -> timeoutMs * 2 / 3
                else -> timeoutMs - timeoutMs * 2 / 3
            }
            val fix = withTimeoutOrNull(budget) { singleFix(mgr, provider) }
            if (fix != null) {
                Log.i(TAG, "Fresh fix from $provider: ±${fix.accuracy}m")
                return fix
            }
        }
        Log.i(TAG, "No fresh fix within ${timeoutMs}ms")
        return null
    }

    /** A fresh fix, or the cached one when the radios stay silent. */
    suspend fun best(maxAgeMs: Long = 2 * 60 * 1000L): Location? {
        val cached = lastKnown()
        if (cached != null && System.currentTimeMillis() - cached.time <= maxAgeMs) return cached
        return fresh() ?: cached
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun singleFix(mgr: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    mgr.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { fix ->
                        if (cont.isActive) cont.resume(fix)
                    }
                } else {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching { mgr.removeUpdates(this) }
                            if (cont.isActive) cont.resume(location)
                        }

                        // These were abstract on API 26–29. Relying on the newer default
                        // methods can crash an older phone when GPS is switched off.
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) {
                            runCatching { mgr.removeUpdates(this) }
                            if (cont.isActive) cont.resume(null)
                        }
                        @Deprecated("Deprecated in Android")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    }
                    cont.invokeOnCancellation { runCatching { mgr.removeUpdates(listener) } }
                    mgr.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.e(TAG, "SINGLE_FIX_FAILED[$provider]: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /** True when the device has location switched on at all. */
    fun isLocationEnabled(): Boolean = try {
        when {
            manager == null -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> manager.isLocationEnabled
            else -> manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (e: Exception) {
        false
    }
}
