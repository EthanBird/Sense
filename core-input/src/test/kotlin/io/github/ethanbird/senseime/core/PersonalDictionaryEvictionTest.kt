package io.github.ethanbird.senseime.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Lock down the comparator being replaced, including ties and every public eviction budget. */
class PersonalDictionaryEvictionTest {
    private val now = 1_800_000_000_000L
    private val weakest = MemoryUserLexicon::class.java.getDeclaredMethod("weakestKey", Collection::class.java)
        .apply { isAccessible = true }

    private fun row(i: Int, full: String = code(i), text: String = "词$i") = LearnedPhrase(
        full, "qq", text, (i % 17) + 1, now - 900_000L,
        now - (i % 31) * 86_400_000L, aliases = setOf("qqq"),
        positiveEvidence = (i % 23).toFloat(), negativeEvidence = (i % 7).toFloat(),
        lastPositiveEvidence = (i % 5) * 0.7f, lastNegativeAtMillis = now - (i % 13) * 86_400_000L,
    )

    private fun code(i: Int) = "pin" + ('a' + i / 676) + ('a' + i / 26 % 26) + ('a' + i % 26)
    private fun key(p: LearnedPhrase) = p.fullPinyin to p.text
    private fun oracle(rows: List<LearnedPhrase>): Map<Pair<String, String>, LearnedPhrase> {
        val all = MemoryUserLexicon(rows, clock = { now }, maximumRecords = 10_000,
            maximumRecordsPerFullPinyin = 10_000, maximumRecordsPerLookupCode = 10_000)
        return rows.map { it.fullPinyin }.distinct().flatMap { all.lookup(it, 10_000) }.associateBy(::key)
    }

    // Exactly the previous weakestKey ordering, using public scoring from an unbounded store.
    private fun legacy(scores: Map<Pair<String, String>, LearnedPhrase>) =
        compareBy<Pair<String, String>> { scores[it]?.rankingBoost ?: Float.NEGATIVE_INFINITY }
            .thenBy { scores[it]?.lastUsedAtMillis ?: Long.MIN_VALUE }.thenByDescending { it.second }

    @Test fun deterministicSubsetsMatchLegacyOrderingIncludingInvalidPersistedEvidence() {
        val values = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f, 0f, -1f, 1f, 10_000f)
        val rows = List(500) { i -> row(i).copy(positiveEvidence = values[i % values.size],
            negativeEvidence = values[(i / 8) % values.size], lastPositiveEvidence = values[(i / 64) % values.size]) }
        var clocks = 0
        val store = MemoryUserLexicon(rows, clock = { clocks++; now }, maximumRecordsPerLookupCode = 1_000)
        val scores = oracle(rows)
        val keys = rows.map(::key) + listOf("absent" to "缺失", "absent" to "另一项")
        val random = Random(3301)
        repeat(1_000) {
            val subset = List(random.nextInt(0, 200)) { keys[random.nextInt(keys.size)] }
            val before = clocks
            assertEquals(subset.minWithOrNull(legacy(scores)), weakest.invoke(store, subset))
            assertEquals("One clock snapshot per eviction, including empty/singleton", before + 1, clocks)
        }
    }

    @Test fun completeTiesKeepFirstEncounterWhileTextAndTimeBreakPartialTies() {
        val same = row(0).copy(positiveEvidence = 10_000f, lastPositiveEvidence = 10f,
            negativeEvidence = 0f, lastNegativeAtMillis = 0L, lastUsedAtMillis = now + 1)
        val rows = listOf(same.copy(fullPinyin = "a", text = "同"), same.copy(fullPinyin = "b", text = "同"),
            same.copy(fullPinyin = "c", text = "甲"), same.copy(fullPinyin = "d", text = "乙"),
            same.copy(fullPinyin = "e", text = "同", lastUsedAtMillis = now + 2))
        val store = MemoryUserLexicon(rows, clock = { now })
        val a = key(rows[0]); val b = key(rows[1])
        assertEquals(a, weakest.invoke(store, listOf(a, b)))
        assertEquals(b, weakest.invoke(store, listOf(b, a)))
        val scores = oracle(rows)
        for (keys in listOf(rows.map(::key), rows.map(::key).reversed(), listOf(key(rows[4]), a)))
            assertEquals(keys.minWithOrNull(legacy(scores)), weakest.invoke(store, keys))
        assertEquals(null, weakest.invoke(store, emptyList<Pair<String, String>>()))
        assertEquals(a, weakest.invoke(store, listOf(a)))
    }

    @Test fun saturatedInitialsAndAliasBucketsPreserveEveryCanonicalRow() {
        val rows = List(500) { row(it) }
        val scores = oracle(rows); val retained = linkedSetOf<Pair<String, String>>()
        rows.forEach { retained += key(it); if (retained.size > 128) retained.remove(retained.minWithOrNull(legacy(scores))) }
        val forgotten = mutableListOf<Pair<String, String>>()
        val store = MemoryUserLexicon(rows, clock = { now }, onForget = { f, t -> forgotten += f to t })
        for (query in listOf("qq", "qqq")) assertEquals(retained, store.lookup(query, 1_000).map(::key).toSet())
        rows.forEach { assertEquals(it.text, store.lookup(it.fullPinyin, 1).single().text) }
        assertTrue("Index pressure alone must not delete durable words", forgotten.isEmpty())
    }

    @Test fun fullAndGlobalBudgetsRemoveExactlyTheLegacyWeakestAndAllItsIndices() {
        for (sameCode in listOf(false, true)) {
            val rows = List(24) { row(it, full = if (sameCode) "quanli" else code(it)) }
            val scores = oracle(rows); val expected = rows.map(::key).toMutableList()
            val removed = mutableListOf<Pair<String, String>>()
            while (expected.size > 8) removed += expected.minWithOrNull(legacy(scores))!!.also { expected.remove(it) }
            val forgotten = mutableListOf<Pair<String, String>>()
            val store = MemoryUserLexicon(rows, clock = { now }, onForget = { f, t -> forgotten += f to t },
                maximumRecords = if (sameCode) 100 else 8, maximumRecordsPerFullPinyin = if (sameCode) 8 else 64)
            assertEquals(removed, forgotten)
            for (query in listOf("qq", "qqq")) assertEquals(expected.toSet(), store.lookup(query, 100).map(::key).toSet())
            assertEquals(expected.toSet(), rows.flatMap { store.lookup(it.fullPinyin, 100) }.map(::key).toSet())
        }
    }

    @Test fun emptyAliasFastPathKeepsRestorationLearningAndSubsequentAliasUpdatesIndependent() {
        val first = row(0).copy(aliases = emptySet())
        val second = row(1).copy(aliases = emptySet())
        val store = MemoryUserLexicon(listOf(first, second), clock = { now })
        assertTrue(store.lookup(first.fullPinyin, 1).single().aliases.isEmpty())
        assertTrue(store.lookup(second.fullPinyin, 1).single().aliases.isEmpty())
        assertTrue(store.lookup("qqq", 10).isEmpty())
        val updated = store.record(first.fullPinyin, first.initials, first.text,
            setOf("Q Q Q", "zzz", "", first.fullPinyin, first.initials))
        assertEquals(setOf("qqq", "zzz"), updated.aliases)
        assertEquals(first.text, store.lookup("qqq", 1).single().text)
        assertTrue(store.lookup(second.fullPinyin, 1).single().aliases.isEmpty())
        assertEquals(updated.aliases, store.record(first.fullPinyin, first.initials, first.text).aliases)
        assertTrue(store.record("xinci", "xc", "新词").aliases.isEmpty())
    }
}
