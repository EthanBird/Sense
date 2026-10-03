package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class M8DailyInputBenchmarkTest {
    @Test
    fun scalarEditDistanceHandlesInsertDeleteSubstituteAndSupplementaryHan() {
        assertEquals(0, M8DailyInputBenchmark.editDistance("天气", "天气"))
        assertEquals(1, M8DailyInputBenchmark.editDistance("天气", "天其"))
        assertEquals(1, M8DailyInputBenchmark.editDistance("天气", "天"))
        assertEquals(1, M8DailyInputBenchmark.editDistance("天", "天气"))
        assertEquals(1, M8DailyInputBenchmark.editDistance("", String(Character.toChars(0x20000))))
    }

    @Test
    fun goldenLengthAuditAcceptsAmbiguousSegmentationButRejectsExtraSyllables() {
        val syllables = setOf("xian", "xi", "an", "qu", "zhe", "ge", "shi", "jie", "hen", "da")
        assertTrue(M8DailyInputBenchmark.hasSyllableCount("xianquxian", 4, syllables))
        assertTrue(M8DailyInputBenchmark.hasSyllableCount("zhegeshijiehenda", 6, syllables))
        assertFalse(M8DailyInputBenchmark.hasSyllableCount("zhegeshijiehenshida", 6, syllables))
    }

    @Test
    fun replayParserNormalizesVisualSpacesAndRejectsDuplicateIntentRows() {
        val file = File.createTempFile("sense-daily-", ".tsv")
        try {
            file.writeText("ni hao\t你好\tchat\t\t您好\n")
            val case = M8DailyInputBenchmark.readCases(file).single()
            assertEquals("nihao", case.query)
            assertEquals(listOf("您好"), case.aliases)
            file.appendText("nihao\t你号\tchat\n")
            assertTrue(runCatching { M8DailyInputBenchmark.readCases(file) }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            file.delete()
        }
    }
}
