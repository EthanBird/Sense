package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class PinyinLanguageScorerTest {
    private val model = object : CharacterLanguageModel {
        override fun containsCodePoint(codePoint: Int) = codePoint != '陌'.code
        override fun unigramLogProbability(next: Int) = -6f
        override fun logProbability(previous2: Int, previous1: Int, next: Int): Float =
            when (next) { '我'.code -> -2f; 0x20000 -> -4f; else -> -3f }
    }

    @Test fun memoPreservesScalarOrderUnknownNonfiniteClippingAndPunctuationAfterSaturation() {
        val varied = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun containsCodePoint(codePoint: Int) = codePoint % 7 != 0
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float =
                when (next % 13) {
                    0 -> Float.NaN
                    1 -> Float.NEGATIVE_INFINITY
                    else -> ((previous2 * 11 + previous1 * 3 + next) % 113 - 90) / 5.5f
                }
        }
        val state = PinyinLanguageScorer.context("你好")
        val scorer = PinyinLanguageScorer(PinyinLanguageBinding(varied, .5f, oovFeature = -4f), "woxihuan", state)
        val random = java.util.Random(410)
        repeat(6_000) {
            val points = IntArray(5) { index ->
                if (index == 2 && it % 3 == 0) '.'.code
                else (if (it % 5 == 0) 0x20000 else 0x4E00) + random.nextInt(256)
            }
            val text = String(points, 0, points.size)
            var a = '你'.code; var b = '好'.code; var score = 0f
            for (next in points) {
                if (Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN) {
                    a = CharacterLanguageModel.BOS; b = CharacterLanguageModel.BOS; continue
                }
                if (varied.containsCodePoint(next)) {
                    val p = varied.logProbability(a, b, next)
                    if (p.isFinite()) score += (p + 6f).coerceIn(-6f, 6f)
                } else score += -4f
                a = b; b = next
            }
            val result = scorer.extend(state, text)
            assertEquals(PinyinLanguageScorer.pack(a, b), result.state)
            assertEquals((score * .5f).toRawBits(), result.score.toRawBits())
        }
    }

    @Test fun fourgramUsesAllThreeContextTokensAndNeverEntersTrigramMemo() {
        val fourgram = object : FourgramLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float = error("Trigram fallback")
            override fun logProbabilityWithThirdContext(previous3: Int, previous2: Int, previous1: Int, next: Int) =
                if (previous3 == '甲'.code) -2f else -5f
        }
        val state = PinyinLanguageScorer.context3("甲你好")
        val scorer = PinyinLanguageScorer(PinyinLanguageBinding(fourgram, .5f), "wo", state)
        val a = scorer.extend(state, "我")
        val b = scorer.extend(PinyinLanguageScorer.context3("乙你好"), "我")
        assertEquals(a.state, b.state)
        assertEquals(2f, a.score, 0f)
        assertEquals(.5f, b.score, 0f)
    }

    @Test
    fun wordSplitsCountTheSameCharactersExactlyOnceIncludingSupplementaryHan() {
        val state = PinyinLanguageScorer.context("你好")
        val scorer = PinyinLanguageScorer(PinyinLanguageBinding(model, .5f), "woxihuan", state)
        val whole = scorer.extend(state, "我𠀀喜欢")
        val left = scorer.extend(state, "我𠀀")
        val right = scorer.extend(left.state, "喜欢")
        assertEquals(whole.state, right.state)
        assertEquals(whole.score, left.score + right.score, 0f)
        assertEquals(whole.score / scorer.normalizer, scorer.feature("我𠀀喜欢"), 0f)
        assertEquals(scorer.extend(state, "我陌"), scorer.extend(state, "我陌"))
    }

    @Test
    fun unknownEmissionIsNeutralAndPunctuationResetsBothContextSlots() {
        val scorer = PinyinLanguageScorer(PinyinLanguageBinding(model, 1f), "mo", PinyinLanguageScorer.context("你好"))
        assertEquals(0f, scorer.feature("陌"), 0f)
        assertEquals(PinyinLanguageScorer.context("𠀀好"), PinyinLanguageScorer.context("long editor。𠀀好"))
        assertEquals(PinyinLanguageScorer.context(-1), PinyinLanguageScorer.context("你好。"))
        assertEquals(PinyinLanguageScorer.context("好"), PinyinLanguageScorer.context("𠀀。好"))
    }
}
