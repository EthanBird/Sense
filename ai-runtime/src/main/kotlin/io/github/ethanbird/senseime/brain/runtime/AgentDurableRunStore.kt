package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.AtomicFile
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal enum class AgentDurableRunOutcome {
    RUNNING,
    ANSWER,
    CANCELLED,
    FAILED,
    PATCH,
}

internal data class AgentDurableRunRecord(
    val requestId: String,
    val generation: Long,
    val userMessage: String,
    val userCreatedAtEpochMs: Long,
    val outcome: AgentDurableRunOutcome = AgentDurableRunOutcome.RUNNING,
    val payload: String = "",
)

/** Atomic cross-process recovery marker shared by the IME projection and Brain run owner. */
internal class AgentDurableRunStore(context: Context) {
    private val root = File(context.filesDir, "agent/sessions/default")
    private val file = AtomicFile(File(root, "durable-run.v1"))

    fun load(): Result<AgentDurableRunRecord?> = runCatching {
        if (!file.baseFile.exists()) return@runCatching null
        AgentDurableRunCodec.decode(
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() },
        )
    }

    fun save(record: AgentDurableRunRecord) {
        if (!root.exists() && !root.mkdirs()) error("Agent session directory could not be created")
        val stream = file.startWrite()
        try {
            stream.write(AgentDurableRunCodec.encode(record).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun complete(event: AiEvent) {
        val current = load().getOrNull() ?: return
        if (current.requestId != event.requestId || current.generation != event.runGeneration) return
        val completed = when (event) {
            is AiEvent.FinalAnswer -> current.copy(
                outcome = AgentDurableRunOutcome.ANSWER,
                payload = event.text,
            )
            is AiEvent.FinalPatch -> current.copy(outcome = AgentDurableRunOutcome.PATCH)
            is AiEvent.Cancelled -> current.copy(
                outcome = AgentDurableRunOutcome.CANCELLED,
                payload = event.reason.name,
            )
            is AiEvent.Failed -> current.copy(
                outcome = AgentDurableRunOutcome.FAILED,
                payload = event.code.name,
            )
            else -> return
        }
        save(completed)
    }

    fun clearIf(requestId: String, generation: Long) {
        val current = load().getOrNull() ?: return
        if (current.requestId == requestId && current.generation == generation) {
            file.delete()
        }
    }

    fun clear() {
        file.delete()
    }
}

internal object AgentDurableRunCodec {
    private const val HEADER = "sense.agent.run.v1"
    private const val MAX_USER_MESSAGE_CHARS = 12_000
    private const val MAX_PAYLOAD_CHARS = 8_192

    fun encode(record: AgentDurableRunRecord): String = buildString {
        appendLine(HEADER)
        appendLine(record.requestId)
        appendLine(record.generation)
        appendLine(record.userCreatedAtEpochMs)
        appendLine(record.outcome.name)
        appendLine(record.userMessage.toWire())
        appendLine(record.payload.toWire())
    }

    fun decode(document: String): AgentDurableRunRecord {
        val lines = document.lineSequence().toList()
        require(lines.size >= 7 && lines[0] == HEADER)
        val record = AgentDurableRunRecord(
            requestId = lines[1],
            generation = lines[2].toLong(),
            userCreatedAtEpochMs = lines[3].toLong(),
            outcome = AgentDurableRunOutcome.valueOf(lines[4]),
            userMessage = lines[5].fromWire(),
            payload = lines[6].fromWire(),
        )
        require(record.requestId.isNotBlank())
        require(record.generation > 0)
        require(record.userMessage.isNotBlank())
        require(record.userMessage.length <= MAX_USER_MESSAGE_CHARS)
        require(record.payload.length <= MAX_PAYLOAD_CHARS)
        return record
    }

    private fun String.toWire(): String = if (isEmpty()) {
        "~"
    } else {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.fromWire(): String = if (this == "~") {
        ""
    } else {
        Base64.getUrlDecoder().decode(this).toString(StandardCharsets.UTF_8)
    }
}
