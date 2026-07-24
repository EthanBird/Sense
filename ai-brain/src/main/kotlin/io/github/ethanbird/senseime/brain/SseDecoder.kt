package io.github.ethanbird.senseime.brain

import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
)

/**
 * Incremental SSE decoder. It scans ASCII line endings in the byte stream and decodes each full
 * line as strict UTF-8, so a multibyte code point may be split across arbitrary network chunks.
 */
internal class SseDecoder(
    private val maxLineBytes: Int = 262_144,
    private val maxEventBytes: Int = 1_048_576,
) {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var eventBytes = 0
    private var finished = false
    private var firstLine = true

    init {
        require(maxLineBytes > 0)
        require(maxEventBytes >= maxLineBytes)
    }

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<SseEvent> {
        check(!finished) { "SSE decoder is already finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "invalid SSE byte range"
        }
        val emitted = mutableListOf<SseEvent>()
        for (index in offset until offset + length) {
            val byte = bytes[index]
            if (byte == '\n'.code.toByte()) {
                processLine(emitted)
            } else {
                if (line.size() >= maxLineBytes) {
                    throw ProviderPayloadException("SSE line exceeds $maxLineBytes bytes")
                }
                line.write(byte.toInt())
            }
        }
        return emitted
    }

    fun finish(): List<SseEvent> {
        check(!finished) { "SSE decoder is already finished" }
        finished = true
        val emitted = mutableListOf<SseEvent>()
        if (line.size() > 0) processLine(emitted)
        dispatch(emitted)
        return emitted
    }

    private fun processLine(emitted: MutableList<SseEvent>) {
        var bytes = line.toByteArray()
        line.reset()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        val decoded = decodeUtf8(bytes)
        val value = if (firstLine) {
            firstLine = false
            decoded.removePrefix("\uFEFF")
        } else {
            decoded
        }
        if (value.isEmpty()) {
            dispatch(emitted)
            return
        }
        if (value.startsWith(":")) return
        val colon = value.indexOf(':')
        val field = if (colon < 0) value else value.substring(0, colon)
        val fieldValue = if (colon < 0) {
            ""
        } else {
            value.substring(colon + 1).removePrefix(" ")
        }
        when (field) {
            "event" -> event = fieldValue
            "data" -> {
                val added = fieldValue.length + if (data.isEmpty()) 0 else 1
                eventBytes = Math.addExact(eventBytes, added)
                if (eventBytes > maxEventBytes) {
                    throw ProviderPayloadException("SSE event exceeds $maxEventBytes characters")
                }
                if (data.isNotEmpty()) data.append('\n')
                data.append(fieldValue)
            }
            "id" -> if (!fieldValue.contains('\u0000')) id = fieldValue
            // retry and unknown extension fields are transport concerns.
        }
    }

    private fun dispatch(emitted: MutableList<SseEvent>) {
        if (data.isNotEmpty()) {
            emitted += SseEvent(event = event, data = data.toString(), id = id)
        }
        data.setLength(0)
        event = null
        eventBytes = 0
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw ProviderPayloadException("SSE contains invalid UTF-8")
    }
}
