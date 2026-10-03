package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.CharacterLanguageModel
import io.github.ethanbird.senseime.core.EnglishLexicon
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.UserLexicon

/** One publication/generation and shared learning store, but no unvalidated LM change to T9. */
internal data class ProductionPinyinDecoders(val fullPinyin: AdaptivePinyinDecoder, val t9: AdaptivePinyinDecoder) {
    companion object {
        fun create(base: InputDecoder, userLexicon: UserLexicon, segmenter: PinyinSyllableSegmenter,
                   english: EnglishLexicon, languageModel: CharacterLanguageModel): ProductionPinyinDecoders {
            val legacy = AdaptivePinyinDecoder(base, userLexicon, segmenter, english)
            val full = if (base is PinyinDecoder && languageModel !== CharacterLanguageModel.EMPTY)
                AdaptivePinyinDecoder(base.withLanguageModel(languageModel, BundledPinyinLanguageModel.WEIGHT,
                    BundledPinyinLanguageModel.OOV_FEATURE), userLexicon, segmenter, english)
                else legacy
            return ProductionPinyinDecoders(full, legacy)
        }
    }
}
