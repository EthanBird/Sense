package io.github.ethanbird.senseime.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class UserPinyinLatticeTest {
    @Test
    fun matchesOnlyCanonicalOccurrencesNotInitialsOrTypoAliases() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        store.record("chengche", "cc", "程彻", setOf("chengce"))
        val matches = store.matchFullPinyin("wochengchechengche")
        assertEquals(listOf(2, 10), matches.map { it.start })
        assertEquals(listOf(10, 18), matches.map { it.end })
        assertTrue(matches.all { it.phrase.rankingBoost > 0f })
        assertTrue(store.matchFullPinyin("wocc").isEmpty())
        assertTrue(store.matchFullPinyin("wochengce").isEmpty())
    }

    @Test
    fun removingOneHomophoneRetainsItsCodeUntilTheLastOneIsRemoved() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        store.record("xian", "xa", "西安")
        store.record("xian", "x", "先")
        assertTrue(store.forget("xian", "先"))
        assertEquals(listOf("西安"), store.matchFullPinyin("woxian").map { it.phrase.text })
        assertTrue(store.forget("xian", "西安"))
        assertTrue(store.matchFullPinyin("woxian").isEmpty())
        store.record("xian", "x", "现")
        assertEquals(listOf("现"), store.matchFullPinyin("woxian").map { it.phrase.text })
    }

    @Test
    fun restoreAndCapacityEvictionKeepCanonicalLengthIndexConsistent() {
        val saved = MemoryUserLexicon(clock = { 1_000L }).record("chengche", "cc", "程彻")
        val restored = MemoryUserLexicon(initial = listOf(saved), clock = { 1_001L }, maximumRecords = 1)
        assertEquals("程彻", restored.matchFullPinyin("wochengche").single().phrase.text)
        restored.record("zhinengti", "znt", "智能体")
        assertTrue(restored.matchFullPinyin("wochengche").isEmpty())
        assertEquals("智能体", restored.matchFullPinyin("wozhinengti").single().phrase.text)
    }

    @Test
    fun demotionIsVisibleImmediatelyAndEarlierSnapshotsAreNotMutated() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        store.record("zhinengti", "znt", "智能体")
        val before = store.matchFullPinyin("wozhinengti").single()
        store.demote("zhinengti", "智能体", UserNegativeFeedback.QUICK_DELETE)
        val after = store.matchFullPinyin("wozhinengti").single()
        assertTrue(before.phrase.rankingBoost > 0f)
        assertTrue(after.phrase.rankingBoost < 0f)
    }

    @Test
    fun matchingHasIndependentPerCodePerStartAndInputBudgets() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        repeat(24) { store.record("shi", "s", ('一'.code + it).toChar().toString()) }
        assertEquals(4, store.matchFullPinyin("shi").size)
        assertEquals(2, store.matchFullPinyin("shi", 2).size)
        assertTrue(store.matchFullPinyin("shi", 0).isEmpty())
        assertTrue(store.matchFullPinyin("SHI").isEmpty())
        assertTrue(store.matchFullPinyin("shi'qi").isEmpty())
        assertTrue(store.matchFullPinyin("a".repeat(97)).isEmpty())
    }

    @Test
    fun latticeRejectsMisalignedNonHanAndInvalidMetadataEdges() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val phrase = store.record("chengche", "cc", "程彻")
        val rows = listOf(
            UserPinyinMatch(-1, phrase), UserPinyinMatch(0, phrase),
            UserPinyinMatch(2, phrase.copy(text = "English")),
            UserPinyinMatch(2, phrase.copy(initials = "c")),
            UserPinyinMatch(2, phrase.copy(rankingBoost = Float.NaN)),
        )
        assertTrue(UserPinyinLattice.from("wochengche", rows).isEmpty)
        val valid = UserPinyinLattice.from("wochengche", listOf(UserPinyinMatch(2, phrase)))
        assertEquals(10, valid.maximumEnd(2))
        assertEquals(listOf(phrase), valid.at(2, 10))
        assertTrue(valid.at(0, 8).isEmpty())
    }

    @Test
    fun concurrentLearningForgettingAndMatchingReturnWholeRecords() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val workers = Executors.newFixedThreadPool(4)
        try {
            workers.invokeAll((0 until 200).map { i -> Callable {
                if (i % 3 == 0) store.record("chengche", "cc", "程彻")
                if (i % 3 == 1) store.forget("chengche", "程彻")
                store.matchFullPinyin("wochengche").forEach {
                    assertEquals(2, it.start)
                    assertEquals("程彻", it.phrase.text)
                    assertTrue(it.phrase.rankingBoost.isFinite())
                }
            } }).forEach { it.get() }
        } finally {
            workers.shutdownNow()
        }
    }
}
