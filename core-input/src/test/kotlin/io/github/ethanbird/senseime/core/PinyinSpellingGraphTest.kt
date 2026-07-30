package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinSpellingGraphTest {
    private val graph = PinyinSpellingGraph(
        listOf("an", "hao", "mi", "ni", "ren", "xian", "xi"),
    )

    @Test
    fun legalExactSpellingAndKeyboardNeighborRemainInTheSameGraph() {
        val paths = graph.paths("mi")

        assertEquals(0f, paths.first { it.canonical == "mi" }.cost)
        assertTrue(paths.any { it.canonical == "ni" && it.cost in 0f..1f })
    }

    @Test
    fun adjacentTranspositionIsAWeightedSpellingPath() {
        val corrected = graph.paths("nihoa").first { it.canonical == "nihao" }

        assertTrue(corrected.cost > 0f)
        assertEquals(2, corrected.syllableCount)
        assertEquals(listOf(2, 5), corrected.syllableEnds)
    }

    @Test
    fun apostropheForcesASyllableJoint() {
        val paths = graph.paths("xi'an")

        assertTrue(paths.any { it.canonical == "xian" && it.syllableCount == 2 })
        assertTrue(paths.none { it.canonical == "xian" && it.syllableCount == 1 })
        assertEquals(listOf(2, 4), paths.first { it.canonical == "xian" }.syllableEnds)
    }

    @Test
    fun ambiguousExactSegmentationsDoNotStarveATailCorrection() {
        val ambiguous = PinyinSpellingGraph(listOf("a", "aa", "aaa", "aaaa", "ao"))

        val paths = ambiguous.paths("aaaaaaaa", maxPaths = 24)

        assertTrue(paths.any { it.canonical == "aaaaaaao" && it.cost > 0f })
        assertTrue(
            paths.count { it.canonical == "aaaaaaaa" && it.cost == 0f } <= 4,
        )
    }

    @Test
    fun productionInventoryRetainsTheLatestRepeatedKeyCorrectionWithinFortyEightPaths() {
        val production = PinyinSpellingGraph(
            repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
        )

        val corrected = production.paths("nihaoshijiee", maxPaths = 48)
            .firstOrNull {
                it.canonical == "nihaoshijie" &&
                    it.syllableEnds == listOf(2, 5, 8, 11)
            }

        assertTrue(corrected != null)
        assertEquals(11, corrected?.firstEditOffset)
        assertTrue(requireNotNull(corrected).cost > 0f)
    }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Repository fixture is missing: $relativePath")
}
