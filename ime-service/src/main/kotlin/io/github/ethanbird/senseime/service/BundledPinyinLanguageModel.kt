package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.BinaryCharacterLanguageModel
import io.github.ethanbird.senseime.core.CharacterLanguageModel
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Load on the existing decoder I/O executor, never from an IME view/lifecycle callback. */
internal object BundledPinyinLanguageModel {
    const val ASSET = "pinyin_character_lm.scng"
    const val WEIGHT = .5f // Frozen development selection, m12-p2c-development-freeze.json.
    const val OOV_FEATURE = -4f // E6 bounded feature; not an unknown-glyph probability.
    private const val BYTES = 3_090_930
    private const val SHA256 = "39ea5d90a38ce2b6f498a0dcc9f4b161ea92168759ebeedd03ce2f3340c45a7d"
    enum class State { READY, DISABLED, MISSING, INVALID, IO_ERROR }
    data class Result(val model: CharacterLanguageModel, val state: State)

    fun load(enabled: Boolean = true, open: (String) -> InputStream): Result {
        fun fallback(state: State) = Result(CharacterLanguageModel.EMPTY, state)
        if (!enabled) return fallback(State.DISABLED)
        return try {
            val bytes = open(ASSET).use { input ->
                // Exact pinned allocation: a replaced asset never controls an allocation size.
                val data = ByteArray(BYTES)
                var offset = 0
                while (offset < data.size) {
                    val count = input.read(data, offset, data.size - offset)
                    require(count > 0) { "Truncated model asset" }
                    offset += count
                }
                require(input.read() == -1) { "Model asset exceeds pinned size" }
                data
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == SHA256) { "Model asset digest mismatch" }
            Result(BinaryCharacterLanguageModel.fromBytes(bytes), State.READY)
        } catch (_: FileNotFoundException) {
            fallback(State.MISSING)
        } catch (_: IOException) {
            fallback(State.IO_ERROR)
        } catch (_: IllegalArgumentException) {
            fallback(State.INVALID)
        }
    }
}
