package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class QueryLexicalCacheTest {
    @Test fun repeatedScoutsReadEachRetainedDictionaryRecordOnce() {
        val reads = mutableListOf<Int>()
        val source = mutableListOf(Candidate("我", 1f), Candidate("窝", .5f))
        val cache = QueryLexicalCache({ record, _, _ -> reads += record; source })
        val first = cache.get(7)
        source.clear()
        repeat(48) { assertSame(first, cache.get(7)) }
        assertEquals(listOf(7), reads)
        assertEquals(listOf("我", "窝"), first.map { it.text })
        assertTrue(runCatching { (first as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun bothBudgetsEvictLeastRecentlyUsedRecordsIncludingEmptyRecords() {
        val reads = mutableListOf<Int>()
        val cache = QueryLexicalCache({ record, _, _ ->
            reads += record
            when (record) { 3 -> listOf(Candidate("三"), Candidate("叁")); 4, 5, 6 -> emptyList(); else -> listOf(Candidate("$record")) }
        }, maximumEntries = 2, maximumCandidates = 3)
        cache.get(1); cache.get(2); cache.get(1); cache.get(3)
        cache.get(1) // Candidate budget evicted record 2, not the recently used record 1.
        assertEquals(listOf(1, 2, 3), reads)
        cache.get(2)
        assertEquals(listOf(1, 2, 3, 2), reads)
        cache.get(4); cache.get(5); cache.get(6); cache.get(4)
        assertEquals(listOf(1, 2, 3, 2, 4, 5, 6, 4), reads)
        // Exercise the candidate cap independently of the entry cap.
        val candidateReads = mutableListOf<Int>()
        val candidateBound = QueryLexicalCache({ record, _, _ ->
            candidateReads += record
            listOf(Candidate("甲"), Candidate("乙"))
        }, maximumEntries = 10, maximumCandidates = 3)
        candidateBound.get(1); candidateBound.get(2); candidateBound.get(2); candidateBound.get(1)
        assertEquals(listOf(1, 2, 1), candidateReads)
    }

    @Test fun oversizedResultsExceptionsAndNewQueriesNeverReuseStaleValues() {
        var reads = 0
        val load: (Int, Int?, Int) -> List<Candidate> = { record, _, _ ->
            reads++
            if (record == 1) error("incomplete dictionary read")
            listOf(Candidate("甲"), Candidate("乙"))
        }
        val cache = QueryLexicalCache(load, maximumCandidates = 1)
        repeat(2) { assertEquals(2, cache.get(2).size) }
        repeat(2) { assertTrue(runCatching { cache.get(1) }.isFailure) }
        assertEquals(4, reads)
        val firstQuery = QueryLexicalCache(load)
        firstQuery.get(2); firstQuery.get(2)
        QueryLexicalCache(load).get(2)
        assertEquals(6, reads)
    }

    @Test fun cachedHitsStillHonorCancellationAndInvalidKeysDoNotReachTheLoader() {
        var reads = 0
        val cache = QueryLexicalCache({ _, _, _ -> reads++; listOf(Candidate("词")) })
        var current = true
        val failure = runCatching {
            DecodeWorkScope.whileCurrent({ current }) {
                cache.get(7)
                current = false
                cache.get(7)
            }
        }.exceptionOrNull()
        assertTrue(failure is DecodeSupersededException)
        assertTrue(runCatching { cache.get(-1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, reads)
        assertEquals("词", cache.get(7).single().text)
    }

    @Test fun syllableConstraintsAndPreviousCharactersArePartOfTheCacheKey() {
        val calls = mutableListOf<Triple<Int, Int?, Int>>()
        val cache = QueryLexicalCache({ record, syllables, previous ->
            calls += Triple(record, syllables, previous)
            listOf(Candidate("$record/$syllables/$previous"))
        })
        val plain = cache.get(7)
        val one = cache.get(7, 1)
        val two = cache.get(7, 2)
        val context = cache.get(7, 2, '我'.code)
        assertEquals(4, setOf(plain, one, two, context).size)
        repeat(48) {
            assertSame(one, cache.get(7, 1))
            assertSame(context, cache.get(7, 2, '我'.code))
        }
        assertEquals(4, calls.size)
    }
}
