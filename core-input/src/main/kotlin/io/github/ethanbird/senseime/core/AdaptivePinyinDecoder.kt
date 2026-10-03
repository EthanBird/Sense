package io.github.ethanbird.senseime.core

class AdaptivePinyinDecoder(
    base: InputDecoder,
    private val userLexicon: UserLexicon,
    private val segmenter: PinyinSyllableSegmenter,
    private val englishLexicon: EnglishLexicon = EnglishLexicon.EMPTY,
) : ProgressivePinyinDecoder,
    ContextualInputDecoder,
    CanonicalChineseOnlyInputDecoder,
    CanonicalChineseLexicalProbeDecoder {
    // Each adaptive instance owns its personal overlay. Immutable dictionary/model arrays are
    // shared; binding one user must never mutate a base decoder used by another profile.
    private val base: InputDecoder = (base as? PinyinDecoder)?.withUserLexicon(userLexicon) ?: base
    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }

        val chinese = decodeChinese(decoderInput, query, limit)
        return MixedCandidateRanker.merge(chinese, englishSuggestions(composing, query, limit), limit)
    }

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        val chinese = decodeChinese(decoderInput, query, limit, previousCodePoint)
        return MixedCandidateRanker.merge(chinese, englishSuggestions(composing, query, limit), limit)
    }

    override fun decodeChineseOnly(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        return pureHanCandidates(decodeChinese(decoderInput, query, limit), limit)
    }

    override fun decodeChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        return pureHanCandidates(
            decodeChinese(decoderInput, query, limit, previousCodePoint),
            limit,
        )
    }

    override fun decodeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        return pureHanCandidates(
            decodeChinese(decoderInput, query, limit, prefixProbe = true),
            limit,
        )
    }

    override fun decodeCanonicalChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0 || !Character.isValidCodePoint(previousCodePoint)) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        return pureHanCandidates(
            decodeChinese(
                decoderInput,
                query,
                limit,
                previousCodePoint,
                prefixProbe = true,
            ),
            limit,
        )
    }

    override fun probeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> =
        probeCanonicalChinese(composing, limit, previousCodePoint = null)

    override fun probeCanonicalChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return probeCanonicalChinese(composing, limit, previousCodePoint)
    }

    private fun probeCanonicalChinese(
        composing: String,
        limit: Int,
        previousCodePoint: Int?,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val decoderInput = normalizeDecoderInput(composing)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        if (base !is CanonicalChineseLexicalProbeDecoder) {
            return pureHanCandidates(
                decodeChinese(
                    decoderInput,
                    query,
                    limit,
                    previousCodePoint,
                    prefixProbe = true,
                ),
                limit,
            )
        }
        val baseCandidates = previousCodePoint
            ?.let { base.probeCanonicalChineseOnlyAfter(it, decoderInput, limit) }
            ?: base.probeCanonicalChineseOnly(decoderInput, limit)
        val candidates = mergeUserAndBase(
            query = query,
            limit = limit,
            baseCandidates = baseCandidates,
            boundaryInput = decoderInput.takeIf { '\'' in it },
        )
        return pureHanCandidates(candidates, limit)
    }

    /**
     * Chinese-only decode seam used by progressive prefix probes.
     *
     * Prefix candidates accept Han text only, so running the English lexicon
     * for every prefix used to allocate and rank values that were immediately
     * discarded.
     */
    private fun decodeChinese(
        decoderInput: String,
        query: String,
        limit: Int,
        previousCodePoint: Int? = null,
        prefixProbe: Boolean = false,
        leftContext: CharSequence? = null,
    ): List<Candidate> {
        val baseCandidates = when {
            leftContext != null && base is TextContextualInputDecoder ->
                base.decodeWithContext(leftContext, decoderInput, limit, prefixProbe)

            prefixProbe && base is ProgressivePrefixProbeDecoder && previousCodePoint != null ->
                base.decodePrefixProbeAfter(previousCodePoint, decoderInput, limit)

            prefixProbe && base is ProgressivePrefixProbeDecoder ->
                base.decodePrefixProbe(decoderInput, limit)

            previousCodePoint != null && base is ContextualInputDecoder ->
                base.decodeAfter(previousCodePoint, decoderInput, limit)

            else -> base.decode(decoderInput, limit)
        }
        return mergeUserAndBase(
            query = query,
            limit = limit,
            baseCandidates = baseCandidates,
            boundaryInput = decoderInput.takeIf { '\'' in it },
        )
    }

    /** Preserve the production list instance when it is already all-Han and within the limit. */
    private fun pureHanCandidates(candidates: List<Candidate>, limit: Int): List<Candidate> {
        if (candidates.size <= limit && candidates.all { isPureHanText(it.text) }) {
            return candidates
        }
        return candidates.asSequence()
            .filter { isPureHanText(it.text) }
            .take(limit)
            .toList()
    }

    private fun isPureHanText(value: String): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun mergeUserAndBase(
        query: String,
        limit: Int,
        baseCandidates: List<Candidate>,
        boundaryInput: String? = null,
    ): List<Candidate> {
        val learnedLookupLimit = if (boundaryInput == null) {
            limit
        } else {
            maxOf(limit, FORCED_BOUNDARY_USER_LOOKUP_LIMIT)
        }
        val learnedCandidates = userLexicon.lookup(query, learnedLookupLimit).let { learned ->
            if (boundaryInput == null) {
                learned
            } else {
                learned.filter { phrase ->
                    segmenter.segmentMixed(
                        value = boundaryInput,
                        canonicalPinyin = phrase.fullPinyin,
                        canonicalInitials = phrase.initials,
                    ) != null
                }.take(limit)
            }
        }
        // Production base results already satisfy RankedCandidateDecoder. Most
        // composing queries have no personalization row, so preserve that result
        // instead of copying every candidate through adjustment maps and a second
        // CandidateRanker pass. Generic decoders retain the normalizing path.
        if (learnedCandidates.isEmpty() && base is RankedCandidateDecoder) return baseCandidates
        val negativeAdjustmentByText = HashMap<String, Float>()
        learnedCandidates.forEach { learned ->
            if (learned.rankingBoost < -MIN_ACTIVE_PERSONALIZATION_ADJUSTMENT) {
                negativeAdjustmentByText.merge(learned.text, learned.rankingBoost, ::minOf)
            }
        }
        val adjustedBaseCandidates = baseCandidates.map { candidate ->
            negativeAdjustmentByText[candidate.text]
                ?.let { candidate.copy(score = candidate.score + it) }
                ?: candidate
        }
        val baseByText = LinkedHashMap<String, Candidate>(adjustedBaseCandidates.size)
        adjustedBaseCandidates.forEach { candidate ->
            baseByText.putIfAbsent(candidate.text, candidate)
        }
        val candidates = ArrayList<Candidate>(adjustedBaseCandidates.size + limit)
        candidates.addAll(adjustedBaseCandidates)
        val hasCanonicalExact =
            adjustedBaseCandidates.any { it.matchKind == CandidateMatchKind.BASE_EXACT }
        val hasCanonicalComposition =
            adjustedBaseCandidates.any { it.matchKind == CandidateMatchKind.BASE_COMPOSED }
        val topBaseTotal = adjustedBaseCandidates.maxOfOrNull { candidate ->
            candidate.score + CandidateRanker.sourcePrior(
                candidate.matchKind,
                hasCanonicalExact,
                hasCanonicalComposition,
            )
        } ?: 0f
        learnedCandidates.forEach { learned ->
            val fullMatch = learned.fullPinyin == query
            val initialsMatch = !fullMatch && learned.initials == query
            val userKind =
                if (initialsMatch) CandidateMatchKind.USER_INITIALS else CandidateMatchKind.USER_FULL
            val matchPenalty = if (initialsMatch) USER_INITIALS_AMBIGUITY_PENALTY else 0f
            val personalizationBonus = learned.rankingBoost - matchPenalty
            if (personalizationBonus <= MIN_ACTIVE_PERSONALIZATION_ADJUSTMENT) return@forEach
            val baseCandidate = baseByText[learned.text]
            val userPrior = CandidateRanker.sourcePrior(
                userKind,
                hasCanonicalExact,
                hasCanonicalComposition,
            )
            val baseTotal = baseCandidate?.let {
                it.score + CandidateRanker.sourcePrior(
                    it.matchKind,
                    hasCanonicalExact,
                    hasCanonicalComposition,
                )
            } ?: (topBaseTotal - USER_ONLY_BASE_GAP)
            // A strong, exact user observation (canonical or its explicitly learned alias)
            // must not inherit an arbitrarily deep composed/corrected
            // base score. Otherwise the same learned phrase is near the front with a small decode
            // limit (where it is user-only), yet disappears with production's 255-result limit
            // after colliding with a deep BASE_COMPOSED row.
            val effectiveBaseTotal = if (
                (fullMatch || (!initialsMatch && query in learned.aliases)) &&
                learned.rankingBoost >= USER_OBSERVED_FLOOR_MIN_BOOST
            ) {
                maxOf(baseTotal, topBaseTotal - USER_ONLY_BASE_GAP)
            } else {
                baseTotal
            }
            candidates += Candidate(
                text = learned.text,
                score = effectiveBaseTotal - userPrior + personalizationBonus,
                canonicalPinyin = learned.fullPinyin,
                matchKind = userKind,
                canonicalInitials = learned.initials,
            )
        }
        return CandidateRanker.rank(
            candidates = candidates,
            limit = limit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = hasCanonicalComposition,
        )
    }

    override fun decodeProgressively(
        composition: PinyinComposition,
        limit: Int,
    ): ProgressivePinyinDecoding = decodeProgressively(
        composition = composition,
        leftContext = "",
        limit = limit,
    )

    override fun decodeProgressively(
        composition: PinyinComposition,
        leftContext: CharSequence,
        limit: Int,
    ): ProgressivePinyinDecoding {
        DecodeWorkScope.checkpoint()
        if (composition.composingCodeLength > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return ProgressivePinyinDecoding(
                revision = composition.revision,
                remainingPinyin = composition.remainingPinyin,
                wholeCandidates = emptyList(),
                prefixCandidates = emptyList(),
            )
        }
        val rawInput = composition.remainingPinyin
        val decoderInput = normalizeDecoderInput(rawInput)
        val query = PinyinSyllableSegmenter.normalize(decoderInput)
        if (limit <= 0 || query.isEmpty()) {
            return ProgressivePinyinDecoding(
                revision = composition.revision,
                remainingPinyin = rawInput,
                wholeCandidates = emptyList(),
                prefixCandidates = emptyList(),
            )
        }

        // Preserve the external preceding character when only one composing segment was
        // accepted. The LM consumes at most the final two scalars, never a stored editor body.
        val localContext = leftContext.takeLast(4).toString() + composition.acceptedText.takeLast(4)
        val contextCodePoint = localContext
            .takeIf { it.isNotEmpty() }
            ?.let { it.codePointBefore(it.length) }
        val chinese = decodeChinese(decoderInput, query, limit, contextCodePoint, leftContext = localContext)
        DecodeWorkScope.checkpoint()
        val wholeCandidates = MixedCandidateRanker.merge(chinese, englishSuggestions(rawInput, query, limit), limit)
        val wholePrefixCodePoints = IntArray(wholeCandidates.size) { NO_CODE_POINT }
        wholeCandidates.forEachIndexed { index, candidate ->
            if (candidate.text.isNotEmpty()) {
                wholePrefixCodePoints[index] = candidate.text.codePointAt(0)
            }
        }

        val prefixLimit = minOf(limit, MAX_PROGRESSIVE_CANDIDATES)
        val segmentablePrefixLengths = segmenter.selectablePrefixLengths(decoderInput)
        val fallbackPrefixLength = if (segmenter.isComplete(decoderInput)) {
            0
        } else {
            minOf(query.length - 1, MAX_FALLBACK_PREFIX_LENGTH)
        }
        val maximumPrefixLength = maxOf(
            segmentablePrefixLengths.lastOrNull() ?: 0,
            fallbackPrefixLength,
        )
        val prefixGroups = ArrayList<List<RankedPrefixCandidate>>()
        // A consumed raw span owns its following explicit separator. The tail starts
        // with letters; undo restores the exact typed boundary. Plain input allocates
        // no offset map and keeps the established fast path.
        val rawCuts = if (rawInput == query) null else IntArray(query.length + 1).also { cuts ->
            var letters = 0
            rawInput.forEachIndexed { index, c ->
                if (c.lowercaseChar() in 'a'..'z') letters++
                if (letters <= query.length) cuts[letters] = index + 1
            }
        }
        var segmentableIndex = 0
        for (length in 1..maximumPrefixLength) {
            DecodeWorkScope.checkpoint()
            if (length >= query.length) continue
            while (
                segmentableIndex < segmentablePrefixLengths.size &&
                segmentablePrefixLengths[segmentableIndex] < length
            ) {
                segmentableIndex += 1
            }
            val isSegmentable =
                segmentableIndex < segmentablePrefixLengths.size &&
                    segmentablePrefixLengths[segmentableIndex] == length
            if (!isSegmentable && length > fallbackPrefixLength) continue
            val consumed = query.substring(0, length)
            val rawCut = rawCuts?.get(length) ?: length
            val consumedRaw = rawInput.substring(0, rawCut)
            val remaining = rawInput.substring(rawCut)
            val maximumHanCharacters = if (isSegmentable) {
                1
            } else {
                MAX_FALLBACK_PREFIX_HAN_CHARACTERS
            }
            val decoded = decodeChinese(normalizeDecoderInput(consumedRaw), consumed, prefixLimit, contextCodePoint, prefixProbe = true, leftContext = localContext)
            val group = ArrayList<RankedPrefixCandidate>()
            val seenTexts = HashSet<String>()
            decoded.forEach { candidate ->
                if (
                    isSelectableHanCandidate(candidate, maximumHanCharacters) &&
                    seenTexts.add(candidate.text)
                ) {
                    val prefix = PinyinPrefixCandidate(candidate, consumedRaw, remaining)
                    group += RankedPrefixCandidate(
                        value = prefix,
                        identity = PrefixIdentity(consumedRaw, candidate.text),
                        wholeRank = wholePrefixRank(
                            wholePrefixCodePoints,
                            candidate.text.codePointAt(0),
                        ),
                        decodedRank = group.size,
                    )
                }
            }
            if (group.isNotEmpty()) {
                prefixGroups += group
            }
        }
        val prefixes = mergePrefixGroups(
            groups = prefixGroups,
            limit = prefixLimit,
        )
        return ProgressivePinyinDecoding(
            revision = composition.revision,
            remainingPinyin = rawInput,
            wholeCandidates = wholeCandidates,
            prefixCandidates = prefixes,
        )
    }

    private fun wholePrefixRank(
        wholePrefixCodePoints: IntArray,
        codePoint: Int,
    ): Int {
        for (index in wholePrefixCodePoints.indices) {
            if (wholePrefixCodePoints[index] == codePoint) return index
        }
        return Int.MAX_VALUE
    }

    private data class PrefixIdentity(
        val consumedPinyin: String,
        val text: String,
    )

    /**
     * Internal rank metadata computed once per public prefix candidate.
     *
     * Keeping it beside the candidate removes substring, Pair and map work from
     * the sort comparator, which is invoked O(n log n) times.
     */
    private data class RankedPrefixCandidate(
        val value: PinyinPrefixCandidate,
        val identity: PrefixIdentity,
        val wholeRank: Int,
        val decodedRank: Int,
    )

    /** Keeps every valid first-syllable path represented before filling by score. */
    private fun mergePrefixGroups(
        groups: List<List<RankedPrefixCandidate>>,
        limit: Int,
    ): List<PinyinPrefixCandidate> {
        if (limit <= 0 || groups.isEmpty()) return emptyList()
        val values = ArrayList<RankedPrefixCandidate>(limit)
        val seen = HashSet<PrefixIdentity>()
        groups.forEach { group ->
            val first = group.firstOrNull() ?: return@forEach
            if (values.size < limit && seen.add(first.identity)) values += first
        }
        val ordered = ArrayList<RankedPrefixCandidate>(
            groups.sumOf { group -> group.size },
        )
        groups.forEach(ordered::addAll)
        ordered.sortWith(RANKED_PREFIX_ORDER)
        ordered.forEach { candidate ->
            if (
                values.size < limit &&
                seen.add(candidate.identity)
            ) {
                values += candidate
            }
        }
        values.sortWith(RANKED_PREFIX_ORDER)
        return values.mapTo(ArrayList(values.size)) { it.value }
    }

    /** Records only complete Chinese selections whose pinyin can be split unambiguously by character count. */
    fun learn(
        rawInput: String,
        candidate: Candidate,
        evidence: UserLearningEvidence = UserLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedPhrase? {
        val characterCount = countHanCodePoints(candidate.text)
        if (characterCount == 0) return null
        if (candidate.matchKind == CandidateMatchKind.BASE_INITIALS) return null
        val normalizedRawInput = PinyinSyllableSegmenter.normalize(rawInput)
        val canonical = when (candidate.matchKind) {
            CandidateMatchKind.BASE_PREFIX -> candidate.canonicalPinyin ?: return null
            else -> candidate.canonicalPinyin ?: normalizedRawInput
        }.let(PinyinSyllableSegmenter::normalize)
        if (
            candidate.matchKind == CandidateMatchKind.BASE_PREFIX &&
            (normalizedRawInput.isEmpty() || !canonical.startsWith(normalizedRawInput))
        ) {
            return null
        }
        if (!segmenter.isComplete(canonical)) return null
        val suppliedInitials = candidate.canonicalInitials
            ?.let(PinyinSyllableSegmenter::normalize)
            ?.takeIf { it.length == characterCount }
        val initials = suppliedInitials ?: segmenter.initials(canonical, characterCount) ?: return null
        val aliases = linkedSetOf<String>()
        sequenceOf(normalizedRawInput, candidate.pinyinInputAlias)
            .filterNotNull()
            .map(PinyinSyllableSegmenter::normalize)
            .filter { it.isNotEmpty() && it != canonical && it != initials }
            .forEach(aliases::add)
        return userLexicon.record(canonical, initials, candidate.text, aliases, evidence)
    }

    fun demote(
        phrase: LearnedPhrase,
        feedback: UserNegativeFeedback = UserNegativeFeedback.MANUAL_DEMOTION,
    ): LearnedPhrase? = userLexicon.demote(phrase.fullPinyin, phrase.text, feedback)

    fun forget(phrase: LearnedPhrase): Boolean = userLexicon.forget(phrase.fullPinyin, phrase.text)

    private fun englishSuggestions(raw: String, normalized: String, limit: Int): List<Candidate> =
        // A typed separator is explicit Chinese segmentation, not an English completion request.
        if ('\'' in raw) emptyList() else englishLexicon.suggest(normalized, limit)

    /** Keeps explicit syllable joints for the base spelling graph while normalizing user lookup. */
    private fun normalizeDecoderInput(value: String): String {
        if (value.all { it in 'a'..'z' }) return value
        return buildString(value.length) {
            var previousWasApostrophe = false
            value.forEach { character ->
                val lower = character.lowercaseChar()
                when {
                    lower in 'a'..'z' -> {
                        append(lower)
                        previousWasApostrophe = false
                    }

                    character == '\'' && isNotEmpty() && !previousWasApostrophe -> {
                        append(character)
                        previousWasApostrophe = true
                    }
                }
            }
            if (lastOrNull() == '\'') deleteCharAt(lastIndex)
        }
    }

    private fun countHanCodePoints(value: String): Int {
        var count = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) count += 1
            offset += Character.charCount(codePoint)
        }
        return count
    }

    private fun isSelectableHanCandidate(
        candidate: Candidate,
        maximumHanCharacters: Int,
    ): Boolean {
        var characterCount = 0
        var offset = 0
        while (offset < candidate.text.length) {
            val codePoint = candidate.text.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            characterCount += 1
            if (characterCount > maximumHanCharacters) return false
            offset += Character.charCount(codePoint)
        }
        return characterCount > 0 &&
            candidate.canonicalInitials?.length == characterCount
    }

    private companion object {
        const val FORCED_BOUNDARY_USER_LOOKUP_LIMIT = 128
        val RANKED_PREFIX_ORDER =
            compareBy<RankedPrefixCandidate> { it.wholeRank }
                .thenBy { it.decodedRank }
                .thenBy { it.value.consumedPinyin.length }
                .thenBy { it.value.candidate.text }

        const val USER_ONLY_BASE_GAP = 2.25f
        const val USER_OBSERVED_FLOOR_MIN_BOOST = 1f
        const val USER_INITIALS_AMBIGUITY_PENALTY = 0.2f
        const val MIN_ACTIVE_PERSONALIZATION_ADJUSTMENT = 0.001f
        const val MAX_PROGRESSIVE_CANDIDATES = 255
        const val MAX_FALLBACK_PREFIX_LENGTH = 8
        const val MAX_FALLBACK_PREFIX_HAN_CHARACTERS = 4
        const val NO_CODE_POINT = -1
    }
}
