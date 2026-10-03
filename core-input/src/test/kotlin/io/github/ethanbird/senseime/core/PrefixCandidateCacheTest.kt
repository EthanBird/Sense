package io.github.ethanbird.senseime.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PrefixCandidateCacheTest {
    private fun key(input: String = "shi", limit: Int = 10, context: Int = -1) = PrefixCandidateCache.Key(input, limit, context)

    @Test
    fun snapshotsAreDetachedImmutableAndKeyedByContextAndBudget() {
        val cache = PrefixCandidateCache()
        val mutable = mutableListOf(Candidate("时"))
        val first = cache.getOrCompute(key()) { mutable }
        mutable.clear()
        assertEquals(listOf(Candidate("时")), first)
        assertSame(first, cache.getOrCompute(key()) { error("cache miss") })
        assertTrue(runCatching { (first as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals("是", cache.getOrCompute(key(context = '我'.code)) { listOf(Candidate("是")) }.single().text)
        assertEquals("试", cache.getOrCompute(key(limit = 1)) { listOf(Candidate("试")) }.single().text)
    }

    @Test
    fun bothEntryAndCandidateBudgetsEvictLeastRecentlyUsedEntries() {
        val cache = PrefixCandidateCache(maximumEntries = 2, maximumCandidates = 3)
        cache.getOrCompute(key("a")) { listOf(Candidate("啊")) }
        cache.getOrCompute(key("e")) { listOf(Candidate("饿")) }
        cache.getOrCompute(key("a")) { error("cache miss") }
        cache.getOrCompute(key("o")) { listOf(Candidate("哦"), Candidate("噢")) }
        assertEquals("new", cache.getOrCompute(key("e")) { listOf(Candidate("new")) }.single().text)
        // Empty entries still consume an entry budget.
        repeat(3) { cache.getOrCompute(key("x$it")) { emptyList() } }
        assertEquals("newer", cache.getOrCompute(key("e")) { listOf(Candidate("newer")) }.single().text)
    }

    @Test
    fun longCompositionsAndOversizedResultsAreNeverRetained() {
        val cache = PrefixCandidateCache(maximumCandidates = 1)
        var calls = 0
        repeat(2) { cache.getOrCompute(key("zhongguoren")) { calls++; emptyList() } }
        repeat(2) { cache.getOrCompute(key()) { calls++; listOf(Candidate("时"), Candidate("是")) } }
        assertEquals(4, calls)
    }

    @Test
    fun simultaneousMissesAndEvictionsPublishCompleteSnapshots() {
        val cache = PrefixCandidateCache(maximumEntries = 3, maximumCandidates = 5)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll((0 until 200).map { i -> Callable {
                val k = key(context = i % 7)
                cache.getOrCompute(k) { listOf(Candidate(k.previousCodePoint.toString())) }.single().text
            } })
            results.forEachIndexed { i, result -> assertEquals((i % 7).toString(), result.get()) }
        } finally {
            workers.shutdownNow()
        }
    }
}
