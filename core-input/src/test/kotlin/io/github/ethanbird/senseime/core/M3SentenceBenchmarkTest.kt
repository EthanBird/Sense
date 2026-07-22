package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3SentenceBenchmarkTest {
    @Test
    fun candidateRanksAreOneBasedAndMissingValuesAreNull() {
        val values = listOf(Candidate("你"), Candidate("我"))
        assertEquals(2, M3SentenceBenchmark.rankOf(values, "我"))
        assertNull(M3SentenceBenchmark.rankOf(values, "他"))
    }

    @Test
    fun nearestRankUsesTheRequestedPercentile() {
        assertEquals(3, M3SentenceBenchmark.nearestRankIndex(7, 0.5))
        assertEquals(6, M3SentenceBenchmark.nearestRankIndex(7, 0.95))
    }

    @Test
    fun replayParserRejectsDuplicateOrNonAsciiQueries() {
        val duplicate = File.createTempFile("sense-replay", ".tsv").apply {
            writeText("nihao\t你好\nnihao\t你号\n")
            deleteOnExit()
        }
        val invalid = File.createTempFile("sense-replay", ".tsv").apply {
            writeText("ni'hao\t你好\n")
            deleteOnExit()
        }

        assertTrue(runCatching { M3SentenceBenchmark.readReplay(duplicate) }.isFailure)
        assertTrue(runCatching { M3SentenceBenchmark.readReplay(invalid) }.isFailure)
    }
}
