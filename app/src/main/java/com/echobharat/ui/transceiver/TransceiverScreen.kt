package com.echobharat.ui.transceiver

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echobharat.conversation.VoiceEngines
import com.echobharat.mesh.EchoBharatMeshManager
import com.echobharat.mesh.LocationProvider
import com.echobharat.schema.Peer
import com.echobharat.schema.VoicePreferences
import com.echobharat.ui.theme.*
import kotlinx.coroutines.launch

/**
 * One conversation with one peer.
 *
 * Everything here is addressed to [peer] and goes out Noise-encrypted. There is no
 * broadcast from this screen and no way to widen the audience by accident — the only
 * message that reaches everyone is a distress call, and it asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
    meshManager: EchoBharatMeshManager,
    peer: Peer,
    onBack: () -> Unit,
    onOpenLanguages: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: TransceiverViewModel = viewModel(
        key = "chat-${peer.peerId}",
        factory = TransceiverViewModel.factory(app, peer.peerId)
    )
    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val engines = remember { VoiceEngines.getInstance(context) }

    val conversation by vm.conversation.collectAsState()
    val livePeer by vm.livePeer.collectAsState()
    val sttState by vm.stt.state.collectAsState()
    val ttsState by engines.tts.state.collectAsState()
    val isMeshRunning by meshManager.isMeshRunning.collectAsState()
    val sosAnnouncements by meshManager.sos.announcements.collectAsState()
    val selectedLanguage by voicePrefs.activeLanguage.collectAsState()
    val autoSpeak by voicePrefs.autoSpeak.collectAsState()
    val showTimings by voicePrefs.showTimings.collectAsState()

    // The live entry carries fresh name, model and hop count; fall back to what we were
    // opened with while the peer is out of range.
    val shownPeer = livePeer ?: peer
    val entries = conversation?.entries.orEmpty()

    var typedText by rememberSaveable { mutableStateOf("") }
    var isAlertMode by rememberSaveable { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    // Being on screen is what makes incoming lines "read" and auto-spoken.
    DisposableEffect(peer.peerId) {
        vm.onShown(peer)
        onDispose { vm.onHidden() }
    }

    // Warm the speech model as soon as the chat opens, the language changes, or a pack
    // finishes installing (noticed by polling the disk, since installs happen on another
    // screen). Without this the first press paid for a multi-second load.
    LaunchedEffect(selectedLanguage) {
        var wasInstalled = false
        while (true) {
            val installed = vm.isSpeechInstalled(selectedLanguage)
            if (installed && !wasInstalled) vm.prepareSpeech(selectedLanguage)
            wasInstalled = installed
            kotlinx.coroutines.delay(1_500)
        }
    }

    // Follow the conversation as it grows.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ConversationTitle(shownPeer, present = livePeer != null, meshOnline = isMeshRunning) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to people", tint = TextSecondary)
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showLangPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceVariantBg)
                    ) {
                        Icon(Icons.Default.Translate, null, tint = AccentSaffron, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            selectedLanguage.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = { isAlertMode = !isAlertMode }) {
                        Icon(
                            Icons.Default.Campaign,
                            contentDescription = if (isAlertMode) "Alert mode on" else "Alert mode off",
                            tint = if (isAlertMode) AccentAlert else TextMuted
                        )
                    }
                    IconButton(onClick = { showSosDialog = true }) {
                        Icon(Icons.Default.Sos, "Raise distress announcement", tint = AccentAlert)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More options", tint = TextSecondary)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            CheckItem("Read incoming aloud", autoSpeak) { voicePrefs.setAutoSpeak(!autoSpeak) }
                            CheckItem("Show timings", showTimings) { voicePrefs.setShowTimings(!showTimings) }
                            DropdownMenuItem(
                                text = { Text("Language packs") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { showMenu = false; onOpenLanguages() }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear conversation") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = { showMenu = false; confirmClear = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (livePeer == null) OutOfRangeStrip(shownPeer.name)

            // A blocked mesh and the MIUI battery warning are never shown together: the
            // first stops discovery outright and is the fix that matters.
            val meshBlocked = MeshReadinessNotice()
            if (!meshBlocked) BatteryOptimizationNotice()

            if (sosAnnouncements.isNotEmpty()) {
                SosBanner(
                    announcements = sosAnnouncements,
                    locationProvider = remember { LocationProvider(context) },
                    onResolve = { meshManager.sos.resolve(it) }
                )
            }

            AnimatedVisibility(visible = isAlertMode) { AlertModeBanner() }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (entries.isEmpty()) item { EmptyConversation(shownPeer.name) }
                items(entries, key = { it.msgId }) { entry ->
                    MessageBubble(
                        entry = entry,
                        showTimings = showTimings,
                        onReadAloud = { vm.readAloud(entry.msgId) }
                    )
                }
            }

            if (ttsState is com.echobharat.tts.TtsManager.State.Unavailable) {
                TextButton(onClick = onOpenLanguages, modifier = Modifier.fillMaxWidth()) {
                    Text("Voice needs updating — open Language Packs. Text is still available.", color = AccentAlert)
                }
            }
            SpeechStatusBar(state = sttState, onOpenLanguages = onOpenLanguages)

            Surface(color = SurfaceCard, border = BorderStroke(1.dp, BorderSubtle)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Composer(
                        text = typedText,
                        onTextChange = { typedText = it },
                        onSend = {
                            vm.sendTyped(shownPeer, typedText, selectedLanguage, isAlertMode)
                            typedText = ""
                        }
                    )
                    PushToTalkButton(
                        alert = isAlertMode,
                        hasMicPermission = hasMicPermission,
                        sttState = sttState,
                        onRequestPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onPress = { vm.startTalking(selectedLanguage) },
                        onRelease = { vm.stopTalking(shownPeer, selectedLanguage, isAlertMode) }
                    )
                }
            }
        }
    }

    if (showSosDialog) {
        SosConfirmDialog(
            onDismiss = { showSosDialog = false },
            onConfirm = { note ->
                showSosDialog = false
                scope.launch {
                    meshManager.sos.raise(
                        text = note.ifBlank { "Distress signal — assistance needed" },
                        srcLang = selectedLanguage
                    )
                }
            }
        )
    }

    if (showLangPicker) {
        LanguagePickerDialog(
            voicePrefs = voicePrefs,
            engines = engines,
            onDismiss = { showLangPicker = false },
            onOpenLanguages = onOpenLanguages
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this conversation?") },
            text = { Text("Messages with ${shownPeer.name} are removed from this phone. Their copy is not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearConversation() }) {
                    Text("Clear", color = AccentAlert, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = SurfaceCard
        )
    }
}

@Composable
private fun ConversationTitle(peer: Peer, present: Boolean, meshOnline: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SurfaceVariantBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                peer.name.trim().take(1).uppercase().ifBlank { "?" },
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
        }
        Column {
            Text(
                peer.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val color = when {
                    !meshOnline -> AccentAlert
                    present -> AccentEmerald
                    else -> TextMuted
                }
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = when {
                        !meshOnline -> "mesh offline"
                        !present -> "out of range"
                        peer.hops <= 1 -> "direct" + peer.deviceModel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        else -> "${peer.hops} hops away"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) AccentSaffron else TextMuted
            )
        },
        onClick = onToggle
    )
}

/**
 * Losing the peer does not silently widen the audience — it says so, and says what
 * happens to what is typed next.
 */
@Composable
private fun OutOfRangeStrip(name: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceVariantBg,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.PortableWifiOff, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Text(
                "$name is out of range. New messages wait up to 10 minutes and are sent if they come back.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun AlertModeBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AccentAlert.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, AccentAlert.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Emergency, null, tint = AccentAlert, modifier = Modifier.size(18.dp))
            Text(
                "ALERT MODE — they hear it at full volume, even on silent",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AccentAlert
            )
        }
    }
}

@Composable
private fun EmptyConversation(name: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Hearing, null, tint = TextMuted, modifier = Modifier.size(40.dp))
        Text("Say something to $name", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Text(
            "Hold the button to speak, or type below.\nOnly text crosses the link — never your voice.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Type a message…", color = TextMuted) },
            maxLines = 4,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentSaffron,
                unfocusedBorderColor = BorderSubtle,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = SurfaceVariantBg,
                unfocusedContainerColor = SurfaceVariantBg
            )
        )
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (text.isNotBlank()) AccentSaffron else SurfaceVariantBg)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) SurfaceBg else TextMuted
            )
        }
    }
}
