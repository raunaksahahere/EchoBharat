package com.echobharat.ui.languages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echobharat.models.LanguageModelSpec
import com.echobharat.models.ModelCatalog
import com.echobharat.models.ModelManager
import com.echobharat.models.PackSharing
import com.echobharat.models.TranslationFamilySpec
import com.echobharat.schema.VoicePreferences
import com.echobharat.translate.TranslationRoutes
import com.echobharat.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager.getInstance(context) }
    val sharing = remember { PackSharing(context) }
    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val packs = remember { ModelCatalog.languages(context) }
    val families = remember { ModelCatalog.translationFamilies(context) }
    val activeLanguage by voicePrefs.activeLanguage.collectAsState()
    val progress by modelManager.progress.collectAsState()
    val enabledLanguages by voicePrefs.enabledLanguages.collectAsState()
    val autoUpdateVoicePacks by voicePrefs.autoUpdateVoicePacks.collectAsState()
    val scope = rememberCoroutineScope()
    val meteredUpdateLanguages by modelManager.networkConsent.collectAsState()
    val revision by modelManager.revision.collectAsState()

    // Recomputed after each install so status badges reflect what is actually on disk.
    var refreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(autoUpdateVoicePacks) {
        if (autoUpdateVoicePacks) modelManager.requestAutomaticUpdates()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }

    // Packs received from another phone: verified by hash, installed wherever they belong.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val report = modelManager.importFiles(uris)
            importing = false
            refreshToken++
            snackbarHostState.showSnackbar(
                buildList {
                    if (report.installed.isNotEmpty()) add("${report.installed.size} installed")
                    if (report.alreadyPresent.isNotEmpty()) add("${report.alreadyPresent.size} already here")
                    if (report.rejected.isNotEmpty()) add("${report.rejected.size} not EchoBharat model files — skipped")
                }.joinToString(" · ").ifEmpty { "Nothing to import" }
            )
        }
    }

    fun share(files: List<java.io.File>, title: String) {
        val intent = sharing.shareIntent(files, title)
        if (intent != null) context.startActivity(intent)
        else scope.launch { snackbarHostState.showSnackbar("Nothing installed to share") }
    }

    LaunchedEffect(progress) {
        when (val p = progress) {
            is ModelManager.Progress.Done -> {
                refreshToken++
                val family = families.firstOrNull { it.id == p.lang }
                snackbarHostState.showSnackbar(
                    if (family != null) "${family.title} installed" else "${p.lang.uppercase()} pack installed"
                )
            }
            is ModelManager.Progress.Failed -> {
                refreshToken++
                snackbarHostState.showSnackbar(p.reason)
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Language Packs (${packs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentSaffron)
                    } else {
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, tint = AccentSaffron, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import", color = AccentSaffron, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = SurfaceBg
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item { OfflineHint() }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatically update voice packs", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Wi-Fi by default; mobile data always asks first", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoUpdateVoicePacks,
                        onCheckedChange = voicePrefs::setAutoUpdateVoicePacks,
                        colors = SwitchDefaults.colors(checkedThumbColor = SurfaceCard, checkedTrackColor = AccentSaffron)
                    )
                }
            }

            item { SectionHeader("Voice packs", "Listen and speak, one language each") }

            items(packs, key = { it.lang }) { pack ->
                val status by produceState(ModelManager.PackStatus.UNKNOWN, pack.lang, refreshToken, revision) {
                    value = modelManager.status(pack.lang)
                }
                val busy = progress.isFor(pack.lang)
                LanguagePackItem(
                    pack = pack,
                    status = status,
                    enabled = pack.lang in enabledLanguages,
                    progress = progress.takeIf { busy },
                    onInstall = { scope.launch { modelManager.install(pack.lang) } },
                    onUninstall = { scope.launch {
                        modelManager.uninstall(pack.lang)
                        refreshToken++
                    } },
                    onShare = { share(sharing.voicePackFiles(pack.lang), "Share ${pack.displayName} voice pack") },
                    onToggleEnabled = { checked ->
                        if (checked) voicePrefs.enable(pack.lang) else voicePrefs.disable(pack.lang)
                    }
                )
            }

            if (families.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Translation",
                        "Optional. Shared by every language — install once. Incoming messages " +
                            "are translated into your language on this phone."
                    )
                }
                items(families, key = { "mt-${it.id}" }) { family ->
                    val installed = remember(family.id, refreshToken) { modelManager.hasTranslation(family.id) }
                    val fast = remember(family.id, refreshToken) { modelManager.hasFastTranslation(family.id) }
                    val neededBy = enabledLanguages.filter { family.id in TranslationRoutes.familiesFor(it) }
                    TranslationFamilyItem(
                        family = family,
                        installed = installed,
                        fast = fast,
                        neededByActive = family.id in TranslationRoutes.familiesFor(activeLanguage),
                        neededBy = neededBy.mapNotNull { l -> packs.firstOrNull { it.lang == l }?.displayName },
                        progress = progress.takeIf { it.isFor(family.id) },
                        onInstall = { withFast ->
                            scope.launch {
                                modelManager.installTranslation(family.id, includeFast = withFast)
                                refreshToken++
                            }
                        },
                        onUninstall = { scope.launch {
                            modelManager.uninstallTranslation(family.id)
                            refreshToken++
                        } },
                        onShare = { share(sharing.translationFiles(family.id), "Share ${family.title}") }
                    )
                }
            }
        }
    }

    if (meteredUpdateLanguages.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { scope.launch { modelManager.dismissNetworkUpdates() } },
            title = { Text("Download voices on this network?") },
            text = {
                Text(
                    "These verified voice updates may be hundreds of megabytes: " +
                        meteredUpdateLanguages.joinToString(", ").uppercase() + "."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { modelManager.approveNetworkUpdates() }
                }) { Text("Use mobile data", color = AccentSaffron) }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { modelManager.dismissNetworkUpdates() } }) { Text("Not now") }
            }
        )
    }
}

private fun ModelManager.Progress.isFor(id: String): Boolean =
    (this is ModelManager.Progress.Downloading && lang == id) ||
        (this is ModelManager.Progress.Verifying && lang == id)

/** How to provision a phone that has no connection at all. */
@Composable
private fun OfflineHint() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AccentCyan.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = null, tint = AccentCyan)
            Text(
                "No internet? A phone that already has a pack can send it with the share button " +
                    "over Quick Share or Bluetooth. Tap Import here to install it — every file is " +
                    "checked against the same fingerprints as a download.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun TranslationFamilyItem(
    family: TranslationFamilySpec,
    installed: Boolean,
    fast: Boolean,
    neededByActive: Boolean,
    neededBy: List<String>,
    progress: ModelManager.Progress?,
    onInstall: (withFast: Boolean) -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (neededByActive && !installed) AccentSaffron else BorderSubtle
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(family.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        "IndicTrans2 · ~${family.bytes / 1_000_000} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                when {
                    progress != null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentSaffron)
                    installed -> Row {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "Share ${family.title} with another phone", tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onUninstall) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${family.title}", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> OutlinedButton(
                        onClick = { onInstall(false) },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentSaffron),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSaffron),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (progress is ModelManager.Progress.Downloading) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentSaffron,
                    trackColor = SurfaceVariantBg
                )
            }

            Text(
                text = if (neededBy.isEmpty()) "Not needed by the languages you have enabled"
                else "Used by " + neededBy.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = if (neededByActive && !installed) AccentSaffronDeep else TextSecondary,
                fontSize = 11.sp
            )

            if (installed && family.fast.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (fast) "Faster decoding on" else "Faster decoding (+${family.fastBytes / 1_000_000} MB, about 2× on long sentences)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fast) AccentEmerald else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!fast && progress == null) {
                        TextButton(onClick = { onInstall(true) }) {
                            Text("Add", fontSize = 11.sp, color = AccentSaffron, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePackItem(
    pack: LanguageModelSpec,
    status: ModelManager.PackStatus,
    enabled: Boolean,
    progress: ModelManager.Progress?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pack.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "(${pack.nativeName})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentSaffronDeep
                        )
                    }

                    Text(
                        text = buildString {
                            append("STT: IndicConformer • TTS: FastPitch+HiFi-GAN")
                            if (pack.totalBytes > 0) append(" • ~${pack.totalBytes / 1_000_000} MB")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                StatusAction(status, progress, onInstall, onUninstall, onShare)
            }

            if (progress is ModelManager.Progress.Downloading) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentSaffron,
                        trackColor = SurfaceVariantBg
                    )
                    Text(
                        text = "${progress.fileName} — ${(progress.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            } else if (progress is ModelManager.Progress.Verifying) {
                Text(
                    text = "Verifying checksum of ${progress.fileName}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan,
                    fontSize = 10.sp
                )
            }

            when (status) {
                ModelManager.PackStatus.UNPUBLISHED -> Text(
                    text = "Not available yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp
                )
                ModelManager.PackStatus.VOICE_NEEDS_UPDATING -> Text(
                    text = "Voice needs updating — text remains available",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentAlert,
                    fontSize = 10.sp
                )
                ModelManager.PackStatus.UPDATE_AVAILABLE -> Text(
                    text = "Update available — current files are kept until the new voice loads",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentAlert,
                    fontSize = 10.sp
                )
                else -> Unit
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = CheckboxDefaults.colors(checkedColor = AccentSaffron)
                )
                Text(
                    text = "Available for speaking and listening",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) TextSecondary else TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusAction(
    status: ModelManager.PackStatus,
    progress: ModelManager.Progress?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit
) {
    if (progress != null) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentSaffron)
        return
    }

    when (status) {
        ModelManager.PackStatus.UPDATE_AVAILABLE, ModelManager.PackStatus.VOICE_NEEDS_UPDATING -> {
            OutlinedButton(
                onClick = onInstall,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentAlert),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAlert),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Update", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        ModelManager.PackStatus.INSTALLED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentEmerald.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = AccentEmerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Ready",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentEmerald
                        )
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share pack with another phone",
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove pack",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        ModelManager.PackStatus.UNPUBLISHED -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariantBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Text(
                    text = "Not published",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
            }
        }

        else -> {
            OutlinedButton(
                onClick = onInstall,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentSaffron),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSaffron),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (status == ModelManager.PackStatus.PARTIAL) "Resume" else "Download",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
