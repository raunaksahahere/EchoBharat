package com.echobharat.mesh.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Handles all Bluetooth permission checking logic.
 *
 * Only covers the permissions needed to *call* the BLE APIs. Whether a scan will actually
 * return anything additionally depends on location permission and the system Location toggle —
 * see [BleReadiness], which is what the diagnostics and the UI warning use.
 */
class BluetoothPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothPermissionManager"
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    /** Names of the required permissions that are not currently granted. */
    fun missingPermissions(): List<String> = requiredPermissions().filterNot {
        ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required Bluetooth permissions are granted.
     *
     * Logs the missing ones rather than the whole granted set: a caller reading logcat after a
     * failed start needs the names of what is missing, and the full list buries them.
     */
    fun hasBluetoothPermissions(): Boolean {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            Log.e(
                BleDiagnostics.TAG,
                "PERMISSION denied (api=${Build.VERSION.SDK_INT}): " +
                    missing.joinToString { it.substringAfterLast('.') }
            )
            Log.e(TAG, "Missing BLE permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        return missing.isEmpty()
    }

    /**
     * Full discovery precondition check, including the location permission and system Location
     * toggle that gate whether scan results are delivered at all.
     */
    fun readiness(): BleReadinessReport = BleReadiness.check(context)
}
