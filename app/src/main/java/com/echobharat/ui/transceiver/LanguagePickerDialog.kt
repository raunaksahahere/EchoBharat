package com.echobharat.ui.transceiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echobharat.conversation.VoiceEngines
import com.echobharat.models.ModelCatalog
import com.echobharat.schema.VoicePreferences
import com.echobharat.ui.theme.*

/**
 * Multi-select language picker with live switching between enabled languages. Nothing
 * here is forced on launch; it opens only when the user asks for it.
 */
@Composable
internal fun LanguagePickerDialog(
    voicePrefs: VoicePreferences,
    engines: VoiceEngines,
    onDismiss: () -> Unit,
    onOpenLanguages: () -> Unit
) {
    val context = LocalContext.current
    val enabledLanguages by voicePrefs.enabledLanguages.collectAsState()
    val selectedLanguage by voicePrefs.activeLanguage.collectAsState()
    val catalog = remember { ModelCatalog.languages(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Voice Languages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap to speak in a language. Use the checkbox to keep it enabled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                catalog.forEach { spec ->
                    val isEnabled = spec.lang in enabledLanguages
                    val isActive = spec.lang == selectedLanguage
                    val sttReady = remember(spec.lang) { engines.stt.isAvailable(spec.lang) }
                    val ttsReady = remember(spec.lang) { engines.tts.isAvailable(spec.lang) }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Switching is live — no restart, no reload prompt.
                                voicePrefs.setActive(spec.lang)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isActive) AccentSaffron.copy(alpha = 0.2f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) voicePrefs.enable(spec.lang)
                                    else voicePrefs.disable(spec.lang)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentSaffron)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${spec.nativeName} (${spec.displayName})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) AccentSaffron else TextPrimary
                                )
                                Text(
                                    text = buildString {
                                        append(if (sttReady) "STT ready" else "STT missing")
                                        append(" • ")
                                        append(if (ttsReady) "TTS ready" else "TTS missing")
                                        if (spec.bundled) append(" • bundled")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sttReady && ttsReady) AccentEmerald else TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            if (isActive) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = "Active",
                                    tint = AccentSaffron,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenLanguages()
            }) {
                Text("Manage packs", color = AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentSaffron)
            }
        },
        containerColor = SurfaceCard
    )
}
