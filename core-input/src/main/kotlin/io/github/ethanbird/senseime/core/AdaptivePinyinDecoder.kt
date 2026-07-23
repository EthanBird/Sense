package io.github.ethanbird.senseime.core

import kotlin.math.ln

data class LearnedPhrase(
    val fullPinyin: String,
    val initials: String,
    val text: String,
    val useCount: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val aliases: Set<String> = emptySet(),
)

interface UserLexicon : AutoCloseable {
    fun lookup(code: String, limit: Int): List<LearnedPhrase>
    fun record(
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String> = emptySet(),
    ): LearnedPhrase
    override fun close() = Unit
}

class MemoryUserLexicon(
    initial: Collection<LearnedPhrase> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (LearnedPhrase) -> Unit = {},
) : UserLexicon {
    private val records = LinkedHashMap<Pair<String, String>, LearnedPhrase>()
    private val fullIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private val initialsIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private val aliasIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private var latestAssignedUsedAtMillis = Long.MIN_VALUE

    init {
        initial.forEach(::restore)
    }

    @Synchronized
    override fun lookup(code: String, limit: Int): List<LearnedPhrase> {
        if (limit <= 0) return emptyList()
        val normalized = PinyinSyllableSegmenter.normalize(code)
        val keys = LinkedHashSet<Pair<String, String>>()
        fullIndex[normalized]?.let(keys::addAll)
        initialsIndex[normalized]?.let(keys::addAll)
        aliasIndex[normalized]?.let(keys::addAll)
        return keys
            .asSequence()
            .mapNotNull(records::get)
            .sortedWith(
                compareByDescending<LearnedPhrase> { it.initials == normalized }
                    .thenByDescending { it.lastUsedAtMillis }
                    .thenByDescending { it.useCount }
                    .thenBy { it.text },
            )
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun record(
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String>,
    ): LearnedPhrase {
        val full = PinyinSyllableSegmenter.normalize(fullPinyin)
        val short = PinyinSyllableSegmenter.normalize(initials)
        require(full.isNotEmpty() && short.isNotEmpty() && text.isNotEmpty())
        val now = nextUsedAtMillis()
        val key = full to text
        val previous = records[key]
        val normalizedAliases = buildSet {
            previous?.aliases?.forEach(::add)
            aliases
                .asSequence()
                .map(PinyinSyllableSegmenter::normalize)
                .filter { it.isNotEmpty() && it != full && it != short }
                .forEach(::add)
        }
        val value = LearnedPhrase(
            fullPinyin = full,
            initials = short,
            text = text,
            useCount = (previous?.useCount ?: 0) + 1,
            createdAtMillis = previous?.createdAtMillis ?: now,
            lastUsedAtMillis = now,
            aliases = normalizedAliases,
        )
        records[key] = value
        if (previous == null) {
            fullIndex.getOrPut(full) { LinkedHashSet() } += key
            initialsIndex.getOrPut(short) { LinkedHashSet() } += key
        } else if (previous.initials != short) {
            initialsIndex[previous.initials]?.remove(key)
            initialsIndex.getOrPut(short) { LinkedHashSet() } += key
        }
        normalizedAliases.forEach { alias ->
            aliasIndex.getOrPut(alias) { LinkedHashSet() } += key
        }
        onRecord(value)
        return value
    }

    private fun restore(value: LearnedPhrase) {
        val key = value.fullPinyin to value.text
        records[key] = value
        fullIndex.getOrPut(value.fullPinyin) { LinkedHashSet() } += key
        initialsIndex.getOrPut(value.initials) { LinkedHashSet() } += key
        value.aliases.forEach { alias ->
            aliasIndex.getOrPut(alias) { LinkedHashSet() } += key
        }
        latestAssignedUsedAtMillis = maxOf(latestAssignedUsedAtMillis, value.lastUsedAtMillis)
    }

    private fun nextUsedAtMillis(): Long {
        val observed = clock()
        val assigned = when {
            latestAssignedUsedAtMillis == Long.MIN_VALUE -> observed
            observed > latestAssignedUsedAtMillis -> observed
            latestAssignedUsedAtMillis < Long.MAX_VALUE -> latestAssignedUsedAtMillis + 1L
            else -> Long.MAX_VALUE
        }
        latestAssignedUsedAtMillis = assigned
        return assigned
    }
}

class AdaptivePinyinDecoder(
    private val base: InputDecoder,
    private val userLexicon: UserLexicon,
    private val segmenter: PinyinSyllableSegmenter,
    private val englishLexicon: EnglishLexicon = EnglishLexicon.EMPTY,
) : ProgressivePinyinDecoder, ContextualInputDecoder {
    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = PinyinSyllableSegmenter.normalize(composing)
        if (query.isEmpty()) return emptyList()

        val chinese = mergeUserAndBase(query, limit, base.decode(query, limit))
        return MixedCandidateRanker.merge(chinese, englishLexicon.suggest(query, limit), limit)
    }

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = PinyinSyllableSegmenter.normalize(composing)
        if (query.isEmpty()) return emptyList()
        val baseCandidates = (base as? ContextualInputDecoder)
            ?.decodeAfter(previousCodePoint, query, limit)
            ?: base.decode(query, limit)
        val chinese = mergeUserAndBase(query, limit, baseCandidates)
        return MixedCandidateRanker.merge(chinese, englishLexicon.suggest(query, limit), limit)
    }

    private fun mergeUserAndBase(
        query: String,
        limit: Int,
        baseCandidates: List<Candidate>,
    ): List<Candidate> {
        val user = userLexicon.lookup(query, limit).map { learned ->
            val initialsMatch = learned.initials == query
            Candidate(
                text = learned.text,
                score = USER_BONUS + 2f * ln(learned.useCount.toFloat() + 1f),
                canonicalPinyin = learned.fullPinyin,
                matchKind = if (initialsMatch) CandidateMatchKind.USER_INITIALS else CandidateMatchKind.USER_FULL,
                canonicalInitials = learned.initials,
            )
        }
        val combined = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        (user + baseCandidates).forEach { candidate ->
            if (combined.size < limit && seen.add(candidate.text)) combined += candidate
        }
        return combined
    }

    override fun decodeProgressively(
        composition: PinyinComposition,
        limit: Int,
    ): ProgressivePinyinDecoding {
        val query = PinyinSyllableSegmenter.normalize(composition.remainingPinyin)
        if (limit <= 0 || query.isEmpty()) {
            return ProgressivePinyinDecoding(
                revision = composition.revision,
                remainingPinyin = query,
                wholeCandidates = emptyList(),
                prefixCandidates = emptyList(),
            )
        }

        val wholeCandidates = composition.acceptedSegments
            .lastOrNull()
            ?.text
            ?.takeIf { it.codePointCount(0, it.length) == 1 }
            ?.let { decodeAfter(it.codePointAt(0), query, limit) }
            ?: decode(query, limit)
        val wholePrefixRank = HashMap<String, Int>()
        wholeCandidates.forEachIndexed { index, candidate ->
            if (candidate.text.isEmpty()) return@forEachIndexed
            val firstEnd = candidate.text.offsetByCodePoints(0, 1)
            wholePrefixRank.putIfAbsent(candidate.text.substring(0, firstEnd), index)
        }

        val prefixLimit = minOf(limit, MAX_PROGRESSIVE_CANDIDATES)
        val segmentablePrefixLengths = segmenter.selectablePrefixLengths(query).toSet()
        val prefixLengths = LinkedHashSet<Int>()
        prefixLengths.addAll(segmentablePrefixLengths)
        if (!segmenter.isComplete(query)) {
            for (length in 1..minOf(query.length - 1, MAX_FALLBACK_PREFIX_LENGTH)) {
                prefixLengths += length
            }
        }
        val prefixGroups = ArrayList<List<PinyinPrefixCandidate>>()
        for (length in prefixLengths.sorted()) {
            if (length >= query.length) continue
            val consumed = query.substring(0, length)
            val remaining = query.substring(length)
            val maximumHanCharacters = if (length in segmentablePrefixLengths) {
                1
            } else {
                MAX_FALLBACK_PREFIX_HAN_CHARACTERS
            }
            val group = decode(consumed, prefixLimit)
                .asSequence()
                .filter { isSelectableHanCandidate(it, maximumHanCharacters) }
                .distinctBy { it.text }
                .map { PinyinPrefixCandidate(it, consumed, remaining) }
                .sortedWith(prefixComparator(wholePrefixRank))
                .toList()
            if (group.isNotEmpty()) prefixGroups += group
        }
        val prefixes = mergePrefixGroups(prefixGroups, prefixLimit, wholePrefixRank)
        return ProgressivePinyinDecoding(
            revision = composition.revision,
            remainingPinyin = query,
            wholeCandidates = wholeCandidates,
            prefixCandidates = prefixes,
        )
    }

    /** Keeps every valid first-syllable path represented before filling by score. */
    private fun mergePrefixGroups(
        groups: List<List<PinyinPrefixCandidate>>,
        limit: Int,
        wholePrefixRank: Map<String, Int>,
    ): List<PinyinPrefixCandidate> {
        if (limit <= 0 || groups.isEmpty()) return emptyList()
        val values = ArrayList<PinyinPrefixCandidate>(limit)
        val seen = HashSet<Pair<String, String>>()
        groups.forEach { group ->
            val first = group.firstOrNull() ?: return@forEach
            if (values.size < limit && seen.add(first.consumedPinyin to first.candidate.text)) values += first
        }
        groups.asSequence()
            .flatten()
            .sortedWith(prefixComparator(wholePrefixRank))
            .forEach { candidate ->
                if (
                    values.size < limit &&
                    seen.add(candidate.consumedPinyin to candidate.candidate.text)
                ) {
                    values += candidate
                }
            }
        values.sortWith(prefixComparator(wholePrefixRank))
        return values
    }

    private fun prefixComparator(wholePrefixRank: Map<String, Int>) =
        compareBy<PinyinPrefixCandidate> { wholePrefixRank[it.candidate.text] ?: Int.MAX_VALUE }
            .thenByDescending { it.candidate.score }
            .thenBy { it.consumedPinyin.length }
            .thenBy { it.candidate.text }

    /** Records only complete Chinese selections whose pinyin can be split unambiguously by character count. */
    fun learn(rawInput: String, candidate: Candidate): LearnedPhrase? {
        if (!candidate.text.any(::isHanCharacter)) return null
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
        val characterCount = candidate.text.count(::isHanCharacter)
        val suppliedInitials = candidate.canonicalInitials
            ?.let(PinyinSyllableSegmenter::normalize)
            ?.takeIf { it.length == characterCount }
        val initials = suppliedInitials ?: segmenter.initials(canonical, characterCount) ?: return null
        val aliases = normalizedRawInput
            .takeIf { it != canonical && it != initials }
            ?.let(::setOf)
            .orEmpty()
        return userLexicon.record(canonical, initials, candidate.text, aliases)
    }

    private fun isHanCharacter(character: Char): Boolean =
        character.code in 0x3400..0x4DBF ||
            character.code in 0x4E00..0x9FFF ||
            character.code in 0xF900..0xFAFF

    private fun isSelectableHanCandidate(
        candidate: Candidate,
        maximumHanCharacters: Int,
    ): Boolean {
        val characterCount = candidate.text.count(::isHanCharacter)
        return characterCount in 1..maximumHanCharacters &&
            characterCount == candidate.text.length &&
            candidate.canonicalInitials?.length == characterCount
    }

    private companion object {
        const val USER_BONUS = 30f
        const val MAX_PROGRESSIVE_CANDIDATES = 255
        const val MAX_FALLBACK_PREFIX_LENGTH = 8
        const val MAX_FALLBACK_PREFIX_HAN_CHARACTERS = 4
    }
}
