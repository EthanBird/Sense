package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class QuerySelectionCacheTest {
    @Test fun equalLanguageStatesWithDifferentWordBoundariesRemainSeparate() {
        val source = listOf("世人", "诗人")
        val cache = QuerySelectionCache<String>()
        var calls = 0
        fun get(boundary: Int) = cache.getOrCompute(source, 12L, 1, -1, boundary) { listOf("${++calls}") }
        val compound = get(100)
        val single = get(101)
        val differentCharacter = get(102)
        assertSame(compound,get(100));assertSame(single,get(101));assertSame(differentCharacter,get(102))
        assertEquals(3,calls)
    }

    @Test fun identityStateWidthAndContextAllSeparateSelections() {
        val cache = QuerySelectionCache<String>()
        val source = listOf("甲", "乙")
        val equalButDifferent = ArrayList(source)
        var calls = 0
        fun get(input: List<*>, state: Long = 1L, width: Int = 1, context: Int = -1) =
            cache.getOrCompute(input, state, width, context) { listOf("${++calls}") }
        val first = get(source)
        repeat(40) { assertSame(first, get(source)) }
        get(equalButDifferent); get(source, 2L); get(source, width = 2); get(source, context = 100)
        assertEquals(5, calls)
        assertSame(first, get(source))
    }

    @Test fun retainedValuesAreImmutableAndAllThreeBudgetsBoundStorage() {
        val source = listOf(1)
        var calls = 0
        val cache = QuerySelectionCache<Int>(maximumEntries = 2, maximumInputValues = 10, maximumOutputValues = 3)
        val result = mutableListOf(1)
        val first = cache.getOrCompute(source, 0, 1, -1) { calls++; result }
        result.clear()
        assertEquals(listOf(1), first)
        assertThrows(UnsupportedOperationException::class.java) { (first as MutableList).clear() }
        cache.getOrCompute(source, 1, 1, -1) { calls++; listOf(2) }
        cache.getOrCompute(source, 0, 1, -1) { fail("LRU hit"); emptyList() }
        cache.getOrCompute(source, 2, 1, -1) { calls++; listOf(3, 4) }
        cache.getOrCompute(source, 1, 1, -1) { calls++; listOf(2) }
        assertEquals(4, calls)
        val inputBound = QuerySelectionCache<Int>(maximumEntries = 99, maximumInputValues = 2)
        val large = listOf(1, 2)
        var reads = 0
        inputBound.getOrCompute(large, 0, 1, -1) { reads++; listOf(1) }
        inputBound.getOrCompute(large, 1, 1, -1) { reads++; listOf(1) }
        inputBound.getOrCompute(large, 0, 1, -1) { reads++; listOf(1) }
        assertEquals(3, reads)
    }

    @Test fun oversizedFailureCancellationAndNewQueriesDoNotPublishPartialValues() {
        val cache = QuerySelectionCache<Int>(maximumInputValues = 1, maximumOutputValues = 1)
        val source = listOf(1)
        val tooLarge = listOf(1, 2)
        var calls = 0
        repeat(2) { cache.getOrCompute(tooLarge, 0, 1, -1) { calls++; listOf(1) } }
        repeat(2) { cache.getOrCompute(source, 0, 1, -1) { calls++; listOf(1, 2) } }
        repeat(2) { assertThrows(IllegalStateException::class.java) {
            cache.getOrCompute(source, 1, 1, -1) { calls++; error("incomplete") }
        } }
        assertEquals(6, calls)
        cache.getOrCompute(source, 2, 1, -1) { calls++; listOf(1) }
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ false }) { cache.getOrCompute(source, 2, 1, -1) { emptyList() } }
        }
        QuerySelectionCache<Int>().getOrCompute(source, 2, 1, -1) { calls++; listOf(1) }
        assertEquals(8, calls)
        assertThrows(IllegalArgumentException::class.java) { cache.getOrCompute(source, 0, 0, -1) { emptyList() } }
    }
}
