package com.echobharat.mesh.transport

import android.bluetooth.BluetoothGatt

/** Best-effort platform teardown; revocation must not prevent releasing local connection state. */
internal object BluetoothGattCleanup {
    fun close(gatt: BluetoothGatt?) {
        if (gatt == null) return
        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
            // Permission can disappear after a connection was established.
        }
        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Android owns the revoked handle; callers still discard their references.
        }
    }
}
