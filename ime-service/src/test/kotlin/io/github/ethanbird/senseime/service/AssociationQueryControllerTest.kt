package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class AssociationQueryControllerTest {
    @Test fun inferenceRunsOffCallerAndDuplicateRendersReusePendingAndCompletedWork() {
        val mainThread = Thread.currentThread()
        val queue = LinkedBlockingQueue<Runnable>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val engine = engine { _, _ ->
            assertNotSame(mainThread, Thread.currentThread())
            calls++; started.countDown(); check(release.await(5, TimeUnit.SECONDS)); words
        }
        val connection = Any()
        val query = query(engine, connection)
        val delivered = mutableListOf<List<AssociationSuggestion>>()
        val controller = AssociationQueryController(Executor { queue.add(it) }) { _, result -> delivered += result }
        try {
            assertTrue(controller.submit(query))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(controller.submit(query(engine, connection)))
            release.countDown()
            requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run()
            assertEquals(listOf(words), delivered)
            assertFalse(controller.submit(query(engine, connection)))
            assertEquals(1, calls)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun closeOrCancelAfterWorkerCompletionRevokesQueuedMainDelivery() {
        for (close in listOf(false, true)) {
            val queue = LinkedBlockingQueue<Runnable>()
            var delivered = 0
            val controller = AssociationQueryController(Executor { queue.add(it) }) { _, _ -> delivered++ }
            try {
                controller.submit(query(engine { _, _ -> words }))
                val callback = requireNotNull(queue.poll(5, TimeUnit.SECONDS))
                if (close) controller.close() else controller.cancel()
                callback.run()
                assertEquals(0, delivered)
            } finally { controller.close() }
        }
    }

    @Test fun newerEditorRuntimeContextAndPrivacyRequestsRevokeAlreadyQueuedResults() {
        val queue = LinkedBlockingQueue<Runnable>()
        val engine = engine { _, _ -> words }
        val connection = Any()
        val old = query(engine, connection)
        val variants = listOf(
            query(engine, connection, ticket = 2),
            query(engine, connection, editor = 2),
            query(engine, Any()),
            query(engine, connection, context = "昨天"),
            query(engine { _, _ -> words }, connection),
            query(engine, connection, history = false),
        )
        for (new in variants) {
            val received = mutableListOf<AssociationQuery>()
            val controller = AssociationQueryController(Executor { queue.add(it) }) { q, _ -> received += q }
            try {
                controller.submit(old)
                val callback = requireNotNull(queue.poll(5, TimeUnit.SECONDS))
                assertTrue(controller.submit(new))
                callback.run()
                requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run()
                assertEquals(listOf(new), received)
            } finally { controller.close() }
        }
    }

    @Test fun inferenceErrorHidesOptionalPredictionsAndWorkerIsReusable() {
        val queue = LinkedBlockingQueue<Runnable>()
        val results = mutableListOf<List<AssociationSuggestion>>()
        val controller = AssociationQueryController(Executor { queue.add(it) }) { _, r -> results += r }
        try {
            controller.submit(query(engine { _, _ -> error("synthetic failure") }))
            requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run()
            controller.submit(query(engine { _, _ -> words }))
            requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run()
            assertEquals(listOf(emptyList(), words), results)
        } finally { controller.close() }
    }

    private fun query(engine: LocalAssociationEngine, connection: Any = Any(), ticket: Long = 1, editor: Long = 1,
                      context: String = "今天", history: Boolean = true) =
        AssociationQuery(ticket, editor, connection, context, engine, history)

    private fun engine(model: ContextAssociationModel) =
        LocalAssociationEngine(MemoryUserAssociationLexicon(), CharacterBigramModel.EMPTY, model)

    private val words = listOf(AssociationSuggestion("早上", 2f, AssociationSuggestionSource.CONTEXT_LANGUAGE_MODEL))
}
