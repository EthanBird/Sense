package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderTimeouts
import io.github.ethanbird.senseime.brain.api.ReasoningEffort
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.api.ThinkingMode
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
 * Cross-process-safe Provider persistence.
 *
 * The profile and encrypted credential live in one AtomicFile. The AES key is non-exportable and
 * owned by Android Keystore. Every Brain run re-reads this file, so the :ime and :brain processes
 * never depend on stale multi-process SharedPreferences caches.
 */
class ProviderSettingsStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, STORE_LOCK_FILE)

    data class StoredProviderConfig(
        val profile: ProviderProfile,
        val credential: ProviderCredential,
    )

    /**
     * Saves a validated profile. A null [apiKey] preserves the existing credential, while an empty
     * value explicitly selects an unauthenticated endpoint.
     */
    fun save(profile: ProviderProfile, apiKey: CharArray?): Result<Unit> = runCatching {
        try {
            profile.requireValid()
            withStoreLock {
                val previousSecret =
                    if (apiKey == null) readDocumentOrNull()?.let(::encryptedSecret) else null
                val nextSecret = when {
                    apiKey == null -> previousSecret
                    apiKey.isEmpty() -> null
                    else -> {
                        val token = apiKey.concatToString()
                        ProviderCredential.Bearer(token)
                        encrypt(token)
                    }
                }
                val document = encode(profile, nextSecret)
                val stream = file.startWrite()
                try {
                    stream.write(document.toByteArray(StandardCharsets.UTF_8))
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

    fun load(): Result<StoredProviderConfig?> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock null
            val documentRoot = JSONObject(document)
            require(documentRoot.getInt("schema_version") == STORE_SCHEMA_VERSION) {
                "Unsupported AI settings schema"
            }
            val profile = decodeProfile(documentRoot.getJSONObject("profile")).requireValid()
            val secret = encryptedSecret(document)
            val credential = if (secret == null) {
                ProviderCredential.None
            } else {
                ProviderCredential.Bearer(decrypt(secret))
            }
            StoredProviderConfig(profile, credential)
        }
    }

    /** Reads the non-secret profile without asking Keystore to decrypt the API key. */
    fun loadProfile(): Result<ProviderProfile?> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock null
            val documentRoot = JSONObject(document)
            require(documentRoot.getInt("schema_version") == STORE_SCHEMA_VERSION) {
                "Unsupported AI settings schema"
            }
            decodeProfile(documentRoot.getJSONObject("profile")).requireValid()
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
            error("Unable to create private AI settings directory")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun readDocumentOrNull(): String? {
        return try {
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun encode(profile: ProviderProfile, secret: EncryptedSecret?): String =
        JSONObject()
            .put("schema_version", STORE_SCHEMA_VERSION)
            .put(
                "profile",
                JSONObject()
                    .put("schema_version", profile.schemaVersion)
                    .put("id", profile.id)
                    .put("display_name", profile.displayName)
                    .put("api_style", profile.apiStyle.name)
                    .put("base_url", profile.baseUrl)
                    .put("model", profile.model)
                    .put("thinking_mode", profile.thinkingMode.name)
                    .put("reasoning_effort", profile.reasoningEffort.name)
                    .put("streaming", profile.streaming)
                    .put("structured_output", profile.structuredOutput.name)
                    .put("allow_insecure_localhost", profile.allowInsecureLocalhost)
                    .put(
                        "timeouts",
                        JSONObject()
                            .put("connect_ms", profile.timeouts.connectTimeoutMs)
                            .put("first_event_ms", profile.timeouts.firstEventTimeoutMs)
                            .put("idle_ms", profile.timeouts.streamIdleTimeoutMs)
                            .put("total_ms", profile.timeouts.totalTimeoutMs),
                    ),
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

    private fun decodeProfile(json: JSONObject): ProviderProfile {
        val timeouts = json.getJSONObject("timeouts")
        val baseUrl = json.getString("base_url")
        val thinkingMode = json.optString("thinking_mode")
            .takeIf(String::isNotBlank)
            ?.let(ThinkingMode::valueOf)
            ?: ProviderCompatibility.thinkingModeForLegacyProfile(baseUrl)
        return ProviderProfile(
            schemaVersion = json.getInt("schema_version"),
            id = json.getString("id"),
            displayName = json.getString("display_name"),
            apiStyle = ProviderApiStyle.valueOf(json.getString("api_style")),
            baseUrl = baseUrl,
            model = json.getString("model"),
            thinkingMode = thinkingMode,
            reasoningEffort = ReasoningEffort.valueOf(json.getString("reasoning_effort")),
            streaming = json.getBoolean("streaming"),
            structuredOutput = StructuredOutputMode.valueOf(json.getString("structured_output")),
            timeouts = ProviderTimeouts(
                connectTimeoutMs = timeouts.getLong("connect_ms"),
                firstEventTimeoutMs = timeouts.getLong("first_event_ms"),
                streamIdleTimeoutMs = timeouts.getLong("idle_ms"),
                totalTimeoutMs = timeouts.getLong("total_ms"),
            ),
            allowInsecureLocalhost = json.optBoolean("allow_insecure_localhost", false),
        )
    }

    private fun encryptedSecret(document: String): EncryptedSecret? {
        val credential = JSONObject(document).optJSONObject("credential") ?: return null
        require(credential.getString("algorithm") == CIPHER_TRANSFORMATION) {
            "Unsupported AI credential cipher"
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
        private const val STORE_DIRECTORY = "sense-ai"
        private const val STORE_FILE = "provider-profile.json"
        private const val STORE_LOCK_FILE = "provider-profile.lock"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sense.ai.provider.v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val STORE_MUTEX = Any()
    }
}
