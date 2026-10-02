package com.echobharat.mesh.transport

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Everything that has to be true before a BLE scan can return a single result, in one place.
 *
 * A granted permission set is only half the story. On Android 12+ this app declares
 * BLUETOOTH_SCAN *without* `usesPermissionFlags="neverForLocation"`, so the platform treats
 * every scan as location-deriving: it delivers zero results unless location permission is
 * granted *and* the system Location toggle is on. On Android 11 and below the same is true via
 * ACCESS_FINE_LOCATION. Neither case produces a callback — `startScan` succeeds, `onScanFailed`
 * is never called, and nothing ever arrives. That is indistinguishable from "no peers nearby"
 * unless something checks explicitly, which is what this does.
 */
enum class BleBlocker(val title: String, val detail: String) {
    NO_BLE_HARDWARE(
        "Bluetooth LE unavailable",
        "This device reports no Bluetooth LE adapter, so the mesh cannot run."
    ),
    BLUETOOTH_OFF(
        "Bluetooth is off",
        "Turn Bluetooth on so this phone can find and be found by nearby nodes."
    ),
    MISSING_SCAN_PERMISSION(
        "Nearby devices permission missing",
        "Without it Android silently returns no scan results — the app cannot see other phones."
    ),
    MISSING_ADVERTISE_PERMISSION(
        "Advertise permission missing",
        "Without it this phone stays invisible to every other node."
    ),
    MISSING_CONNECT_PERMISSION(
        "Bluetooth connect permission missing",
        "Peers can be seen but never connected to."
    ),
    MISSING_LOCATION_PERMISSION(
        "Location permission missing",
        "Android requires it for Bluetooth scanning on this version. Scans return nothing without it."
    ),
    LOCATION_SERVICES_OFF(
        "Location services are off",
        "Android returns no Bluetooth scan results while the system Location toggle is off, " +
            "however many phones are next to you."
    )
}

/**
 * Snapshot of discovery preconditions. [blockers] is ordered most-fundamental first, so the UI
 * can show the first entry and be showing the thing worth fixing.
 */
data class BleReadinessReport(
    val blockers: List<BleBlocker>,
    val locationRequiredForScan: Boolean,
    val sdkInt: Int
) {
    val isReady: Boolean get() = blockers.isEmpty()

    val canScan: Boolean
        get() = blockers.none {
            it == BleBlocker.NO_BLE_HARDWARE ||
                it == BleBlocker.BLUETOOTH_OFF ||
                it == BleBlocker.MISSING_SCAN_PERMISSION ||
                it == BleBlocker.MISSING_LOCATION_PERMISSION ||
                it == BleBlocker.LOCATION_SERVICES_OFF
        }

    val canAdvertise: Boolean
        get() = blockers.none {
            it == BleBlocker.NO_BLE_HARDWARE ||
                it == BleBlocker.BLUETOOTH_OFF ||
                it == BleBlocker.MISSING_ADVERTISE_PERMISSION
        }

    /** One-line form for logcat. */
    fun summary(): String = buildString {
        append("api=$sdkInt locationRequiredForScan=$locationRequiredForScan ")
        append("canScan=$canScan canAdvertise=$canAdvertise ")
        append(if (blockers.isEmpty()) "blockers=none" else "blockers=${blockers.joinToString(",") { it.name }}")
    }
}

object BleReadiness {

    fun check(context: Context): BleReadinessReport {
        val blockers = mutableListOf<BleBlocker>()
        val locationRequired = isLocationRequiredForScan(context)

        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: Exception) {
            null
        }

        if (adapter == null) {
            blockers.add(BleBlocker.NO_BLE_HARDWARE)
        } else if (!adapter.isEnabled) {
            blockers.add(BleBlocker.BLUETOOTH_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(context, Manifest.permission.BLUETOOTH_SCAN)) {
                blockers.add(BleBlocker.MISSING_SCAN_PERMISSION)
            }
            if (!granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)) {
                blockers.add(BleBlocker.MISSING_ADVERTISE_PERMISSION)
            }
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                blockers.add(BleBlocker.MISSING_CONNECT_PERMISSION)
            }
        }

        if (locationRequired && !hasLocationPermission(context)) {
            blockers.add(BleBlocker.MISSING_LOCATION_PERMISSION)
        }
        if (locationRequired && !isLocationServicesEnabled(context)) {
            blockers.add(BleBlocker.LOCATION_SERVICES_OFF)
        }

        return BleReadinessReport(
            blockers = blockers,
            locationRequiredForScan = locationRequired,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    /**
     * Below API 31 location is always required for BLE scanning. At API 31+ it is required
     * unless BLUETOOTH_SCAN was declared with `neverForLocation`, so this reads the actual
     * manifest flag rather than assuming — if that flag is added later, this check follows it
     * instead of nagging the user about a permission that is no longer needed.
     */
    fun isLocationRequiredForScan(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return !hasDisavowedLocationForScan(context)
    }

    private fun hasDisavowedLocationForScan(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info: PackageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            val names = info.requestedPermissions ?: return false
            val flags = info.requestedPermissionsFlags ?: return false
            val index = names.indexOf(Manifest.permission.BLUETOOTH_SCAN)
            if (index < 0 || index >= flags.size) return false
            (flags[index] and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0
        } catch (_: Exception) {
            false
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** The system-wide Location toggle, which is separate from the app's own permission. */
    fun isLocationServicesEnabled(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        when {
            manager == null -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> manager.isLocationEnabled
            else -> manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Exception) {
        false
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
