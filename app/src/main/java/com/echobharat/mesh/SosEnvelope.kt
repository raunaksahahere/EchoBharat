package com.echobharat.mesh

import com.echobharat.mesh.noise.NoisePeerIdentity
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.MessageType
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Signed, origin-authenticated distress event. Relays must forward its wire bytes unchanged. */
data class SosEnvelope(
    val action: Action,
    val eventId: ByteArray,
    val revision: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val originSigningKey: ByteArray,
    val originNoiseKey: ByteArray,
    val srcLang: String,
    val text: String,
    val senderName: String,
    val deviceModel: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Float? = null,
    val signature: ByteArray
) {
    enum class Action(val wire: Int) { RAISE(1), UPDATE(2), CANCEL(3) }

    val originPeerId: String? get() = NoisePeerIdentity.derivePeerID(originNoiseKey)
    val originFingerprint: String get() = sha256Hex(originSigningKey)
    val isCancellation: Boolean get() = action == Action.CANCEL
    val hasLocation: Boolean get() = latitude != null && longitude != null && gpsAccuracyM != null

    fun toMessage(): EchoBharatMessage = EchoBharatMessage(
        v = 2,
        msgId = eventId.toHex(),
        type = if (isCancellation) MessageType.SOS_RESOLVED else MessageType.SOS,
        srcLang = srcLang,
        text = if (isCancellation) "Resolved" else text,
        senderName = senderName,
        senderId = originPeerId.orEmpty(),
        deviceModel = deviceModel,
        isAlert = !isCancellation,
        ts = createdAt,
        lat = latitude,
        lon = longitude,
        gpsAccuracyM = gpsAccuracyM,
        expiresAt = expiresAt,
        refMsgId = if (isCancellation) eventId.toHex() else null,
        sosVerified = true,
        sosOriginKeyFingerprint = originFingerprint
    )

    companion object {
        const val VERSION = 2
        const val WIRE_PREFIX = "EB2SOS:"
        const val MAX_WIRE_BYTES = 16 * 1024
        const val MAX_TEXT_BYTES = 4096
        const val MAX_FIELD_BYTES = 512
        const val LIFETIME_MS = 60 * 60 * 1000L
        const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
        private const val SIGNATURE_BYTES = 64
        private const val PUBLIC_KEY_BYTES = 32
        private const val EVENT_ID_BYTES = 16
        private val DOMAIN = "EchoBharat/SOS/v2\u0000".toByteArray(Charsets.UTF_8)

        fun create(
            action: SosEnvelope.Action,
            eventId: ByteArray,
            revision: Long,
            createdAt: Long,
            expiresAt: Long,
            originSigningKey: ByteArray,
            originNoiseKey: ByteArray,
            srcLang: String,
            text: String,
            senderName: String,
            deviceModel: String,
            latitude: Double? = null,
            longitude: Double? = null,
            gpsAccuracyM: Float? = null,
            privateKey: ByteArray
        ): SosEnvelope? {
            if (eventId.size != EVENT_ID_BYTES || revision < 0L) return null
            if (!validTimes(createdAt, expiresAt, createdAt)) return null
            val unsigned = SosEnvelope(
                action, eventId.copyOf(), revision, createdAt, expiresAt,
                originSigningKey.copyOf(), originNoiseKey.copyOf(), srcLang, text,
                senderName, deviceModel, latitude, longitude, gpsAccuracyM, ByteArray(0)
            )
            if (!unsigned.isCanonicalDataValid()) return null
            val signature = sign(unsigned.signedBytes(), privateKey) ?: return null
            return unsigned.copy(signature = signature)
        }

        fun encode(envelope: SosEnvelope): ByteArray? {
            if (!envelope.isCanonicalDataValid() || envelope.signature.size != SIGNATURE_BYTES) return null
            return envelope.signedBytes() + envelope.signature
        }

        fun encodeWire(envelope: SosEnvelope): ByteArray? {
            val encoded = encode(envelope) ?: return null
            val value = Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
            return (WIRE_PREFIX + value).toByteArray(Charsets.US_ASCII)
        }

        fun decodeWire(wire: ByteArray): SosEnvelope? {
            if (wire.size <= WIRE_PREFIX.length || wire.size > MAX_WIRE_BYTES) return null
            val text = wire.toString(Charsets.US_ASCII)
            if (!text.startsWith(WIRE_PREFIX) || text.any { it.code > 127 }) return null
            val encoded = runCatching { Base64.getUrlDecoder().decode(text.substring(WIRE_PREFIX.length)) }.getOrNull()
                ?: return null
            return decode(encoded)
        }

        fun decode(encoded: ByteArray): SosEnvelope? {
            if (encoded.size < DOMAIN.size + 4 + SIGNATURE_BYTES || encoded.size > MAX_WIRE_BYTES) return null
            val signatureOffset = encoded.size - SIGNATURE_BYTES
            val signed = encoded.copyOfRange(0, signatureOffset)
            val signature = encoded.copyOfRange(signatureOffset, encoded.size)
            if (!signed.copyOfRange(0, DOMAIN.size).contentEquals(DOMAIN)) return null
            val input = DataInputStream(ByteArrayInputStream(signed, DOMAIN.size, signed.size - DOMAIN.size))
            val bodyLength = input.readInt()
            if (bodyLength < 0 || bodyLength > MAX_WIRE_BYTES || bodyLength != input.available()) return null
            val body = ByteArray(bodyLength)
            input.readFully(body)
            if (input.available() != 0) return null
            return decodeBody(body, signature)
        }

        fun verify(
            envelope: SosEnvelope,
            expectedSigningKey: ByteArray,
            expectedNoiseKey: ByteArray,
            now: Long,
            maxClockSkewMs: Long = MAX_CLOCK_SKEW_MS
        ): Boolean {
            if (!envelope.isCanonicalDataValid()) return false
            if (!envelope.originSigningKey.contentEquals(expectedSigningKey)) return false
            if (!envelope.originNoiseKey.contentEquals(expectedNoiseKey)) return false
            if (!validTimes(envelope.createdAt, envelope.expiresAt, now, maxClockSkewMs)) return false
            if (envelope.action == SosEnvelope.Action.RAISE && envelope.revision != 0L) return false
            if (envelope.action != SosEnvelope.Action.RAISE && envelope.revision <= 0L) return false
            return verifySignature(envelope.signedBytes(), envelope.signature, envelope.originSigningKey)
        }

        fun fingerprint(publicKey: ByteArray): String = sha256Hex(publicKey)

        private fun decodeBody(body: ByteArray, signature: ByteArray): SosEnvelope? = try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readUnsignedByte() != VERSION) return null
            val action = Action.values().firstOrNull { it.wire == input.readUnsignedByte() } ?: return null
            val eventId = ByteArray(EVENT_ID_BYTES).also(input::readFully)
            val revision = input.readLong()
            val createdAt = input.readLong()
            val expiresAt = input.readLong()
            val signingKey = ByteArray(PUBLIC_KEY_BYTES).also(input::readFully)
            val noiseKey = ByteArray(PUBLIC_KEY_BYTES).also(input::readFully)
            val flags = input.readUnsignedByte()
            if (flags and 0xFE != 0) return null
            val latitude: Double?
            val longitude: Double?
            val accuracy: Float?
            if (flags and 1 != 0) {
                latitude = input.readDouble()
                longitude = input.readDouble()
                accuracy = input.readFloat()
            } else {
                latitude = null
                longitude = null
                accuracy = null
            }
            val srcLang = readString(input, MAX_FIELD_BYTES) ?: return null
            val text = readString(input, MAX_TEXT_BYTES) ?: return null
            val senderName = readString(input, MAX_FIELD_BYTES) ?: return null
            val deviceModel = readString(input, MAX_FIELD_BYTES) ?: return null
            if (input.available() != 0) return null
            SosEnvelope(action, eventId, revision, createdAt, expiresAt, signingKey, noiseKey,
                srcLang, text, senderName, deviceModel, latitude, longitude, accuracy, signature)
                .takeIf { it.isCanonicalDataValid() }
        } catch (_: Exception) {
            null
        }

        private fun readString(input: DataInputStream, maxBytes: Int): String? {
            val length = input.readUnsignedShort()
            if (length > maxBytes) return null
            val bytes = ByteArray(length).also(input::readFully)
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
        }

        private fun SosEnvelope.isCanonicalDataValid(): Boolean {
            if (eventId.size != EVENT_ID_BYTES || originSigningKey.size != PUBLIC_KEY_BYTES ||
                originNoiseKey.size != PUBLIC_KEY_BYTES || signature.size !in 0..SIGNATURE_BYTES ||
                revision < 0 || !validTimes(createdAt, expiresAt, createdAt)) return false
            if (action == Action.RAISE && revision != 0L) return false
            if (action != Action.RAISE && revision <= 0L) return false
            if (!validUtf8Field(srcLang, MAX_FIELD_BYTES) ||
                !validUtf8Field(text, MAX_TEXT_BYTES) ||
                !validUtf8Field(senderName, MAX_FIELD_BYTES) ||
                !validUtf8Field(deviceModel, MAX_FIELD_BYTES)
            ) return false
            if ((latitude == null) != (longitude == null) || (latitude == null) != (gpsAccuracyM == null)) return false
            if (latitude != null && (!latitude.isFinite() || !longitude!!.isFinite() || !gpsAccuracyM!!.isFinite() ||
                    latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || gpsAccuracyM < 0f)) return false
            return NoisePeerIdentity.derivePeerID(originNoiseKey) != null
        }

        private fun validUtf8Field(value: String, maxBytes: Int): Boolean {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return bytes.size <= maxBytes && !value.contains('\u0000')
        }

        private fun validTimes(createdAt: Long, expiresAt: Long, now: Long, skew: Long = 0L): Boolean {
            if (createdAt <= 0L || expiresAt <= createdAt || expiresAt - createdAt > LIFETIME_MS) return false
            return createdAt <= now + skew && now < expiresAt
        }

        private fun SosEnvelope.signedBytes(): ByteArray {
            val body = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use { data ->
                    data.writeByte(VERSION)
                    data.writeByte(action.wire)
                    data.write(eventId)
                    data.writeLong(revision)
                    data.writeLong(createdAt)
                    data.writeLong(expiresAt)
                    data.write(originSigningKey)
                    data.write(originNoiseKey)
                    val hasLocation = latitude != null && longitude != null && gpsAccuracyM != null
                    data.writeByte(if (hasLocation) 1 else 0)
                    if (hasLocation) {
                        data.writeDouble(latitude!!)
                        data.writeDouble(longitude!!)
                        data.writeFloat(gpsAccuracyM!!)
                    }
                    writeString(data, srcLang)
                    writeString(data, text)
                    writeString(data, senderName)
                    writeString(data, deviceModel)
                }
            }.toByteArray()
            return DOMAIN + ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array() + body
        }

        private fun writeString(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeShort(bytes.size)
            output.write(bytes)
        }

        private fun sign(data: ByteArray, privateKey: ByteArray): ByteArray? = runCatching {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            signer.update(data, 0, data.size)
            signer.generateSignature()
        }.getOrNull()

        private fun verifySignature(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)

        private fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
