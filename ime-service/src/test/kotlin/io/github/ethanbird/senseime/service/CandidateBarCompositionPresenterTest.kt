package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AcceptedPinyinSegment
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBarCompositionPresenterTest {
    private val segmenter = PinyinSyllableSegmenter(
        setOf(
            "an",
            "de",
            "fan",
            "fang",
            "gan",
            "hao",
            "hun",
            "le",
            "ou",
            "ren",
            "shen",
            "shi",
            "shu",
            "ta",
            "wo",
            "xie",
        ),
    )

    @Test
    fun `pending candidate bar keeps raw input until decode evidence is ready`() {
        val composition = PinyinComposition(remainingPinyin = "hunshenxs")
        val retainedPrimary = chineseCandidate(
            text = "浑身解数",
            canonicalPinyin = "hunshenxieshu",
            canonicalInitials = "hsxs",
            matchKind = CandidateMatchKind.BASE_HYBRID,
        )

        assertEquals(
            "hunshenxs",
            present(composition, retainedPrimary, decodingPending = true),
        )
    }

    @Test
    fun `Chinese hybrid candidate explains implicit full and abbreviated boundaries`() {
        val composition = PinyinComposition(remainingPinyin = "hunshenxs")
        val primary = chineseCandidate(
            text = "浑身解数",
            canonicalPinyin = "hunshenxieshu",
            canonicalInitials = "hsxs",
            matchKind = CandidateMatchKind.BASE_HYBRID,
        )

        assertEquals(
            "hun'shen'x's",
            present(composition, primary),
        )
    }

    @Test
    fun `English exact candidates keep host and world unsegmented`() {
        listOf("host", "world").forEach { raw ->
            assertEquals(
                raw,
                present(
                    PinyinComposition(remainingPinyin = raw),
                    Candidate(
                        text = raw,
                        matchKind = CandidateMatchKind.ENGLISH_EXACT,
                    ),
                ),
            )
        }
    }

    @Test
    fun `canonical initials resolve ambiguous full pinyin boundary`() {
        val composition = PinyinComposition(remainingPinyin = "fangan")
        val primary = chineseCandidate(
            text = "方案",
            canonicalPinyin = "fangan",
            canonicalInitials = "fa",
        )

        assertEquals(
            "fang'an",
            present(composition, primary),
        )
    }

    @Test
    fun `accepted Chinese prefix stays ahead of candidate-aligned pending spelling`() {
        val composition = PinyinComposition(
            acceptedSegments = listOf(AcceptedPinyinSegment("浑", "hun")),
            remainingPinyin = "shenxs",
        )
        val primary = chineseCandidate(
            text = "身解数",
            canonicalPinyin = "shenxieshu",
            canonicalInitials = "sxs",
            matchKind = CandidateMatchKind.BASE_HYBRID,
        )

        assertEquals(
            "浑shen'x's",
            present(composition, primary),
        )
    }

    @Test
    fun `candidate bar projection leaves raw editor composition untouched`() {
        val composition = PinyinComposition(remainingPinyin = "hunshenxs")
        val primary = chineseCandidate(
            text = "浑身解数",
            canonicalPinyin = "hunshenxieshu",
            canonicalInitials = "hsxs",
            matchKind = CandidateMatchKind.BASE_HYBRID,
        )

        present(composition, primary)

        assertEquals("hunshenxs", composition.remainingPinyin)
        assertEquals("hunshenxs", composition.visibleText)
    }

    private fun present(
        composition: PinyinComposition,
        primaryCandidate: Candidate?,
        decodingPending: Boolean = false,
    ): String = CandidateBarCompositionPresenter.text(
        composition = composition,
        segmenter = segmenter,
        decodingPending = decodingPending,
        primaryCandidate = primaryCandidate,
    )

    private fun chineseCandidate(
        text: String,
        canonicalPinyin: String,
        canonicalInitials: String,
        matchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
    ) = Candidate(
        text = text,
        canonicalPinyin = canonicalPinyin,
        canonicalInitials = canonicalInitials,
        matchKind = matchKind,
    )
}
