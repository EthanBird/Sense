package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class T9PinyinChoiceTest {
    private val index by lazy {
        T9SyllableIndex(
            repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
        )
    }

    @Test
    fun hunshenxsChoicesAdvanceOneCanonicalSegmentAtATime() {
        var composition = compositionOf("486743697")

        composition = composition.selectPinyin(index.choices(composition).choice("hun"))
        assertEquals(listOf(T9LockedEdge(0, 3, "hun")), composition.lockedEdges)
        assertEquals("486743697", composition.rawDigits)

        composition = composition.selectPinyin(index.choices(composition).choice("shen"))
        composition = composition.selectPinyin(index.choices(composition).choice("x"))
        composition = composition.selectPinyin(index.choices(composition).choice("s"))

        assertEquals(
            listOf(
                T9LockedEdge(0, 3, "hun"),
                T9LockedEdge(3, 7, "shen"),
                T9LockedEdge(7, 8, "x"),
                T9LockedEdge(8, 9, "s"),
            ),
            composition.lockedEdges,
        )
        assertTrue(index.choices(composition).isEmpty())
        assertEquals(
            listOf("hun'shen'x's"),
            index.paths(composition).map(T9PinyinPath::formatted),
        )
    }

    @Test
    fun ambiguousChoicesAreDeterministicBoundedAndContainOnlyCanonicalPinyin() {
        val composition = compositionOf("486743697")

        val first = index.choices(composition)
        val repeated = index.choices(composition)

        assertEquals(first, repeated)
        assertTrue(first.size <= T9SyllableIndex.DEFAULT_MAX_CHOICES)
        assertEquals(first.size, first.map(T9PinyinChoice::stableId).distinct().size)
        assertTrue(first.any { it.canonicalPinyin == "hun" })
        assertTrue(first.all { choice ->
            choice.canonicalPinyin.all { it in 'a'..'z' } &&
                choice.previewPinyin.all { it in 'a'..'z' || it == '\'' }
        })
        assertEquals(first.take(2), index.choices(composition, maxChoices = 2))
    }

    @Test
    fun decodingPublishesChoicesFromItsExistingPathBeam() {
        val composition = compositionOf("486743697")
        val paths = index.paths(composition, T9SyllableIndex.DEFAULT_MAX_PATHS)
        var pathCalls = 0
        val result = T9AlternativeInputDecoder.decode(
            composition = composition,
            pathSource = T9PinyinPathSource { _, _ ->
                pathCalls += 1
                paths
            },
            pinyinDecoder = FakeDecoder(),
            leftContext = "",
            limit = 8,
        )

        assertEquals(1, pathCalls)
        assertTrue(result.pinyinChoices.any { it.canonicalPinyin == "hun" })
        assertEquals(
            buildT9PinyinChoices(
                composition,
                paths,
                T9SyllableIndex.DEFAULT_MAX_CHOICES,
            ),
            result.pinyinChoices,
        )
    }

    @Test
    fun explicitlySelectedHunshenxsPathKeepsTheProductionCandidateTopRanked() {
        var composition = compositionOf("486743697")
        listOf("hun", "shen", "x", "s").forEach { pinyin ->
            composition = composition.selectPinyin(index.choices(composition).choice(pinyin))
        }
        val bigrams = repositoryFile("ime-service/src/main/assets/pinyin_bigrams.bin")
            .inputStream()
            .buffered()
            .use(BinaryCharacterBigramModel::load)
        val decoder = repositoryFile("ime-service/src/main/assets/pinyin_lexicon.bin")
            .inputStream()
            .buffered()
            .use { input ->
                AdaptivePinyinDecoder(
                    base = PinyinDecoder.load(input, bigrams),
                    userLexicon = MemoryUserLexicon(),
                    segmenter = PinyinSyllableSegmenter(
                        repositoryFile("ime-service/src/main/assets/pinyin_syllables.txt").readLines(),
                    ),
                )
            }

        val result = T9AlternativeInputDecoder.decode(
            composition = composition,
            pathSource = index,
            pinyinDecoder = decoder,
            leftContext = "",
            limit = 64,
        )

        assertEquals("\u6D51\u8EAB\u89E3\u6570", result.candidates.firstOrNull()?.text)
        assertTrue(result.pinyinChoices.isEmpty())
    }

    @Test
    fun staleChoiceDoesNotMutateANewerCompositionRevision() {
        val original = compositionOf("486")
        val choice = index.choices(original).first()
        val newer = original.typeDigit('7')

        assertSame(newer, newer.selectPinyin(choice))
    }

    private fun List<T9PinyinChoice>.choice(pinyin: String): T9PinyinChoice =
        firstOrNull { it.canonicalPinyin == pinyin }
            ?: error("Missing canonical T9 choice: $pinyin from ${map { it.canonicalPinyin }}")

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Repository fixture is missing: $relativePath")
}
