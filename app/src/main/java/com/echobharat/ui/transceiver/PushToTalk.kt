package com.echobharat.ui.transceiver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echobharat.stt.SttManager
import com.echobharat.stt.SttUnavailable
import com.echobharat.ui.theme.*

/**
 * Hold to talk: the microphone opens on press and closes on release, so the captured audio
 * is the utterance and nothing else. [onRelease] fires on cancel too (a finger dragged
 * off the button), because a capture left open would keep recording.
 */
@Composable
internal fun PushToTalkButton(
    alert: Boolean,
    hasMicPermission: Boolean,
    sttState: SttManager.State,
    onRequestPermission: () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    // The gesture handler outlives recompositions; read the latest callbacks through
    // these, or a language or alert-mode change mid-conversation would be ignored.
    val currentState by rememberUpdatedState(sttState)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnRequestPermission by rememberUpdatedState(onRequestPermission)
    val pulse by rememberInfiniteTransition(label = "pttPulse").animateFloat(
        initialValue = 1f,
        targetValue = if (pressed) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .scale(pulse)
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        when {
                            alert -> listOf(AccentAlert, Color(0xFFB91C1C))
                            pressed -> listOf(AccentEmerald, AccentCyan)
                            else -> listOf(AccentSaffron, Color(0xFFEA580C))
                        }
                    )
                )
                .semantics { contentDescription = "Push to talk. Hold while speaking." }
                .pointerInput(hasMicPermission) {
                    detectTapGestures(
                        onPress = {
                            if (!hasMicPermission) {
                                currentOnRequestPermission()
                                return@detectTapGestures
                            }
                            if (currentState is SttManager.State.Transcribing) return@detectTapGestures
                            // Keep the language, peer and alert mode from the start of this utterance.
                            val release = currentOnRelease
                            pressed = true
                            try {
                                currentOnPress()
                                tryAwaitRelease()
                            } finally {
                                // Disposal/rotation cancels this coroutine, not just the pointer gesture.
                                pressed = false
                                release()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (pressed) Icons.Default.GraphicEq else Icons.Default.Mic,
                contentDescription = null,
                tint = SurfaceBg,
                modifier = Modifier.size(36.dp)
            )
        }
        Text(
            text = when {
                !hasMicPermission -> "TAP TO ALLOW THE MICROPHONE"
                sttState is SttManager.State.Transcribing -> "TRANSCRIBING…"
                pressed -> "LISTENING — RELEASE TO SEND"
                alert -> "HOLD TO SEND AN ALERT"
                else -> "HOLD TO TALK"
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (pressed) AccentEmerald else if (alert) AccentAlert else TextSecondary,
            fontSize = 10.sp
        )
    }
}

/** Small holder so the status branches below can destructure in one expression. */
private data class Quad(
    val accent: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val detail: String
)

@Composable
internal fun SpeechStatusBar(state: SttManager.State, onOpenLanguages: () -> Unit) {
    val visible = state !is SttManager.State.Idle
    AnimatedVisibility(visible = visible) {
        val (accent, icon, label, detail) = when (state) {
            is SttManager.State.Listening -> Quad(
                if (state.speech) AccentEmerald else AccentSaffron,
                Icons.Default.Mic,
                if (state.speech) "SPEECH DETECTED" else "LISTENING",
                "Release PTT to transcribe and send"
            )
            is SttManager.State.Transcribing -> Quad(
                AccentCyan,
                Icons.Default.GraphicEq,
                "TRANSCRIBING",
                "Running IndicConformer on-device"
            )
            is SttManager.State.Unavailable -> when (val reason = state.reason) {
                is SttUnavailable.ModelMissing -> Quad(
                    AccentAlert,
                    Icons.Default.CloudDownload,
                    "SPEECH MODEL MISSING (${reason.lang.uppercase()})",
                    "Install the language pack to enable voice input"
                )
                is SttUnavailable.LoadFailed -> Quad(
                    AccentAlert, Icons.Default.ErrorOutline, "SPEECH ENGINE FAILED", reason.reason
                )
                SttUnavailable.PermissionDenied -> Quad(
                    AccentAlert,
                    Icons.Default.MicOff,
                    "MICROPHONE BLOCKED",
                    "Grant microphone access to use push-to-talk"
                )
            }
            SttManager.State.Idle -> Quad(TextMuted, Icons.Default.Mic, "", "")
        }

        Surface(
            color = accent.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
                if (state is SttManager.State.Unavailable &&
                    state.reason is SttUnavailable.ModelMissing
                ) {
                    TextButton(onClick = onOpenLanguages) {
                        Text("Install", color = accent)
                    }
                }
            }
        }
    }
}
