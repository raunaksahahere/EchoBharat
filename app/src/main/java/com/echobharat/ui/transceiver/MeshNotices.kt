package com.echobharat.ui.transceiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echobharat.mesh.transport.BleBlocker
import com.echobharat.mesh.transport.BleReadiness
import com.echobharat.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Says out loud why the peer list is empty when the cause is on this phone.
 *
 * Android withholds BLE scan results entirely when location permission is missing or the system
 * Location toggle is off — no callback, no error, just nothing — so without this the screen shows
 * "Scanning for nearby EchoBharat nodes" forever and blames the mesh for a settings problem.
 *
 * Polled rather than observed: the user fixes these in system settings and comes back, and there
 * is no single broadcast covering permission grants, the Location toggle and the Bluetooth
 * adapter together.
 */
@Composable
internal fun MeshReadinessNotice(): Boolean {
    val context = LocalContext.current
    var report by remember { mutableStateOf(BleReadiness.check(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            report = BleReadiness.check(context)
        }
    }

    val blocker = report.blockers.firstOrNull() ?: return false

    val action: (() -> Unit)? = when (blocker) {
        BleBlocker.NO_BLE_HARDWARE -> null
        BleBlocker.BLUETOOTH_OFF -> {
            // The system's one-tap "turn on Bluetooth" dialog; the mesh restarts by itself.
            { context.openSettings(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
        BleBlocker.LOCATION_SERVICES_OFF -> {
            { context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        else -> {
            {
                context.openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AccentAlert.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AccentAlert.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (blocker) {
                    BleBlocker.LOCATION_SERVICES_OFF, BleBlocker.MISSING_LOCATION_PERMISSION ->
                        Icons.Default.LocationOff
                    BleBlocker.BLUETOOTH_OFF -> Icons.Default.BluetoothDisabled
                    else -> Icons.Default.ErrorOutline
                },
                contentDescription = null,
                tint = AccentAlert,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blocker.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = blocker.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (report.blockers.size > 1) {
                    Text(
                        text = "+${report.blockers.size - 1} more to fix",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
            if (action != null) {
                TextButton(onClick = action) {
                    Text("Fix", color = AccentAlert, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    return true
}

/** Opens a settings screen, tolerating devices that do not expose the exact activity. */
private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.util.Log.e("MeshReadiness", "SETTINGS_OPEN_FAILED: ${e.message}", e)
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }
}

@Composable
internal fun BatteryOptimizationNotice() {
    val context = LocalContext.current
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val isXiaomiFamily = manufacturer.contains("xiaomi", ignoreCase = true) ||
        manufacturer.contains("redmi", ignoreCase = true) ||
        manufacturer.contains("poco", ignoreCase = true)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val ignoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    if (isXiaomiFamily && !ignoringOptimizations) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AccentAlert.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, AccentAlert.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = AccentAlert)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow unrestricted battery use", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Xiaomi/MIUI can stop BLE discovery. Disable battery optimization and enable Autostart for EchoBharat.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("BatteryOptimization", "BATTERY_SETTINGS_OPEN_FAILED: ${e.message}", e)
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) {
                    Text("Fix", color = AccentAlert)
                }
            }
        }
    }
}
