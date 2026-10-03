package io.github.ethanbird.senseime.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SpellingEdgeReuseTest {
    @Test fun appendDeleteBoundariesAndLongUnitsKeepTheFrozenUncachedGraphPaths() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "ime-service/src/main/assets/pinyin_syllables.txt").isFile }
        val graphs = mapOf(
            "syllables" to PinyinSpellingGraph(File(root, "ime-service/src/main/assets/pinyin_syllables.txt").readLines()),
            "ambiguous" to PinyinSpellingGraph(listOf("a", "aa", "aaa", "aaaa", "ao", "ni", "hao", "wo", "xi", "an", "xian")),
            "long" to PinyinSpellingGraph(listOf("abcdefghijklmnopqrstuvwx", "ni", "hao", "wo", "a", "o")),
        )
        val lines = requireNotNull(javaClass.getResourceAsStream("/spelling-edge-reuse-v1.tsv"))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(1481, lines.size)
        // Ascending prefixes then reverse simulate append/deletion; the full corpus also churns the bounded cache.
        for (line in lines + lines.reversed()) {
            val (inventory, input, limit, cost, expected) = line.split('\t')
            val result = graphs.getValue(inventory).paths(input, limit.toInt(), cost.toFloat())
            val actual = MessageDigest.getInstance("SHA-256").digest(result.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("$inventory / $input / $limit / $cost", expected, actual)
        }
    }
}
