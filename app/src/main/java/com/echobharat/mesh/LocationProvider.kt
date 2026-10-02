package com.echobharat.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
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
