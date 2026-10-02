package com.echobharat.ui.transceiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echobharat.conversation.ChatEntry
import com.echobharat.conversation.StoredTranslation
import com.echobharat.conversation.Timings
import com.echobharat.mesh.Delivery
import com.echobharat.schema.Languages
import com.echobharat.schema.MessageType
import com.echobharat.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun MessageBubble(
    entry: ChatEntry,
    showTimings: Boolean,
    onReadAloud: () -> Unit
) {
    val message = entry.message
    val isMine = entry.outgoing
    val isAlert = message.isAlert || message.type == MessageType.ALERT || message.type == MessageType.SOS
    val formattedTime = remember(message.ts) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.ts))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            ),
            color = when {
                isAlert -> AccentAlert.copy(alpha = 0.10f)
                isMine -> BubbleMine
                else -> BubbleTheirs
            },
            border = BorderStroke(
                1.dp,
                when {
                    isAlert -> AccentAlert
                    isMine -> AccentSaffron.copy(alpha = 0.5f)
                    else -> BorderSubtle
                }
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BubbleHeader(message.type, message.srcLang, isAlert, onReadAloud)

                // When a translation exists the reader sees it first, with the original
                // underneath — and when one was needed but could not be produced, that is
                // said outright. Untranslated text passed off as translated is the failure
                // that matters in a distress message.
                val translation = entry.translation
                val shown = if (translation?.status == StoredTranslation.Status.TRANSLATED) {
                    translation.text ?: message.text
                } else message.text
                Text(text = shown, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)

                translation?.let { TranslationNote(it, message.srcLang, message.text) }
                entry.pendingWork?.let { pending ->
                    Text(
                        text = if (pending.failed) "Speech/model work failed — tap Read aloud to retry"
                            else if (pending.urgent) "Urgent speech pending — message retained"
                            else "Translation/speech pending — message retained",
                        color = if (pending.failed || pending.urgent) AccentAlert else TextMuted,
                        fontSize = 11.sp,
                        modifier = if (pending.failed) Modifier.clickable(onClick = onReadAloud) else Modifier
                    )
                }

                if (showTimings) entry.timings?.takeUnless { it.isEmpty }?.let { TimingsLine(it) }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(formattedTime, color = TextMuted, fontSize = 10.sp)
                    if (isMine) entry.delivery?.let { DeliveryMark(it) }
                }
            }
        }
    }
}

@Composable
private fun BubbleHeader(type: MessageType, srcLang: String, isAlert: Boolean, onReadAloud: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = when (type) {
                MessageType.VOICE_TEXT -> Icons.Default.RecordVoiceOver
                MessageType.ALERT -> Icons.Default.Emergency
                MessageType.TYPED_TEXT -> Icons.Default.ChatBubbleOutline
                MessageType.SYSTEM -> Icons.Default.Info
                MessageType.SOS -> Icons.Default.Sos
                MessageType.SOS_RESOLVED -> Icons.Default.CheckCircle
            },
            contentDescription = null,
            tint = if (isAlert) AccentAlert else AccentSaffron,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = when (type) {
                MessageType.VOICE_TEXT -> "SPEECH"
                MessageType.ALERT -> "ALERT"
                MessageType.TYPED_TEXT -> "TEXT"
                MessageType.SYSTEM -> "SYSTEM"
                MessageType.SOS -> "DISTRESS"
                MessageType.SOS_RESOLVED -> "RESOLVED"
            } + " · ${Languages.badge(srcLang)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isAlert) AccentAlert else TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        // Every message exists as both text and speech.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Read aloud",
            tint = if (isAlert) AccentAlert else AccentCyan,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onReadAloud)
        )
    }
}

@Composable
private fun TranslationNote(translation: StoredTranslation, srcLang: String, original: String) {
    val (text, color) = when (translation.status) {
        StoredTranslation.Status.TRANSLATED ->
            "translated from ${Languages.badge(srcLang)}" +
                translation.via?.let { " via ${Languages.badge(it)}" }.orEmpty() +
                " · $original" to TextMuted
        StoredTranslation.Status.UNAVAILABLE ->
            "shown as received — no ${Languages.badge(srcLang)}→${Languages.badge(translation.target)} " +
                "translation on this phone" to AccentAlert
        StoredTranslation.Status.UNKNOWN_SOURCE ->
            "shown as received — the sender's app did not say what language this is" to AccentAlert
        StoredTranslation.Status.NOT_NEEDED -> return
    }
    Text(text = text, color = color, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall)
}

/** The on-device numbers that the README declines to quote until someone measures them. */
@Composable
private fun TimingsLine(t: Timings) {
    val parts = buildList {
        t.sttMs?.let { stt ->
            val audio = t.audioMs
            add(
                if (audio != null && audio > 0) {
                    "STT %d ms / %.1f s · RTF %.2f".format(stt, audio / 1000f, stt.toFloat() / audio)
                } else "STT $stt ms"
            )
        }
        t.translateMs?.let { add("MT $it ms") }
        t.synthesisMs?.let { syn ->
            val spoken = t.spokenMs
            add(if (spoken != null && spoken > 0) "TTS %d ms / %.1f s".format(syn, spoken / 1000f) else "TTS $syn ms")
        }
    }
    Text(
        text = parts.joinToString("  ·  "),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = AccentCyan
    )
}

@Composable
private fun DeliveryMark(delivery: Delivery) {
    val (icon: ImageVector, tint, label) = when (delivery) {
        Delivery.QUEUED -> Triple(Icons.Default.Schedule, TextMuted, "Waiting for a secure link")
        Delivery.SENT -> Triple(Icons.Default.Done, TextMuted, "Sent")
        Delivery.DELIVERED -> Triple(Icons.Default.DoneAll, AccentEmerald, "Delivered")
        Delivery.FAILED -> Triple(Icons.Default.ErrorOutline, AccentAlert, "Not delivered")
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
    if (delivery == Delivery.FAILED) {
        Text("not delivered", color = AccentAlert, fontSize = 10.sp)
    }
}
