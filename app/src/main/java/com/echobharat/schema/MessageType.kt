package com.echobharat.schema

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Message types carried in the EchoBharat mesh payload.
 */
@Parcelize
enum class MessageType : Parcelable {
    @SerializedName("VOICE_TEXT")
    VOICE_TEXT,

    @SerializedName("TYPED_TEXT")
    TYPED_TEXT,

    @SerializedName("ALERT")
    ALERT,

    @SerializedName("SYSTEM")
    SYSTEM,

    /** Distress announcement: broadcast, no recipient, optional coordinates. */
    @SerializedName("SOS")
    SOS,

    /** Sender cancelling one of their own SOS announcements early. */
    @SerializedName("SOS_RESOLVED")
    SOS_RESOLVED
}
