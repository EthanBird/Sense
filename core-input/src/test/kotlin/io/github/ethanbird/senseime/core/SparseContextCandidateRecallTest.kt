package io.github.ethanbird.senseime.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** E36 regression from the attributed p2c-supervised-v6 development replay, record 01e2b34c... . */
class SparseContextCandidateRecallTest {
    private val decoder by lazy {
        val model = asset("pinyin_character_lm.scng").inputStream().use(BinaryCharacterLanguageModel::load)
        val base = asset("pinyin_lexicon.bin").inputStream().use { PinyinDecoder.load(it,
            asset("pinyin_bigrams.bin").inputStream().use(BinaryCharacterBigramModel::load)) }
            .withLanguageModel(model, .5f, -4f)
        AdaptivePinyinDecoder(base, MemoryUserLexicon(),
            PinyinSyllableSegmenter(asset("pinyin_syllables.txt").readLines()),
            asset("english_lexicon.txt").inputStream().use(EnglishLexicon::load))
    }

    @Test fun rareKnownWordRemainsSelectableInACompleteSentence() = assertRecall(
        "qingbanidexinglifangzaizhechengshang", "", "请把你的行李放在这秤上",
    )

    @Test fun sparseContextDoesNotEraseTheSameWordAfterPartialCommit() = assertRecall(
        "lifangzaizhechengshang", "的行", "李放在这秤上",
    )

    private fun assertRecall(query: String, context: String, expected: String) {
        repeat(2) {
            val result = decoder.decodeProgressively(PinyinComposition(emptyList(), query), context, 255)
            // Protect selectability, not the existing wrong first result or a fixed target rank.
            assertTrue("Target must remain selectable: $expected", result.wholeCandidates.any { it.text == expected })
        }
    }

    private fun asset(name: String): File = listOf(File("../ime-service/src/main/assets/$name"),
        File("ime-service/src/main/assets/$name")).first { it.isFile }
}
