package io.github.ethanbird.senseime.core

/**
 * Host-only experiment, not installed by the Android decoder factory.
 * A bounded emission feature for out-of-vocabulary Han isolates the current neutral
 * feature from dictionary coverage. This is NOT a calibrated UNK glyph probability.
 * Context still passes through the original model's normal UNK backoff.
 */
internal object OovLanguageModelAblation {
    fun bind(model: CharacterLanguageModel, feature: Float): CharacterLanguageModel {
        require(feature.isFinite() && feature in -6f..0f)
        if (feature == 0f) return model
        return object : CharacterLanguageModel {
            override fun containsCodePoint(codePoint: Int): Boolean =
                model.containsCodePoint(codePoint) ||
                    (Character.isValidCodePoint(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)

            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float =
                if (!model.containsCodePoint(next) && Character.isValidCodePoint(next) &&
                    Character.UnicodeScript.of(next) == Character.UnicodeScript.HAN) feature - 6f
                else model.logProbability(previous2, previous1, next)

            override fun unigramLogProbability(next: Int): Float = model.unigramLogProbability(next)
        }
    }
}
