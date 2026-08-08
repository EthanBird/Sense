package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.github.ethanbird.senseime.brain.api.ActionCredentialRef
import io.github.ethanbird.senseime.brain.api.ActionSkillAuthMode
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

/** Android-Keystore-backed credentials addressed by opaque Action Skill handles. */
class AndroidActionCredentialVault(context: Context) : ActionCredentialVault {
    private val root = File(context.applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, LOCK_FILE)

    override fun store(ref: ActionCredentialRef, secret: CharArray): Result<Unit> = runCatching {
        try {
            require(secret.isNotEmpty()) { "Action credential is empty" }
            withStoreLock {
                val document = readDocumentOrEmpty()
                val credentials = document.getJSONObject("credentials")
                val encrypted = encrypt(secret.concatToString())
                credentials.put(
                    ref.handle,
                    JSONObject()
                        .put("auth_mode", ref.authMode.name)
                        .put("header_name", ref.headerName)
                        .put("algorithm", CIPHER_TRANSFORMATION)
                        .put("iv", encrypted.ivBase64)
                        .put("ciphertext", encrypted.ciphertextBase64),
                )
                writeDocument(document)
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    override fun lease(ref: ActionCredentialRef): Result<ActionCredentialMaterial?> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock null
            val stored = document.getJSONObject("credentials").optJSONObject(ref.handle)
                ?: return@withStoreLock null
            require(stored.getString("auth_mode") == ref.authMode.name) {
                "Action credential authentication mode changed"
            }
            require(stored.optString("header_name").ifBlank { null } == ref.headerName) {
                "Action credential header changed"
            }
            require(stored.getString("algorithm") == CIPHER_TRANSFORMATION)
            val plainText = decrypt(
                EncryptedSecret(
                    ivBase64 = stored.getString("iv"),
                    ciphertextBase64 = stored.getString("ciphertext"),
                ),
            )
            ActionCredentialMaterial(ref, plainText.toCharArray())
        }
    }

    override fun revoke(handle: String): Result<Unit> = runCatching {
        require(handle.matches(io.github.ethanbird.senseime.brain.api.ActionSkillDescriptor.ID_PATTERN))
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock
            document.getJSONObject("credentials").remove(handle)
            writeDocument(document)
        }
    }

    private fun readDocumentOrEmpty(): JSONObject = readDocumentOrNull() ?: JSONObject()
        .put("schema_version", STORE_SCHEMA_VERSION)
        .put("credentials", JSONObject())

    private fun readDocumentOrNull(): JSONObject? = try {
        val document = file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        JSONObject(document).also {
            require(it.getInt("schema_version") == STORE_SCHEMA_VERSION)
            require(it.has("credentials"))
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun writeDocument(document: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(document.toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Action credential directory could not be created")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
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
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(secret.ivBase64, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(secret.ciphertextBase64, Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
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

    private data class EncryptedSecret(val ivBase64: String, val ciphertextBase64: String)

    private companion object {
        const val STORE_SCHEMA_VERSION = 1
        const val STORE_DIRECTORY = "agent/action-credentials"
        const val STORE_FILE = "vault.v1.json"
        const val LOCK_FILE = "vault.lock"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sense.action.credentials.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        val STORE_MUTEX = Any()
    }
}
