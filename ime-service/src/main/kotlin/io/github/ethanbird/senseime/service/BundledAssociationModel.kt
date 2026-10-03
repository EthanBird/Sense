package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.BinaryContextAssociationModel
import io.github.ethanbird.senseime.core.ContextAssociationModel
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Invoked by the existing decoder I/O lane. Missing/corrupt assets retain legacy predictions. */
internal object BundledAssociationModel {
    const val ASSET = "association_words.snwp"
    private const val BYTES = 3_756_683
    private const val SHA256 = "d0b157b46ee6df5fe0466870f6a5bf2951b0498a081d4860b3791bb2f123bf39"
    enum class State { READY, MISSING, INVALID, IO_ERROR }
    data class Result(val model: ContextAssociationModel?, val state: State)

    fun load(open: (String) -> InputStream): Result = try {
        val bytes = open(ASSET).use { input ->
            val buffer = ByteArray(BYTES)
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                require(count > 0) { "Truncated association asset" }
                offset += count
            }
            require(input.read() == -1) { "Oversized association asset" }
            buffer
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == SHA256) { "Association digest mismatch" }
        Result(BinaryContextAssociationModel.fromBytes(bytes), State.READY)
    } catch (_: FileNotFoundException) {
        Result(null, State.MISSING)
    } catch (_: IOException) {
        Result(null, State.IO_ERROR)
    } catch (_: IllegalArgumentException) {
        Result(null, State.INVALID)
    }
}
