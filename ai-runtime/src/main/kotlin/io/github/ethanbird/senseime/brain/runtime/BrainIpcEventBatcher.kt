package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.AiEvent

/**
 * Coalesces model-sized text fragments before they cross Binder.
 *
 * Providers commonly split function arguments into one- or two-character deltas. Sending every
 * fragment through Messenger starves the IME main queue and turns Canvas text layout into an
 * O(n²) workload. This buffer preserves event order while merging adjacent deltas of the same
 * kind. Non-text events are deliberately handled by the Service so they can flush this buffer
 * before crossing the process boundary.
 */
internal class BrainIpcEventBatcher(
    private val flushAtChars: Int = DEFAULT_FLUSH_AT_CHARS,
) {
    private val segments = ArrayList<Segment>(4)
    private var bufferedChars = 0

    init {
        require(flushAtChars > 0)
    }

    val isEmpty: Boolean
        get() = segments.isEmpty()

    fun append(event: AiEvent): Boolean {
        val kind = when (event) {
            is AiEvent.DescriptionDelta -> Kind.DESCRIPTION
            is AiEvent.PreviewDelta -> Kind.PREVIEW
            else -> error("Only visible text deltas may be batched")
        }
        val text = when (event) {
            is AiEvent.DescriptionDelta -> event.text
            is AiEvent.PreviewDelta -> event.text
            else -> error("unreachable")
        }
        if (text.isEmpty()) return bufferedChars >= flushAtChars
        val last = segments.lastOrNull()
        if (
            last != null &&
            last.kind == kind &&
            last.requestId == event.requestId &&
            last.generation == event.runGeneration
        ) {
            last.text.append(text)
        } else {
            segments += Segment(
                kind = kind,
                requestId = event.requestId,
                generation = event.runGeneration,
                text = StringBuilder(text),
            )
        }
        bufferedChars += text.length
        return bufferedChars >= flushAtChars
    }

    fun drain(): List<AiEvent> {
        if (segments.isEmpty()) return emptyList()
        val result = segments.map { segment ->
            when (segment.kind) {
                Kind.DESCRIPTION -> AiEvent.DescriptionDelta(
                    requestId = segment.requestId,
                    runGeneration = segment.generation,
                    text = segment.text.toString(),
                )
                Kind.PREVIEW -> AiEvent.PreviewDelta(
                    requestId = segment.requestId,
                    runGeneration = segment.generation,
                    text = segment.text.toString(),
                )
            }
        }
        segments.clear()
        bufferedChars = 0
        return result
    }

    /**
     * Atomically describes the required wire order at a non-text boundary.
     *
     * The caller owns the synchronization boundary. Keeping this operation
     * explicit makes it impossible to accidentally send a terminal event
     * before the final buffered preview/description fragments.
     */
    fun drainBefore(event: AiEvent): List<AiEvent> = drain() + event

    fun clear() {
        segments.clear()
        bufferedChars = 0
    }

    private data class Segment(
        val kind: Kind,
        val requestId: String,
        val generation: Long,
        val text: StringBuilder,
    )

    private enum class Kind {
        DESCRIPTION,
        PREVIEW,
    }

    companion object {
        const val DEFAULT_FLUSH_AT_CHARS = 192
    }
}
