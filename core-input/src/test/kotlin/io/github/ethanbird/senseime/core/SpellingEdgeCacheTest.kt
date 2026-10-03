package io.github.ethanbird.senseime.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class SpellingEdgeCacheTest {
    private fun key(text: String = "xian", joints: Long = 0) = SpellingEdgeCache.Key(text, joints)

    @Test fun snapshotsAreImmutableAndForcedJointsAndInventoryInstancesAreIsolated() {
        val cache = SpellingEdgeCache<Int>(); val source = mutableListOf(4)
        val first = cache.getOrCompute(key()) { source }; source.clear()
        assertEquals(listOf(4), first)
        assertSame(first, cache.getOrCompute(key()) { error("miss") })
        assertThrows(UnsupportedOperationException::class.java) { (first as MutableList).clear() }
        assertEquals(listOf(2), cache.getOrCompute(key(joints = 4L)) { listOf(2) })
        assertEquals(listOf(8), cache.getOrCompute(key().copy(previousLetter = 'n'.code)) { listOf(8) })
        assertSame(first, cache.getOrCompute(key()) { error("miss after different preceding letter") })
        assertEquals(listOf(9), SpellingEdgeCache<Int>().getOrCompute(key()) { listOf(9) })
    }

    @Test fun entryAndEdgeBudgetsBoundStorageIncludingEmptyLists() {
        val cache = SpellingEdgeCache<Int>(maximumEntries = 2, maximumEdges = 3)
        cache.getOrCompute(key("a")) { listOf(1) }; cache.getOrCompute(key("b")) { listOf(2) }
        cache.getOrCompute(key("a")) { error("miss") }; cache.getOrCompute(key("c")) { listOf(3, 4) }
        var rebuilt = false
        cache.getOrCompute(key("b")) { rebuilt = true; emptyList() }; assertTrue(rebuilt)
        repeat(3) { cache.getOrCompute(key("empty$it")) { emptyList() } }
        cache.getOrCompute(key("c")) { rebuilt = false; emptyList() }; assertFalse(rebuilt)
    }

    @Test fun oversizedFailuresAndCancellationDoNotPublishAndCachedHitsStillCancel() {
        val cache = SpellingEdgeCache<Int>(maximumEdges = 1); var calls = 0
        repeat(2) { cache.getOrCompute(key("a".repeat(26))) { calls++; emptyList() } }
        repeat(2) { cache.getOrCompute(key()) { calls++; listOf(1, 2) } }
        repeat(2) { assertThrows(IllegalStateException::class.java) { cache.getOrCompute(key()) { calls++; error("fixture") } } }
        assertEquals(6, calls)
        var active = true
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) {
                cache.getOrCompute(key()) { active = false; listOf(1) }
            }
        }
        assertEquals(listOf(2), cache.getOrCompute(key()) { listOf(2) })
        active = true
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) { active = false; cache.getOrCompute(key()) { error("cached") } }
        }
    }

    @Test fun concurrentMissesAndEvictionsNeverReturnPartialOrWrongKeys() {
        val cache = SpellingEdgeCache<String>(maximumEntries = 3, maximumEdges = 5)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0 until 200).map { i -> Callable {
                cache.getOrCompute(key("q${i % 7}", (i % 3).toLong())) { listOf("${i % 7}:${i % 3}") }.single()
            } })
            results.forEachIndexed { i, result -> assertEquals("${i % 7}:${i % 3}", result.get()) }
        } finally { pool.shutdownNow() }
    }
}
