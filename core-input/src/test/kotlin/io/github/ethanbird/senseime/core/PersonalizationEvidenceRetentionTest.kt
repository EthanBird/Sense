package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class PersonalizationEvidenceRetentionTest {
    @Test fun weakConfirmationRetainsStrongPreferenceAndReloadedRowsKeepIt() {
        var now = 1_000L
        val journal = mutableListOf<LearnedPhrase>()
        val store = MemoryUserLexicon(clock = { now }, onRecord = { journal += it })
        val original = store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        val before = store.lookup("chengche", 1).single().rankingBoost
        now += 1_000L
        repeat(5) { store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.DEFAULT_ACCEPT) }
        val current = store.lookup("chengche", 1).single()
        assertTrue(current.rankingBoost > before)
        assertEquals(original.lastPositiveEvidence, current.lastPositiveEvidence, .00001f)
        val restored = MemoryUserLexicon(initial = listOf(journal.last()), clock = { now })
        assertEquals(current.rankingBoost, restored.lookup("chengche", 1).single().rankingBoost, .00001f)
    }

    @Test fun confirmationDoesNotRefreshAnOldPeakToItsOriginalStrength() {
        var now = 1_000L
        val store = MemoryUserLexicon(clock = { now })
        val original = store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        now += HALF_LIFE
        val half = store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.DEFAULT_ACCEPT)
        assertEquals(original.lastPositiveEvidence / 2, half.lastPositiveEvidence, .00001f)
        now += HALF_LIFE
        val quarter = store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.DEFAULT_ACCEPT)
        assertEquals(original.lastPositiveEvidence / 4, quarter.lastPositiveEvidence, .00001f)
    }

    @Test fun defaultAcceptanceAloneStaysWeakAndExplicitRejectionIsRetained() {
        val store = MemoryUserLexicon(clock = { 1_000L })
        val weak = store.record("chengche", "cc", "乘车", evidence = UserLearningEvidence.DEFAULT_ACCEPT)
        repeat(10) { store.record("chengche", "cc", "乘车", evidence = UserLearningEvidence.DEFAULT_ACCEPT) }
        assertEquals(weak.lastPositiveEvidence, store.lookup("chengche", 1).single().lastPositiveEvidence, .00001f)
        store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.EXPLICIT_SELECTION)
        store.demote("chengche", "程彻", UserNegativeFeedback.IMMEDIATE_REPLACEMENT)
        store.record("chengche", "cc", "程彻", evidence = UserLearningEvidence.DEFAULT_ACCEPT)
        val rejected = store.lookup("chengche", 4).first { it.text == "程彻" }
        assertEquals(4f, rejected.negativeEvidence, .00001f)
        assertTrue(rejected.rankingBoost < 0f)
    }

    companion object { const val HALF_LIFE = 14L * 24 * 60 * 60 * 1_000 }
}
