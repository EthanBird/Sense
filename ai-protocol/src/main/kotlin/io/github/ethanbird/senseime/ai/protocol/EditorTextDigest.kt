package io.github.ethanbird.senseime.ai.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical M8 editor digest: lowercase SHA-256 of the exact snapshot text encoded as UTF-8.
 */
object EditorTextDigest {
    fun sha256Utf8(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return buildString(SenseAiProtocol.SHA256_HEX_CHARS) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
