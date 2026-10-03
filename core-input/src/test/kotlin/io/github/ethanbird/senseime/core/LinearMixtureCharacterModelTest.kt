package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class LinearMixtureCharacterModelTest {
    private val eos = CharacterLanguageModel.EOS
    private val unk = CharacterLanguageModel.UNK
    private val bos = CharacterLanguageModel.BOS
    private class Toy(val probabilities: Map<Int, Double>) : CharacterLanguageModel {
        override fun containsCodePoint(codePoint: Int) = codePoint in 0..0x10FFFF && probabilities.containsKey(codePoint)
        override fun unigramLogProbability(next: Int) = ln(probabilities[next] ?: probabilities.getValue(CharacterLanguageModel.UNK)).toFloat()
        override fun logProbability(previous2: Int, previous1: Int, next: Int) = unigramLogProbability(next)
        fun support() = probabilities.keys.sorted().toIntArray()
    }
    private val left = Toy(mapOf('我'.code to .4, '你'.code to .3, eos to .1, unk to .2))
    private val right = Toy(mapOf('我'.code to .2, '他'.code to .2, 0x20000 to .2, eos to .1, unk to .3))
    private fun model(alpha: Double) = LinearMixtureCharacterModel.combine(left,left.support(),right,right.support(),alpha)

    @Test fun projectionPreservesMassOverSharedVocabularyAndResidualUnknown() {
        val union=(left.support().toList()+right.support().toList()).distinct()
        for (alpha in listOf(.1,.25,.5)) {
            val m=model(alpha)
            assertEquals(1.0,union.sumOf { exp(m.logProbability(bos,bos,it).toDouble()) },1e-7)
            assertEquals(1.0,union.sumOf { exp(m.unigramLogProbability(it).toDouble()) },1e-7)
        }
    }

    @Test fun newGlyphGetsOnlyItsShareOfBaselineUnknownMass() {
        val m=model(.25)
        // Base UNK .2 splits in proportions new-only .2, .2, residual .3.
        assertEquals(.75*(.2*.2/.7)+.25*.2,exp(m.logProbability(bos,bos,'他'.code).toDouble()),1e-7)
        // Adaptation UNK .3 splits across base-only 你 .3 and residual .2.
        assertEquals(.75*.3+.25*(.3*.3/.5),exp(m.unigramLogProbability('你'.code).toDouble()),1e-7)
        assertEquals(.75*(.2*.3/.7)+.25*(.3*.2/.5),exp(m.unigramLogProbability(unk).toDouble()),1e-7)
    }

    @Test fun interpolationHappensInProbabilitySpaceNotLogScoreSpace() {
        assertEquals(.35,exp(model(.25).unigramLogProbability('我'.code).toDouble()),1e-7)
        assertEquals(.1,exp(model(.25).unigramLogProbability(eos).toDouble()),1e-7)
    }

    @Test fun endpointsReturnOriginalModelsAndUnionMembershipIsUnicodeAware() {
        assertSame(left,model(0.0));assertSame(right,model(1.0))
        val m=model(.25)
        for (cp in listOf('我'.code,'你'.code,'他'.code,0x20000)) assertTrue(m.containsCodePoint(cp))
        for (cp in listOf('陌'.code,eos,unk,bos,-1,0xD800)) assertFalse(m.containsCodePoint(cp))
        assertEquals(m.unigramLogProbability(unk),m.unigramLogProbability('陌'.code),0f)
    }

    @Test fun identicalModelsAndSharedVocabularyDoNotChangeProbabilities() {
        val m=LinearMixtureCharacterModel.combine(left,left.support(),left,left.support(),.25)
        for (cp in left.support()) assertEquals(left.unigramLogProbability(cp),m.unigramLogProbability(cp),1e-7f)
    }

    @Test fun invalidCoefficientsAndSupportFailAtConstruction() {
        for (a in listOf(-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { model(a) }
        }
        for (support in listOf(intArrayOf(),intArrayOf(unk,eos),intArrayOf(eos,unk,unk),intArrayOf(eos),intArrayOf(0xD800,eos,unk))) {
            assertThrows(IllegalArgumentException::class.java) { LinearMixtureCharacterModel.combine(left,support,right,right.support(),.25) }
        }
        assertThrows(IllegalArgumentException::class.java) { model(.25).logProbability(bos,bos,bos) }
    }
}
