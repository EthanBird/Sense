package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class M12P2cBenchmarkTest {
    @Test fun reviewedCorpusRowsAreCompleteAndPinyinAligned() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "benchmarks/corpus/p2c-v1/frozen.json").isFile }
        val dev = M12P2cBenchmark.readCases(File(root, "benchmarks/corpus/p2c-v1/dev.tsv"))
        val test = M12P2cBenchmark.readCases(File(root, "benchmarks/corpus/p2c-v1/test.tsv"))
        assertEquals(63, dev.size); assertEquals(125, test.size)
        assertTrue(dev.map { it.id }.toSet().intersect(test.map { it.id }.toSet()).isEmpty())
        assertTrue((dev + test).all { it.query == it.syllables.joinToString("") })
    }

    @Test fun malformedOrDuplicateAnnotationsAreRejectedRatherThanSkipped() {
        val file = File.createTempFile("sense-p2c-", ".tsv")
        try {
            val row = "${"a".repeat(64)}\tnihao\t你好\tshort\tni hao\n"
            file.writeText(row)
            assertEquals(1, M12P2cBenchmark.readCases(file).size)
            for (bad in listOf(row + row, row.replace("ni hao", "ni"), row.replace("nihao", "ni1hao"))) {
                file.writeText(bad)
                assertTrue(runCatching { M12P2cBenchmark.readCases(file) }.isFailure)
            }
        } finally { file.delete() }
    }
}
