package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class OovLanguageModelAblationTest {
    private val model = object : CharacterLanguageModel {
        override fun containsCodePoint(codePoint: Int) = codePoint == '跨'.code
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = -10f
        override fun unigramLogProbability(next: Int) = -9f
    }

    @Test fun zeroUsesTheExactOriginalModelAndKnownScoresNeverChange() {
        assertSame(model, OovLanguageModelAblation.bind(model, 0f))
        for (feature in listOf(-2f, -4f, -6f)) {
            val bound = OovLanguageModelAblation.bind(model, feature)
            assertEquals(model.logProbability(1, 2, '跨'.code), bound.logProbability(1, 2, '跨'.code), 0f)
            assertEquals(model.unigramLogProbability('胯'.code), bound.unigramLogProbability('胯'.code), 0f)
        }
    }

    @Test fun neutralUnknownCanOutscoreAnObservedGlyphAndAblationChangesOnlyThatFeature() {
        fun scorer(m: CharacterLanguageModel) = PinyinLanguageScorer(PinyinLanguageBinding(m, .5f), "kua", PinyinLanguageScorer.context(-1))
        val original = scorer(model)
        assertEquals(-2f, original.feature("跨"), 0f)
        assertEquals(0f, original.feature("胯"), 0f)
        val penalized = scorer(OovLanguageModelAblation.bind(model, -4f))
        assertEquals(original.feature("跨"), penalized.feature("跨"), 0f)
        assertEquals(-2f, penalized.feature("胯"), 0f)
        assertEquals(-2f, penalized.feature("𠀀"), 0f)
        assertEquals(original.extend(original.initialState, "胯").state, penalized.extend(penalized.initialState, "胯").state)
        assertEquals(0f, penalized.feature("a!"), 0f)
    }

    @Test fun noUnboundedOrPositiveUnknownBonusIsAccepted() {
        for (feature in listOf(Float.NaN, Float.NEGATIVE_INFINITY, .1f, -6.1f))
            assertThrows(IllegalArgumentException::class.java) { OovLanguageModelAblation.bind(model, feature) }
    }

    @Test fun nativeScorerMatchesTheHostOnlyProbeAcrossBoundariesAndSupplementaryHan() {
        for (feature in listOf(0f, -2f, -4f, -6f)) for (query in listOf("kua", "kuazaiwojiashang")) {
            val context = PinyinLanguageScorer.context("我胯")
            val probe = PinyinLanguageScorer(PinyinLanguageBinding(OovLanguageModelAblation.bind(model, feature), .5f), query, context)
            val native = PinyinLanguageScorer(PinyinLanguageBinding(model, .5f, oovFeature = feature), query, context)
            for (text in listOf("跨", "胯", "𠀀", "胯跨", "胯。跨", "我胯a𠀀")) {
                assertEquals(probe.extend(context, text), native.extend(context, text))
                assertEquals(probe.feature(text), native.feature(text), 0f)
            }
        }
    }
}
