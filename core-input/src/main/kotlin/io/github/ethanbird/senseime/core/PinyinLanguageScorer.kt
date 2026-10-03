package io.github.ethanbird.senseime.core

/** Immutable, opt-in binding. A missing model/zero weight uses the original decoder exactly. */
internal data class PinyinLanguageBinding(
    val model: CharacterLanguageModel,
    val weight: Float,
    val correctionCompositionBoost: Float = 12f,
    val oovFeature: Float = 0f,
)

/**
 * Query-local log-linear feature, NOT a normalized sentence probability. Character emissions
 * are counted once, independent of word boundaries; no EOS is added to an unfinished editor.
 * All spellings for this raw query share one positive score normalizer (six letters/word).
 * +6 is an explicit character insertion prior. Unknown Han has a separate bounded
 * feature, not the probability of the entire UNK class assigned to every glyph.
 * Generic bindings retain legacy neutrality; the production full-pinyin factory
 * explicitly selects its evaluated feature. User-new-word LM backoff is unchanged.
 */
internal class PinyinLanguageScorer(
    private val binding: PinyinLanguageBinding,
    rawQuery: String,
    val initialState: Long,
) {
    val normalizer = maxOf(1f, rawQuery.length / 6f)
    data class Step(val state: Long, val score: Float)
    private data class Edge(val state: Long, val text: String)
    private val cache = HashMap<Edge, Step>()
    private val fourgram = binding.model as? FourgramLanguageModel

    fun extend(state: Long, text: String): Step {
        val key = Edge(state, text)
        cache[key]?.let { return it }
        fourgram?.let { model ->
            val result = extendFourgram(state, text, model)
            if (cache.size < 4_096) cache[key] = result
            return result
        }
        var previous2 = (state ushr 21).toInt()
        var previous1 = (state and MASK).toInt()
        var score = 0f
        var offset = 0
        while (offset < text.length) {
            val next = text.codePointAt(offset)
            offset += Character.charCount(next)
            if (Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN) {
                previous2 = CharacterLanguageModel.BOS
                previous1 = CharacterLanguageModel.BOS
                continue
            }
            if (binding.model.containsCodePoint(next)) {
                val logProbability = binding.model.logProbability(previous2, previous1, next)
                if (logProbability.isFinite()) score += (logProbability + 6f).coerceIn(-6f, 6f)
            } else {
                score += binding.oovFeature
            }
            previous2 = previous1
            previous1 = next
        }
        val result = Step(pack(previous2, previous1), score * binding.weight)
        // Never retain editor text beyond one decode; cap even adversarial correction lattices.
        if (cache.size < 4_096) cache[key] = result
        return result
    }

    private fun extendFourgram(state: Long, text: String, model: FourgramLanguageModel): Step {
        var previous3 = (state ushr 42).toInt()
        var previous2 = ((state ushr 21) and MASK).toInt()
        var previous1 = (state and MASK).toInt()
        var score = 0f; var offset = 0
        while (offset < text.length) {
            val next = text.codePointAt(offset); offset += Character.charCount(next)
            if (Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN) {
                previous3 = CharacterLanguageModel.BOS; previous2 = CharacterLanguageModel.BOS; previous1 = CharacterLanguageModel.BOS
                continue
            }
            if (model.containsCodePoint(next)) {
                val logProbability = model.logProbabilityWithThirdContext(previous3, previous2, previous1, next)
                if (logProbability.isFinite()) score += (logProbability + 6f).coerceIn(-6f, 6f)
            } else score += binding.oovFeature
            previous3 = previous2; previous2 = previous1; previous1 = next
        }
        return Step(pack3(previous3, previous2, previous1), score * binding.weight)
    }

    fun feature(text: String): Float = extend(initialState, text).score / normalizer

    /**
     * Keep evidence for syllables already touched by the literal input prefix, including
     * its unfinished last syllable. Only the untyped suffix loses the character insertion
     * prior: predictable extra words must not manufacture additional positive evidence.
     * Reuse the ordinary LM cache; the source-dependent adjustment is never cached there.
     * OOV emissions already have their own penalty, so do not subtract a prior from them.
     */
    fun completionFeature(text: String, supportedCharacters: Int): Float {
        require(supportedCharacters >= 0)
        val characters = text.codePointCount(0, text.length)
        if (supportedCharacters >= characters) return feature(text)
        var offset = text.offsetByCodePoints(0, supportedCharacters)
        var untypedKnownHan = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset); offset += Character.charCount(cp)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN && binding.model.containsCodePoint(cp)) untypedKnownHan++
        }
        return (extend(initialState, text).score - untypedKnownHan * 6f * binding.weight) / normalizer
    }
    /**
     * A syntactically valid word path is weaker evidence than an exact dictionary word.
     * In the LM score domain the old composed-vs-corrected prior gap was 14 points,
     * dwarfing contextual evidence even for nonsensical literal parses. Preserve a
     * 2-point typed prior, in addition to the unchanged spelling edit penalty.
     * Stored once in Candidate.score so personalization/mixed ranking never adds it twice.
     */
    fun sourceAdjustment(
        kind: CandidateMatchKind, exact: Boolean, composed: Boolean,
        literalCompletionLeads: Boolean = false,
    ): Float {
        if (kind != CandidateMatchKind.CORRECTED || exact || !composed) return 0f
        if (!literalCompletionLeads) return binding.correctionCompositionBoost
        // The existing boost offsets the composition prior, not the lower completion prior.
        // Preserve the same typed-evidence margin when the best literal path is a touched
        // word completion; do not let an unrelated composable parse subsidize every repair.
        val priorGap = CandidateRanker.sourcePrior(CandidateMatchKind.BASE_COMPOSED, false, true) -
            CandidateRanker.sourcePrior(CandidateMatchKind.BASE_PREFIX, false, true)
        return (binding.correctionCompositionBoost - priorGap).coerceAtLeast(0f)
    }
    fun lexical(score: Float, logMass: Float): Float = logMass + (score - logMass) / normalizer

    companion object {
        private const val MASK = 0x1FFFFFL
        fun pack(previous2: Int, previous1: Int): Long = (previous2.toLong() shl 21) or previous1.toLong()
        fun pack3(previous3: Int, previous2: Int, previous1: Int): Long =
            (previous3.toLong() shl 42) or (previous2.toLong() shl 21) or previous1.toLong()
        fun context3(text: CharSequence): Long {
            val points = IntArray(3) { CharacterLanguageModel.BOS }
            var end = text.length
            for (index in 2 downTo 0) {
                if (end == 0) break
                val cp = Character.codePointBefore(text, end)
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) break
                points[index] = cp; end -= Character.charCount(cp)
            }
            return pack3(points[0], points[1], points[2])
        }
        fun context3(previousCodePoint: Int): Long = pack3(CharacterLanguageModel.BOS, CharacterLanguageModel.BOS,
            if (Character.isValidCodePoint(previousCodePoint) && Character.UnicodeScript.of(previousCodePoint) == Character.UnicodeScript.HAN)
                previousCodePoint else CharacterLanguageModel.BOS)
        fun context(text: CharSequence): Long {
            if (text.isEmpty()) return context(-1)
            val last = Character.codePointBefore(text, text.length)
            if (Character.UnicodeScript.of(last) != Character.UnicodeScript.HAN) return context(-1)
            val offset = text.length - Character.charCount(last)
            val previous = if (offset > 0) Character.codePointBefore(text, offset) else -1
            return pack(if (Character.isValidCodePoint(previous) && Character.UnicodeScript.of(previous) == Character.UnicodeScript.HAN)
                previous else CharacterLanguageModel.BOS, last)
        }
        fun context(previousCodePoint: Int): Long = pack(CharacterLanguageModel.BOS,
            if (Character.isValidCodePoint(previousCodePoint) && Character.UnicodeScript.of(previousCodePoint) == Character.UnicodeScript.HAN)
                previousCodePoint else CharacterLanguageModel.BOS)
    }
}
