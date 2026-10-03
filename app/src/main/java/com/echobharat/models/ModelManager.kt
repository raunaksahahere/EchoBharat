package com.echobharat.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.echobharat.BuildConfig
import com.echobharat.conversation.VoiceEngines
import com.echobharat.schema.VoicePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Process-wide, serialized provisioning. Inference never downloads a file. */
class ModelManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        private const val FREE_SPACE_MARGIN = 200L * 1024 * 1024
        const val IMPORT = "import"
        @Volatile private var instance: ModelManager? = null
        fun getInstance(context: Context): ModelManager = instance ?: synchronized(this) {
            instance ?: ModelManager(context.applicationContext).also { instance = it }
        }
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val lang: String, val fileName: String, val fraction: Float) : Progress
        data class Verifying(val lang: String, val fileName: String) : Progress
        data class Done(val lang: String) : Progress
        data class Failed(val lang: String, val reason: String) : Progress
    }
    enum class PackStatus { INSTALLED, UPDATE_AVAILABLE, VOICE_NEEDS_UPDATING, PARTIAL, NOT_INSTALLED, UNPUBLISHED, UNKNOWN }

    private val store = ModelStore(context)
    private val voicePrefs = VoicePreferences.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val downloader by lazy { VerifiedDownloader(http) }
    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _consent = MutableStateFlow<Set<String>>(emptySet())
    val networkConsent: StateFlow<Set<String>> = _consent.asStateFlow()
    private val pendingConsent = mutableMapOf<String, Boolean>() // lang -> voice-only update
    private var started = false
    private var automaticJob: Job? = null

    /** Re-scan on startup, APK replacement's next start, preference enable, and Wi-Fi reconnect. */
    @Synchronized fun start() {
        if (started) return
        started = true
        context.getSystemService(ConnectivityManager::class.java)?.let { connectivity ->
            runCatching {
                connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        requestAutomaticUpdates()
                    }
                })
            }.onFailure { Log.w(TAG, "Network callback unavailable", it) }
        }
        scope.launch { voicePrefs.autoUpdateVoicePacks.collect { enabled -> if (enabled) requestAutomaticUpdates() } }
        requestAutomaticUpdates()
    }

    @Synchronized fun requestAutomaticUpdates() {
        if (automaticJob?.isActive == true) return
        automaticJob = scope.launch {
            if (!voicePrefs.autoUpdateVoicePacks.value) return@launch
            operation.withLock {
                try {
                    // Always re-check the manifest, even at the same APK code after an interrupted update.
                    val prefs = context.getSharedPreferences("voice_pack_updates", Context.MODE_PRIVATE)
                    val previous = prefs.getInt("last_scanned_apk", 0)
                    Log.i(TAG, "Checking voice packs: APK $previous -> ${BuildConfig.VERSION_CODE}")
                    for (spec in ModelCatalog.languages(context)) {
                        if (!voicePrefs.autoUpdateVoicePacks.value) break
                        if (store.voices.needsUpdate(spec) && spec.ttsSpecs.all { it.isPublished }) {
                            installLocked(spec.lang, voiceOnly = true, allowMetered = false, automatic = true)
                        }
                    }
                    prefs.edit().putInt("last_scanned_apk", BuildConfig.VERSION_CODE).apply()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Voice update scan failed", e) }
                finally { _revision.value++ }
            }
        }
    }

    private fun networkPermitted(allowMetered: Boolean): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return VoicePackUpgrade.mayUseNetwork(
            activeNetwork = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = connectivity.isActiveNetworkMetered,
            allowMetered = allowMetered,
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        )
    }

    private fun requestConsent(lang: String, voiceOnly: Boolean) {
        if (networkPermitted(allowMetered = true) && !networkPermitted(allowMetered = false)) {
            pendingConsent[lang] = voiceOnly
            _consent.value = pendingConsent.keys.toSet()
        }
    }

    suspend fun approveNetworkUpdates() = withContext(Dispatchers.IO) {
        operation.withLock {
            val requests = pendingConsent.toMap()
            pendingConsent.clear()
            _consent.value = emptySet()
            for ((lang, voiceOnly) in requests) {
                installLocked(lang, voiceOnly, allowMetered = true, automatic = false)
            }
            _revision.value++
        }
    }

    suspend fun dismissNetworkUpdates() = withContext(Dispatchers.IO) {
        operation.withLock { pendingConsent.clear(); _consent.value = emptySet() }
    }

    /** Explicit download or update; non-Wi-Fi use still requires the screen's confirmation. */
    suspend fun install(lang: String): Boolean = withContext(Dispatchers.IO) {
        operation.withLock {
            try { installLocked(lang, voiceOnly = false, allowMetered = false, automatic = false) }
            finally { _revision.value++ }
        }
    }

    private suspend fun installLocked(lang: String, voiceOnly: Boolean, allowMetered: Boolean, automatic: Boolean): Boolean {
        val spec = ModelCatalog.byLang(context, lang) ?: return fail(lang, "Unknown language")
        try {
            val wanted = if (voiceOnly) spec.ttsSpecs else spec.models
            if (wanted.isEmpty() || wanted.any { !it.isPublished }) return fail(lang, "Not available yet")
            val permit = { networkPermitted(allowMetered) && (!automatic || voicePrefs.autoUpdateVoicePacks.value) }
            if (!voiceOnly) {
                // Recognition is unchanged by a voice upgrade and is not downloaded automatically.
                val missing = spec.sttSpecs.filter { store.resolve(lang, it.fileName) == null }
                val dir = store.installDir(lang).apply { mkdirs() }
                if (!hasRoomFor(lang, dir, missing)) return false
                for (model in missing) {
                    if (!permit()) { requestConsent(lang, voiceOnly); return fail(lang, "Waiting for Wi-Fi or network permission") }
                    if (!downloadAndVerify(lang, model, dir, permit)) { requestConsent(lang, voiceOnly); return false }
                }
            }
            if (store.voices.isCurrent(spec)) {
                if (store.voices.needsVoiceRepair(spec)) return fail(lang, "Voice needs updating — no newer verified voice is available")
                _progress.value = Progress.Done(lang)
                return true
            }
            val dir = store.voices.candidateDir(spec).apply { mkdirs() }
            // Account for complete candidates as well as resumable bytes before copying/downloading.
            val missing = spec.ttsSpecs.filterNot { VoicePackFiles.matches(File(dir, it.fileName), it) }
            if (!hasRoomFor(lang, dir, missing)) return false
            store.voices.seedCandidates(spec)
            for (model in spec.ttsSpecs) {
                if (VoicePackFiles.matches(File(dir, model.fileName), model)) continue
                if (!permit()) { requestConsent(lang, voiceOnly); return fail(lang, "Waiting for Wi-Fi or network permission") }
                if (!downloadAndVerify(lang, model, dir, permit)) { requestConsent(lang, voiceOnly); return false }
            }
            currentCoroutineContext().ensureActive()
            _progress.value = Progress.Verifying(lang, "voice compatibility")
            if (!VoiceEngines.getInstance(context).tts.activateVoicePack(spec)) {
                return fail(lang, "Voice needs updating — replacement could not load; previous files kept")
            }
            _progress.value = Progress.Done(lang)
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Voice installation failed", e); return fail(lang, "Update failed; previous files kept") }
    }

    private suspend fun downloadAndVerify(
        lang: String, model: ModelSpec, dir: File, permit: () -> Boolean = { true }
    ): Boolean {
        _progress.value = Progress.Downloading(lang, model.fileName, 0f)
        return when (val result = downloader.download(
            sources = listOf(model.url, model.mirrorUrl).filter { it.isNotBlank() },
            target = File(dir, model.fileName), sha256 = model.sha256, expectedBytes = model.sizeBytes,
            mayDownload = permit
        ) { _progress.value = Progress.Downloading(lang, model.fileName, it) }) {
            is VerifiedDownloader.Result.Installed -> true
            is VerifiedDownloader.Result.Failed -> fail(lang, result.reason)
        }
    }

    private fun hasRoomFor(lang: String, dir: File, files: List<ModelSpec>): Boolean {
        val needed = files.sumOf {
            (it.sizeBytes - File(dir, "${it.fileName}.part").length()).coerceAtLeast(0)
        }
        if (needed + FREE_SPACE_MARGIN > dir.usableSpace) return fail(lang, "Not enough storage for a safe update; previous files kept")
        return true
    }

    suspend fun uninstall(lang: String): Boolean = withContext(Dispatchers.IO) {
        operation.withLock {
            VoiceEngines.getInstance(context).tts.release()
            val result = store.delete(lang)
            _revision.value++
            result
        }
    }

    suspend fun installTranslation(familyId: String, includeFast: Boolean): Boolean = withContext(Dispatchers.IO) {
        operation.withLock {
            val spec = ModelCatalog.translationFamily(context, familyId) ?: return@withLock fail(familyId, "Unknown translation model")
            val missing = store.missingFamily(spec, includeFast)
            if (missing.any { !it.isPublished }) return@withLock fail(familyId, "Not available yet")
            val dir = store.translationDir.apply { mkdirs() }
            if (!hasRoomFor(familyId, dir, missing)) return@withLock false
            for (model in missing) {
                currentCoroutineContext().ensureActive()
                if (!downloadAndVerify(familyId, model, dir)) return@withLock false
            }
            _progress.value = Progress.Done(familyId)
            _revision.value++
            true
        }
    }

    suspend fun importFiles(uris: List<android.net.Uri>): ModelImporter.Report = withContext(Dispatchers.IO) {
        operation.withLock {
            val languages = ModelCatalog.languages(context)
            val importer = ModelImporter(
                ModelImporter.index(languages, ModelCatalog.translationFamilies(context), store::installDir,
                    store.translationDir, store.voices::candidateDir), File(context.cacheDir, "import")
            )
            var report = ModelImporter.Report()
            for ((i, uri) in uris.withIndex()) {
                val name = displayName(uri) ?: "file ${i + 1}"
                _progress.value = Progress.Verifying(IMPORT, name)
                report += try {
                    context.contentResolver.openInputStream(uri)?.let { importer.import(name, it) }
                        ?: ModelImporter.Report(rejected = listOf(name))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ModelImporter.Report(rejected = listOf(name)) }
            }
            var activationFailed = false
            for (spec in languages) {
                if (!store.voices.isCurrent(spec) && store.voices.candidatesReady(spec)) {
                    if (!VoiceEngines.getInstance(context).tts.activateVoicePack(spec)) activationFailed = true
                }
            }
            if (activationFailed) fail(IMPORT, "Voice needs updating — imported bytes verified but voice could not load")
            else _progress.value = Progress.Idle
            _revision.value++
            report
        }
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun hasTranslation(id: String): Boolean = ModelCatalog.translationFamily(context, id)?.let { store.hasFamily(it) } ?: false
    fun hasFastTranslation(id: String): Boolean = ModelCatalog.translationFamily(context, id)?.let { store.hasFastDecoder(it) } ?: false
    suspend fun uninstallTranslation(id: String) = withContext(Dispatchers.IO) {
        operation.withLock { ModelCatalog.translationFamily(context, id)?.let { store.deleteFamily(it) }; _revision.value++ }
    }

    /** Hash scans run on IO; callers must not call this from composition. */
    suspend fun status(lang: String): PackStatus = withContext(Dispatchers.IO) {
        val spec = ModelCatalog.byLang(context, lang) ?: return@withContext PackStatus.UNKNOWN
        when {
            store.voices.needsVoiceRepair(spec) -> PackStatus.VOICE_NEEDS_UPDATING
            store.voices.needsUpdate(spec) -> PackStatus.UPDATE_AVAILABLE
            store.missing(spec).isEmpty() -> PackStatus.INSTALLED
            spec.models.none { it.isPublished } -> PackStatus.UNPUBLISHED
            store.missing(spec).size < spec.models.size -> PackStatus.PARTIAL
            else -> PackStatus.NOT_INSTALLED
        }
    }

    private fun fail(lang: String, reason: String): Boolean {
        Log.w(TAG, "INSTALL_FAILED[$lang]: $reason")
        _progress.value = Progress.Failed(lang, reason)
        return false
    }
}
