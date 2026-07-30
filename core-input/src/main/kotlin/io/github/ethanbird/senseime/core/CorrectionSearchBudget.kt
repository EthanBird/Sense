package io.github.ethanbird.senseime.core

/**
 * Explicit resource policy for weighted spelling correction.
 *
 * Keeping this policy outside [PinyinDecoder] removes the former fixture-size
 * branch: production and tests now exercise the same search implementation,
 * while a focused test can inject a deliberately narrow budget.
 */
data class CorrectionSearchBudget(
    val spellingPathsWithoutCanonical: Int,
    val spellingPathsWithCanonical: Int,
    val exactCandidatesPerPath: Int,
    val composedPathsWithoutCanonical: Int,
    val composedPathsWithCanonical: Int,
    val composedCandidatesPerPath: Int,
    val segmentCandidatesPerKey: Int,
    val segmentBeamWidth: Int,
    val compactSpellingPathsWithCanonical: Int = spellingPathsWithCanonical,
    val compactComposedPathsWithCanonical: Int = composedPathsWithCanonical,
    val compactOutputLimitThreshold: Int = 64,
) {
    init {
        require(spellingPathsWithoutCanonical > 0)
        require(spellingPathsWithCanonical > 0)
        require(exactCandidatesPerPath > 0)
        require(composedPathsWithoutCanonical > 0)
        require(composedPathsWithCanonical > 0)
        require(composedCandidatesPerPath > 0)
        require(segmentCandidatesPerKey > 0)
        require(segmentBeamWidth > 0)
        require(compactSpellingPathsWithCanonical in 1..spellingPathsWithCanonical)
        require(compactComposedPathsWithCanonical in 1..composedPathsWithCanonical)
        require(compactOutputLimitThreshold > 0)
    }

    fun spellingPathLimit(allowComposedCorrections: Boolean, outputLimit: Int): Int = when {
        allowComposedCorrections -> spellingPathsWithoutCanonical
        outputLimit < compactOutputLimitThreshold -> compactSpellingPathsWithCanonical
        else -> spellingPathsWithCanonical
    }

    fun composedPathLimit(allowComposedCorrections: Boolean, outputLimit: Int): Int = when {
        allowComposedCorrections -> composedPathsWithoutCanonical
        outputLimit < compactOutputLimitThreshold -> compactComposedPathsWithCanonical
        else -> composedPathsWithCanonical
    }

    companion object {
        val PRODUCTION = CorrectionSearchBudget(
            spellingPathsWithoutCanonical = 48,
            spellingPathsWithCanonical = 48,
            exactCandidatesPerPath = 48,
            composedPathsWithoutCanonical = 4,
            composedPathsWithCanonical = 3,
            composedCandidatesPerPath = 24,
            segmentCandidatesPerKey = 8,
            segmentBeamWidth = 32,
            compactSpellingPathsWithCanonical = 24,
            compactComposedPathsWithCanonical = 1,
            compactOutputLimitThreshold = 64,
        )
    }
}
