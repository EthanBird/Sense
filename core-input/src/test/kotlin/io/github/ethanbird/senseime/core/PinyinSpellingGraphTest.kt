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
    fun transpositionAcrossASyllableJointIsOneEditWithBothEndsPreserved() {
        val units = PinyinSpellingGraph(listOf("ni", "hao", "hen"))
        data class Example(val typed: String, val canonical: String, val ends: List<Int>, val offset: Int)
        for ((typed, canonical, ends, offset) in listOf(
            Example("nhiao", "nihao", listOf(2, 5), 1),
            Example("hehnao", "henhao", listOf(3, 6), 2),
        )) {
            val path = units.paths(typed, maxPaths = 48, maxCost = .45f)
                .firstOrNull { it.canonical == canonical && it.syllableEnds == ends }
            assertTrue("Missing one-edit cross-joint path for $typed", path != null)
            assertEquals(.45f, requireNotNull(path).cost, .00001f)
            assertEquals(offset, path.firstEditOffset)
            assertEquals(false, path.singleInsertionDeletion)
        }
    }

    @Test
    fun crossJointSwapRespectsExplicitJointsAndEditBudget() {
        val units = PinyinSpellingGraph(listOf("ni", "hao", "wo"))
        assertTrue(units.paths("nh'iao", 48, .45f).none { it.canonical == "nihao" })
        assertTrue(units.paths("nhiao", 48, .44f).none { it.canonical == "nihao" })
        assertTrue(units.paths("wo'nhiao", 48, .45f).any {
            it.canonical == "wonihao" && it.syllableEnds == listOf(2, 4, 7) && it.firstEditOffset == 3
        })
        assertEquals(0f, units.paths("nihao", 48).first { it.canonical == "nihao" }.cost)
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

    @Test
    fun cheapFuzzyCorrectionsAreNotStarvedByUnrelatedEditsAtTheEnd() {
        val production = PinyinSpellingGraph(
            repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
        )
        val cases = listOf(
            "tanmuweixieguonima" to "tang mu wei xie guo ni ma",
            "wobuxifuanzuoye" to "wo bu xi huan zuo ye",
            "wobujieyizaiyuzongmanbu" to "wo bu jie yi zai yu zhong man bu",
        )
        for ((typed, spelling) in cases) {
            val syllables = spelling.split(' ')
            val ends = syllables.runningFold(0) { offset, syllable -> offset + syllable.length }.drop(1)
            val paths = production.paths(typed, maxPaths = 48)
            assertTrue("$typed lost its single low-cost fuzzy correction", paths.any {
                it.canonical == syllables.joinToString("") && it.syllableEnds == ends && it.cost == .25f
            })
            assertTrue(paths.size <= 48)
        }
    }

    @Test
    fun channelFrontierKeepsCapsDeterminismAndForcedBoundaries() {
        for (limit in listOf(1, 4, 12, 24, 48)) {
            for (cost in listOf(0f, .25f, .35f, 1.05f)) {
                for (typed in listOf("xi'an", "nihao", "nihoa", "nihaoo", "nihaoshijiee")) {
                    val paths = graph.paths(typed, limit, cost)
                    assertEquals(paths, graph.paths(typed, limit, cost))
                    assertTrue(paths.size <= limit)
                    assertTrue(paths.all { it.cost <= cost + .0001f })
                    assertTrue(paths.zipWithNext().all { (a, b) -> a.cost <= b.cost })
                    assertEquals(paths.size, paths.map { it.canonical to it.syllableEnds }.distinct().size)
                    if (typed.contains('\'')) assertTrue(paths.all { it.syllableCount >= 2 })
                }
            }
        }
    }

    @Test
    fun genericSingleGapMetadataDoesNotConfuseRepeatedFuzzyOrMultipleEdits() {
        val units = PinyinSpellingGraph(listOf("ni", "hao"))
        fun target(typed: String, maxCost: Float = 1.05f) = units.paths(typed, 48, maxCost)
            .first { it.canonical == "nihao" && it.syllableEnds == listOf(2, 5) }
        assertEquals(true, target("niho").singleInsertionDeletion)
        assertEquals(false, target("nihaoo").singleInsertionDeletion)
        assertEquals(false, target("nhai", 1.5f).singleInsertionDeletion)
        assertEquals(false, PinyinSpellingGraph(listOf("zhong", "zong")).paths("zong")
            .first { it.canonical == "zhong" }.singleInsertionDeletion)
    }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Repository fixture is missing: $relativePath")
}
