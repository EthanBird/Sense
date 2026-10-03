package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.CharacterBigramModel
import io.github.ethanbird.senseime.core.PinyinDecoder
import java.io.InputStream
import java.security.MessageDigest

/** One exact-size allocation for the installed lexicon, avoiding readBytes' grow-and-copy peak. */
internal object BundledPinyinLexicon {
    const val ASSET = "pinyin_lexicon.bin"
    private const val BYTES = 46_495_514
    private const val SHA256 = "edc44ba08920436ee0b6fbbfed6adad7e5028ac35c2ce4af87c5394fdf046232"
    private val processCache = ProcessPinyinLexiconCache()

    /** Called only on the decoder I/O worker. Cache the immutable base, never a Service,
     * asset opener, user lexicon, English usage store, or personalized decoder wrapper. */
    fun loadForProcess(bigramModel: CharacterBigramModel, open: (String) -> InputStream): PinyinDecoder =
        processCache.getOrLoad { load(bigramModel, open) }

    fun load(bigramModel: CharacterBigramModel, open: (String) -> InputStream): PinyinDecoder {
        val bytes = open(ASSET).use { input ->
            // The package pin, not available() or an untrusted file header, controls allocation.
            val data = ByteArray(BYTES)
            var offset = 0
            while (offset < data.size) {
                val count = input.read(data, offset, data.size - offset)
                require(count > 0) { "Truncated pinyin asset" }
                offset += count
            }
            require(input.read() == -1) { "Pinyin asset exceeds pinned size" }
            data
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == SHA256) { "Pinyin asset digest mismatch" }
        return PinyinDecoder.fromBytes(bytes, bigramModel)
    }
}

/** Single-flight publication avoids overlapping Service generations allocating separate packs. */
internal class ProcessPinyinLexiconCache {
    private var value: PinyinDecoder? = null

    @Synchronized fun getOrLoad(build: () -> PinyinDecoder): PinyinDecoder =
        value ?: build().also { value = it } // failure is not cached; a later start may retry
}
