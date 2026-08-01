package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9SyllableIndexTest {
    @Test
    fun singleSyllableRoundTripsThroughItsT9Signature() {
        val index = T9SyllableIndex(listOf("hun"))
        val composition = compositionOf("486")

        assertEquals("486", T9SyllableIndex.digitsFor("hun"))
        assertTrue(index.paths(composition).any { it.formatted == "hun" })
    }

    @Test
    fun continuousDigitsProduceFullPinyinPlusInitialsWithoutLetterExpansion() {
        val index = T9SyllableIndex(
            repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
        )

        val paths = index.paths(compositionOf("486743697"))
        assertTrue(paths.any { it.formatted == "hun'shen'x's" })
        assertTrue(paths.size <= 32)
    }

    @Test
    fun lockedSpellingFiltersOtherT9CollisionsWithoutRewritingDigits() {
        val index = T9SyllableIndex(listOf("hun", "huo", "shen"))
        val composition = compositionOf("4867436")
            .lockEdge(T9LockedEdge(0, 3, "hun"))

        val paths = index.paths(composition)

        assertEquals("4867436", composition.rawDigits)
        assertTrue(paths.any { it.formatted == "hun'shen" })
        assertTrue(paths.none { it.segments.first().spelling == "huo" })
    }

    @Test
    fun forcedJointPreventsASyllableFromCrossingTheSeparator() {
        val index = T9SyllableIndex(listOf("an", "xi", "xian"))
        val composition = T9Composition()
            .typeDigit('9')
            .typeDigit('4')
            .forceJoint()
            .typeDigit('2')
            .typeDigit('6')

        val paths = index.paths(composition)

        assertTrue(paths.any { it.formatted == "xi'an" })
        assertTrue(paths.none { it.formatted == "xian" })
    }

    @Test
    fun repositoryCanonicalSyllablesAllRoundTripThroughTheGeneratedIndex() {
        val inventory = repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines()
        val index = T9SyllableIndex(inventory)

        inventory.forEach { syllable ->
            val signature = requireNotNull(T9SyllableIndex.digitsFor(syllable))
            val hasExactSyllable = index.paths(compositionOf(signature)).any { path ->
                path.segments.size == 1 &&
                    path.segments.single().kind == T9PinyinSegmentKind.SYLLABLE &&
                    path.segments.single().spelling == syllable
            }
            assertTrue("Missing T9 round-trip for $syllable ($signature)", hasExactSyllable)
        }
    }

    @Test
    fun anIncompleteSyllableIsConsideredOnlyAtTheInputTail() {
        val index = T9SyllableIndex(listOf("a", "shuang"))

        val tail = index.paths(compositionOf("748"))
        val followedByAnotherDigit = index.paths(compositionOf("7482"))

        assertTrue(
            tail.any { path ->
                path.formatted == "shu" &&
                    path.segments.single().kind == T9PinyinSegmentKind.INCOMPLETE
            },
        )
        assertTrue(followedByAnotherDigit.none { it.segments.first().spelling == "shu" })
    }

    @Test
    fun ninetySixDigitWorstCaseRetainsOnlyDistinctBoundedPaths() {
        val index = T9SyllableIndex(
            repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
        )

        val paths = index.paths(compositionOf("7426".repeat(24)))

        assertTrue(paths.isNotEmpty())
        assertTrue(paths.size <= T9SyllableIndex.DEFAULT_MAX_PATHS)
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(paths.all { path -> path.segments.zipWithNext().all { (left, right) -> left.digitEnd == right.digitStart } })
    }

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Repository fixture is missing: $relativePath")
}
