package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AgentProgressKind
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.SenseAiProtocol
import io.github.ethanbird.senseime.ai.protocol.isTerminal

/**
 * Bounded public projection retained by the Brain process while a durable run is active.
 *
 * A newly-created IME process can attach to the foreground service and receive this compact
 * snapshot instead of owning the run or replaying every token-sized Binder message.
 */
internal class BrainRunReplay(
    private val requestId: String,
    private val generation: Long,
) {
    private var started: AiEvent.Started? = null
    private var status: AiEvent.Status? = null
    private var previewAttempt = 1
    private var preview = ""
    private val progress = linkedMapOf<String, AiEvent.AgentProgress>()
    private var usage: AiEvent.Usage? = null
    private var terminal: AiEvent? = null

    fun accept(event: AiEvent) {
        require(event.requestId == requestId && event.runGeneration == generation)
        when (event) {
            is AiEvent.Started -> started = event
            is AiEvent.Status -> status = event
            is AiEvent.DescriptionDelta -> Unit
            is AiEvent.PreviewReset -> {
                previewAttempt = event.attempt
                preview = ""
            }
            is AiEvent.PreviewDelta -> {
                preview = (preview + event.text).take(SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS)
            }
            is AiEvent.PreviewReplace -> {
                previewAttempt = event.attempt
                preview = event.text.take(SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS)
            }
            is AiEvent.AgentProgress -> {
                val key = event.toolCallId?.let { "tool:$it" } ?: "step:${event.stepId}"
                progress[key] = event
                while (progress.size > MAX_PROGRESS_ROWS) {
                    progress.remove(progress.keys.first())
                }
            }
            is AiEvent.Usage -> usage = event
            else -> if (event.isTerminal) terminal = event
        }
    }

    fun snapshot(): List<AiEvent> = buildList {
        started?.let(::add)
        status?.let(::add)
        addAll(
            progress.values.sortedWith(
                compareBy<AiEvent.AgentProgress> {
                    if (it.kind == AgentProgressKind.TOOL) 1 else 0
                }.thenBy(AiEvent.AgentProgress::revision),
            ),
        )
        if (preview.isNotEmpty()) {
            add(AiEvent.PreviewReset(requestId, generation, previewAttempt))
            add(AiEvent.PreviewDelta(requestId, generation, preview))
        }
        usage?.let(::add)
        terminal?.let(::add)
    }

    private companion object {
        const val MAX_PROGRESS_ROWS = 24
    }
}
