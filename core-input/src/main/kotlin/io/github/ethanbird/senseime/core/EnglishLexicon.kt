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
) {
    fun suggest(composing: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = normalize(composing)
        if (query.isEmpty()) return emptyList()

        val exactIndex = findExact(query)
        val matches = ArrayList<Int>(minOf(limit * 4, MAX_PREFIX_MATCHES))
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
                if (matches.size >= MAX_PREFIX_MATCHES) break
            }
        }

        val ordered = ArrayList<Int>(minOf(limit, matches.size + 1))
        val seen = HashSet<Int>()
        fun add(wordIndex: Int) {
            if (wordIndex >= 0 && ordered.size < limit && seen.add(wordIndex)) ordered += wordIndex
        }

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

        return ordered.map { index ->
            val word = words[index]
            Candidate(
                text = word,
                score = ENGLISH_SCORE_BASE - ln(index.toFloat() + 2f),
                matchKind = if (word == query) {
                    CandidateMatchKind.ENGLISH_EXACT
                } else {
                    CandidateMatchKind.ENGLISH_PREFIX
                },
            )
        }
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

        fun load(input: InputStream, maximumWords: Int = DEFAULT_MAXIMUM_WORDS): EnglishLexicon =
            input.bufferedReader().useLines { lines ->
                fromWords(
                    lines
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .take(maximumWords)
                        .toList(),
                )
            }

        fun fromWords(values: List<String>): EnglishLexicon {
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

        private const val ALPHABET_SIZE = 26
        private const val DEFAULT_MAXIMUM_WORDS = 20_000
        private const val MAX_PREFIX_MATCHES = 96
        private const val MAX_WORD_LENGTH = 32
        private const val ENGLISH_SCORE_BASE = 18f
        private val DEFERRED_INFLECTION_SUFFIXES = setOf("ed", "ing", "er", "ers", "ly")
        private val VALID_SINGLE_LETTER_WORDS = setOf("a", "i")
    }
}
