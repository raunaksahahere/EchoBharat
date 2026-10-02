package com.echobharat.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.echobharat.schema.Identity
import com.echobharat.services.AppStateStore
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages local cryptographic identity for EchoBharat.
 *
 * Requirements (from Rules.md & Schema.md):
 * 1. User enters a display name on onboarding.
 * 2. App generates Curve25519 keypair and Ed25519 signing keypair.
 * 3. Peer ID is derived from SHA-256 fingerprint of the public key (unique per install,
 *    even for identical device models in the same room).
 * 4. Device model is read from Build.MANUFACTURER + Build.MODEL and cached.
 * 5. Private keys are stored in encrypted device storage and never exported or sent over network.
 * 6. No accounts, no password, no server.
 */
class IdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "IdentityManager"
        private const val PREFS_NAME = "echobharat_identity_secure"

        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_DEVICE_MODEL = "device_model"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_CREATED_AT = "created_at"

        @Volatile
        private var instance: IdentityManager? = null

        fun getInstance(context: Context): IdentityManager {
            return instance ?: synchronized(this) {
                instance ?: IdentityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        initPrefs()
    }

    private fun initPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw IllegalStateException("Android Keystore-backed identity storage is unavailable", e)
        }
    }

    /**
     * Retrieves existing identity or creates a new one with the given display name.
     */
    fun getOrCreateIdentity(displayName: String): Identity {
        val existing = getCurrentIdentity()
        if (existing != null) {
            if (displayName.isNotBlank() && existing.displayName != displayName) {
                setDisplayName(displayName)
                return existing.copy(displayName = displayName)
            }
            AppStateStore.setNickname(existing.displayName)
            return existing
        }

        return createNewIdentity(displayName)
    }

    /**
     * Returns currently stored identity, or null if onboarding has not been completed.
     */
    fun getCurrentIdentity(): Identity? {
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        val pubKeyStr = prefs.getString(KEY_STATIC_PUBLIC_KEY, null) ?: return null
        val privKeyStr = prefs.getString(KEY_STATIC_PRIVATE_KEY, null) ?: return null
        val peerId = prefs.getString(KEY_PEER_ID, null) ?: return null
        val deviceModel = prefs.getString(KEY_DEVICE_MODEL, null) ?: DeviceUtils.getDeviceModel()
        val createdAt = prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis())

        val publicKey = Base64.decode(pubKeyStr, Base64.NO_WRAP)
        val privateKey = Base64.decode(privKeyStr, Base64.NO_WRAP)

        val identity = Identity(
            displayName = displayName,
            publicKey = publicKey,
            privateKey = privateKey,
            peerId = peerId,
            deviceModel = deviceModel,
            createdAt = createdAt
        )
        AppStateStore.setNickname(identity.displayName)
        return identity
    }

    /**
     * Updates the user's display name.
     */
    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        AppStateStore.setNickname(trimmed)
    }

    /**
     * Returns the cached peer ID or generates one if absent.
     */
    fun getPeerId(): String {
        val saved = prefs.getString(KEY_PEER_ID, null)
        if (saved != null) return saved

        val identity = getOrCreateIdentity("User")
        return identity.peerId
    }

    /**
     * Returns the cached device model.
     */
    fun getDeviceModel(): String {
        return prefs.getString(KEY_DEVICE_MODEL, null) ?: DeviceUtils.getDeviceModel()
    }

    /**
     * Returns the static public key bytes (Curve25519 / X25519).
     */
    fun getPublicKey(): ByteArray {
        val pubKeyStr = prefs.getString(KEY_STATIC_PUBLIC_KEY, null)
        return if (pubKeyStr != null) {
            Base64.decode(pubKeyStr, Base64.NO_WRAP)
        } else {
            getOrCreateIdentity("User").publicKey
        }
    }

    /**
     * Returns the Ed25519 signing public key bytes.
     */
    fun getSigningPublicKey(): ByteArray {
        val signingPubKeyStr = prefs.getString(KEY_SIGNING_PUBLIC_KEY, null)
        return if (signingPubKeyStr != null) {
            Base64.decode(signingPubKeyStr, Base64.NO_WRAP)
        } else {
            val keypair = generateEd25519Keys()
            saveSigningKeys(keypair.first, keypair.second)
            keypair.second
        }
    }

    /**
     * Checks if initial identity onboarding is complete.
     */
    fun hasIdentity(): Boolean {
        return prefs.contains(KEY_DISPLAY_NAME) && prefs.contains(KEY_PEER_ID)
    }

    private fun createNewIdentity(displayName: String): Identity {
        val secureRandom = SecureRandom()

        // Generate Curve25519 static keypair (32 bytes private, 32 bytes public)
        val privateKey = ByteArray(32)
        secureRandom.nextBytes(privateKey)
        // Clamp private key for Curve25519
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = (privateKey[31].toInt() and 127).toByte()
        privateKey[31] = (privateKey[31].toInt() or 64).toByte()

        // Compute public key via Curve25519
        val publicKey = ByteArray(32)
        org.bouncycastle.math.ec.rfc7748.X25519.scalarMultBase(privateKey, 0, publicKey, 0)

        // Derive Peer ID: first 16 hex characters of SHA-256(publicKey)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        val peerId = hash.take(8).joinToString("") { "%02x".format(it) }

        // Format device model string
        val deviceModel = DeviceUtils.getDeviceModel()
        val createdAt = System.currentTimeMillis()

        // Generate Ed25519 signing keys
        val signingKeys = generateEd25519Keys()

        // Persist securely
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, displayName.trim().ifEmpty { "EchoUser" })
            .putString(KEY_PEER_ID, peerId)
            .putString(KEY_DEVICE_MODEL, deviceModel)
            .putString(KEY_STATIC_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .putString(KEY_STATIC_PRIVATE_KEY, Base64.encodeToString(privateKey, Base64.NO_WRAP))
            .putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(signingKeys.second, Base64.NO_WRAP))
            .putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(signingKeys.first, Base64.NO_WRAP))
            .putLong(KEY_CREATED_AT, createdAt)
            .apply()

        AppStateStore.setNickname(displayName.trim().ifEmpty { "EchoUser" })
        Log.i(TAG, "Created new EchoBharat identity: peerId=$peerId, model=$deviceModel")

        return Identity(
            displayName = displayName,
            publicKey = publicKey,
            privateKey = privateKey,
            peerId = peerId,
            deviceModel = deviceModel,
            createdAt = createdAt
        )
    }

    private fun generateEd25519Keys(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val priv = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return Pair(priv, pub)
    }

    private fun saveSigningKeys(priv: ByteArray, pub: ByteArray) {
        prefs.edit()
            .putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(priv, Base64.NO_WRAP))
            .putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(pub, Base64.NO_WRAP))
            .apply()
    }
}
