package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3SentenceBenchmarkTest {
    @Test
    fun candidateRanksAreOneBasedAndAcceptedAliasesParticipate() {
        val values = listOf(Candidate("你"), Candidate("我"), Candidate("干吗"))
        val item = M3SentenceBenchmark.ReplayCase(
            query = "ganma",
            expected = "干嘛",
            aliases = listOf("干吗"),
        )

        assertEquals(2, M3SentenceBenchmark.rankOf(values, "我"))
        assertEquals(3, M3SentenceBenchmark.rankOf(values, item))
        assertNull(M3SentenceBenchmark.rankOf(values, "他"))
    }

    @Test
    fun nearestRankUsesTheRequestedPercentile() {
        assertEquals(3, M3SentenceBenchmark.nearestRankIndex(7, 0.5))
        assertEquals(6, M3SentenceBenchmark.nearestRankIndex(7, 0.95))
        assertEquals(18, M3SentenceBenchmark.nearestRankIndex(20, 0.95))
        assertEquals(98, M3SentenceBenchmark.nearestRankIndex(100, 0.99))
    }

    @Test
    fun replayParserSupportsLegacyAndRichRows() {
        val replay = temporaryReplay(
            """
            query	expected	mode	context	aliases	frequencyBucket
            nihao	你好
            ganma	干嘛	colloquial	你	干吗|做什么	mid
            """.trimIndent() + "\n",
        )

        val values = M3SentenceBenchmark.readReplay(replay)

        assertEquals(2, values.size)
        assertEquals(M3SentenceBenchmark.ReplayMode.FULL_PINYIN, values[0].mode)
        assertNull(values[0].context)
        assertEquals(M3SentenceBenchmark.FrequencyBucket.UNKNOWN, values[0].frequencyBucket)
        assertEquals(M3SentenceBenchmark.ReplayMode.COLLOQUIAL, values[1].mode)
        assertEquals("你", values[1].context)
        assertEquals(listOf("干吗", "做什么"), values[1].aliases)
        assertEquals(M3SentenceBenchmark.FrequencyBucket.MID, values[1].frequencyBucket)
    }

    @Test
    fun replayIdentityIncludesModeAndContext() {
        val valid = temporaryReplay(
            """
            shi	是	full	我		head
            shi	事	full	做		head
            shi	市	name			tail
            """.trimIndent() + "\n",
        )
        val duplicate = temporaryReplay(
            """
            nihao	你好	full
            nihao	你号	full
            """.trimIndent() + "\n",
        )

        assertEquals(3, M3SentenceBenchmark.readReplay(valid).size)
        assertTrue(runCatching { M3SentenceBenchmark.readReplay(duplicate) }.isFailure)
    }

    @Test
    fun replayParserRejectsInvalidQueryModeBucketAndRepeatedExpectedAlias() {
        val invalidQuery = temporaryReplay("ni'hao\t你好\n")
        val invalidMode = temporaryReplay("nihao\t你好\tunsupported\n")
        val invalidBucket = temporaryReplay("nihao\t你好\tfull\t\t\tvery-high\n")
        val repeatedAlias = temporaryReplay("ganma\t干嘛\tcolloquial\t\t干嘛\tmid\n")

        assertTrue(runCatching { M3SentenceBenchmark.readReplay(invalidQuery) }.isFailure)
        assertTrue(runCatching { M3SentenceBenchmark.readReplay(invalidMode) }.isFailure)
        assertTrue(runCatching { M3SentenceBenchmark.readReplay(invalidBucket) }.isFailure)
        assertTrue(runCatching { M3SentenceBenchmark.readReplay(repeatedAlias) }.isFailure)
    }

    @Test
    fun qualityMetricsReportTopKCoverageAndMrr() {
        val metrics = M3SentenceBenchmark.summarizeRanks(listOf(1, 2, 4, 11, null))

        assertEquals(5, metrics.cases)
        assertEquals(1, metrics.top1)
        assertEquals(2, metrics.top3)
        assertEquals(3, metrics.top10)
        assertEquals(4, metrics.covered)
        assertEquals((1.0 + 0.5 + 0.25 + 1.0 / 11.0) / 5.0, metrics.meanReciprocalRank, 1e-9)
        assertEquals(0.8, metrics.coverageRate, 1e-9)
    }

    private fun temporaryReplay(contents: String): File =
        File.createTempFile("sense-replay", ".tsv").apply {
            writeText(contents)
            deleteOnExit()
        }
}
