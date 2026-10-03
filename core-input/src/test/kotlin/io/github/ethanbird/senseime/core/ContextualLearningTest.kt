package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class ContextualLearningTest {
    private val right = Candidate("权利", 0f, "quanli", CandidateMatchKind.BASE_EXACT, "ql")
    private val power = Candidate("权力", 0f, "quanli", CandidateMatchKind.BASE_EXACT, "ql")
    private fun decoder(store: UserLexicon) = AdaptivePinyinDecoder(object : InputDecoder {
        override fun decode(composing: String, limit: Int) = listOf(right, power).take(limit)
    }, store, PinyinSyllableSegmenter(setOf("quan", "li")))
    private fun first(decoder: AdaptivePinyinDecoder, context: String, limit: Int = 255) =
        decoder.decodeProgressively(PinyinComposition(emptyList(), "quanli"), context, limit).wholeCandidates.first().text
    private fun choose(decoder: AdaptivePinyinDecoder, context: String, candidate: Candidate) =
        decoder.learn("quanli", candidate, UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1, context))!!

    @Test fun explicitChoicesInDifferentContextsSurviveEachOtherAndRestore() {
        var now = 1_000L
        val store = MemoryUserLexicon(clock = { now++ })
        val decoder = decoder(store)
        choose(decoder, "人民", right)
        choose(decoder, "获得", power)
        for (limit in listOf(1, 12, 255)) {
            assertEquals("人民的选择被另一个语境覆盖", "权利", first(decoder, "人民", limit))
            assertEquals("权力", first(decoder, "获得", limit))
        }
        val restored = decoder(MemoryUserLexicon(store.lookup("quanli", 255), clock = { now++ }))
        assertEquals("权利", first(restored, "人民"))
        assertEquals("权力", first(restored, "获得"))
    }

    @Test fun defaultAcceptanceDoesNotReplaceExplicitContextChoice() {
        var now = 1_000L
        val store = MemoryUserLexicon(clock = { now++ })
        val decoder = decoder(store)
        choose(decoder, "人民", right)
        repeat(20) {
            decoder.learn("quanli", power, UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT, 0, "人民"))
        }
        assertEquals("权利", first(decoder, "人民"))
        // No punctuation-crossing preference; ordinary global learning remains available.
        assertEquals(first(decoder, ""), first(decoder, "人民。"))
    }

    @Test fun latestExplicitChoiceWinsOnlyItsExactContextAndExpires() {
        var now = 1_000L
        val store = MemoryUserLexicon(clock = { now++ })
        val decoder = decoder(store)
        choose(decoder, "人民", right)
        choose(decoder, "获得", power)
        choose(decoder, "人民", power)
        assertEquals("权力", first(decoder, "人民"))
        choose(decoder, "人民", right)
        assertEquals("权利", first(decoder, "人民"))
        assertEquals("权力", first(decoder, "获得"))
        assertFalse(store.lookupInContext("ql", "人民", 8).any { it.preferredInContext })
        assertFalse(store.lookupInContext("quanli", "公民", 8).any { it.preferredInContext })
        now += UserContextSelection.LIFETIME_MILLIS + 1_000
        assertFalse(store.lookupInContext("quanli", "人民", 8).any { it.preferredInContext })
        assertEquals(first(decoder, ""), first(decoder, "人民"))
    }

    @Test fun rejectionIsScopedAndOldCallbackPreservesANewerExplicitSelection() {
        var now = 1_000L
        val store = MemoryUserLexicon(clock = { now++ })
        val decoder = decoder(store)
        val old = choose(decoder, "人民", right)
        choose(decoder, "公民", right)
        choose(decoder, "人民", right)
        val withoutContext = decoder.learn("quanli", right, UserLearningEvidence.DEFAULT_ACCEPT)!!
        decoder.demote(withoutContext, UserNegativeFeedback.QUICK_DELETE)
        assertEquals("权利", first(decoder, "人民"))
        assertEquals("权利", first(decoder, "公民"))
        decoder.demote(old, UserNegativeFeedback.QUICK_DELETE)
        assertEquals("权利", first(decoder, "人民"))
        val current = choose(decoder, "人民", right)
        decoder.demote(current, UserNegativeFeedback.IMMEDIATE_REPLACEMENT)
        assertFalse(store.lookupInContext("quanli", "人民", 8).any { it.preferredInContext })
        assertEquals("权利", first(decoder, "公民"))
        decoder.demote(current, UserNegativeFeedback.MANUAL_DEMOTION)
        assertFalse(store.lookupInContext("quanli", "公民", 8).any { it.preferredInContext })
        choose(decoder, "人民", right)
        assertTrue(decoder.forget(store.lookup("quanli", 8).first { it.text == "权利" }))
        assertFalse(store.lookupInContext("quanli", "人民", 8).any { it.preferredInContext })
    }
}
