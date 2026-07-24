package io.github.ethanbird.senseime.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Cross-process-safe ASR provider persistence.
 *
 * The profile and encrypted API key are committed atomically. Passing null to [save] preserves the
 * existing key; an empty array explicitly clears it. The caller-owned array is zeroed after use.
 */
class SpeechProviderSettingsStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, STORE_LOCK_FILE)

    data class StoredSpeechProviderConfig(
        val profile: SpeechProviderProfile,
        val apiKey: String?,
    )

    fun save(profile: SpeechProviderProfile, apiKey: CharArray?): Result<Unit> = runCatching {
        try {
            profile.requireValid()
            if (apiKey != null && apiKey.isNotEmpty()) {
                SpeechProviderCredentialPolicy.requireValid(apiKey)
            }
            withStoreLock {
                val previousSecret =
                    if (apiKey == null) readDocumentOrNull()?.let(::encryptedSecret) else null
                val nextSecret = when {
                    apiKey == null -> previousSecret
                    apiKey.isEmpty() -> null
                    else -> encrypt(apiKey.concatToString())
                }
                val stream = file.startWrite()
                try {
                    stream.write(
                        encode(profile, nextSecret).toByteArray(StandardCharsets.UTF_8),
                    )
                    stream.flush()
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }
        } finally {
            apiKey?.fill('\u0000')
        }
    }

    fun load(): Result<StoredSpeechProviderConfig?> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock null
            val root = JSONObject(document)
            require(root.getInt("schema_version") == STORE_SCHEMA_VERSION) {
                "Unsupported speech settings schema"
            }
            val profile = decodeProfile(root.getJSONObject("profile")).requireValid()
            val secret = encryptedSecret(document)
            StoredSpeechProviderConfig(
                profile = profile,
                apiKey = secret?.let(::decrypt),
            )
        }
    }

    /** Reads non-secret configuration without asking Android Keystore to decrypt the API key. */
    fun loadProfile(): Result<SpeechProviderProfile?> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock null
            val root = JSONObject(document)
            require(root.getInt("schema_version") == STORE_SCHEMA_VERSION) {
                "Unsupported speech settings schema"
            }
            decodeProfile(root.getJSONObject("profile")).requireValid()
        }
    }

    fun hasCredential(): Boolean =
        runCatching {
            withStoreLock { readDocumentOrNull()?.let(::encryptedSecret) != null }
        }.getOrDefault(false)

    fun clear(): Result<Unit> = runCatching {
        withStoreLock { file.delete() }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Unable to create private speech settings directory")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun readDocumentOrNull(): String? =
        try {
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }

    private fun encode(
        profile: SpeechProviderProfile,
        secret: EncryptedSecret?,
    ): String =
        JSONObject()
            .put("schema_version", STORE_SCHEMA_VERSION)
            .put(
                "profile",
                JSONObject()
                    .put("schema_version", profile.schemaVersion)
                    .put("preset_id", profile.presetId)
                    .put("display_name", profile.displayName)
                    .put("transport", profile.transport.name)
                    .put("protocol", profile.protocol.name)
                    .put("endpoint_url", profile.endpointUrl)
                    .put("model", profile.model)
                    .put("language_tag", profile.languageTag)
                    .put("prefer_on_device", profile.preferOnDevice)
                    .put("interim_results", profile.interimResults)
                    .put("punctuation", profile.punctuation),
            )
            .apply {
                if (secret != null) {
                    put(
                        "credential",
                        JSONObject()
                            .put("algorithm", CIPHER_TRANSFORMATION)
                            .put("iv", secret.ivBase64)
                            .put("ciphertext", secret.ciphertextBase64),
                    )
                }
            }
            .toString()

    private fun decodeProfile(json: JSONObject): SpeechProviderProfile =
        SpeechProviderProfileMigration.migrate(
            SpeechProviderProfile(
                schemaVersion = json.getInt("schema_version"),
                presetId = json.getString("preset_id"),
                displayName = json.getString("display_name"),
                transport = SpeechProviderTransport.valueOf(json.getString("transport")),
                protocol = SpeechProviderProtocol.valueOf(json.getString("protocol")),
                endpointUrl = json.optionalString("endpoint_url"),
                model = json.optionalString("model"),
                languageTag = json.getString("language_tag"),
                preferOnDevice = json.getBoolean("prefer_on_device"),
                interimResults = json.getBoolean("interim_results"),
                punctuation = json.getBoolean("punctuation"),
            ),
        )

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else getString(name).takeIf(String::isNotBlank)

    private fun encryptedSecret(document: String): EncryptedSecret? {
        val credential = JSONObject(document).optJSONObject("credential") ?: return null
        require(credential.getString("algorithm") == CIPHER_TRANSFORMATION) {
            "Unsupported speech credential cipher"
        }
        return EncryptedSecret(
            ivBase64 = credential.getString("iv"),
            ciphertextBase64 = credential.getString("ciphertext"),
        )
    }

    private fun encrypt(plainText: String): EncryptedSecret {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return EncryptedSecret(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
        )
    }

    private fun decrypt(secret: EncryptedSecret): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(
                GCM_TAG_BITS,
                Base64.decode(secret.ivBase64, Base64.NO_WRAP),
            ),
        )
        val bytes = cipher.doFinal(Base64.decode(secret.ciphertextBase64, Base64.NO_WRAP))
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private data class EncryptedSecret(
        val ivBase64: String,
        val ciphertextBase64: String,
    )

    companion object {
        private const val STORE_SCHEMA_VERSION = 1
        private const val STORE_DIRECTORY = "sense-speech"
        private const val STORE_FILE = "provider-profile.json"
        private const val STORE_LOCK_FILE = "provider-profile.lock"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sense.speech.provider.v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val STORE_MUTEX = Any()
    }
}
