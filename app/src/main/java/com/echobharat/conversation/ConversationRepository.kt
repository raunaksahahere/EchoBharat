package com.echobharat.conversation

import android.content.Context
import android.util.Log
import com.echobharat.mesh.Delivery
import com.echobharat.mesh.EchoBharatMeshManager
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.Languages
import com.echobharat.schema.MessageType
import com.echobharat.schema.Peer
import com.echobharat.schema.VoicePreferences
import com.echobharat.services.AndroidConversationStorageCipher
import com.echobharat.stt.SttManager
import com.echobharat.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The conversation layer between the mesh and the screens.
 *
 * It lives for the whole process, not for a screen, which is what fixes three things at
 * once: messages that arrive while no chat is open are kept (they used to be dropped), a
 * conversation survives leaving and re-entering it, and alerts are spoken with the app in
 * the background.
 *
 * Model work — translating and speaking — goes through one queue, one job at a time. Two
 * messages arriving together used to start two syntheses that talked over each other, and
 * running models concurrently on a low-end phone is the fastest way to run out of memory.
 */
class ConversationRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "Conversations"

        /** How far back re-translation reaches when a conversation is opened. */
        private const val RETRANSLATE_WINDOW = 40

        private const val SAVE_DEBOUNCE_MS = 750L

        @Volatile
        private var instance: ConversationRepository? = null

        fun getInstance(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(context.applicationContext).also { instance = it }
            }
    }

    private sealed interface Job {
        /** Translate (if needed) and optionally speak a message that just arrived. */
        data class Incoming(val msgId: String, val speak: Boolean) : Job
        /** Bring an older message's translation up to date with the current language. */
        data class Retranslate(val msgId: String) : Job
        data class ReadAloud(val msgId: String) : Job
    }

    private val mesh = EchoBharatMeshManager.getInstance(context)
    private val prefs = VoicePreferences.getInstance(context)
    val engines = VoiceEngines.getInstance(context)

    private val log = ConversationLog()
    private val store = ConversationStore(
        file = File(context.filesDir, "conversations/history.bin"),
        cipher = AndroidConversationStorageCipher("echobharat_conversations_v1")
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<Job>(Channel.UNLIMITED)

    val conversations: StateFlow<Map<String, Conversation>> = log.state

    private val _activePeer = MutableStateFlow<String?>(null)

    /** The conversation on screen, or null. Incoming lines there are read, not unread. */
    val activePeer: StateFlow<String?> = _activePeer.asStateFlow()

    @Volatile
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true

        scope.launch {
            val restored = withContext(Dispatchers.IO) { store.load() }
            log.restore(restored)
            Log.i(TAG, "Restored ${restored.size} conversation(s)")

            // Persist after the restore so the empty initial state never overwrites history.
            launch {
                log.state.drop(1).debounce(SAVE_DEBOUNCE_MS).collect { snapshot ->
                    withContext(Dispatchers.IO) { store.save(snapshot.values) }
                }
            }
            launch { mesh.incomingMessages.collect { onIncoming(it) } }
            launch { mesh.deliveryUpdates.collect { log.updateDelivery(it.msgId, it.delivery) } }
            launch {
                mesh.connectedPeers.collect { peers ->
                    peers.forEach { log.touchPeer(it.peerId, it.name, it.deviceModel) }
                }
            }
            launch {
                prefs.activeLanguage.drop(1).distinctUntilChanged().collect {
                    _activePeer.value?.let { peer -> queueRetranslation(peer) }
                }
            }
        }
        scope.launch { for (job in jobs) runJob(job) }
    }

    // ---- screens -----------------------------------------------------------------------

    fun openConversation(peer: Peer) {
        _activePeer.value = peer.peerId
        log.touchPeer(peer.peerId, peer.name, peer.deviceModel)
        log.markRead(peer.peerId)
        queueRetranslation(peer.peerId)
    }

    fun closeConversation(peerId: String) {
        if (_activePeer.value == peerId) _activePeer.value = null
        log.markRead(peerId)
    }

    fun clearConversation(peerId: String) = log.clear(peerId)

    fun readAloud(msgId: String) {
        jobs.trySend(Job.ReadAloud(msgId))
    }

    // ---- sending -----------------------------------------------------------------------

    fun sendText(peer: Peer, text: String, lang: String, alert: Boolean): EchoBharatMessage =
        send(peer, mesh.compose(MessageType.TYPED_TEXT, text, lang, alert), timings = null)

    fun sendSpeech(peer: Peer, heard: SttManager.Transcript, lang: String, alert: Boolean): EchoBharatMessage =
        send(
            peer,
            mesh.compose(MessageType.VOICE_TEXT, heard.text, lang, alert),
            Timings(sttMs = heard.inferenceMs, audioMs = heard.audioMs)
        )

    private fun send(peer: Peer, message: EchoBharatMessage, timings: Timings?): EchoBharatMessage {
        // Recorded before it is handed to the mesh, so a delivery update can never arrive
        // for a message the log has not seen yet.
        log.record(
            peerId = peer.peerId,
            peerName = peer.name,
            deviceModel = peer.deviceModel,
            entry = ChatEntry(message, outgoing = true, delivery = Delivery.QUEUED, timings = timings),
            countUnread = false
        )
        if (!mesh.sendPrivate(message, peer.peerId)) {
            log.updateDelivery(message.msgId, Delivery.FAILED)
        }
        return message
    }

    // ---- receiving ---------------------------------------------------------------------

    private fun onIncoming(message: EchoBharatMessage) {
        val peerId = message.senderId
        val open = _activePeer.value == peerId
        val added = log.record(
            peerId = peerId,
            peerName = message.senderName,
            deviceModel = message.deviceModel,
            entry = ChatEntry(message, outgoing = false),
            countUnread = !open
        )
        // A copy of something already recorded: no second translation, no second reading.
        if (!added) return

        val urgent = message.isAlert ||
            message.type == MessageType.ALERT ||
            message.type == MessageType.SOS
        val speak = urgent || (open && prefs.autoSpeak.value)

        // A message nobody is looking at and nobody will hear is translated when its
        // conversation is opened, so a busy mesh does not keep a 300 MB model resident
        // in the background.
        if (open || speak) jobs.trySend(Job.Incoming(message.msgId, speak))
    }

    private fun queueRetranslation(peerId: String) {
        val target = prefs.activeLanguage.value
        log.state.value[peerId]?.entries
            ?.takeLast(RETRANSLATE_WINDOW)
            ?.filter { !it.outgoing && it.translation?.target != target }
            ?.forEach { jobs.trySend(Job.Retranslate(it.msgId)) }
    }

    private suspend fun runJob(job: Job) {
        try {
            when (job) {
                is Job.Incoming -> {
                    translate(job.msgId)
                    if (job.speak) speak(job.msgId)
                }
                is Job.Retranslate -> translate(job.msgId)
                is Job.ReadAloud -> speak(job.msgId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Job $job failed: ${e.message}", e)
        }
    }

    private suspend fun translate(msgId: String) {
        val entry = log.entry(msgId) ?: return
        if (entry.outgoing) return
        val target = prefs.activeLanguage.value
        if (entry.translation?.target == target) return

        val message = entry.message
        val result = if (!Languages.isDetermined(message.srcLang)) {
            StoredTranslation(target, StoredTranslation.Status.UNKNOWN_SOURCE)
        } else {
            when (val outcome = engines.translation.translate(message.text, message.srcLang, target)) {
                is TranslationManager.Outcome.NotNeeded ->
                    StoredTranslation(target, StoredTranslation.Status.NOT_NEEDED)
                is TranslationManager.Outcome.Translated ->
                    StoredTranslation(
                        target, StoredTranslation.Status.TRANSLATED, outcome.text, outcome.millis, outcome.via
                    )
                is TranslationManager.Outcome.Failed ->
                    StoredTranslation(target, StoredTranslation.Status.UNAVAILABLE)
            }
        }
        log.update(msgId) {
            it.copy(
                translation = result,
                timings = (it.timings ?: Timings()).copy(translateMs = result.millis)
            )
        }
    }

    /**
     * Reads a message aloud in a voice that can actually pronounce it: the translation in
     * this phone's language, or else the original in its own language when that voice is
     * installed. Anything else stays silent — Hindi read out by an English voice is worse
     * than nothing.
     */
    private suspend fun speak(msgId: String) {
        val entry = log.entry(msgId) ?: return
        val message = entry.message
        val translation = entry.translation
        val (text, lang) = when {
            translation?.status == StoredTranslation.Status.TRANSLATED && translation.text != null ->
                translation.text to translation.target
            Languages.isDetermined(message.srcLang) && engines.tts.isAvailable(message.srcLang) ->
                message.text to message.srcLang
            else -> {
                Log.w(TAG, "Not speaking $msgId: no voice for '${message.srcLang}' and no translation")
                return
            }
        }
        val alert = message.isAlert || message.type == MessageType.ALERT || message.type == MessageType.SOS
        val spoken = engines.tts.speak(text, lang, alert) ?: return
        log.update(msgId) {
            it.copy(
                timings = (it.timings ?: Timings()).copy(
                    synthesisMs = spoken.synthesisMs,
                    spokenMs = spoken.audioMs
                )
            )
        }
    }
}
