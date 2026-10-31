package com.echobharat.conversation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.echobharat.services.ConversationStorageCipher
import java.io.File

/**
 * Keeps conversation history on disk, encrypted with a key that never leaves Android
 * Keystore.
 *
 * One file, rewritten atomically (temp file + rename) so a crash mid-write leaves the
 * previous history intact rather than a truncated one. A file that cannot be decrypted —
 * the Keystore key was wiped, the app data was restored onto another phone — is set aside
 * and history starts empty: an unreadable transcript is not worth a crash loop.
 */
internal class ConversationStore(
    private val file: File,
    private val cipher: ConversationStorageCipher,
    private val gson: Gson = GsonBuilder().create()
) {

    companion object {
        private const val TAG = "ConversationStore"
        private const val VERSION = 1

        /** Binds the ciphertext to its purpose, so it cannot be replayed as another blob. */
        private val AAD = "echobharat.conversations.v$VERSION".toByteArray()
    }

    private data class Snapshot(
        @SerializedName("version") val version: Int,
        @SerializedName("conversations") val conversations: List<Conversation>
    )

    fun load(): List<Conversation> {
        if (!file.isFile) return emptyList()
        return try {
            val json = String(cipher.decrypt(file.readBytes(), AAD), Charsets.UTF_8)
            val snapshot = gson.fromJson(json, Snapshot::class.java)
            require(snapshot != null && snapshot.version == VERSION) { "Unsupported history schema" }
            // Gson ignores Kotlin nullability; drop anything a future or corrupt file left
            // half-filled rather than handing the UI a null it was promised could not exist.
            @Suppress("SENSELESS_COMPARISON")
            snapshot.conversations.orEmpty()
                .filter { it != null && !it.peerId.isNullOrBlank() && it.entries != null &&
                    it.peerName != null && it.deviceModel != null }
                .map { c ->
                    val entries = c.entries.filter { entry ->
                        val m = entry?.message
                        m != null && !m.msgId.isNullOrBlank() && m.type != null && m.text != null &&
                            m.srcLang != null && m.senderId != null && m.senderName != null && m.deviceModel != null
                    }.map { entry ->
                        val t = entry.translation
                        if (t != null && (t.target == null || t.status == null)) entry.copy(translation = null) else entry
                    }
                    c.copy(entries = entries, unread = c.unread.coerceIn(0, entries.size))
                }
        } catch (e: Exception) {
            Log.e(TAG, "History unreadable (${e.javaClass.simpleName}); starting empty", e)
            file.renameTo(File(file.parentFile, "${file.name}.unreadable"))
            emptyList()
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.unreadable").delete()
    }

    fun destroyKey() = cipher.destroyKey()

    fun save(conversations: Collection<Conversation>) {
        try {
            file.parentFile?.mkdirs()
            val json = gson.toJson(Snapshot(VERSION, conversations.toList()))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(cipher.encrypt(json.toByteArray(Charsets.UTF_8), AAD))
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not save history: ${e.message}", e)
        }
    }
}
