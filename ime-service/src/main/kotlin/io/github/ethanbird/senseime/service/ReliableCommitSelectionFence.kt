package io.github.ethanbird.senseime.service

/**
 * Preserves lexical continuity when the host acknowledges our commit through a
 * later onUpdateSelection callback. Any different callback invalidates the fence.
 */
internal class ReliableCommitSelectionFence(
    private val clock: () -> Long,
    private val maximumAgeMillis: Long = 1_500L,
) {
    private data class ExpectedSelection(
        val editorSessionId: Long,
        val connectionIdentity: Any,
        val cursor: Int,
        val createdAtMillis: Long,
    )

    private var expected: ExpectedSelection? = null

    init {
        require(maximumAgeMillis >= 0L)
    }

    fun expect(editorSessionId: Long, connectionIdentity: Any?, cursor: Int) {
        expected = if (connectionIdentity != null && cursor >= 0) {
            ExpectedSelection(editorSessionId, connectionIdentity, cursor, clock())
        } else {
            null
        }
    }

    fun acknowledge(
        editorSessionId: Long,
        connectionIdentity: Any?,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        val value = expected ?: return false
        expected = null
        if (clock() - value.createdAtMillis > maximumAgeMillis) return false
        return value.editorSessionId == editorSessionId &&
            value.connectionIdentity === connectionIdentity &&
            selectionStart == value.cursor &&
            selectionEnd == value.cursor
    }

    fun clear() {
        expected = null
    }
}
