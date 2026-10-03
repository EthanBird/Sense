package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class PinyinExplicitBoundaryTest {
    private val segmenter = PinyinSyllableSegmenter(setOf("xi", "xian", "an", "hao"))
    private fun decoder(store: UserLexicon = MemoryUserLexicon(), english: EnglishLexicon = EnglishLexicon.EMPTY): AdaptivePinyinDecoder {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val words = listOf(Triple("an", "安", "a"), Triple("hao", "好", "h"), Triple("xi", "西", "x"),
                Triple("xian", "先", "x"), Triple("xian", "西安", "xa"), Triple("xianhao", "西安好", "xah"))
                .groupBy { it.first }.toSortedMap()
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(words.size)
            for ((code, values) in words) {
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(values.size)
                for ((_, text, initials) in values) {
                    val textBytes = text.toByteArray(); out.writeByte(textBytes.size); out.write(textBytes)
                    out.writeInt(if (text == "先") 100000 else 100); out.writeByte(initials.length); out.writeBytes(initials); out.writeByte(0)
                }
            }
        }
        return AdaptivePinyinDecoder(PinyinDecoder.fromBytes(bytes.toByteArray()), store, segmenter, english)
    }
    private fun typed(input: String) = input.fold(PinyinComposition()) { state, c -> state.type(c) }

    @Test fun typedSeparatorIsAnEditableCharacterNotAnInferredDisplayJoint() {
        val state = typed("xi'an")
        assertEquals("xi'an", state.remainingPinyin)
        assertEquals("xi'an", state.confirmRaw())
        assertEquals("xi'", state.backspace().backspace().remainingPinyin)
        assertEquals("xi", state.backspace().backspace().backspace().remainingPinyin)
    }

    @Test fun leadingAndRepeatedSeparatorsAreNoOpsWithinTheExistingInputBudget() {
        val empty = PinyinComposition(); assertSame(empty, empty.type('\''))
        val state = typed("xi'"); assertSame(state, state.type('\''))
        val full = typed("a".repeat(PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH))
        assertSame(full, full.type('\''))
    }

    @Test fun progressiveWholeCandidatesKeepTheSameForcedJointAsDirectDecode() {
        val decoder = decoder(); val state = PinyinComposition(remainingPinyin = "xi'an", revision = 7)
        val result = decoder.decodeProgressively(state, 64)
        assertEquals("xi'an", result.remainingPinyin)
        assertTrue(result.wholeCandidates.any { it.text == "西安" })
        assertFalse("Explicit xi + an must not turn into the xian syllable", result.wholeCandidates.any { it.text == "先" })
        assertEquals(decoder.decode("xi'an", 64), result.wholeCandidates)
    }

    @Test fun prefixSelectionConsumesTheTypedBoundaryAndRollbackRestoresIt() {
        val decoder = decoder(); val typed = PinyinComposition(remainingPinyin = "xi'an", revision = 3)
        val result = decoder.decodeProgressively(typed, 64)
        val prefix = result.prefixCandidates.first { it.candidate.text == "西" }
        assertEquals("xi'", prefix.consumedPinyin); assertEquals("an", prefix.remainingPinyin)
        val accepted = typed.acceptPrefix(result.revision, prefix)
        assertEquals("西an", accepted.visibleText)
        assertEquals("西安", accepted.confirmPrimary(Candidate("安")))
        assertEquals("xi'", accepted.backspace().backspace().backspace().remainingPinyin)
        assertEquals(typed, typed.acceptPrefix(result.revision - 1, prefix))
    }

    @Test fun learnedSingleSyllableNeverIgnoresTheExplicitTwoSyllableBoundary() {
        val store = MemoryUserLexicon(clock = { 1000L }); store.record("xian", "x", "先")
        val values = decoder(store).decodeProgressively(PinyinComposition(remainingPinyin = "xi'an"), 64)
        assertFalse(values.wholeCandidates.any { it.text == "先" })
    }

    @Test fun prefixInventoryRespectsBoundariesInsteadOfInventingASyllableAcrossThem() {
        assertEquals(listOf("xi", "an"), segmenter.segment("xi'an"))
        assertEquals(listOf(2), segmenter.selectablePrefixLengths("xi'anhao").toList())
        assertFalse(segmenter.isComplete("x'ian"))
    }

    @Test fun trailingSeparatorKeepsTheResultBoundToItsExactCompositionSnapshot() {
        val state = PinyinComposition(remainingPinyin = "xi'", revision = 9)
        val result = decoder().decodeProgressively(state, 64)
        assertEquals(state.remainingPinyin, result.remainingPinyin)
        assertTrue(result.wholeCandidates.any { it.text == "西" })
    }

    @Test fun explicitChineseBoundarySuppressesEnglishCompletionsAcrossEntryPoints() {
        val decoder = decoder(english = EnglishLexicon.fromWords(listOf("xian", "host")))
        assertTrue(decoder.decode("xian", 64).any { it.text == "xian" })
        for (raw in listOf("xi'an", "xian'", "ho'st", "host'")) {
            assertFalse(decoder.decode(raw, 64).any { it.matchKind == CandidateMatchKind.ENGLISH_EXACT })
            assertFalse(decoder.decodeAfter('我'.code, raw, 64).any { it.matchKind == CandidateMatchKind.ENGLISH_EXACT })
            assertFalse(decoder.decodeProgressively(typed(raw), 64).wholeCandidates.any { it.matchKind == CandidateMatchKind.ENGLISH_EXACT })
        }
    }

    @Test fun everyPrefixRetainsARawSpanThatCanBeAcceptedAndUndone() {
        val decoder = decoder()
        for (raw in listOf("xi'an", "xi'an'hao", "xi'an'h", "x'ian", "xi'anhao", "xi'anhao'")) {
            val state = typed(raw)
            val result = decoder.decodeProgressively(state, 64)
            assertEquals(raw, result.remainingPinyin)
            for (prefix in result.prefixCandidates) {
                assertEquals(raw, prefix.consumedPinyin + prefix.remainingPinyin)
                assertFalse(prefix.remainingPinyin.startsWith('\''))
                var accepted = state.acceptPrefix(result.revision, prefix)
                assertEquals(prefix.remainingPinyin, accepted.remainingPinyin)
                repeat(prefix.remainingPinyin.length + 1) { accepted = accepted.backspace() }
                assertEquals(prefix.consumedPinyin, accepted.remainingPinyin)
                assertTrue(accepted.acceptedSegments.isEmpty())
            }
        }
    }
}
