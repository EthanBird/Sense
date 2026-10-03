package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AssociationSuggestion
import io.github.ethanbird.senseime.core.LocalAssociationEngine
import java.util.concurrent.Executor

/** Snapshot captured on the IME thread; the worker never accesses an InputConnection. */
internal class AssociationQuery(
    val ticket: Long,
    val editorSessionId: Long,
    val connectionIdentity: Any?,
    val context: String,
    val engine: LocalAssociationEngine,
    val includeUserHistory: Boolean,
) {
    fun sameAs(other: AssociationQuery): Boolean = ticket == other.ticket &&
        editorSessionId == other.editorSessionId && connectionIdentity === other.connectionIdentity &&
        context == other.context && engine === other.engine && includeUserHistory == other.includeUserHistory
}

/** Main-thread-owned, with a serial worker limited to one running and one pending query. */
internal class AssociationQueryController(
    deliveryExecutor: Executor,
    private val deliver: (AssociationQuery, List<AssociationSuggestion>) -> Unit,
) : AutoCloseable {
    private var current: AssociationQuery? = null
    private var closed = false
    private val runner = LatestOnlyTaskRunner<AssociationQuery, List<AssociationSuggestion>>(
        threadName = "sense-associations",
        work = { query, shouldContinue ->
            if (shouldContinue()) query.engine.suggest(query.context, 8, query.includeUserHistory) else emptyList()
        },
        deliver = { _, query, suggestions ->
            deliveryExecutor.execute { if (!closed && current === query) deliver(query, suggestions) }
        },
        fail = { _, query, _ ->
            // Optional predictions never turn a lookup error into an editor mutation.
            deliveryExecutor.execute { if (!closed && current === query) deliver(query, emptyList()) }
        },
    )

    /** True only for a new query; repeated renders reuse the same pending/published snapshot. */
    fun submit(query: AssociationQuery): Boolean {
        if (closed || current?.sameAs(query) == true) return false
        current = query
        if (runner.submit(query) < 0) current = null
        return true
    }

    fun cancel() {
        current = null
        runner.invalidate()
    }

    override fun close() {
        closed = true
        current = null
        runner.close()
    }
}
