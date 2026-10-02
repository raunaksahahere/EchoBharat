package com.echobharat.schema

import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Core application-level message payload carried inside bitchat mesh packets.
 *
 * CRITICAL CONSTRAINT:
 * Only text is ever transmitted over the mesh — NEVER audio bytes.
 */
@Parcelize
data class EchoBharatMessage(
    @SerializedName("v")
    val v: Int = 1,

    @SerializedName("msgId")
    val msgId: String = UUID.randomUUID().toString(),

    @SerializedName("type")
    val type: MessageType = MessageType.VOICE_TEXT,

    @SerializedName("srcLang")
    val srcLang: String = "hi",

    @SerializedName("text")
    val text: String,

    @SerializedName("senderName")
    val senderName: String,

    @SerializedName("senderId")
    val senderId: String,

    @SerializedName("deviceModel")
    val deviceModel: String,

    @SerializedName("isAlert")
    val isAlert: Boolean = false,

    @SerializedName("ts")
    val ts: Long = System.currentTimeMillis(),

    /** Sender's latitude at send time. Null whenever there was no GPS fix. */
    @SerializedName("lat")
    val lat: Double? = null,

    /** Sender's longitude at send time. Null whenever there was no GPS fix. */
    @SerializedName("lon")
    val lon: Double? = null,

    /** Reported accuracy of [lat]/[lon] in metres, for honest display. */
    @SerializedName("gpsAccuracyM")
    val gpsAccuracyM: Float? = null,

    /**
     * Wall-clock instant after which this message must stop propagating.
     * Set for SOS (creation + 1 hour); null for ordinary traffic.
     */
    @SerializedName("expiresAt")
    val expiresAt: Long? = null,

    /** For SOS_RESOLVED: the msgId of the announcement being cancelled. */
    @SerializedName("refMsgId")
    val refMsgId: String? = null
) : Parcelable {

    val isSos: Boolean get() = type == MessageType.SOS

    val hasLocation: Boolean get() = lat != null && lon != null

    /** True once [expiresAt] has passed. Messages without an expiry never expire. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && now >= expiresAt

    /** Remaining lifetime in milliseconds, floored at zero. */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        expiresAt?.let { (it - now).coerceAtLeast(0L) } ?: 0L

    fun origin(): com.echobharat.mesh.RangePolicy.Origin? =
        if (lat != null && lon != null) {
            com.echobharat.mesh.RangePolicy.Origin(lat, lon)
        } else null


    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toByteArray(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val gson: Gson = GsonBuilder().create()

        /**
         * Parses a payload, or returns null when it is not a usable message.
         *
         * Gson fills objects reflectively and ignores Kotlin nullability, so a payload
         * missing `text`, or carrying a `type` this build does not know (a newer client's),
         * would otherwise arrive as an object whose non-null fields are null — and crash
         * whichever screen touched it first.
         */
        fun fromJson(json: String): EchoBharatMessage? {
            val parsed = try {
                gson.fromJson(json, EchoBharatMessage::class.java)
            } catch (e: Exception) {
                null
            } ?: return null
            return parsed.takeIf { it.isWellFormed() }
        }

        @Suppress("SENSELESS_COMPARISON")
        private fun EchoBharatMessage.isWellFormed(): Boolean =
            msgId != null && msgId.isNotBlank() &&
                type != null &&
                text != null &&
                senderId != null &&
                senderName != null &&
                deviceModel != null &&
                srcLang != null

        fun fromByteArray(bytes: ByteArray): EchoBharatMessage? {
            return try {
                fromJson(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }
}
