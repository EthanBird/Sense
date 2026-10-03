package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class M15TypoRecallBenchmarkTest {
    @Test fun frozenRowsKeepPairedCleanCasesAndDisjointSourceFamilies() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it,"benchmarks/corpus/typo-v1/frozen.json").isFile }
        val dev = M15TypoRecallBenchmark.readCases(File(root,"benchmarks/corpus/typo-v1/dev.tsv"))
        val test = M15TypoRecallBenchmark.readCases(File(root,"benchmarks/corpus/typo-v1/test.tsv"))
        assertEquals(945,dev.size); assertEquals(1874,test.size)
        assertTrue(dev.map { it.sourceId }.toSet().intersect(test.map { it.sourceId }.toSet()).isEmpty())
        for (rows in listOf(dev,test)) for (family in rows.groupBy { it.sourceId }.values) {
            assertEquals(1,family.count { it.operation == "clean" })
            assertEquals(1,family.count { it.operation == "joints" })
            assertEquals(1,family.map { it.expected }.distinct().size)
            assertEquals(1,family.map { it.canonical }.distinct().size)
        }
    }

    @Test fun invalidRowsAreRejectedInsteadOfSilentlyReducingTheDenominator() {
        val file = File.createTempFile("sense-typo-", ".tsv")
        try {
            val row = "${"a".repeat(64)}\t${"b".repeat(64)}\tniho\tnihao\t你好\tshort\tni hao\tdelete\thead\t3\n"
            file.writeText(row); assertEquals(1,M15TypoRecallBenchmark.readCases(file).size)
            for (invalid in listOf(row+row,row.replace("ni hao","ni"),row.replace("delete","other"))) {
                file.writeText(invalid)
                assertTrue(runCatching { M15TypoRecallBenchmark.readCases(file) }.isFailure)
            }
        } finally { file.delete() }
    }
}
