package io.github.ethanbird.senseime.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * Opt-in conditional probability mixture; it does not average already clipped IME scores.
 * Each component keeps its own context/backoff distribution. Its UNK is partitioned over
 * missing union-vocabulary glyphs plus residual UNK, rather than repeated for every glyph.
 * No editor text, per-query cache, or mutable adaptation state is retained here.
 */
class LinearMixtureCharacterModel private constructor(
    private val baseline: CharacterLanguageModel,
    private val adaptation: CharacterLanguageModel,
    private val vocabulary: IntArray,
    private val baselineSplit: DoubleArray,
    private val adaptationSplit: DoubleArray,
    alpha: Double,
) : CharacterLanguageModel {
    private val baselineWeight = ln1p(-alpha)
    private val adaptationWeight = ln(alpha)
    private val unknownIndex = vocabulary.binarySearch(CharacterLanguageModel.UNK)

    override fun containsCodePoint(codePoint: Int): Boolean =
        codePoint in 0..0x10FFFF && vocabulary.binarySearch(codePoint) >= 0

    private fun index(next: Int): Int {
        require(next != CharacterLanguageModel.BOS) { "BOS is context only" }
        return vocabulary.binarySearch(next).let { if (it >= 0) it else unknownIndex }
    }

    private fun mix(a: Float, b: Float, index: Int): Float {
        val left = a.toDouble() + baselineSplit[index] + baselineWeight
        val right = b.toDouble() + adaptationSplit[index] + adaptationWeight
        val high = maxOf(left, right)
        return (high + ln1p(exp(minOf(left, right) - high))).toFloat().coerceAtMost(0f)
    }

    override fun logProbability(previous2: Int, previous1: Int, next: Int): Float {
        val index = index(next)
        return mix(baseline.logProbability(previous2, previous1, next),
            adaptation.logProbability(previous2, previous1, next), index)
    }

    override fun unigramLogProbability(next: Int): Float =
        mix(baseline.unigramLogProbability(next), adaptation.unigramLogProbability(next), index(next))

    companion object {
        fun fromBytes(baseline: ByteArray, adaptation: ByteArray, alpha: Double): CharacterLanguageModel {
            require(alpha.isFinite() && alpha in 0.0..1.0)
            // Endpoints are the original models, byte-for-byte semantics, without loading the unused side.
            if (alpha == 0.0) return BinaryCharacterLanguageModel.fromBytes(baseline)
            if (alpha == 1.0) return BinaryCharacterLanguageModel.fromBytes(adaptation)
            val left = BinaryCharacterLanguageModel.fromBytes(baseline)
            val right = BinaryCharacterLanguageModel.fromBytes(adaptation)
            fun support(bytes: ByteArray): IntArray {
                // Both files have already passed the complete SCNG validator above.
                val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val count = input.getInt(6)
                return IntArray(count) { input.getInt(26 + it * 8) }
            }
            return combine(left, support(baseline), right, support(adaptation), alpha)
        }

        internal fun combine(baseline: CharacterLanguageModel, baselineSupport: IntArray,
            adaptation: CharacterLanguageModel, adaptationSupport: IntArray, alpha: Double): CharacterLanguageModel {
            require(alpha.isFinite() && alpha in 0.0..1.0)
            for (support in listOf(baselineSupport, adaptationSupport)) {
                require(support.isNotEmpty() && support.indices.all { it == 0 || support[it] > support[it - 1] })
                require(support.binarySearch(CharacterLanguageModel.UNK) >= 0 && support.binarySearch(CharacterLanguageModel.EOS) >= 0)
                require(support.all { it == CharacterLanguageModel.UNK || it == CharacterLanguageModel.EOS ||
                    (it in 0..0x10FFFF && it !in 0xD800..0xDFFF) })
            }
            if (alpha == 0.0) return baseline
            if (alpha == 1.0) return adaptation
            val union = (baselineSupport.toList() + adaptationSupport.toList()).distinct().sorted().toIntArray()
            fun split(support: IntArray, other: CharacterLanguageModel): DoubleArray {
                val shares = DoubleArray(union.size)
                var denominator = 0.0
                for (i in union.indices) if (union[i] == CharacterLanguageModel.UNK || support.binarySearch(union[i]) < 0) {
                    val probability = other.unigramLogProbability(union[i]).toDouble()
                    require(probability.isFinite() && probability <= 0.0)
                    shares[i] = exp(probability)
                    denominator += shares[i]
                }
                require(denominator.isFinite() && denominator > 0.0)
                return DoubleArray(union.size) { i -> if (shares[i] > 0.0) ln(shares[i] / denominator) else 0.0 }
            }
            return LinearMixtureCharacterModel(baseline, adaptation, union,
                split(baselineSupport, adaptation), split(adaptationSupport, baseline), alpha)
        }
    }
}
