package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class SortedPinyinPrefixLookupTest {
    private fun table(codes: List<String>): Pair<ByteArray, IntArray> {
        val bytes = ByteArrayOutputStream()
        val offsets = codes.map { code ->
            val offset = bytes.size()
            bytes.write(code.length); bytes.write(code.toByteArray(Charsets.US_ASCII))
            // Variable unrelated payload must not be mistaken for another code byte.
            repeat(code.length % 7 + 1) { bytes.write(255) }
            offset
        }.toIntArray()
        return bytes.toByteArray() to offsets
    }

    @Test fun exactShorterCodePrecedesExtensionsAndNoMatchDoesNotRestartAtAnotherRange() {
        val codes = listOf("a", "an", "ana", "ang", "b", "ni", "nihao", "nihaoma", "z", "{n", "}n|ni", "~nh").sorted()
        val (data, offsets) = table(codes)
        for (query in listOf("a", "anang", "anxni", "nihaoma", "nihaox", "zzzz", "x", "~nh", "{n")) {
            for (start in query.indices) {
                val actual = sortedPinyinPrefixRecords(data, offsets, query, start, query.length)
                assertEquals(-1, actual[0])
                for (size in 1 until actual.size) {
                    assertEquals("$query/$start/$size", codes.indexOf(query.substring(start, start + size)), actual[size])
                }
            }
        }
    }

    @Test fun sparseViewReturnsItsOwnLocalRecordIdsAndHandlesUnsignedLongCodeLengths() {
        val codes = (listOf("a", "aa", "an", "ni", "nihao", "z", "z".repeat(150))).sorted()
        val (data, full) = table(codes)
        val indices = listOf(0, 2, 4, 6)
        val offsets = indices.map { full[it] }.toIntArray()
        val view = indices.map { codes[it] }
        for (query in codes) {
            val actual = sortedPinyinPrefixRecords(data, offsets, query, 0, query.length)
            for (size in 1 until actual.size) assertEquals(view.indexOf(query.take(size)), actual[size])
        }
    }

    @Test fun fixedRandomDictionaryMatchesIndependentSubstringLookupAcrossOffsetsAndMissingCodes() {
        val random = java.util.Random(42)
        val codes = (0 until 2_000).map {
            buildString { repeat(1 + random.nextInt(18)) { append('a' + random.nextInt(6)) } }
        }.distinct().sorted()
        val (data, offsets) = table(codes)
        repeat(600) {
            val query = "x" + codes[random.nextInt(codes.size)] + "xyz"
            val start = random.nextInt(3)
            val end = start + random.nextInt(query.length - start + 1)
            val actual = sortedPinyinPrefixRecords(data, offsets, query, start, end)
            for (size in 1 until actual.size) assertEquals(codes.indexOf(query.substring(start, start + size)), actual[size])
        }
    }

    @Test fun emptyViewEmptySliceAndInvalidBoundsAreExplicit() {
        assertArrayEquals(intArrayOf(-1, -1, -1), sortedPinyinPrefixRecords(byteArrayOf(), intArrayOf(), "ni", 0, 2))
        assertArrayEquals(intArrayOf(-1), sortedPinyinPrefixRecords(byteArrayOf(), intArrayOf(), "ni", 1, 1))
        for ((start, end) in listOf(-1 to 1, 2 to 1, 0 to 3)) {
            assertThrows(IllegalArgumentException::class.java) {
                sortedPinyinPrefixRecords(byteArrayOf(), intArrayOf(), "ni", start, end)
            }
        }
    }

    @Test fun canceledLookupPublishesNothingAndConcurrentQueriesShareOnlyReadOnlyBytes() {
        val codes = listOf("n", "ni", "nih", "niha", "nihao")
        val (data, offsets) = table(codes)
        val originalData = data.copyOf(); val originalOffsets = offsets.copyOf()
        var checkpoints = 0
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ ++checkpoints < 4 }) {
                sortedPinyinPrefixRecords(data, offsets, "nihao", 0, 5)
            }
        }
        assertEquals(4, checkpoints)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((0 until 200).map { java.util.concurrent.Callable {
                assertArrayEquals(intArrayOf(-1, 0, 1, 2, 3, 4), sortedPinyinPrefixRecords(data, offsets, "nihao", 0, 5))
            } }).forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertArrayEquals(originalData, data)
        assertArrayEquals(originalOffsets, offsets)
    }
}
