package io.github.ethanbird.senseime.core

import kotlin.math.ln

data class LearnedPhrase(
    val fullPinyin: String,
    val initials: String,
    val text: String,
    val useCount: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
)

interface UserLexicon : AutoCloseable {
    fun lookup(code: String, limit: Int): List<LearnedPhrase>
    fun record(fullPinyin: String, initials: String, text: String): LearnedPhrase
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
        return keys
            .asSequence()
            .mapNotNull(records::get)
            .sortedWith(
                compareByDescending<LearnedPhrase> { it.initials == normalized }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastUsedAtMillis },
            )
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun record(fullPinyin: String, initials: String, text: String): LearnedPhrase {
        val full = PinyinSyllableSegmenter.normalize(fullPinyin)
        val short = PinyinSyllableSegmenter.normalize(initials)
        require(full.isNotEmpty() && short.isNotEmpty() && text.isNotEmpty())
        val now = clock()
        val key = full to text
        val previous = records[key]
        val value = LearnedPhrase(
            fullPinyin = full,
            initials = short,
            text = text,
            useCount = (previous?.useCount ?: 0) + 1,
            createdAtMillis = previous?.createdAtMillis ?: now,
            lastUsedAtMillis = now,
        )
        records[key] = value
        if (previous == null) {
            fullIndex.getOrPut(full) { LinkedHashSet() } += key
            initialsIndex.getOrPut(short) { LinkedHashSet() } += key
        } else if (previous.initials != short) {
            initialsIndex[previous.initials]?.remove(key)
            initialsIndex.getOrPut(short) { LinkedHashSet() } += key
        }
        onRecord(value)
        return value
    }

    private fun restore(value: LearnedPhrase) {
        val key = value.fullPinyin to value.text
        records[key] = value
        fullIndex.getOrPut(value.fullPinyin) { LinkedHashSet() } += key
        initialsIndex.getOrPut(value.initials) { LinkedHashSet() } += key
    }
}

class AdaptivePinyinDecoder(
    private val base: InputDecoder,
    private val userLexicon: UserLexicon,
    private val segmenter: PinyinSyllableSegmenter,
) : ProgressivePinyinDecoder, ContextualInputDecoder {
    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = PinyinSyllableSegmenter.normalize(composing)
        if (query.isEmpty()) return emptyList()

        return mergeUserAndBase(query, limit, base.decode(query, limit))
    }

    override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = PinyinSyllableSegmenter.normalize(composing)
        if (query.isEmpty()) return emptyList()
        val baseCandidates = (base as? ContextualInputDecoder)
            ?.decodeAfter(previousCodePoint, query, limit)
            ?: base.decode(query, limit)
        return mergeUserAndBase(query, limit, baseCandidates)
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

        val prefixes = ArrayList<PinyinPrefixCandidate>(minOf(limit, MAX_PROGRESSIVE_CANDIDATES))
        val seen = HashSet<Pair<Int, String>>()
        for (length in segmenter.selectablePrefixLengths(query)) {
            val consumed = query.substring(0, length)
            val remaining = query.substring(length)
            for (candidate in decode(consumed, limit)) {
                if (!isSingleHanCandidate(candidate)) continue
                if (!seen.add(length to candidate.text)) continue
                prefixes += PinyinPrefixCandidate(candidate, consumed, remaining)
                if (prefixes.size == minOf(limit, MAX_PROGRESSIVE_CANDIDATES)) break
            }
            if (prefixes.size == minOf(limit, MAX_PROGRESSIVE_CANDIDATES)) break
        }
        prefixes.sortWith(
            compareBy<PinyinPrefixCandidate> { wholePrefixRank[it.candidate.text] ?: Int.MAX_VALUE }
                .thenByDescending { it.candidate.score },
        )
        return ProgressivePinyinDecoding(
            revision = composition.revision,
            remainingPinyin = query,
            wholeCandidates = wholeCandidates,
            prefixCandidates = prefixes,
        )
    }

    /** Records only complete Chinese selections whose pinyin can be split unambiguously by character count. */
    fun learn(rawInput: String, candidate: Candidate): LearnedPhrase? {
        if (!candidate.text.any(::isHanCharacter)) return null
        if (candidate.matchKind == CandidateMatchKind.BASE_PREFIX || candidate.matchKind == CandidateMatchKind.BASE_INITIALS) {
            return null
        }
        val canonical = candidate.canonicalPinyin ?: PinyinSyllableSegmenter.normalize(rawInput)
        val characterCount = candidate.text.count(::isHanCharacter)
        val suppliedInitials = candidate.canonicalInitials
            ?.let(PinyinSyllableSegmenter::normalize)
            ?.takeIf { it.length == characterCount }
        val initials = suppliedInitials ?: segmenter.initials(canonical, characterCount) ?: return null
        return userLexicon.record(canonical, initials, candidate.text)
    }

    private fun isHanCharacter(character: Char): Boolean =
        character.code in 0x3400..0x4DBF ||
            character.code in 0x4E00..0x9FFF ||
            character.code in 0xF900..0xFAFF

    private fun isSingleHanCandidate(candidate: Candidate): Boolean =
        candidate.text.length == 1 &&
            isHanCharacter(candidate.text[0]) &&
            candidate.canonicalInitials?.length == 1

    private companion object {
        const val USER_BONUS = 30f
        const val MAX_PROGRESSIVE_CANDIDATES = 32
    }
}
