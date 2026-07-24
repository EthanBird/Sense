package io.github.ethanbird.senseime.brain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object SenseSoul {
    const val VERSION = "sense.soul.v1"
    const val RESOURCE_PATH = "sense/soul.md"
    private const val MAX_SOUL_BYTES = 32 * 1_024

    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { load() }

    internal fun load(
        classLoader: ClassLoader = SenseSoul::class.java.classLoader,
    ): String {
        val bytes = classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (output.size() <= MAX_SOUL_BYTES) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("missing classpath resource $RESOURCE_PATH")
        require(bytes.size <= MAX_SOUL_BYTES) { "$RESOURCE_PATH exceeds $MAX_SOUL_BYTES bytes" }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw IllegalStateException("$RESOURCE_PATH is not valid UTF-8", error)
        }
        val normalized = decoded.trim()
        require(normalized.startsWith("# $VERSION")) {
            "$RESOURCE_PATH must start with version marker $VERSION"
        }
        require('\u0000' !in normalized) { "$RESOURCE_PATH contains a NUL character" }
        return normalized
    }
}
