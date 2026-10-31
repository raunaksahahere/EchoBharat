package com.echobharat.ui.transceiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.echobharat.mesh.LocationProvider
import com.echobharat.mesh.RangePolicy
import com.echobharat.mesh.SosManager
import com.echobharat.ui.theme.*

@Composable
internal fun SosBanner(
    announcements: List<SosManager.Active>,
    locationProvider: LocationProvider,
    onResolve: (String) -> Unit
) {
    var expanded by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AccentAlert.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, AccentAlert.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Sos, contentDescription = null, tint = AccentAlert, modifier = Modifier.size(18.dp))
                Text(
                    text = "${announcements.size} ACTIVE DISTRESS ${if (announcements.size == 1) "CALL" else "CALLS"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentAlert
                )
            }

            announcements.forEach { entry ->
                val msg = entry.message
                val minsLeft = msg.remainingMillis() / 60000

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == msg.msgId) null else msg.msgId }
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.isMine) "You" else msg.senderName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (msg.sosVerified) "✅ Verified SOS" else "⚠ Unverified legacy SOS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (msg.sosVerified) AccentEmerald else AccentAlert,
                                fontSize = 10.sp
                            )
                            Text(
                                text = buildString {
                                    if (entry.isMine) append("Your SOS is live  •  ")
                                append("${minsLeft} min left")
                                    append(if (msg.hasLocation) "  •  location attached" else "  •  no location")
                                    append("  •  tap for details")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        if (entry.isMine) {
                            Button(
                                onClick = { onResolve(msg.msgId) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Stop SOS", color = SurfaceCard, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    if (expanded == msg.msgId) {
                        // A live position, refreshed while the details are open: the cached fix was
                        // often null, which is why the distance used to read "no position".
                        val hereState = produceState<android.location.Location?>(
                            initialValue = locationProvider.lastKnown(),
                            key1 = msg.msgId
                        ) {
                            while (true) {
                                locationProvider.fresh(8_000L)?.let { value = it }
                                delay(10_000L)
                            }
                        }
                        val here = hereState.value
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = if (msg.lat != null && msg.lon != null) {
                                        "Origin: %.5f, %.5f".format(msg.lat, msg.lon) +
                                            (msg.gpsAccuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                                    } else {
                                        "Origin: no position — the sender had no GPS fix"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = if (here != null) {
                                        "You: %.5f, %.5f".format(here.latitude, here.longitude)
                                    } else {
                                        "You: no position available"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                                if (msg.lat != null && msg.lon != null && here != null) {
                                    val metres = RangePolicy.distanceMeters(
                                        msg.lat, msg.lon, here.latitude, here.longitude
                                    )
                                    val toward = RangePolicy.compassPoint(
                                        RangePolicy.bearingDegrees(
                                            here.latitude, here.longitude, msg.lat, msg.lon
                                        )
                                    )
                                    // GPS error on both phones adds up; say so rather than
                                    // implying metre precision.
                                    val slack = ((msg.gpsAccuracyM ?: 0f) + here.accuracy).toInt()
                                    Text(
                                        text = "About ${RangePolicy.formatDistance(metres)} away, " +
                                            "towards the $toward" +
                                            if (slack > 0) " (±$slack m)" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentAlert,
                                        fontSize = 11.sp
                                    )
                                } else if (!entry.isMine && !locationProvider.hasPermission()) {
                                    Text(
                                        text = "Allow location to see how far away this is",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentAlert,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Confirmation before broadcasting a distress call, with an optional note. */
@Composable
internal fun SosConfirmDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sos, contentDescription = null, tint = AccentAlert) },
        title = { Text("Broadcast distress call?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This goes to every phone in range and keeps being passed on for one hour, " +
                        "including to people who arrive later. Your location is attached if " +
                        "available. You can cancel it at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Add a short note (optional)", color = TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAlert,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(note) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentAlert)
            ) {
                Text("Broadcast SOS", fontWeight = FontWeight.Bold, color = SurfaceCard)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = SurfaceCard
    )
}
