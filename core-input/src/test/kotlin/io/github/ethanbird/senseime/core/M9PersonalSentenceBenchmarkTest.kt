package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class M9PersonalSentenceBenchmarkTest {
    @Test
    fun replaySeparatesRegressionExamplesFromDiagnosticsAndHasAlignedMetadata() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "benchmarks/replay/m9-personal-word-sentences.tsv").isFile }
        val rows = M9PersonalSentenceBenchmark.readCases(File(root, "benchmarks/replay/m9-personal-word-sentences.tsv"))
        assertEquals(16, rows.size)
        assertEquals(4, rows.count { it.required })
        assertEquals(6, rows.distinctBy { it.code to it.text }.size)
        val segmenter = PinyinSyllableSegmenter(File(root, "ime-service/src/main/assets/pinyin_syllables.txt").readLines())
        rows.forEach { assertTrue(segmenter.matchesFullSpelling(it.code, 0, it.code.length, it.initials, null)) }
    }

    @Test
    fun capacityFixtureHasTenThousandDistinctFullCodesAndHanWords() {
        val rows = M9PersonalSentenceBenchmark.capacityRows()
        assertEquals(10_000, rows.size)
        assertEquals(rows.size, rows.distinctBy { it.fullPinyin }.size)
        rows.forEach {
            assertEquals(6, it.fullPinyin.length)
            assertEquals(3, it.text.codePointCount(0, it.text.length))
            assertEquals(3, it.initials.length)
            assertTrue(it.text.codePoints().allMatch { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN })
        }
    }
}
