package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.LearnedWubiCandidate
import io.github.ethanbird.senseime.core.WubiLearningEvidence
import io.github.ethanbird.senseime.core.WubiUserLexiconMutation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersistentWubi86UserLexiconTest {
    @Test
    fun schemaUsesAnIndependentDatabaseTableAndCanonicalCodeKey() {
        assertEquals("sense_wubi86_user_lexicon.db", Wubi86UserLexiconSchema.DATABASE_NAME)
        assertEquals("wubi86_user_candidate", Wubi86UserLexiconSchema.TABLE_CANDIDATE)
        assertEquals(1, Wubi86UserLexiconSchema.DATABASE_VERSION)
        assertEquals("canonical_code", Wubi86UserLexiconSchema.COLUMNS.first())
        assertFalse(Wubi86UserLexiconSchema.COLUMNS.contains("full_pinyin"))
        assertFalse(Wubi86UserLexiconSchema.COLUMNS.contains("initials"))
        assertFalse(Wubi86UserLexiconSchema.COLUMNS.contains("aliases"))
    }

    @Test
    fun loadFailureClosesStorageAndKeepsTheOriginalFailure() {
        val loadFailure = IllegalStateException("load failed")
        val closeFailure = IllegalStateException("close failed")
        val storage = FakeStorage(loadFailure = loadFailure, closeFailure = closeFailure)

        val thrown = captureFailure<IllegalStateException> {
            PersistentWubi86UserLexiconResourceFactory.create(
                openStorage = { storage },
                threadName = "wubi-load-failure-test",
            )
        }

        assertSame(loadFailure, thrown)
        assertEquals(1, storage.closeCount)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }

    @Test
    fun acceptedMutationIsVisibleInMemoryWhilePersistenceIsBlockedThenDrainsBeforeClose() {
        val persistStarted = CountDownLatch(1)
        val releasePersist = CountDownLatch(1)
        val storage = FakeStorage(
            persistAction = { mutation ->
                persistStarted.countDown()
                check(releasePersist.await(5, TimeUnit.SECONDS))
                "persist:${mutationLabel(mutation)}"
            },
        )
        val errors = mutableListOf<Throwable>()
        val lexicon = PersistentWubi86UserLexicon(
            storage = storage,
            clock = { 1_000L },
            threadName = "wubi-persistence-order-test",
            onError = { error -> synchronized(errors) { errors += error } },
        )

        lexicon.record("abcd", "立即可见", WubiLearningEvidence.EXPLICIT_SELECTION)
        assertEquals("立即可见", lexicon.lookup("a", 1).single().text)
        assertTrue(persistStarted.await(5, TimeUnit.SECONDS))

        lexicon.close()
        assertFalse(lexicon.awaitPersistenceClosed(20, TimeUnit.MILLISECONDS))
        captureFailure<IllegalStateException> {
            lexicon.record("abce", "关闭后写入")
        }
        assertEquals("立即可见", lexicon.lookup("abcd", 1).single().text)
        releasePersist.countDown()

        assertTrue(lexicon.awaitPersistenceClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf("persist:upsert:abcd:立即可见", "close"), storage.events())
        assertEquals(listOf("wubi-persistence-order-test"), storage.persistThreadNames())
        assertTrue(synchronized(errors) { errors.isEmpty() })
        lexicon.close()
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun upsertAndDeleteSnapshotsArePersistedInFifoOrder() {
        val storage = FakeStorage()
        val lexicon = PersistentWubi86UserLexicon(
            storage = storage,
            clock = { 1_000L },
            threadName = "wubi-fifo-test",
        )

        lexicon.record("abcd", "候选")
        assertTrue(lexicon.forget("abcd", "候选"))
        lexicon.close()

        assertTrue(lexicon.awaitPersistenceClosed(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("persist:upsert:abcd:候选", "persist:delete:abcd:候选", "close"),
            storage.events(),
        )
    }

    @Test
    fun onePersistenceFailureDoesNotStopLaterSnapshotsOrStorageClose() {
        var attempt = 0
        val storage = FakeStorage(
            persistAction = { mutation ->
                attempt += 1
                if (attempt == 1) throw IllegalStateException("first write failed")
                "persist:${mutationLabel(mutation)}"
            },
        )
        val errors = mutableListOf<Throwable>()
        val lexicon = PersistentWubi86UserLexicon(
            storage = storage,
            clock = { 1_000L },
            threadName = "wubi-error-continuation-test",
            onError = { error -> synchronized(errors) { errors += error } },
        )

        lexicon.record("abcd", "第一")
        lexicon.record("abce", "第二")
        lexicon.close()

        assertTrue(lexicon.awaitPersistenceClosed(5, TimeUnit.SECONDS))
        assertEquals(2, attempt)
        assertEquals(listOf("persist:upsert:abce:第二", "close"), storage.events())
        assertEquals(listOf("first write failed"), synchronized(errors) { errors.map { it.message } })
    }

    @Test
    fun initialRowsLoadSynchronouslyAndLookupNeverTouchesStorage() {
        val initial = LearnedWubiCandidate(
            canonicalCode = "abcd",
            text = "重载",
            useCount = 3,
            createdAtMillis = 10L,
            lastUsedAtMillis = 20L,
            positiveEvidence = 4f,
            lastPositiveEvidence = 2f,
        )
        val callerThread = Thread.currentThread().name
        val storage = FakeStorage(initial = listOf(initial))
        val lexicon = PersistentWubi86UserLexicon(
            storage = storage,
            clock = { 20L },
            threadName = "wubi-reload-test",
        )

        assertEquals(callerThread, storage.loadThreadName)
        repeat(20) {
            assertEquals("重载", lexicon.lookup("ab", 1).single().text)
        }
        assertTrue(storage.events().isEmpty())
        lexicon.close()
        assertTrue(lexicon.awaitPersistenceClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf("close"), storage.events())
    }

    private inline fun <reified T : Throwable> captureFailure(block: () -> Unit): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }

    private class FakeStorage(
        private val initial: List<LearnedWubiCandidate> = emptyList(),
        private val loadFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val persistAction: (WubiUserLexiconMutation) -> String = { mutation ->
            "persist:${mutationLabel(mutation)}"
        },
    ) : Wubi86UserLexiconStorage {
        private val eventLog = mutableListOf<String>()
        private val writerThreads = mutableListOf<String>()

        @Volatile
        var closeCount = 0
            private set

        @Volatile
        var loadThreadName: String? = null
            private set

        override fun loadAll(): List<LearnedWubiCandidate> {
            loadThreadName = Thread.currentThread().name
            loadFailure?.let { throw it }
            return initial
        }

        override fun persist(mutation: WubiUserLexiconMutation) {
            synchronized(writerThreads) { writerThreads += Thread.currentThread().name }
            val event = persistAction(mutation)
            synchronized(eventLog) { eventLog += event }
        }

        override fun close() {
            closeCount += 1
            synchronized(eventLog) { eventLog += "close" }
            closeFailure?.let { throw it }
        }

        fun events(): List<String> = synchronized(eventLog) { eventLog.toList() }

        fun persistThreadNames(): List<String> = synchronized(writerThreads) {
            writerThreads.distinct()
        }
    }
}

private fun mutationLabel(mutation: WubiUserLexiconMutation): String = when (mutation) {
    is WubiUserLexiconMutation.Upsert ->
        "upsert:${mutation.candidate.canonicalCode}:${mutation.candidate.text}"
    is WubiUserLexiconMutation.Delete ->
        "delete:${mutation.canonicalCode}:${mutation.text}"
}
