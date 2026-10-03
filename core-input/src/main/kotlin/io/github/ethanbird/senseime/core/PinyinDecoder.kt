package io.github.ethanbird.senseime.core

import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ln
import io.github.ethanbird.senseime.core.PinyinScoreDiagnostics.Feature

/**
 * Read-only decoder for Sense's compact pinyin lexicon.
 *
 * The binary remains in one byte array and an offset table. Candidate lookup is
 * a binary search, so the IME does not allocate tens of thousands of map entries.
 */
class PinyinDecoder private constructor(
    private val data: ByteArray,
    private val baseIndex: LexiconIndex,
    private val bigramModel: CharacterBigramModel,
    private val layeredIndex: LexiconIndex,
    private val spellingGraph: PinyinSpellingGraph,
    private val segmenter: PinyinSyllableSegmenter,
    private val correctionBudget: CorrectionSearchBudget,
    private val unigramLogMass: Float,
    private val unigramMeanScore: Float,
    private val userLexicon: UserLexicon? = null,
    private val languageBinding: PinyinLanguageBinding? = null,
) : TextContextualInputDecoder,
    ProgressivePrefixProbeDecoder,
    CanonicalChineseLexicalProbeDecoder,
    RankedCandidateDecoder {

    // One immutable byte payload, two compact lookup views. Tier 2 has only been
    // calibrated with the full-pinyin LM. Legacy/T9 must see the exact old graph,
    // including record absence and prefix scan budgets, not just filtered output.
    private class LexiconIndex(val offsets: IntArray, val syllables: Array<IntArray>)
    private val activeIndex = if (languageBinding == null) baseIndex else layeredIndex
    private val recordOffsets get() = activeIndex.offsets
    private val syllableRecordIndicesByInitial get() = activeIndex.syllables

    @Volatile
    private var cachedSpellingPaths: CachedSpellingPaths? = null
    private val prefixCandidateCache = PrefixCandidateCache()

    internal fun withUserLexicon(lexicon: UserLexicon): PinyinDecoder = PinyinDecoder(
        data, baseIndex, bigramModel, layeredIndex, spellingGraph, segmenter,
        correctionBudget, unigramLogMass, unigramMeanScore, lexicon, languageBinding,
    )

    fun withLanguageModel(model: CharacterLanguageModel, weight: Float = 1f, oovFeature: Float = 0f): PinyinDecoder {
        require(weight.isFinite() && weight in 0f..4f)
        require(oovFeature.isFinite() && oovFeature in -6f..0f)
        val binding = if (model === CharacterLanguageModel.EMPTY || weight == 0f) null
            else PinyinLanguageBinding(model, weight, oovFeature = oovFeature)
        return PinyinDecoder(data, baseIndex, bigramModel, layeredIndex, spellingGraph,
            segmenter, correctionBudget, unigramLogMass, unigramMeanScore, userLexicon, binding)
    }

    /** Host ablation seam; ordinary callers keep the development-selected LM calibration. */
    internal fun withCorrectionCalibration(boost: Float): PinyinDecoder {
        require(boost.isFinite() && boost in 0f..16f)
        return PinyinDecoder(data, baseIndex, bigramModel, layeredIndex, spellingGraph,
            segmenter, correctionBudget, unigramLogMass, unigramMeanScore, userLexicon,
            languageBinding?.copy(correctionCompositionBoost = boost))
    }

    private fun personalWords(query: String): UserPinyinLattice =
        UserPinyinLattice.from(query, userLexicon?.matchFullPinyin(query).orEmpty())

    override fun decode(composing: String, limit: Int): List<Candidate> =
        decodeInternal(composing, limit, NO_CODE_POINT, includeCorrections = true)

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return decodeInternal(composing, limit, previousCodePoint, includeCorrections = true)
    }

    override fun decodeWithContext(leftContext: CharSequence, composing: String, limit: Int, prefixProbe: Boolean): List<Candidate> {
        val previous = if (leftContext.isEmpty()) NO_CODE_POINT else Character.codePointBefore(leftContext, leftContext.length)
        val state = if (languageBinding == null) null else if (languageBinding.model is FourgramLanguageModel)
            PinyinLanguageScorer.context3(leftContext) else PinyinLanguageScorer.context(leftContext)
        return if (prefixProbe) cachedPrefixProbe(composing, limit, previous, state)
        else decodeInternal(composing, limit, previous, includeCorrections = true, languageContext = state)
    }

    override fun decodePrefixProbe(composing: String, limit: Int): List<Candidate> =
        cachedPrefixProbe(composing, limit, NO_CODE_POINT)

    override fun decodePrefixProbeAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return cachedPrefixProbe(composing, limit, previousCodePoint)
    }

    private fun cachedPrefixProbe(composing: String, limit: Int, previousCodePoint: Int, languageContext: Long? = null): List<Candidate> {
        DecodeWorkScope.checkpoint()
        if (limit <= 0) return emptyList()
        val words = personalWords(normalize(composing))
        if (!words.isEmpty || PinyinScoreDiagnostics.isObserving) {
            return decodeInternal(composing, limit, previousCodePoint, includeCorrections = false, userWords = words, languageContext = languageContext)
        }
        return prefixCandidateCache.getOrCompute(PrefixCandidateCache.Key(composing, limit, previousCodePoint, languageContext ?: 0L)) {
            decodeInternal(composing, limit, previousCodePoint, includeCorrections = false, userWords = UserPinyinLattice.EMPTY, languageContext = languageContext)
        }
    }

    override fun probeCanonicalChineseOnly(composing: String, limit: Int): List<Candidate> =
        decodeLexicalProbe(composing, limit, NO_CODE_POINT)

    override fun probeCanonicalChineseOnlyAfter(
        previousCodePoint: Int,
        composing: String,
        limit: Int,
    ): List<Candidate> {
        if (!Character.isValidCodePoint(previousCodePoint)) return emptyList()
        return decodeLexicalProbe(composing, limit, previousCodePoint)
    }

    /**
     * Lexical namespaces plus a length-bounded mixed-spelling expansion: no sentence DAG or
     * correction graph. T9 probes this cheaper path across numeric alternatives before fully
     * decoding winners.
     */
    private fun decodeLexicalProbe(composing: String, limit: Int, previousCodePoint: Int): List<Candidate> =
        if (PinyinScoreDiagnostics.isObserving) PinyinScoreDiagnostics.query {
            decodeLexicalProbeBody(composing, limit, previousCodePoint)
        } else decodeLexicalProbeBody(composing, limit, previousCodePoint)

    private fun decodeLexicalProbeBody(
        composing: String,
        limit: Int,
        previousCodePoint: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val parsedQuery = parseQuery(composing)
        val query = parsedQuery.code
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        val outputLimit = minOf(limit, MAX_DECODE_CANDIDATES)
        val exactRecord = if (parsedQuery.hasForcedJoints) -1 else findExact(query)
        val hasCanonicalExact = exactRecord >= 0
        val candidates = ArrayList<Candidate>(outputLimit * 3)
        if (exactRecord >= 0) {
            candidates += readContextualCandidates(
                exactRecord,
                outputLimit,
                CandidateMatchKind.BASE_EXACT,
                query,
                previousCodePoint,
            )
        }
        if (!parsedQuery.hasForcedJoints) {
            candidates += readHybridCandidates(query, outputLimit)
            if (query.length <= MAX_LEXICAL_PROBE_DYNAMIC_LENGTH) {
                candidates += readDynamicMixedCandidates(
                    paths = segmenter.segmentMixedPaths(query, MAX_DYNAMIC_MIXED_PATHS),
                    limit = outputLimit,
                ).candidates
            }
            candidates += readInitialsCandidates(query, outputLimit)
        }
        val contextual = if (previousCodePoint == NO_CODE_POINT) {
            candidates
        } else {
            candidates.map { candidate ->
                val context = contextScore(previousCodePoint, candidate)
                candidate.copy(score = candidate.score + context).also {
                    PinyinScoreDiagnostics.current?.add(candidate, it, Feature.EXTERNAL_BIGRAM, context)
                }
            }
        }
        return CandidateRanker.rank(
            candidates = contextual,
            limit = outputLimit,
            hasCanonicalExact = hasCanonicalExact,
            hasCanonicalComposition = false,
        ).also {
            PinyinScoreDiagnostics.record(composing, "lexical", hasCanonicalExact, false, contextual, it)
        }
    }

    /**
     * Collects every viable source before applying one calibrated ranker.
     *
     * Canonical exact, composed, hybrid, initials and corrected spellings are
     * alternatives in one score domain; an exact dictionary hit no longer
     * terminates typo or segmentation recall.
     */
    private fun decodeInternal(
        composing: String, limit: Int, previousCodePoint: Int, includeCorrections: Boolean,
        userWords: UserPinyinLattice? = null, languageContext: Long? = null,
    ): List<Candidate> = if (PinyinScoreDiagnostics.isObserving) PinyinScoreDiagnostics.query {
        decodeBody(composing, limit, previousCodePoint, includeCorrections, userWords, languageContext)
    } else decodeBody(composing, limit, previousCodePoint, includeCorrections, userWords, languageContext)

    private fun decodeBody(
        composing: String,
        limit: Int,
        previousCodePoint: Int,
        includeCorrections: Boolean,
        userWords: UserPinyinLattice? = null,
        languageContext: Long? = null,
    ): List<Candidate> {
        DecodeWorkScope.checkpoint()
        if (limit <= 0) return emptyList()
        val parsedQuery = parseQuery(composing)
        val query = parsedQuery.code
        if (query.isEmpty() || query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            return emptyList()
        }
        val outputLimit = minOf(limit, MAX_DECODE_CANDIDATES)
        val exactRecord = if (parsedQuery.hasForcedJoints) -1 else findExact(query)
        val hasCanonicalExact = exactRecord >= 0
        val words = userWords ?: personalWords(query)
        // A corpus with no personalized name/class model must not erase a freshly learned
        // word. Conservatively use the proven lexical path for queries containing a positive
        // personal-only multi-character word. Ordinary learned dictionary words keep the LM.
        val personalBackoff = languageBinding != null && words.anyPhrase { phrase ->
            phrase.rankingBoost > 0f && phrase.initials.length >= 2 && run {
                val record = findExact(phrase.fullPinyin)
                // A low-confidence coverage entry is not corpus evidence for a learned
                // word. Preserve personal-only LM backoff when installing supplemental data.
                record < 0 || readCandidates(record, MAX_DECODE_CANDIDATES, includeSupplemental = false).none { it.text == phrase.text }
            }
        }
        val language = languageBinding?.takeUnless { personalBackoff }?.let {
            PinyinLanguageScorer(it, query, languageContext ?: if (it.model is FourgramLanguageModel)
                PinyinLanguageScorer.context3(previousCodePoint) else PinyinLanguageScorer.context(previousCodePoint))
        }
        var hasCanonicalComposition = hasMultiSegmentComposition(
            query,
            parsedQuery.forcedJoints,
        )
        val composed = if (hasCanonicalComposition || !words.isEmpty || parsedQuery.hasForcedJoints) {
            composeCandidates(
                query,
                outputLimit,
                segmentCandidatesPerKey = segmentCandidateLimit(outputLimit),
                beamWidth = segmentBeamWidth(outputLimit),
                forcedJoints = parsedQuery.forcedJoints,
                previousCodePoint = previousCodePoint,
                userWords = words,
                language = language,
            )
        } else emptyList()
        // A full dictionary word surviving explicit syllable joints has the same typed
        // evidence as the separator-free exact entry, including across T9 query plans.
        val hasValidatedExact = hasCanonicalExact || composed.any { it.matchKind == CandidateMatchKind.BASE_EXACT }
        hasCanonicalComposition = hasCanonicalComposition || composed.isNotEmpty()
        // Short canonical spellings already have stronger evidence than an
        // inferred mixed path. Keep dynamic recall only for longer collisions
        // such as `gongjijin` -> `gong'ji'ji'n`.
        val shouldInferMixedCandidates =
            (!hasCanonicalExact && !hasCanonicalComposition) ||
                query.length >= MIN_DYNAMIC_MIXED_WITH_CANONICAL_LENGTH
        val requiresForcedMixedRecall =
            parsedQuery.hasForcedJoints &&
                hasAbbreviatedForcedRegion(query, parsedQuery.forcedJoints)
        val candidates = ArrayList<Candidate>(outputLimit * 3)
        var hasProducedStrongMixedCandidate = false

        if (exactRecord >= 0) {
            candidates += readContextualCandidates(
                exactRecord,
                outputLimit,
                CandidateMatchKind.BASE_EXACT,
                query,
                previousCodePoint,
                language,
            ).exportLexical(language)
        }
        candidates += composed
        if (!parsedQuery.hasForcedJoints || requiresForcedMixedRecall) {
            val mixedPaths = if (shouldInferMixedCandidates || requiresForcedMixedRecall) {
                segmenter.segmentMixedPaths(
                    if (parsedQuery.hasForcedJoints) composing else query,
                    MAX_DYNAMIC_MIXED_PATHS,
                )
            } else {
                emptyList()
            }
            val rawHybridCandidates = readHybridCandidates(query, outputLimit)
                .let { values ->
                    if (!parsedQuery.hasForcedJoints) {
                        values
                    } else {
                        values.filter { candidate ->
                            mixedPaths.any { path ->
                                segmenter.matchesCanonical(
                                    path,
                                    candidate.canonicalPinyin,
                                    candidate.canonicalInitials,
                                )
                            }
                        }
                    }
                }
            val hasStrongMixedPath = mixedPaths.any { it.hasStrongTypedEvidence }
            val hybridCandidates =
                if (rawHybridCandidates.isEmpty() || !hasStrongMixedPath) {
                    rawHybridCandidates
                } else {
                    rawHybridCandidates.map { candidate ->
                        val hasStrongPath = mixedPaths.any { path ->
                            path.hasStrongTypedEvidence &&
                                segmenter.matchesCanonical(
                                    path,
                                    candidate.canonicalPinyin,
                                    candidate.canonicalInitials,
                                )
                        }
                        if (hasStrongPath) {
                            hasProducedStrongMixedCandidate = true
                            candidate.copy(
                                score = candidate.score + STRONG_MIXED_TYPED_EVIDENCE_BONUS,
                            ).also {
                                PinyinScoreDiagnostics.current?.add(candidate, it, Feature.MIXED_TYPED, STRONG_MIXED_TYPED_EVIDENCE_BONUS)
                            }
                        } else {
                            candidate
                        }
                    }
                }
            candidates += hybridCandidates.exportLexical(language)
            if (
                (shouldInferMixedCandidates || requiresForcedMixedRecall) &&
                hybridCandidates.size < minOf(outputLimit, DYNAMIC_MIXED_FILL_FLOOR)
            ) {
                val dynamicMixed = readDynamicMixedCandidates(
                    paths = mixedPaths,
                    limit = outputLimit,
                )
                candidates += dynamicMixed.candidates.exportLexical(language)
                hasProducedStrongMixedCandidate =
                    hasProducedStrongMixedCandidate || dynamicMixed.hasStrongCandidate
            }
            val initialsCandidates = readInitialsCandidates(query, outputLimit)
            candidates += (if (!parsedQuery.hasForcedJoints) {
                initialsCandidates
            } else {
                initialsCandidates.filter { candidate ->
                    mixedPaths.any { path ->
                        segmenter.matchesCanonical(
                            path,
                            candidate.canonicalPinyin,
                            candidate.canonicalInitials,
                        )
                    }
                }
            }).exportLexical(language)
            // An exact dictionary key is evidence, not a veto on a literal continuation.
            // With the LM, unfinished multi-syllable words compete in the same score domain;
            // untyped suffixes retain the coverage penalty. Completed atomic syllables
            // (za, ya, ...) and legacy/T9 keep their existing literal recall.
            val allowExactContinuation = language != null && !segmenter.isCompleteSyllable(query)
            if (!parsedQuery.hasForcedJoints && (!hasCanonicalExact || allowExactContinuation)) {
                candidates += readStatisticalPrefixCandidates(query, outputLimit).exportLexical(language)
                candidates += readPrefixCandidates(query, outputLimit, completeFinalSyllable = language != null).exportLexical(language)
            }
        }
        // While the first syllable is still valid and unfinished (go -> gong/gou),
        // keep literal input rather than inventing an edit (go -> wo). Completed,
        // invalid and multi-syllable spellings retain their existing correction search.
        val unfinishedFirstSyllable = language != null && !hasValidatedExact && !hasCanonicalComposition &&
            candidates.any { it.matchKind == CandidateMatchKind.BASE_PREFIX } &&
            segmenter.syllablesStartingWith(query).any { it.length > query.length }
        if (includeCorrections && !parsedQuery.hasForcedJoints && !unfinishedFirstSyllable) {
            candidates += readSpellingGraphCorrections(
                rawInput = composing,
                normalizedQuery = query,
                limit = outputLimit,
                allowComposedCorrections = !hasCanonicalExact && !hasCanonicalComposition,
                includeComposedCorrections = !hasProducedStrongMixedCandidate,
                language = language,
                previousCodePoint = previousCodePoint,
            )
        }

        val contextual = scoreCollectedCandidates(query, candidates, language, previousCodePoint,
            hasValidatedExact, hasCanonicalComposition, includeCorrections)
        CandidateRankingDiagnostics.record(composing, outputLimit, hasValidatedExact, hasCanonicalComposition, contextual)
        return CandidateRanker.rank(
            candidates = contextual,
            limit = outputLimit,
            hasCanonicalExact = hasValidatedExact,
            hasCanonicalComposition = hasCanonicalComposition,
        ).also {
            PinyinScoreDiagnostics.record(composing, if (includeCorrections) "decode" else "prefix",
                hasValidatedExact, hasCanonicalComposition, contextual, it)
        }
    }

    private fun scoreCollectedCandidates(
        query: String, candidates: List<Candidate>, language: PinyinLanguageScorer?,
        previousCodePoint: Int, hasValidatedExact: Boolean, hasCanonicalComposition: Boolean,
        includeCorrections: Boolean,
    ): List<Candidate> {
        // Prefix records with the same output initials share their bounded alignment work.
        val completionCoverage = if (language == null) null else HashMap<String?, Int>()
        fun coveredCharacters(candidate: Candidate): Int = completionCoverage!!.getOrPut(candidate.canonicalInitials) {
            segmenter.coveredPrefixCharacters(query, candidate.canonicalInitials)
        }
        fun languageFeature(candidate: Candidate): Float = if (candidate.matchKind == CandidateMatchKind.BASE_PREFIX) {
            language!!.completionFeature(candidate.text, coveredCharacters(candidate))
        } else language!!.feature(candidate.text)
        fun fullyTouchedCompletion(candidate: Candidate): Boolean = candidate.matchKind == CandidateMatchKind.BASE_PREFIX &&
            coveredCharacters(candidate) >= candidate.text.codePointCount(0, candidate.text.length)

        // Calibrate against the actual strongest literal source, not merely the existence
        // of some valid composition. Predictions with untouched characters do not qualify.
        // Correction-free probes and pools without repairs need no calibration comparison.
        // Cache actual features only on this path: the final scoring pass then consumes them
        // rather than repeating LM completion scans and binary bigram lookups.
        val compareLiteralSources = language != null && includeCorrections &&
            language.sourceAdjustment(CandidateMatchKind.CORRECTED, hasValidatedExact, hasCanonicalComposition) > 0f &&
            candidates.any { it.matchKind == CandidateMatchKind.CORRECTED } &&
            candidates.any { it.matchKind == CandidateMatchKind.BASE_PREFIX }
        val cachedLanguage = if (compareLiteralSources) FloatArray(candidates.size) else null
        val cachedContext = if (compareLiteralSources && previousCodePoint != NO_CODE_POINT) FloatArray(candidates.size) else null
        val literalCompletionLeads = if (compareLiteralSources) {
            var bestCompletion = Float.NEGATIVE_INFINITY
            var bestOther = Float.NEGATIVE_INFINITY
            for ((index, candidate) in candidates.withIndex()) {
                val lm = languageFeature(candidate)
                cachedLanguage!![index] = lm
                val context = if (previousCodePoint == NO_CODE_POINT) 0f else contextScore(previousCodePoint, candidate)
                cachedContext?.set(index, context)
                if (candidate.matchKind == CandidateMatchKind.CORRECTED) continue
                val score = candidate.score + lm + context +
                    CandidateRanker.sourcePrior(candidate.matchKind, hasValidatedExact, hasCanonicalComposition)
                if (fullyTouchedCompletion(candidate)) bestCompletion = maxOf(bestCompletion, score)
                else bestOther = maxOf(bestOther, score)
            }
            bestCompletion > bestOther
        } else false
        val languageScored = if (language == null) candidates else candidates.mapIndexed { index, candidate ->
            val lm = cachedLanguage?.get(index) ?: languageFeature(candidate)
            val calibration = language.sourceAdjustment(candidate.matchKind, hasValidatedExact, hasCanonicalComposition, literalCompletionLeads)
            candidate.copy(score = candidate.score + lm + calibration).also {
                PinyinScoreDiagnostics.current?.let { trace ->
                    trace.put(it, trace.get(candidate).plus(Feature.CHARACTER_LM, lm)
                        .plus(Feature.CORRECTION_CALIBRATION, calibration))
                }
            }
        }
        return if (previousCodePoint == NO_CODE_POINT) {
            languageScored
        } else {
            languageScored.mapIndexed { index, candidate ->
                val context = cachedContext?.get(index) ?: contextScore(previousCodePoint, candidate)
                candidate.copy(score = candidate.score + context).also {
                    PinyinScoreDiagnostics.current?.add(candidate, it, Feature.EXTERNAL_BIGRAM, context)
                }
            }
        }
    }

    private fun findExact(query: String, view: LexiconIndex = activeIndex): Int {
        var low = 0
        var high = view.offsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = compareCode(middle, query, view)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun lowerBound(query: String, view: LexiconIndex = activeIndex): Int {
        var low = 0
        var high = view.offsets.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (compareCode(middle, query, view) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private data class CompositionPath(
        val text: String,
        val initials: String,
        val segments: Int,
        val score: Float,
        val lastCodePoint: Int,
        val lastSegmentWasSingleCodePoint: Boolean,
        val searchContextScore: Float = 0f,
        val singleWordMatchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
        val languageState: Long = 0L,
        val languageScore: Float = 0f,
        val evidence: PinyinScoreDiagnostics.Evidence? = null,
    ) {
        val searchScore = score + languageScore + searchContextScore
    }

    private data class CompositionIdentity(
        val text: String,
        val segments: Int,
        val lastSegmentWasSingleCodePoint: Boolean,
        val languageState: Long,
    )

    /** Unicode metadata belongs to the lexical edge, not every path reaching that edge. */
    private class CompositionOption(val candidate: Candidate) {
        val firstCodePoint = candidate.text.codePointAt(0)
        val lastCodePoint = candidate.text.codePointBefore(candidate.text.length)
        val isSingleCodePoint = candidate.text.codePointCount(0, candidate.text.length) == 1
    }

    private data class LanguageOption(val option: CompositionOption, val step: PinyinLanguageScorer.Step, val score: Float)

    private val languageOptionOrder = Comparator<LanguageOption> { a, b ->
        val score = java.lang.Float.compare(b.score, a.score)
        if (score != 0) score else a.option.candidate.text.compareTo(b.option.candidate.text)
    }

    /**
     * Word-lattice beam search with additive log-unigram priors and boundary evidence.
     * Averaging each path by its own word count is not a Viterbi objective: splitting a word
     * into unrelated frequent characters can raise its average and eliminate the correct path.
     * Normalize only when exporting scores, with ONE shared denominator for all completions.
     */
    private fun composeCandidates(
        query: String,
        limit: Int,
        segmentCandidatesPerKey: Int,
        beamWidth: Int,
        forcedJoints: BooleanArray? = null,
        spellingSyllableEnds: List<Int>? = null,
        previousCodePoint: Int = NO_CODE_POINT,
        userWords: UserPinyinLattice = UserPinyinLattice.EMPTY,
        language: PinyinLanguageScorer? = null,
    ): List<Candidate> {
        val paths = composePaths(
            query = query,
            limit = limit,
            segmentCandidatesPerKey = segmentCandidatesPerKey,
            beamWidth = beamWidth,
            forcedJoints = forcedJoints,
            spellingSyllableEnds = spellingSyllableEnds,
            previousCodePoint = previousCodePoint,
            userWords = userWords,
            language = language,
        )
        val scoreSegments = paths.firstOrNull()?.segments ?: return emptyList()
        return paths.map { it.toCandidate(query, scoreSegments, language) }
    }

    private fun CompositionPath.toCandidate(query: String, scoreSegments: Int, language: PinyinLanguageScorer? = null): Candidate = Candidate(
        text = text,
        score = unigramLogMass + score / (language?.normalizer ?: scoreSegments.toFloat()),
        canonicalPinyin = query,
        matchKind = if (segments == 1) singleWordMatchKind else CandidateMatchKind.BASE_COMPOSED,
        canonicalInitials = initials.ifEmpty { null },
    ).also { candidate ->
        evidence?.let {
            PinyinScoreDiagnostics.current?.put(candidate,
                it.divide(language?.normalizer ?: scoreSegments.toFloat())
                    .plus(Feature.NORMALIZATION_ANCHOR, unigramLogMass))
        }
    }

    private fun composePaths(
        query: String,
        limit: Int,
        segmentCandidatesPerKey: Int,
        beamWidth: Int,
        forcedJoints: BooleanArray? = null,
        spellingSyllableEnds: List<Int>? = null,
        previousCodePoint: Int = NO_CODE_POINT,
        userWords: UserPinyinLattice = UserPinyinLattice.EMPTY,
        language: PinyinLanguageScorer? = null,
        lexicalCache: QueryLexicalCache? = null,
        selectionCache: QuerySelectionCache<LanguageOption>? = null,
    ): List<CompositionPath> {
        val beams = arrayOfNulls<MutableList<CompositionPath>>(query.length + 1)
        val syllableIndexByOffset = spellingSyllableEnds?.let { ends ->
            IntArray(query.length + 1) { -1 }.also { indices ->
                indices[0] = 0
                ends.forEachIndexed { index, end ->
                    if (end in 1..query.length) indices[end] = index + 1
                }
            }
        }
        val scoreTrace = PinyinScoreDiagnostics.current
        beams[0] = mutableListOf(CompositionPath("", "", 0, 0f, NO_CODE_POINT, false,
            languageState = language?.initialState ?: 0L,
            evidence = if (scoreTrace == null) null else PinyinScoreDiagnostics.Evidence.EMPTY))
        query.indices.forEach { start ->
            DecodeWorkScope.checkpoint()
            val paths = beams[start]?.also { pruneBeam(it, beamWidth) } ?: return@forEach
            val startSyllableIndex = syllableIndexByOffset?.get(start) ?: 0
            if (syllableIndexByOffset != null && startSyllableIndex < 0) return@forEach
            val maxEnd = minOf(query.length, maxOf(start + MAX_SEGMENT_CODE_LENGTH, userWords.maximumEnd(start)))
            for (end in (start + 1)..maxEnd) {
                DecodeWorkScope.checkpoint()
                val baseEdgeAllowed = end - start <= MAX_SEGMENT_CODE_LENGTH &&
                    isCompositionEdgeAllowed(query, start, end)
                val crossesJoint = crossesForcedJoint(forcedJoints, start, end)
                val learned = userWords.at(start, end).filter { phrase ->
                    !crossesJoint || segmenter.matchesFullSpelling(query, start, end, phrase.initials, forcedJoints)
                }
                if (!baseEdgeAllowed && learned.isEmpty()) continue
                val edgeSyllableCount = syllableIndexByOffset?.let { indices ->
                    val endSyllableIndex = indices[end]
                    if (endSyllableIndex <= startSyllableIndex) return@let -1
                    endSyllableIndex - startSyllableIndex
                }
                if (edgeSyllableCount != null && edgeSyllableCount < 1) continue
                val record = if (baseEdgeAllowed) findExact(query, start, end) else -1
                if (record < 0 && learned.isEmpty()) continue
                val context = if (start == 0) previousCodePoint else NO_CODE_POINT
                val options = if (language == null && learned.isEmpty() && !crossesJoint && edgeSyllableCount == null) {
                    readContextualCandidates(record, segmentCandidatesPerKey, previousCodePoint = context)
                } else if (lexicalCache != null && language != null && learned.isEmpty() && !crossesJoint) {
                    lexicalCache.get(record, edgeSyllableCount, context)
                } else {
                    // Validate BEFORE truncation: an incompatible homophone may have higher
                    // weight than the matching multi-syllable dictionary word.
                    val lexical = if (record < 0) emptyList() else readCandidates(record, MAX_DECODE_CANDIDATES)
                    val validLexical = lexical.filter { option ->
                        (edgeSyllableCount == null || option.canonicalInitials?.length == edgeSyllableCount) &&
                            (!crossesJoint || segmenter.matchesFullSpelling(query, start, end, option.canonicalInitials, forcedJoints))
                    }
                    mergePersonalWordOptions(validLexical, learned.filter {
                        edgeSyllableCount == null || it.initials.length == edgeSyllableCount
                    }, if (language == null) segmentCandidatesPerKey else MAX_DECODE_CANDIDATES, context)
                }
                val edgeOptions = options.map(::CompositionOption)
                val target = beams[end] ?: mutableListOf<CompositionPath>().also { beams[end] = it }
                val languageSelections = if (language == null) null else HashMap<Long, List<LanguageOption>>()
                paths.forEach { path ->
                    DecodeWorkScope.checkpoint()
                    // Context-dependent homophones must survive the edge cap, not just the
                    // completed N-best list. Cache each (two-character state, word) once.
                    // Paths sharing the same LM state share one edge selection. Evaluate
                    // once before sorting; comparator calls must not query the model.
                    val selected = if (language == null) null else languageSelections!!.getOrPut(path.languageState) {
                        fun select(): List<LanguageOption> = stableTopK(edgeOptions.map { edge ->
                                val option = edge.candidate
                                val step = language.extend(path.languageState, option.text)
                                LanguageOption(edge, step, option.score + step.score +
                                    if (start == 0 && previousCodePoint != NO_CODE_POINT) contextScore(previousCodePoint, option) * language.normalizer else 0f)
                            }, segmentCandidatesPerKey, languageOptionOrder)
                        if (selectionCache != null && lexicalCache != null && learned.isEmpty() && !crossesJoint) {
                            selectionCache.getOrCompute(options, path.languageState, segmentCandidatesPerKey, context, ::select)
                        } else select()
                    }
                    fun append(edge: CompositionOption, step: PinyinLanguageScorer.Step?) {
                        val option = edge.candidate
                        val firstCodePoint = edge.firstCodePoint
                        val optionIsSingleCodePoint = edge.isSingleCodePoint
                        val boundaryScore = when {
                            path.lastCodePoint == NO_CODE_POINT -> 0f
                            !path.lastSegmentWasSingleCodePoint && !optionIsSingleCodePoint ->
                                bigramModel.score(path.lastCodePoint, firstCodePoint) * COMPOUND_BOUNDARY_SCALE

                            else -> bigramModel.score(path.lastCodePoint, firstCodePoint)
                        }
                        addToBeam(
                            target,
                            CompositionPath(
                                text = path.text + option.text,
                                initials = path.initials + option.canonicalInitials.orEmpty(),
                                segments = path.segments + 1,
                                score = path.score + option.score - unigramLogMass + boundaryScore -
                                    if (path.segments > 0) WORD_BOUNDARY_COST else 0f,
                                lastCodePoint = edge.lastCodePoint,
                                lastSegmentWasSingleCodePoint = optionIsSingleCodePoint,
                                singleWordMatchKind = option.matchKind,
                                languageState = step?.state ?: 0L,
                                languageScore = path.languageScore + (step?.score ?: 0f),
                                // Search LM/context affect pruning, not this lexical score.
                                // Their final candidate contributions are recorded exactly once later.
                                evidence = path.evidence?.let {
                                    it.append(checkNotNull(scoreTrace).get(option))
                                        .plus(Feature.UNIGRAM_MASS, -unigramLogMass)
                                        .plus(Feature.INTERNAL_BIGRAM, boundaryScore)
                                        .plus(Feature.WORD_BOUNDARY, if (path.segments > 0) -WORD_BOUNDARY_COST else 0f)
                                },
                                searchContextScore = if (start == 0 && previousCodePoint != NO_CODE_POINT) {
                                    contextScore(previousCodePoint, option) * (language?.normalizer ?: 1f)
                                } else {
                                    path.searchContextScore
                                },
                            ),
                            beamWidth,
                        )
                    }
                    if (selected == null) edgeOptions.forEach { append(it, null) }
                    else selected.forEach { append(it.option, it.step) }
                }
            }
        }

        val completed = beams[query.length] ?: mutableListOf()
        pruneBeam(completed, beamWidth)
        return completed
            .filter { it.segments > 1 || crossesForcedJoint(forcedJoints, 0, query.length) }
            .sortedWith(compositionComparator)
            .distinctBy { it.text }
            .take(limit)
    }

    private fun addToBeam(
        beam: MutableList<CompositionPath>,
        value: CompositionPath,
        beamWidth: Int,
    ) {
        beam += value
        if (beam.size >= beamWidth * BEAM_PRUNE_MULTIPLIER) pruneBeam(beam, beamWidth)
    }

    /** Batch pruning avoids sorting a several-hundred-entry beam for every path expansion. */
    private fun pruneBeam(beam: MutableList<CompositionPath>, beamWidth: Int) {
        if (beam.isEmpty()) return
        val best = HashMap<CompositionIdentity, CompositionPath>(beam.size)
        beam.forEach { path ->
            val key = CompositionIdentity(path.text, path.segments, path.lastSegmentWasSingleCodePoint, path.languageState)
            val previous = best[key]
            if (previous == null || path.score > previous.score) best[key] = path
        }
        if (best.size == beam.size && beam.size <= beamWidth) return
        val retained = best.values.sortedWith(compositionComparator).take(beamWidth)
        beam.clear()
        beam.addAll(retained)
    }

    // Primitive comparisons avoid boxing a Float/Int for every beam-sort comparison on ART.
    private val compositionComparator = Comparator<CompositionPath> { a, b ->
        val score = java.lang.Float.compare(b.searchScore, a.searchScore)
        if (score != 0) score else {
            val segments = a.segments.compareTo(b.segments)
            if (segments != 0) segments else a.text.compareTo(b.text)
        }
    }

    private fun contextScore(previousCodePoint: Int, candidate: Candidate): Float =
        bigramModel.score(previousCodePoint, candidate.text.codePointAt(0))
            .coerceIn(-CONTEXT_SCORE_CAP, CONTEXT_SCORE_CAP)

    private fun List<Candidate>.exportLexical(language: PinyinLanguageScorer?): List<Candidate> =
        if (language == null) this else map { candidate ->
            candidate.copy(score = language.lexical(candidate.score, unigramLogMass)).also {
                PinyinScoreDiagnostics.current?.normalize(candidate, it, unigramLogMass, language.normalizer)
            }
        }

    /** Apply context before a small output/edge limit hides the preferred homophone. */
    private fun readContextualCandidates(
        index: Int,
        limit: Int,
        matchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
        canonicalPinyin: String? = null,
        previousCodePoint: Int = NO_CODE_POINT,
        language: PinyinLanguageScorer? = null,
    ): List<Candidate> {
        if (previousCodePoint == NO_CODE_POINT && language == null) return readCandidates(index, limit, matchKind, canonicalPinyin)
        val candidates = readCandidates(index, MAX_DECODE_CANDIDATES, matchKind, canonicalPinyin)
        if (candidates.size <= limit) return candidates
        return candidates
            .sortedWith(compareByDescending<Candidate> {
                (language?.lexical(it.score, unigramLogMass) ?: it.score) +
                    (if (previousCodePoint == NO_CODE_POINT) 0f else contextScore(previousCodePoint, it)) + (language?.feature(it.text) ?: 0f)
            }.thenBy { it.text })
            .take(limit)
    }

    private fun mergePersonalWordOptions(
        lexical: List<Candidate>,
        learned: List<LearnedPhrase>,
        limit: Int,
        previousCodePoint: Int,
    ): List<Candidate> {
        val options = lexical.associateByTo(LinkedHashMap()) { it.text }
        val floor = lexical.maxOfOrNull { it.score }?.minus(USER_WORD_BASE_GAP) ?: unigramMeanScore
        for (phrase in learned) {
            val original = options[phrase.text]
            val adjustment = phrase.rankingBoost.coerceIn(-MAX_USER_WORD_ADJUSTMENT, MAX_USER_WORD_ADJUSTMENT)
            if (adjustment < 0f) {
                if (original != null) options[phrase.text] = original.copy(score = original.score + adjustment).also {
                    PinyinScoreDiagnostics.current?.add(original, it, Feature.PERSONAL_ADJUSTMENT, adjustment)
                }
            } else if (adjustment > 0f) {
                options[phrase.text] = Candidate(
                    text = phrase.text,
                    score = maxOf(original?.score ?: Float.NEGATIVE_INFINITY, floor) + adjustment,
                    canonicalPinyin = phrase.fullPinyin,
                    canonicalInitials = phrase.initials,
                    matchKind = CandidateMatchKind.USER_FULL,
                ).also { PinyinScoreDiagnostics.current?.personal(original, it, floor, adjustment) }
            }
        }
        return options.values.sortedWith(compareByDescending<Candidate> {
            it.score + if (previousCodePoint == NO_CODE_POINT) 0f else contextScore(previousCodePoint, it)
        }.thenBy { it.text }).take(limit)
    }

    /**
     * Small UI/benchmark requests keep a compact search budget. The IME's
     * 255-candidate production decode receives the wider budget needed for
     * deep alternatives such as `hua -> 滑` inside a composed phrase.
     */
    private fun segmentCandidateLimit(limit: Int): Int =
        if (limit >= WIDE_COMPOSITION_LIMIT) MAX_SEGMENT_CANDIDATES_PER_KEY else MIN_SEGMENT_CANDIDATES_PER_KEY

    private fun segmentBeamWidth(limit: Int): Int =
        if (limit >= WIDE_COMPOSITION_LIMIT) {
            MAX_SEGMENT_BEAM_WIDTH
        } else {
            maxOf(MIN_SEGMENT_BEAM_WIDTH, limit)
        }

    private fun mergeCandidates(candidates: List<Candidate>, limit: Int): List<Candidate> =
        CandidateRanker.rank(candidates, limit, hasCanonicalExact = false)

    private fun findExact(query: String, start: Int, end: Int): Int {
        var low = 0
        var high = recordOffsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = compareCode(middle, query, start, end)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    private fun readStatisticalPrefixCandidates(query: String, limit: Int): List<Candidate> {
        val record = findExact(PREFIX_NAMESPACE + query)
        if (record < 0) return emptyList()
        return readCandidates(record, limit, CandidateMatchKind.BASE_PREFIX)
            .map { candidate -> candidate.copy(canonicalPinyin = null, matchKind = CandidateMatchKind.BASE_PREFIX)
                .also { PinyinScoreDiagnostics.current?.inherit(candidate, it) } }
    }

    private fun readInitialsCandidates(query: String, limit: Int): List<Candidate> {
        if (query.length < MIN_INITIALS_LENGTH) return emptyList()
        val record = findExact(INITIALS_NAMESPACE + query)
        if (record < 0) return emptyList()
        return readCandidates(record, limit, CandidateMatchKind.BASE_INITIALS)
            .map { candidate ->
                val bonus = initialsLengthBonus(candidate.text, query)
                candidate.copy(
                    score = candidate.score + bonus,
                    canonicalPinyin = null,
                    canonicalInitials = query,
                    matchKind = CandidateMatchKind.BASE_INITIALS,
                ).also { PinyinScoreDiagnostics.current?.add(candidate, it, Feature.INITIALS_LENGTH, bonus) }
            }
    }

    private fun initialsLengthBonus(text: String, query: String): Float {
        val textLength = text.codePointCount(0, text.length)
        if (textLength != query.length) return 0f
        return if (textLength == 4) {
            FOUR_CHARACTER_INITIALS_BONUS
        } else {
            EXACT_INITIALS_LENGTH_BONUS
        }
    }

    private fun readHybridCandidates(query: String, limit: Int): List<Candidate> {
        if (query.length < MIN_HYBRID_LENGTH) return emptyList()
        val prefix = HYBRID_NAMESPACE + query + HYBRID_SEPARATOR
        val values = LinkedHashMap<String, Candidate>(minOf(limit * 2, 64))
        val retainedCapacity = maxOf(limit * HYBRID_RETAINED_LIMIT_MULTIPLIER, 32)
        val pruneThreshold = maxOf(limit * HYBRID_PRUNE_LIMIT_MULTIPLIER, 64)
        fun add(candidate: Candidate) {
            val previous = values[candidate.text]
            if (previous == null || candidate.score > previous.score) {
                values[candidate.text] = candidate
            }
            if (values.size > pruneThreshold) {
                val retained = CandidateRanker.rank(
                    values.values,
                    retainedCapacity,
                    hasCanonicalExact = false,
                )
                values.clear()
                retained.forEach { values[it.text] = it }
            }
        }
        var index = lowerBound(prefix)
        var scanned = 0
        while (
            index < recordOffsets.size &&
            scanned < HYBRID_SCAN_LIMIT &&
            codeStartsWith(index, prefix)
        ) {
            val code = readCode(index)
            val canonical = code.substring(prefix.length)
            readCandidates(
                index,
                minOf(limit, HYBRID_CANDIDATES_PER_RECORD),
                CandidateMatchKind.BASE_HYBRID,
                canonical,
            ).forEach(::add)
            index += 1
            scanned += 1
        }
        return mergeCandidates(values.values.toList(), limit)
    }

    /**
     * Recovers mixed full-pinyin/initial spellings omitted from the compact
     * prebuilt hybrid namespace by low-frequency asset thresholds.
     *
     * Each abbreviated edge expands only through the syllable inventory. A
     * binary-search prefix probe discards branches absent from the canonical
     * lexicon immediately, while path, beam, probe and record budgets keep the
     * fallback independent of total lexicon size.
     */
    private data class DynamicMixedResult(
        val candidates: List<Candidate>,
        val hasStrongCandidate: Boolean,
        val budgetLimited: Boolean = false,
    )

    private fun readDynamicMixedCandidates(
        paths: List<MixedPinyinPath>,
        limit: Int,
    ): DynamicMixedResult {
        val expanded = readDynamicMixedCandidates(paths, limit, activeIndex, languageBinding != null)
        if (!expanded.budgetLimited || activeIndex === baseIndex) return expanded
        // Coverage-only records must not spend the base dictionary's entire
        // abbreviation budget. Only a truncated expanded search gets one extra,
        // independently bounded base pass; both share the immutable byte payload.
        DecodeWorkScope.checkpoint()
        val protectedBase = readDynamicMixedCandidates(paths, limit, baseIndex, false)
        return DynamicMixedResult(
            mergeCandidates(protectedBase.candidates + expanded.candidates, limit),
            protectedBase.hasStrongCandidate || expanded.hasStrongCandidate,
            budgetLimited = true,
        )
    }

    private fun readDynamicMixedCandidates(
        paths: List<MixedPinyinPath>,
        limit: Int,
        view: LexiconIndex,
        includeSupplemental: Boolean,
    ): DynamicMixedResult {
        val viablePaths = paths.filter { path ->
            path.rawCode.length >= MIN_HYBRID_LENGTH &&
                path.fullSyllables > 0 &&
                path.abbreviatedSyllables in 1..MAX_DYNAMIC_MIXED_ABBREVIATIONS &&
                path.segments.size <= MAX_DYNAMIC_MIXED_SYLLABLES
        }
        if (viablePaths.isEmpty()) return DynamicMixedResult(emptyList(), false)

        val values = LinkedHashMap<String, Candidate>(minOf(limit * 2, 64))
        val canonicalPrefixPresence = HashMap<String, Boolean>(MAX_DYNAMIC_MIXED_PREFIX_PROBES_PER_PATH)
        var hasStrongCandidate = false
        var searchBudgetLimited = false
        for (path in viablePaths) {
            DecodeWorkScope.checkpoint()
            var remainingPrefixProbes = MAX_DYNAMIC_MIXED_PREFIX_PROBES_PER_PATH
            var inspectedExactRecords = 0
            var prefixes: List<String> = listOf("")
            var pathIsViable = true
            for ((segmentIndex, segment) in path.segments.withIndex()) {
                DecodeWorkScope.checkpoint()
                val expansions = if (segment.abbreviated) {
                    segmenter.syllablesStartingWith(segment.code)
                } else {
                    listOf(segment.code)
                }
                if (expansions.isEmpty()) {
                    pathIsViable = false
                    break
                }
                val next = LinkedHashSet<String>(minOf(MAX_DYNAMIC_MIXED_BEAM, prefixes.size * expansions.size))
                var budgetExhausted = false
                prefixLoop@ for (prefix in prefixes) {
                    for (syllable in expansions) {
                        if (remainingPrefixProbes <= 0) {
                            budgetExhausted = true
                            searchBudgetLimited = true
                            break@prefixLoop
                        }
                        remainingPrefixProbes -= 1
                        val canonical = prefix + syllable
                        val hasPrefix = canonicalPrefixPresence.getOrPut(canonical) {
                            hasCanonicalRecordPrefix(canonical, view)
                        }
                        if (hasPrefix) {
                            next += canonical
                            if (next.size >= MAX_DYNAMIC_MIXED_BEAM) {
                                searchBudgetLimited = true
                                break@prefixLoop
                            }
                        }
                    }
                }
                if (next.isEmpty()) {
                    pathIsViable = false
                    break
                }
                prefixes = next.toList()
                if (budgetExhausted && segmentIndex < path.segments.lastIndex) {
                    pathIsViable = false
                    break
                }
            }
            if (!pathIsViable) continue

            val expectedInitials = path.initials
            for (canonical in prefixes) {
                if (inspectedExactRecords >= MAX_DYNAMIC_MIXED_EXACT_RECORDS_PER_PATH) {
                    searchBudgetLimited = true
                    break
                }
                val record = findExact(canonical, view)
                if (record < 0) continue
                inspectedExactRecords += 1
                readCandidates(
                    record,
                    minOf(limit, DYNAMIC_MIXED_CANDIDATES_PER_RECORD),
                    CandidateMatchKind.BASE_HYBRID,
                    canonical,
                    includeSupplemental = includeSupplemental,
                    view = view,
                ).asSequence()
                    .filter { it.canonicalInitials == expectedInitials }
                    .map { candidate ->
                        candidate.copy(
                            score = candidate.score +
                                if (path.hasStrongTypedEvidence) {
                                    STRONG_MIXED_TYPED_EVIDENCE_BONUS
                                } else {
                                    0f
                                },
                        ).also {
                            PinyinScoreDiagnostics.current?.add(candidate, it, Feature.MIXED_TYPED,
                                if (path.hasStrongTypedEvidence) STRONG_MIXED_TYPED_EVIDENCE_BONUS else 0f)
                        }
                    }
                    .forEach { candidate ->
                        if (path.hasStrongTypedEvidence) hasStrongCandidate = true
                        val previous = values[candidate.text]
                        if (previous == null || candidate.score > previous.score) {
                            values[candidate.text] = candidate
                        }
                    }
            }
        }
        return DynamicMixedResult(
            candidates = mergeCandidates(values.values.toList(), limit),
            hasStrongCandidate = hasStrongCandidate,
            budgetLimited = searchBudgetLimited,
        )
    }

    private fun hasCanonicalRecordPrefix(prefix: String, view: LexiconIndex): Boolean {
        val index = lowerBound(prefix, view)
        return index < view.offsets.size && codeStartsWith(index, prefix, view)
    }

    private fun readPrefixCandidates(query: String, limit: Int, completeFinalSyllable: Boolean): List<Candidate> {
        val values = HashMap<String, Candidate>()
        fun readRecord(index: Int) {
            val codeLength = unsigned(data[recordOffsets[index]])
            val canonicalPinyin = readCode(index)
            val completionPenalty = (codeLength - query.length).coerceAtLeast(0) * 0.08f
            readCandidates(index, PREFIX_CANDIDATES_PER_KEY).forEach { candidate ->
                val score = candidate.score - completionPenalty
                if (score > (values[candidate.text]?.score ?: Float.NEGATIVE_INFINITY)) {
                    values[candidate.text] = candidate.copy(
                        score = score,
                        canonicalPinyin = canonicalPinyin,
                        matchKind = CandidateMatchKind.BASE_PREFIX,
                    ).also { PinyinScoreDiagnostics.current?.add(candidate, it, Feature.COMPLETION, -completionPenalty) }
                }
            }
        }

        syllableRecordIndicesByInitial[query.first() - 'a'].forEach { index ->
            if (codeStartsWith(index, query)) readRecord(index)
        }
        var index = lowerBound(query)
        var scanned = 0
        while (index < recordOffsets.size && scanned < PREFIX_SCAN_LIMIT && codeStartsWith(index, query)) {
            readRecord(index)
            index += 1
            scanned += 1
        }
        if (completeFinalSyllable && scanned == PREFIX_SCAN_LIMIT &&
            index < recordOffsets.size && codeStartsWith(index, query)) {
            // Lexicographic truncation can hide a frequent word behind many longer
            // phrases. Probe only exact last-syllable completions beyond that window;
            // keep the scan/beam caps, ordinary prefix scores and canonical identity.
            for (code in segmenter.finalSyllableCompletions(query)) {
                DecodeWorkScope.checkpoint()
                val record = findExact(code)
                if (record >= index) readRecord(record)
            }
        }
        return values.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.text.length })
            .take(limit)
    }

    /**
     * Resolves weighted spelling-graph routes through the same word lattice as
     * canonical input. This keeps a legal exact spelling and likely typo
     * alternatives together instead of making correction an early-return
     * fallback.
     */
    private fun readSpellingGraphCorrections(
        rawInput: String,
        normalizedQuery: String,
        limit: Int,
        allowComposedCorrections: Boolean,
        includeComposedCorrections: Boolean = true,
        language: PinyinLanguageScorer? = null,
        previousCodePoint: Int = NO_CODE_POINT,
    ): List<Candidate> {
        val values = ArrayList<Candidate>()
        val composedProbes = ArrayList<CorrectionCompositionProbe>()
        val spellingPathLimit =
            correctionBudget.spellingPathLimit(allowComposedCorrections, limit)
        val paths = spellingPaths(rawInput, spellingPathLimit)
            .asSequence()
            .filter { it.cost > 0f && it.canonical != normalizedQuery }
            .toList()
        // Share only prepared base edges whose complete validation/ordering key is known.
        // Personalized or forced-joint edges bypass this cache; LM selections always run
        // with the reaching path's state and budget, including the width-one scout.
        val lexicalCache = if (language != null && includeComposedCorrections) {
            QueryLexicalCache({ record, syllables, context ->
                val lexical = readCandidates(record, MAX_DECODE_CANDIDATES).filter {
                    syllables == null || it.canonicalInitials?.length == syllables
                }
                mergePersonalWordOptions(lexical, emptyList(), MAX_DECODE_CANDIDATES, context)
            })
        } else null
        // Same immutable lexical list + reaching state has the same selection across
        // correction spellings. Scout/full widths remain distinct; budgets are unchanged.
        val selectionCache = if (lexicalCache == null) null else QuerySelectionCache<LanguageOption>()
        paths.forEach { path ->
            DecodeWorkScope.checkpoint()
            val exact = findExact(path.canonical)
            if (exact >= 0) {
                readContextualCandidates(
                    exact,
                    minOf(limit, correctionBudget.exactCandidatesPerPath),
                    CandidateMatchKind.CORRECTED,
                    path.canonical,
                    if (language == null) NO_CODE_POINT else previousCodePoint,
                    language,
                ).exportLexical(language).asSequence()
                    .filter { candidate ->
                        candidate.canonicalInitials?.length == path.syllableCount
                    }
                    .forEach { candidate -> values += candidate.withCorrectionPenalty(path) }
            }
            if (includeComposedCorrections) {
                // A lexical-only mean can discard a plausible missing-key sentence before
                // the LM ever sees it. Use a width-one contextual scout for scheduling, then
                // spend the unchanged full-beam budget on the selected spellings. This is
                // approximate lookahead, NOT an admissible bound or a published candidate.
                // Legacy / disabled-LM callers retain their existing scheduling exactly.
                val estimate = if (language == null) lexicalCompositionEstimate(path.canonical) else {
                    composePaths(
                        path.canonical, limit = 1, segmentCandidatesPerKey = 1, beamWidth = 1,
                        spellingSyllableEnds = path.syllableEnds,
                        userWords = personalWords(path.canonical), language = language,
                        previousCodePoint = previousCodePoint,
                        lexicalCache = lexicalCache,
                        selectionCache = selectionCache,
                    ).firstOrNull()?.let { unigramLogMass + it.searchScore / language.normalizer }
                }
                estimate?.let {
                    composedProbes += CorrectionCompositionProbe(
                        path = path,
                        lexicalEstimate = estimate,
                        hasExactRecord = exact >= 0,
                    )
                }
            }
        }

        val composedBudget =
            correctionBudget.composedPathLimit(allowComposedCorrections, limit)
        val correctionProbeOrder =
            compareByDescending<CorrectionCompositionProbe> { it.hasExactRecord }
                .thenByDescending {
                    it.lexicalEstimate - it.path.cost * CORRECTION_PENALTY
                }
                .thenBy { it.path.cost }
                .thenBy { it.path.canonical }
        val orderedComposedProbes = composedProbes
            .sortedWith(correctionProbeOrder)
            .distinctBy { it.path.canonical }
        val selectedComposedProbes = LinkedHashSet<CorrectionCompositionProbe>(composedBudget)
        // Quotas add diversity around the best contextual scout, never instead of it.
        // This invariant also applies to the compact one-path budget.
        orderedComposedProbes.firstOrNull()?.let(selectedComposedProbes::add)
        orderedComposedProbes.asSequence()
            .filter { isSingleCharacterTailExtension(normalizedQuery, it.path.canonical) }
            .take(minOf(TAIL_EXTENSION_PROBE_LIMIT, composedBudget))
            .forEach { if (selectedComposedProbes.size < composedBudget) selectedComposedProbes.add(it) }
        val latestEditOffset = composedProbes.maxOfOrNull { it.path.firstEditOffset }
        if (latestEditOffset != null && selectedComposedProbes.size < composedBudget) {
            orderedComposedProbes.asSequence()
                .filter { it.path.firstEditOffset >= latestEditOffset - RECENT_EDIT_OFFSET_WINDOW }
                .firstOrNull()
                ?.let(selectedComposedProbes::add)
        }
        // With no canonical word path, a single missing/extra key needs a chance beside
        // cheaper fuzzy/neighbor routes. Reserve at most half the EXISTING full-beam slots;
        // a graph-discovered repair is otherwise often lost in this second pruning stage.
        if (allowComposedCorrections && selectedComposedProbes.size < composedBudget) {
            orderedComposedProbes.asSequence().filter { it.path.singleInsertionDeletion }
                .take(maxOf(1, composedBudget / 2))
                .forEach { if (selectedComposedProbes.size < composedBudget) selectedComposedProbes.add(it) }
        }
        orderedComposedProbes.forEach { probe ->
            if (selectedComposedProbes.size < composedBudget) selectedComposedProbes += probe
        }
        CorrectionSearchDiagnostics.record(rawInput, paths,
            { orderedComposedProbes.map { it.path } }, { selectedComposedProbes.map { it.path } })
        val correctedSentences = ArrayList<CorrectedSentencePath>()
        selectedComposedProbes
            .forEach { probe ->
                DecodeWorkScope.checkpoint()
                composePaths(
                    probe.path.canonical,
                    minOf(limit, correctionBudget.composedCandidatesPerPath),
                    segmentCandidatesPerKey = correctionBudget.segmentCandidatesPerKey,
                    beamWidth = correctionBudget.segmentBeamWidth,
                    spellingSyllableEnds = probe.path.syllableEnds,
                    userWords = personalWords(probe.path.canonical),
                    language = language,
                    previousCodePoint = if (language == null) NO_CODE_POINT else previousCodePoint,
                    lexicalCache = lexicalCache,
                    selectionCache = selectionCache,
                ).asSequence()
                    .filter { sentence ->
                        sentence.initials.length == probe.path.syllableCount
                    }
                    .forEach { sentence ->
                        correctedSentences += CorrectedSentencePath(probe.path, sentence)
                    }
            }
        // All spelling alternatives compete for ONE raw query. Independent per-spelling
        // calibration rewards extra frequent syllables (e.g. `...era` over transposed `...ren`).
        // Retain additive path evidence until one common score domain can be selected.
        val correctionScoreSegments = correctedSentences.maxByOrNull {
            it.sentence.score - it.spelling.cost * CORRECTION_PENALTY
        }?.sentence?.segments
        if (correctionScoreSegments != null) {
            correctedSentences.forEach { (spelling, sentence) ->
                values += sentence.toCandidate(spelling.canonical, correctionScoreSegments, language)
                    .withCorrectionPenalty(spelling)
            }
        }
        // LM is added once for every source at the final merge. Early truncation here would
        // drop language-preferred corrected spellings using lexical-only scores.
        return if (language != null) values else CandidateRanker.rank(values, limit, hasCanonicalExact = false)
    }

    private data class CorrectedSentencePath(
        val spelling: PinyinSpellingPath,
        val sentence: CompositionPath,
    )

    /**
     * Reuses the immutable spelling lattice for repeated renders of the same
     * composing revision. Candidate reads and context scores still run on every
     * decode; only the query-invariant graph expansion is retained.
     *
     * A single volatile entry matches the IME's hot path without an LRU lock.
     * Concurrent misses may duplicate work, but publication and reads remain
     * race-safe and the next render observes a complete value.
     */
    private fun spellingPaths(rawInput: String, maxPaths: Int): List<PinyinSpellingPath> {
        cachedSpellingPaths?.let { cached ->
            if (cached.rawInput == rawInput && cached.maxPaths == maxPaths) {
                return cached.paths
            }
        }
        return spellingGraph.paths(rawInput, maxPaths = maxPaths).also { paths ->
            cachedSpellingPaths = CachedSpellingPaths(rawInput, maxPaths, paths)
        }
    }

    private data class CachedSpellingPaths(
        val rawInput: String,
        val maxPaths: Int,
        val paths: List<PinyinSpellingPath>,
    )

    private fun isSingleCharacterTailExtension(typed: String, canonical: String): Boolean =
        (canonical.length == typed.length + 1 && canonical.startsWith(typed)) ||
            (typed.length == canonical.length + 1 && typed.startsWith(canonical))

    private data class CorrectionCompositionProbe(
        val path: PinyinSpellingPath,
        val lexicalEstimate: Float,
        val hasExactRecord: Boolean,
    )

    private fun Candidate.withCorrectionPenalty(path: PinyinSpellingPath): Candidate =
        copy(
            score = score - path.cost * CORRECTION_PENALTY,
            matchKind = CandidateMatchKind.CORRECTED,
            canonicalPinyin = path.canonical,
        ).also { PinyinScoreDiagnostics.current?.add(this, it, Feature.SPELLING, -path.cost * CORRECTION_PENALTY) }

    /**
     * Cheap lexical scheduling estimate used before spending a full correction beam.
     *
     * It reads only the first candidate of each word-lattice edge and keeps one
     * score per segment count. The optimistic mean is in the exported candidate-score domain,
     * used ONLY for recall budgeting, not for ranking sentence paths. Boundary LM evidence is
     * omitted, so this is a priority estimate, not an admissible bound.
     */
    private fun lexicalCompositionEstimate(query: String): Float? {
        val scores = Array(query.length + 1) {
            FloatArray(query.length + 1) { Float.NEGATIVE_INFINITY }
        }
        scores[0][0] = 0f
        query.indices.forEach { start ->
            // An unreachable offset contributes no path. In particular, do not search every
            // dictionary substring inside syllables for each alternate corrected spelling.
            if (scores[start].none { it.isFinite() }) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (!isCompositionEdgeAllowed(query, start, end)) continue
                val record = findExact(query, start, end)
                if (record < 0) continue
                val topScore = readCandidates(record, 1).firstOrNull()?.score ?: continue
                for (segments in 0 until query.length) {
                    val previous = scores[start][segments]
                    if (!previous.isFinite()) continue
                    scores[end][segments + 1] = maxOf(
                        scores[end][segments + 1],
                        previous + topScore,
                    )
                }
            }
        }
        var best = Float.NEGATIVE_INFINITY
        for (segments in 2..query.length) {
            val total = scores[query.length][segments]
            if (!total.isFinite()) continue
            best = maxOf(best, (total - (segments - 1) * WORD_BOUNDARY_COST) / segments)
        }
        return best.takeIf(Float::isFinite)
    }

    /**
     * A canonical composition used to suppress completion-style corrections
     * must not depend on one-letter fallback records such as `n`. Without this
     * guard, `fun` can be treated as `fu + n`, and a transposition typo ending
     * in `...ern` can look like a valid sentence.
     */
    private fun hasMultiSegmentComposition(
        query: String,
        forcedJoints: BooleanArray? = null,
    ): Boolean {
        val maximumSegments = IntArray(query.length + 1) { -1 }
        maximumSegments[0] = 0
        query.indices.forEach { start ->
            if (maximumSegments[start] < 0) return@forEach
            val maxEnd = minOf(query.length, start + MAX_SEGMENT_CODE_LENGTH)
            for (end in (start + 1)..maxEnd) {
                if (!isCompositionEdgeAllowed(query, start, end, forcedJoints)) continue
                if (findExact(query, start, end) >= 0) {
                    maximumSegments[end] = maxOf(maximumSegments[end], maximumSegments[start] + 1)
                }
            }
        }
        return maximumSegments.last() >= 2
    }

    /**
     * Fully spelled regions such as `ni'hao` are already handled by the canonical sentence DAG.
     * The mixed namespace is needed only when a user-locked boundary leaves an initial or an
     * incomplete tail (for example `hun'shenxs`). Avoiding it for canonical regions keeps an
     * explicit T9 choice as cheap as ordinary full-pinyin composition.
     */
    private fun hasAbbreviatedForcedRegion(
        query: String,
        forcedJoints: BooleanArray,
    ): Boolean {
        var start = 0
        for (end in 1..query.length) {
            if (end != query.length && !forcedJoints.getOrElse(end) { false }) continue
            if (!segmenter.isComplete(query.substring(start, end))) return true
            start = end
        }
        return false
    }

    private fun readCandidates(
        index: Int,
        limit: Int,
        matchKind: CandidateMatchKind = CandidateMatchKind.BASE_EXACT,
        canonicalPinyin: String? = null,
        includeSupplemental: Boolean = languageBinding != null,
        view: LexiconIndex = activeIndex,
    ): List<Candidate> {
        var cursor = view.offsets[index]
        val codeLength = unsigned(data[cursor++])
        val scoreTrace = PinyinScoreDiagnostics.current
        val evidenceCode = if (scoreTrace == null) null else data.decodeToString(cursor, cursor + codeLength)
        cursor += codeLength
        val candidateCount = unsigned(data[cursor++])
        val result = ArrayList<Candidate>(minOf(limit, candidateCount))
        repeat(candidateCount) { candidateIndex ->
            val textLength = unsigned(data[cursor++])
            val text = if (candidateIndex < limit) {
                data.decodeToString(cursor, cursor + textLength)
            } else {
                ""
            }
            cursor += textLength
            val weight = readInt(cursor).toLong() and 0xFFFFFFFFL
            cursor += Int.SIZE_BYTES
            val initialsLength = unsigned(data[cursor++])
            val initials = if (candidateIndex < limit && initialsLength > 0) {
                data.decodeToString(cursor, cursor + initialsLength)
            } else {
                null
            }
            cursor += initialsLength
            val sourceTier = unsigned(data[cursor++])
            if (candidateIndex < limit && (includeSupplemental || sourceTier != SUPPLEMENTAL_SOURCE_TIER)) {
                val frequency = ln(weight.toDouble() + 1.0).toFloat()
                val penalty = if (sourceTier >= FALLBACK_SOURCE_TIER) FALLBACK_SOURCE_PENALTY else 0f
                result += Candidate(text, frequency - penalty, canonicalPinyin, matchKind, initials).also {
                    scoreTrace?.lexical(it, checkNotNull(evidenceCode), weight, sourceTier, frequency, penalty)
                }
            }
        }
        return result
    }

    private fun compareCode(index: Int, query: String, view: LexiconIndex = activeIndex): Int {
        return compareCode(index, query, 0, query.length, view)
    }

    private fun compareCode(index: Int, query: String, start: Int, end: Int, view: LexiconIndex = activeIndex): Int {
        val offset = view.offsets[index]
        val codeLength = unsigned(data[offset])
        val queryLength = end - start
        val shared = minOf(codeLength, queryLength)
        repeat(shared) { characterIndex ->
            val difference = unsigned(data[offset + 1 + characterIndex]) - query[start + characterIndex].code
            if (difference != 0) return difference
        }
        return codeLength - queryLength
    }

    private fun codeStartsWith(index: Int, query: String, view: LexiconIndex = activeIndex): Boolean {
        val offset = view.offsets[index]
        val codeLength = unsigned(data[offset])
        if (codeLength < query.length) return false
        return query.indices.all { unsigned(data[offset + 1 + it]) == query[it].code }
    }

    private fun readCode(index: Int): String {
        val offset = recordOffsets[index]
        val codeLength = unsigned(data[offset])
        return data.decodeToString(offset + 1, offset + 1 + codeLength)
    }

    private fun readInt(offset: Int): Int =
        (unsigned(data[offset]) shl 24) or
            (unsigned(data[offset + 1]) shl 16) or
            (unsigned(data[offset + 2]) shl 8) or
            unsigned(data[offset + 3])

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun normalize(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val lower = character.lowercaseChar()
            if (lower in 'a'..'z') append(lower)
        }
    }

    private data class ParsedQuery(
        val code: String,
        val forcedJoints: BooleanArray,
    ) {
        val hasForcedJoints: Boolean
            get() = forcedJoints.any { it }
    }

    private fun parseQuery(value: String): ParsedQuery {
        if (value.all { it in 'a'..'z' }) {
            return ParsedQuery(value, EMPTY_FORCED_JOINTS)
        }
        val code = StringBuilder(value.length)
        val jointOffsets = ArrayList<Int>()
        var pendingJoint = false
        value.forEach { character ->
            when {
                character == '\'' -> pendingJoint = code.isNotEmpty()
                character.lowercaseChar() in 'a'..'z' -> {
                    if (pendingJoint && code.isNotEmpty()) jointOffsets += code.length
                    code.append(character.lowercaseChar())
                    pendingJoint = false
                }
            }
        }
        val joints = BooleanArray(code.length + 1)
        jointOffsets.forEach { joints[it] = true }
        return ParsedQuery(code.toString(), joints)
    }

    private fun crossesForcedJoint(
        forcedJoints: BooleanArray?,
        start: Int,
        end: Int,
    ): Boolean {
        if (forcedJoints == null) return false
        for (joint in (start + 1) until end) {
            if (forcedJoints.getOrElse(joint) { false }) return true
        }
        return false
    }

    private fun isCompositionEdgeAllowed(
        query: String,
        start: Int,
        end: Int,
        forcedJoints: BooleanArray? = null,
    ): Boolean =
        end > start &&
            (end - start > 1 || query[start] in SINGLE_LETTER_SYLLABLES) &&
            !crossesForcedJoint(forcedJoints, start, end)

    companion object {
        private const val HEADER_SIZE = 10
        private const val VERSION = 3
        private const val LAYERED_VERSION = 4
        private const val MAX_DECODE_CANDIDATES = 255
        private const val PREFIX_SCAN_LIMIT = 96
        private const val PREFIX_CANDIDATES_PER_KEY = 2
        private const val MAX_SEGMENT_CODE_LENGTH = 24
        private const val MIN_SEGMENT_CANDIDATES_PER_KEY = 8
        private const val MAX_SEGMENT_CANDIDATES_PER_KEY = 16
        private const val MIN_SEGMENT_BEAM_WIDTH = 24
        private const val MAX_SEGMENT_BEAM_WIDTH = 96
        private const val WIDE_COMPOSITION_LIMIT = 64
        private const val BEAM_PRUNE_MULTIPLIER = 4
        private const val MAX_PINYIN_SYLLABLE_CODE_LENGTH = 6
        private const val WORD_BOUNDARY_COST = 0.65f
        private const val USER_WORD_BASE_GAP = 1.5f
        private const val MAX_USER_WORD_ADJUSTMENT = 4.5f
        private const val COMPOUND_BOUNDARY_SCALE = 0.04f
        private const val CONTEXT_SCORE_CAP = 3f
        private const val CORRECTION_PENALTY = 4f
        private const val RECENT_EDIT_OFFSET_WINDOW = 1
        private const val TAIL_EXTENSION_PROBE_LIMIT = 2
        private const val NO_CODE_POINT = -1
        private val EMPTY_FORCED_JOINTS = BooleanArray(0)
        private const val SINGLE_LETTER_SYLLABLES = "aeo"
        private const val FALLBACK_SOURCE_TIER = 1
        private const val SUPPLEMENTAL_SOURCE_TIER = 2
        // CC-CEDICT fallback entries use weight 1. Penalizing only fallback
        // entries keeps a zero-weight primary entry ahead without rewarding
        // sentences merely for splitting into more primary-source segments.
        private const val FALLBACK_SOURCE_PENALTY = 1f
        private const val PREFIX_NAMESPACE = "{"
        private const val INITIALS_NAMESPACE = "~"
        private const val HYBRID_NAMESPACE = "}"
        private const val HYBRID_SEPARATOR = "|"
        private const val MIN_INITIALS_LENGTH = 2
        private const val EXACT_INITIALS_LENGTH_BONUS = 3f
        private const val FOUR_CHARACTER_INITIALS_BONUS = 10f
        private const val MIN_HYBRID_LENGTH = 3
        private const val HYBRID_SCAN_LIMIT = 128
        private const val HYBRID_CANDIDATES_PER_RECORD = 16
        private const val HYBRID_RETAINED_LIMIT_MULTIPLIER = 2
        private const val HYBRID_PRUNE_LIMIT_MULTIPLIER = 3
        private const val DYNAMIC_MIXED_FILL_FLOOR = 16
        private const val MAX_DYNAMIC_MIXED_PATHS = 4
        private const val MAX_LEXICAL_PROBE_DYNAMIC_LENGTH = 24
        private const val MIN_DYNAMIC_MIXED_WITH_CANONICAL_LENGTH = 8
        private const val MAX_DYNAMIC_MIXED_ABBREVIATIONS = 4
        private const val MAX_DYNAMIC_MIXED_SYLLABLES = 12
        private const val MAX_DYNAMIC_MIXED_BEAM = 96
        private const val MAX_DYNAMIC_MIXED_PREFIX_PROBES_PER_PATH = 4_096
        private const val MAX_DYNAMIC_MIXED_EXACT_RECORDS_PER_PATH = 16
        private const val DYNAMIC_MIXED_CANDIDATES_PER_RECORD = 16
        private const val STRONG_MIXED_TYPED_EVIDENCE_BONUS = 1f
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte())
        // Separate payload and index budgets. A larger real vocabulary must not turn a
        // forged header into an unbounded offset-table allocation (at most 5 MB here).
        private const val MAX_LEXICON_BYTES = 64 * 1024 * 1024
        private const val MAX_LEXICON_RECORDS = 1_250_000
        private const val MIN_RECORD_BYTES = 12

        fun load(
            input: InputStream,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
            correctionBudget: CorrectionSearchBudget = CorrectionSearchBudget.PRODUCTION,
        ): PinyinDecoder {
            // available() is a hint, not an allocation budget. Bound the read before
            // growing storage; one extra byte distinguishes an exact-size stream.
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val remaining = MAX_LEXICON_BYTES - output.size()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                if (count < 0) break
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    require(remaining > 0) { "Pinyin asset exceeds byte budget" }
                    output.write(byte)
                } else {
                    require(count <= remaining) { "Pinyin asset exceeds byte budget" }
                    output.write(buffer, 0, count)
                }
            }
            return fromBytes(output.toByteArray(), bigramModel, correctionBudget)
        }

        fun fromBytes(
            data: ByteArray,
            bigramModel: CharacterBigramModel = CharacterBigramModel.EMPTY,
            correctionBudget: CorrectionSearchBudget = CorrectionSearchBudget.PRODUCTION,
        ): PinyinDecoder {
            require(data.size <= MAX_LEXICON_BYTES) { "Pinyin asset exceeds byte budget" }
            require(data.size >= HEADER_SIZE) { "Pinyin lexicon header is truncated" }
            require(MAGIC.indices.all { data[it] == MAGIC[it] }) { "Pinyin lexicon magic is invalid" }
            val version = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            require(version == VERSION || version == LAYERED_VERSION) { "Unsupported pinyin lexicon version: $version" }
            val count = ((data[6].toInt() and 0xFF) shl 24) or
                ((data[7].toInt() and 0xFF) shl 16) or
                ((data[8].toInt() and 0xFF) shl 8) or
                (data[9].toInt() and 0xFF)
            require(count in 1..MAX_LEXICON_RECORDS) { "Pinyin lexicon record count is invalid: $count" }
            require(count <= (data.size - HEADER_SIZE) / MIN_RECORD_BYTES) {
                "Pinyin lexicon record count does not fit payload: $count"
            }

            val offsets = IntArray(count)
            val baseOffsets = if (version == LAYERED_VERSION) IntArray(count) else offsets
            var baseRecordCount = 0
            val syllableRecords = Array(26) { ArrayList<Int>() }
            val baseSyllableRecords = Array(26) { ArrayList<Int>() }
            val syllables = LinkedHashSet<String>()
            var cursor = HEADER_SIZE
            var unigramMass = 0.0
            var unigramEntries = 0
            val fallbackWeightScale = kotlin.math.exp(-FALLBACK_SOURCE_PENALTY.toDouble())
            repeat(count) { index ->
                require(cursor < data.size) { "Pinyin lexicon record $index is truncated" }
                offsets[index] = cursor
                val codeLength = data[cursor++].toInt() and 0xFF
                require(codeLength > 0 && cursor + codeLength < data.size) { "Pinyin code $index is invalid" }
                val codeStart = cursor
                val firstCodeByte = data[cursor].toInt() and 0xFF
                val isCanonicalCode =
                    firstCodeByte in 'a'.code..'z'.code &&
                        codeLength <= MAX_SEGMENT_CODE_LENGTH
                cursor += codeLength
                val candidateCount = data[cursor++].toInt() and 0xFF
                require(candidateCount > 0) { "Pinyin code $index has no candidates" }
                var hasSingleSyllableCandidate = false
                var hasBaseCandidate = false
                var seenSupplement = false
                repeat(candidateCount) {
                    require(cursor < data.size) { "Pinyin candidate length is missing" }
                    val textLength = data[cursor++].toInt() and 0xFF
                    require(textLength > 0 && cursor + textLength + Int.SIZE_BYTES <= data.size) {
                        "Pinyin candidate is truncated"
                    }
                    cursor += textLength
                    val weight = ((data[cursor].toLong() and 0xFF) shl 24) or
                        ((data[cursor + 1].toLong() and 0xFF) shl 16) or
                        ((data[cursor + 2].toLong() and 0xFF) shl 8) or
                        (data[cursor + 3].toLong() and 0xFF)
                    cursor += Int.SIZE_BYTES
                    require(cursor < data.size) { "Pinyin initials length is missing" }
                    val initialsLength = data[cursor++].toInt() and 0xFF
                    require(initialsLength > 0 && cursor + initialsLength <= data.size) {
                        "Pinyin candidate initials are truncated"
                    }
                    if (initialsLength == 1) hasSingleSyllableCandidate = true
                    cursor += initialsLength
                    require(cursor < data.size) { "Pinyin candidate source tier is missing" }
                    val sourceTier = data[cursor++].toInt() and 0xFF
                    require(sourceTier in 0..(if (version == LAYERED_VERSION) 2 else 1)) { "Pinyin candidate source tier is invalid" }
                    if (sourceTier == SUPPLEMENTAL_SOURCE_TIER) {
                        seenSupplement = true
                        require(initialsLength in 2..8 && codeLength <= 48 &&
                            (codeStart until codeStart + codeLength).all { data[it].toInt() in 'a'.code..'z'.code }) {
                            "Supplemental entries must be full-pinyin multi-character words, not aliases or syllable inventory"
                        }
                    } else {
                        // Base candidates precede the appended coverage layer, so bounded
                        // legacy reads keep the original candidate-index semantics.
                        require(!seenSupplement) { "Base entries must precede supplemental entries" }
                        hasBaseCandidate = true
                    }
                    // Count canonical pronunciation entries once, not their prefix/initials/hybrid
                    // aliases. Counts are lexical priors, not a claim of sentence-corpus training.
                    // Keep the reference mass/mean calibrated to the immutable base layer.
                    // Supplemental weights are additional log-linear features, not a claim
                    // that the expanded vocabulary is a newly normalized probability model.
                    if (firstCodeByte in 'a'.code..'z'.code && sourceTier != SUPPLEMENTAL_SOURCE_TIER) {
                        unigramEntries++
                        unigramMass += (weight + 1).toDouble() *
                            if (sourceTier == FALLBACK_SOURCE_TIER) fallbackWeightScale else 1.0
                    }
                }
                val isSyllableRecord =
                    codeLength <= MAX_PINYIN_SYLLABLE_CODE_LENGTH &&
                    isCanonicalCode &&
                    hasSingleSyllableCandidate
                if (isSyllableRecord) {
                    // Production packs contain hundreds of thousands of canonical records but only
                    // a few hundred syllables. Decode a String only for graph inventory entries;
                    // the former eager conversion created one short-lived object per record.
                    val canonicalCode = data.decodeToString(codeStart, codeStart + codeLength)
                    syllableRecords[firstCodeByte - 'a'.code] += index
                    baseSyllableRecords[firstCodeByte - 'a'.code] += baseRecordCount
                    syllables += canonicalCode
                }
                if (hasBaseCandidate) baseOffsets[baseRecordCount++] = offsets[index]
            }
            require(cursor == data.size) { "Pinyin lexicon has trailing bytes" }
            require(baseRecordCount > 0) { "Pinyin lexicon needs a base layer" }
            val fullIndex = LexiconIndex(offsets, Array(26) { syllableRecords[it].toIntArray() })
            val baseView = if (baseRecordCount == count) fullIndex else
                LexiconIndex(baseOffsets.copyOf(baseRecordCount), Array(26) { baseSyllableRecords[it].toIntArray() })
            return PinyinDecoder(
                data,
                baseView,
                bigramModel,
                fullIndex,
                PinyinSpellingGraph(syllables),
                PinyinSyllableSegmenter(syllables),
                correctionBudget,
                ln(unigramMass.coerceAtLeast(1.0)).toFloat(),
                ln((unigramMass / unigramEntries.coerceAtLeast(1)).coerceAtLeast(1.0)).toFloat(),
            )
        }
    }
}
