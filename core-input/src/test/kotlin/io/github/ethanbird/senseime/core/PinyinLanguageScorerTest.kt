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
