package com.echobharat.ui.verification

import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echobharat.mesh.EchoBharatMeshManager
import com.echobharat.schema.Peer
import com.echobharat.ui.theme.AccentAlert
import com.echobharat.ui.theme.AccentEmerald
import com.echobharat.ui.theme.SurfaceCard
import com.echobharat.ui.theme.TextPrimary
import com.echobharat.ui.theme.TextSecondary

@Composable
fun ContactVerificationDialog(
    meshManager: EchoBharatMeshManager,
    peer: Peer,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val status = meshManager.contactVerificationStatus(peer.peerId)
    val safetyNumber = meshManager.contactSafetyNumber(peer.peerId) ?: "Unavailable"
    val myQr = remember { meshManager.myVerificationQr().orEmpty() }
    var pastedQr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text("Verify ${peer.name}", color = TextPrimary) },
        text = {
            Column {
                Text(
                    text = when (status) {
                        EchoBharatMeshManager.ContactVerificationStatus.VERIFIED -> "✅ Verified contact"
                        EchoBharatMeshManager.ContactVerificationStatus.KEY_CHANGED -> "⚠ Identity key changed — verify again"
                        EchoBharatMeshManager.ContactVerificationStatus.UNVERIFIED -> "Unverified contact"
                    },
                    color = if (status == EchoBharatMeshManager.ContactVerificationStatus.VERIFIED) AccentEmerald else AccentAlert,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Text("Compare this safety number in person:", color = TextSecondary, fontSize = 12.sp)
                SelectionContainer {
                    Text(safetyNumber, color = TextPrimary, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Text("My signed QR payload (share or copy):", color = TextSecondary, fontSize = 12.sp)
                SelectionContainer {
                    Text(myQr.ifBlank { "Identity is not ready" }, color = TextPrimary, fontSize = 9.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
                OutlinedTextField(
                    value = pastedQr,
                    onValueChange = { pastedQr = it; error = null },
                    label = { Text("Paste their signed QR payload") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                error?.let { Text(it, color = AccentAlert, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    if (myQr.isBlank()) return@TextButton
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, myQr)
                    }, "Share verification payload"))
                }) { Text("Share mine") }
                Button(onClick = {
                    if (meshManager.verifyContact(peer.peerId, pastedQr) == null) {
                        error = "The signed payload does not match this contact."
                    } else {
                        onDismiss()
                    }
                }) { Text("Verify") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
