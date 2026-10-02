package com.echobharat.schema

import java.util.Arrays

/**
 * Local device identity record.
 * Stored locally in secure device storage; privateKey never leaves the device.
 */
data class Identity(
    val displayName: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val peerId: String,
    val deviceModel: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Identity
        if (displayName != other.displayName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!privateKey.contentEquals(other.privateKey)) return false
        if (peerId != other.peerId) return false
        if (deviceModel != other.deviceModel) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + deviceModel.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String {
        return "Identity(displayName='$displayName', peerId='$peerId', deviceModel='$deviceModel', createdAt=$createdAt)"
    }
}
