package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AssociationSuggestion

internal data class AssociationPresentation(
    val revision: Long,
    val editorSessionId: Long,
    val connectionIdentity: Any?,
    val context: String,
    val suggestions: List<AssociationSuggestion>,
)

/** Main-thread snapshot that fences idle suggestions to one exact editor binding. */
internal class AssociationSession {
    private var nextRevision = 0L
    var current: AssociationPresentation? = null
        private set

    fun publish(
        editorSessionId: Long,
        connectionIdentity: Any?,
        context: String,
        suggestions: List<AssociationSuggestion>,
    ): AssociationPresentation {
        nextRevision = if (nextRevision == Long.MAX_VALUE) 1L else nextRevision + 1L
        return AssociationPresentation(
            revision = nextRevision,
            editorSessionId = editorSessionId,
            connectionIdentity = connectionIdentity,
            context = context,
            suggestions = suggestions.toList(),
        ).also { current = it }
    }

    fun select(
        requestedRevision: Long,
        sourceIndex: Int,
        editorSessionId: Long,
        connectionIdentity: Any?,
        context: String,
    ): AssociationSuggestion? {
        val value = current ?: return null
        if (
            value.revision != requestedRevision ||
            value.editorSessionId != editorSessionId ||
            value.connectionIdentity !== connectionIdentity ||
            value.context != context
        ) return null
        return value.suggestions.getOrNull(sourceIndex)
    }

    fun clear() {
        current = null
    }
}
