package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.PinyinPrefixCandidate
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveCandidateSnapshotTest {
    @Test
    fun keepsWholePhraseHeadThenExposesPrefixChoices() {
        val whole = Candidate("匹配")
        val secondWhole = Candidate("屁配")
        val prefix = PinyinPrefixCandidate(Candidate("匹"), "pi", "pei")
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(7, "pipei", listOf(whole, secondWhole), listOf(prefix)),
            limit = 32,
        )

        assertEquals(listOf("匹配", "屁配", "匹"), snapshot.candidates.map { it.text })
        assertTrue(snapshot.select(7, 7, 1) is ProgressiveCandidateChoice.Whole)
        assertTrue(snapshot.select(7, 7, 2) is ProgressiveCandidateChoice.Prefix)
    }

    @Test
    fun staleRevisionAndInvalidIndexCannotSelect() {
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(4, "pi", listOf(Candidate("匹")), emptyList()),
            limit = 32,
        )

        assertNull(snapshot.select(5, 4, 0))
        assertNull(snapshot.select(4, 3, 0))
        assertNull(snapshot.select(4, 4, 9))
    }

    @Test
    fun keepsAUsefulWholePhraseHeadBeforePrefixCharacters() {
        val whole = (0 until 20).map { Candidate("whole-$it") }
        val prefixes = (0 until 20).map { index ->
            PinyinPrefixCandidate(Candidate("prefix-$index"), "pi", "pei")
        }
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(11, "pipei", whole, prefixes),
            limit = 255,
        )

        assertEquals((0 until 12).map { "whole-$it" }, snapshot.candidates.take(12).map { it.text })
        assertTrue(snapshot.select(11, 11, 11) is ProgressiveCandidateChoice.Whole)
        assertTrue(snapshot.select(11, 11, 12) is ProgressiveCandidateChoice.Prefix)
    }

    @Test
    fun bulkPrefixCharactersCannotBuryTheWholeCandidateTail() {
        val whole = (0 until 40).map { Candidate("whole-$it") }
        val prefixes = (0 until 200).map { index ->
            PinyinPrefixCandidate(Candidate("prefix-$index"), "xi", "an")
        }
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(17, "xian", whole, prefixes),
            limit = 255,
        )

        assertEquals((0 until 12).map { "whole-$it" }, snapshot.candidates.take(12).map { it.text })
        assertEquals((0 until 4).map { "prefix-$it" }, snapshot.candidates.drop(12).take(4).map { it.text })
        assertEquals((12 until 40).map { "whole-$it" }, snapshot.candidates.drop(16).take(28).map { it.text })
        assertEquals(43, snapshot.candidates.indexOfFirst { it.text == "whole-39" })
        assertTrue(snapshot.select(17, 17, 43) is ProgressiveCandidateChoice.Whole)
    }

    @Test
    fun exactSingleHanSyllablesExposeOnlyRepresentativeAmbiguousSplitChoices() {
        listOf("xian" to "先", "jian" to "见").forEachIndexed { caseIndex, (pinyin, han) ->
            val whole = listOf(
                Candidate(
                    text = han,
                    canonicalPinyin = pinyin,
                    matchKind = CandidateMatchKind.BASE_EXACT,
                ),
                Candidate(
                    text = "$han$han",
                    canonicalPinyin = pinyin,
                    matchKind = CandidateMatchKind.BASE_EXACT,
                ),
            )
            val prefixes = (0 until 80).map { index ->
                PinyinPrefixCandidate(
                    candidate = Candidate("prefix-$pinyin-$index"),
                    consumedPinyin = pinyin.dropLast(2),
                    remainingPinyin = pinyin.takeLast(2),
                )
            }
            val revision = 31L + caseIndex
            val snapshot = ProgressiveCandidateSnapshot.from(
                ProgressivePinyinDecoding(revision, pinyin, whole, prefixes),
                limit = 510,
            )

            assertEquals(6, snapshot.candidates.size)
            assertEquals(listOf(han, "$han$han"), snapshot.candidates.take(2).map { it.text })
            assertEquals(
                (0 until 4).map { "prefix-$pinyin-$it" },
                snapshot.candidates.drop(2).map { it.text },
            )
            assertTrue(
                (2 until 6).all {
                    snapshot.select(revision, revision, it) is ProgressiveCandidateChoice.Prefix
                },
            )
        }
    }

    @Test
    fun representativePrefixesCoverDistinctConsumptionPathsBeforeFilling() {
        val prefixes = listOf(
            PinyinPrefixCandidate(Candidate("a-0"), "a", "ng"),
            PinyinPrefixCandidate(Candidate("a-1"), "a", "ng"),
            PinyinPrefixCandidate(Candidate("an-0"), "an", "g"),
            PinyinPrefixCandidate(Candidate("a-2"), "a", "ng"),
            PinyinPrefixCandidate(Candidate("an-1"), "an", "g"),
        )
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(
                revision = 37,
                remainingPinyin = "ang",
                wholeCandidates = listOf(Candidate("whole")),
                prefixCandidates = prefixes,
            ),
            limit = 16,
        )

        assertEquals(
            listOf("whole", "a-0", "an-0", "a-1", "a-2", "an-1"),
            snapshot.candidates.map { it.text },
        )
    }

    @Test
    fun exposesTheEntireBoundedCandidateSetForUiPaging() {
        val whole = (0 until 255).map { Candidate("candidate-$it") }
        val prefixes = (0 until 255).map { index ->
            PinyinPrefixCandidate(Candidate("prefix-$index"), "shang", "hua")
        }
        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(19, "shanghua", whole, prefixes),
            limit = 510,
        )

        assertEquals(510, snapshot.candidates.size)
        assertEquals("candidate-254", snapshot.candidates[258].text)
        assertTrue(snapshot.select(19, 19, 258) is ProgressiveCandidateChoice.Whole)
        assertEquals("prefix-254", snapshot.candidates.last().text)
        assertTrue(snapshot.select(19, 19, 509) is ProgressiveCandidateChoice.Prefix)
    }

    @Test
    fun englishHeadUsesThreeSlotsBeforeAnIncompleteChinesePrefix() {
        val whole = listOf("host", "hosts", "hostile", "hosted").mapIndexed { index, text ->
            Candidate(
                text,
                matchKind = if (index == 0) {
                    CandidateMatchKind.ENGLISH_EXACT
                } else {
                    CandidateMatchKind.ENGLISH_PREFIX
                },
            )
        }
        val prefix = PinyinPrefixCandidate(
            Candidate("好哦", canonicalInitials = "ho"),
            consumedPinyin = "ho",
            remainingPinyin = "st",
        )

        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(23, "host", whole, listOf(prefix)),
            limit = 16,
        )

        assertEquals(listOf("host", "hosts", "hostile", "好哦"), snapshot.candidates.take(4).map { it.text })
        assertTrue(snapshot.select(23, 23, 3) is ProgressiveCandidateChoice.Prefix)
    }

    @Test
    fun sameDisplayTextKeepsDistinctWholeAndPrefixSelectionSemantics() {
        val text = Candidate("匹")
        val prefix = PinyinPrefixCandidate(text, consumedPinyin = "pi", remainingPinyin = "pei")

        val snapshot = ProgressiveCandidateSnapshot.from(
            ProgressivePinyinDecoding(29, "pipei", listOf(text), listOf(prefix)),
            limit = 16,
        )

        assertEquals(listOf("匹", "匹"), snapshot.candidates.map { it.text })
        assertTrue(snapshot.select(29, 29, 0) is ProgressiveCandidateChoice.Whole)
        assertTrue(snapshot.select(29, 29, 1) is ProgressiveCandidateChoice.Prefix)
    }
}
