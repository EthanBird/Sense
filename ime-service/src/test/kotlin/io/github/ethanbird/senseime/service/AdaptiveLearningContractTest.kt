package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** End-to-end service contract for the in-memory-first, durable user lexicon. */
class AdaptiveLearningContractTest {
    @Test
    fun oneSelectionTakesFirstPlaceImmediatelyAndAfterReload() {
        val oldFrequentChoice = LearnedPhrase(
            fullPinyin = "de",
            initials = "d",
            text = "地",
            useCount = 20,
            createdAtMillis = 100L,
            lastUsedAtMillis = 500L,
        )
        val journal = linkedMapOf(
            (oldFrequentChoice.fullPinyin to oldFrequentChoice.text) to oldFrequentChoice,
        )
        val firstLexicon = MemoryUserLexicon(
            initial = journal.values,
            clock = { 1_000L },
            onRecord = { learned -> journal[learned.fullPinyin to learned.text] = learned },
        )
        val firstDecoder = decoder(firstLexicon)
        val selected = firstDecoder.decode("d", 255).first { it.text == "的" }

        assertNotNull(firstDecoder.learn("d", selected))
        assertEquals("的", firstDecoder.decode("d", 255).first().text)

        val reloadedDecoder = decoder(MemoryUserLexicon(initial = journal.values))
        assertEquals("的", reloadedDecoder.decode("d", 255).first().text)
    }

    private fun decoder(userLexicon: MemoryUserLexicon): AdaptivePinyinDecoder =
        AdaptivePinyinDecoder(
            base = object : InputDecoder {
                override fun decode(composing: String, limit: Int): List<Candidate> =
                    if (composing == "d") {
                        listOf(
                            Candidate(
                                text = "地",
                                score = 20f,
                                canonicalPinyin = "de",
                                matchKind = CandidateMatchKind.BASE_PREFIX,
                                canonicalInitials = "d",
                            ),
                            Candidate(
                                text = "的",
                                score = 19f,
                                canonicalPinyin = "de",
                                matchKind = CandidateMatchKind.BASE_PREFIX,
                                canonicalInitials = "d",
                            ),
                        ).take(limit)
                    } else {
                        emptyList()
                    }
            },
            userLexicon = userLexicon,
            segmenter = PinyinSyllableSegmenter(setOf("de")),
        )
}
