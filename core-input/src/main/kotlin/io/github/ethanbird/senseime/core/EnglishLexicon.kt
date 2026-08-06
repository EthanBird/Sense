package io.github.ethanbird.senseime.core

import java.io.InputStream
import kotlin.math.ln

/**
 * Read-only, popularity-ranked English suggestions for Chinese-mode mixing.
 *
 * The source file stays in popularity order. One- and two-letter buckets keep
 * each lookup bounded without constructing prefix strings or a large trie on
 * the key-event path.
 */
class EnglishLexicon private constructor(
    private val words: Array<String>,
    private val firstLetterBuckets: Array<IntArray>,
    private val firstTwoLetterBuckets: Array<IntArray>,
    private val alphabeticIndices: IntArray,
    private val usageStore: EnglishWordUsageStore,
) {
    fun suggest(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = normalize(composing)
        if (query.isEmpty()) return emptyList()
        if (words.isEmpty() && usageStore.find(query) == null) return emptyList()

        val exactIndex = findExact(query)
        val prefixMatchBudget = if (query.length == 1) {
            SINGLE_LETTER_PREFIX_MATCHES
        } else {
            MAX_PREFIX_MATCHES
        }
        val matches = ArrayList<Int>(minOf(limit, prefixMatchBudget))
        val bucket = if (query.length == 1) {
            firstLetterBuckets[query[0] - 'a']
        } else {
            firstTwoLetterBuckets[
                (query[0] - 'a') * ALPHABET_SIZE + (query[1] - 'a')
            ]
        }
        for (wordIndex in bucket) {
            if (words[wordIndex].startsWith(query)) {
                matches += wordIndex
                if (matches.size >= prefixMatchBudget) break
            }
        }

        val ordered = ArrayList<String>(minOf(limit, matches.size + 1))
        val seen = HashSet<String>()
        fun add(wordIndex: Int) {
            if (wordIndex >= 0 && ordered.size < limit) {
                val word = words[wordIndex]
                if (seen.add(word)) ordered += word
            }
        }

        if (exactIndex < 0 && usageStore.find(query) != null && seen.add(query)) ordered += query
        add(exactIndex)
        preferredInflections(query).forEach { add(findExact(it)) }

        // Past/continuous/adverbial inflections are useful, but a lexical
        // completion such as "hostile" is generally more informative than
        // "hosted" or "hosting" after the exact word and its plural.
        matches.forEach { index ->
            val suffix = words[index].removePrefix(query)
            if (suffix !in DEFERRED_INFLECTION_SUFFIXES) add(index)
        }
        matches.forEach(::add)

        val candidates = ordered.mapIndexed { displayRank, word ->
            val matchKind = if (word == query) {
                CandidateMatchKind.ENGLISH_EXACT
            } else {
                CandidateMatchKind.ENGLISH_PREFIX
            }
            Candidate(
                text = word,
                // The lexical/inflection policy above is part of recall
                // evidence. Encode it in the shared score domain so the
                // bilingual ranker does not silently restore source-file order.
                score = ENGLISH_SCORE_BASE -
                    ln(displayRank.toFloat() + 2f) -
                    shortQueryPenalty(query.length, matchKind) +
                    usageBoost(word),
                matchKind = matchKind,
            )
        }
        return EnglishSuggestionBatch(query, candidates)
    }

    fun recordAccepted(
        word: String,
        evidence: UserLearningEvidence = UserLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedEnglishWord? {
        if (!word.isAsciiWord()) return null
        return usageStore.record(word.lowercase(), evidence)
    }

    private fun usageBoost(word: String): Float {
        val usage = usageStore.find(word) ?: return 0f
        return (ln(usage.positiveEvidence.toDouble() + 1.0).toFloat() * 0.9f)
            .coerceAtMost(MAX_USAGE_BOOST)
    }

    private fun findExact(query: String): Int {
        var low = 0
        var high = alphabeticIndices.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val index = alphabeticIndices[middle]
            when {
                words[index] < query -> low = middle + 1
                words[index] > query -> high = middle - 1
                else -> return index
            }
        }
        return -1
    }

    companion object {
        val EMPTY = fromWords(emptyList())

        fun load(
            input: InputStream,
            maximumWords: Int = DEFAULT_MAXIMUM_WORDS,
            usageStore: EnglishWordUsageStore = EnglishWordUsageStore.EMPTY,
        ): EnglishLexicon =
            input.bufferedReader().useLines { lines ->
                fromWords(
                    lines
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .take(maximumWords)
                        .toList(),
                    usageStore,
                )
            }

        fun fromWords(
            values: List<String>,
            usageStore: EnglishWordUsageStore = EnglishWordUsageStore.EMPTY,
        ): EnglishLexicon {
            val unique = LinkedHashSet<String>(values.size)
            values.forEach { value ->
                val normalized = normalize(value)
                if (
                    normalized == value.lowercase() &&
                    normalized.length in 1..MAX_WORD_LENGTH &&
                    (normalized.length > 1 || normalized in VALID_SINGLE_LETTER_WORDS)
                ) {
                    unique += normalized
                }
            }
            val words = unique.toTypedArray()
            val first = Array(ALPHABET_SIZE) { ArrayList<Int>() }
            val second = Array(ALPHABET_SIZE * ALPHABET_SIZE) { ArrayList<Int>() }
            words.indices.forEach { index ->
                val word = words[index]
                first[word[0] - 'a'] += index
                if (word.length >= 2) {
                    second[
                        (word[0] - 'a') * ALPHABET_SIZE + (word[1] - 'a')
                    ] += index
                }
            }
            val alphabetic = words.indices.sortedBy(words::get).toIntArray()
            return EnglishLexicon(
                words = words,
                firstLetterBuckets = Array(first.size) { first[it].toIntArray() },
                firstTwoLetterBuckets = Array(second.size) { second[it].toIntArray() },
                alphabeticIndices = alphabetic,
                usageStore = usageStore,
            )
        }

        private fun preferredInflections(query: String): List<String> = buildList(3) {
            add(query + "s")
            add(query + "es")
            if (query.length > 1 && query.endsWith('y') && query[query.lastIndex - 1] !in "aeiou") {
                add(query.dropLast(1) + "ies")
            }
        }

        private fun normalize(value: String): String = buildString(value.length) {
            value.forEach { character ->
                val lower = character.lowercaseChar()
                if (lower in 'a'..'z') append(lower)
            }
        }

        private fun shortQueryPenalty(
            queryLength: Int,
            matchKind: CandidateMatchKind,
        ): Float = when {
            matchKind == CandidateMatchKind.ENGLISH_EXACT && queryLength == 1 ->
                SINGLE_LETTER_EXACT_PENALTY

            matchKind == CandidateMatchKind.ENGLISH_PREFIX && queryLength == 1 ->
                SINGLE_LETTER_PREFIX_PENALTY

            matchKind == CandidateMatchKind.ENGLISH_PREFIX && queryLength == 2 ->
                TWO_LETTER_PREFIX_PENALTY

            else -> 0f
        }

        private const val ALPHABET_SIZE = 26
        private const val DEFAULT_MAXIMUM_WORDS = 20_000
        private const val MAX_PREFIX_MATCHES = 96
        private const val SINGLE_LETTER_PREFIX_MATCHES = 32
        private const val MAX_WORD_LENGTH = 32
        private const val ENGLISH_SCORE_BASE = 18f
        private const val SINGLE_LETTER_PREFIX_PENALTY = 2.4f
        private const val SINGLE_LETTER_EXACT_PENALTY = 0.75f
        private const val TWO_LETTER_PREFIX_PENALTY = 0.8f
        private const val MAX_USAGE_BOOST = 2.6f
        private val DEFERRED_INFLECTION_SUFFIXES = setOf("ed", "ing", "er", "ers", "ly")
        private val VALID_SINGLE_LETTER_WORDS = setOf("a", "i")
    }
}

/**
 * Carries normalized query evidence across the existing List<Candidate> API.
 *
 * Keeping this metadata on the immutable batch avoids overloading Chinese
 * canonical-pinyin fields on English candidates.
 */
internal class EnglishSuggestionBatch(
    val query: String,
    private val values: List<Candidate>,
) : AbstractList<Candidate>() {
    val queryLength: Int
        get() = query.length

    override val size: Int
        get() = values.size

    override fun get(index: Int): Candidate = values[index]
}
