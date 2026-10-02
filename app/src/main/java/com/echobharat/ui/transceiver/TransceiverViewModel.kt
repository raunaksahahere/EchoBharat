package com.echobharat.ui.transceiver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.echobharat.conversation.Conversation
import com.echobharat.conversation.ConversationRepository
import com.echobharat.mesh.EchoBharatMeshManager
import com.echobharat.schema.Peer
import com.echobharat.stt.SttManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * State and actions for one conversation.
 *
 * The transcript itself lives in [ConversationRepository]; this holds only what belongs to
 * the screen. Released utterances run in the app-wide repository so popping this
 * ViewModel's navigation entry does not cancel transcription and lose the message.
 */
class TransceiverViewModel(
    app: Application,
    private val peerId: String
) : AndroidViewModel(app) {

    companion object {
        fun factory(app: Application, peerId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { TransceiverViewModel(app, peerId) }
        }
    }

    private val repository = ConversationRepository.getInstance(app)
    private val mesh = EchoBharatMeshManager.getInstance(app)
    val stt: SttManager = repository.engines.stt

    val conversation: StateFlow<Conversation?> = repository.conversations
        .map { it[peerId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.conversations.value[peerId])

    /** The peer as the mesh currently sees them, or null while out of range. */
    val livePeer: StateFlow<Peer?> = mesh.connectedPeers
        .map { peers -> peers.firstOrNull { it.peerId == peerId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mesh.connectedPeers.value.firstOrNull { it.peerId == peerId })

    fun onShown(peer: Peer) = repository.openConversation(peer)

    fun onHidden() = repository.closeConversation(peerId)

    fun sendTyped(peer: Peer, text: String, lang: String, alert: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) repository.sendText(peer, trimmed, lang, alert)
    }

    fun startTalking(lang: String) = stt.startListening(lang)

    fun isSpeechInstalled(lang: String): Boolean = stt.isAvailable(lang)

    /** Loads the speech model ahead of the first press. */
    fun prepareSpeech(lang: String) = stt.prepare(lang)

    /** Ends the utterance; transcription and sending continue even if the screen goes. */
    fun stopTalking(peer: Peer, lang: String, alert: Boolean) =
        repository.finishSpeech(peer, lang, alert)

    fun readAloud(msgId: String) = repository.readAloud(msgId)

    fun clearConversation() = repository.clearConversation(peerId)
}
