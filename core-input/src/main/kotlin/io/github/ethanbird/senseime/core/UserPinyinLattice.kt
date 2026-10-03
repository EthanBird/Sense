package io.github.ethanbird.senseime.core

/** A canonical spelling occurrence, never an initials/typo alias silently expanded inside a word. */
data class UserPinyinMatch(val start: Int, val phrase: LearnedPhrase) {
    val end: Int get() = start + phrase.fullPinyin.length
}

/** Query-local, validated personal word edges. Never retained in the static prefix cache. */
internal class UserPinyinLattice private constructor(
    private val edges: Map<Int, List<LearnedPhrase>>,
    private val maximumEnds: IntArray,
) {
    val isEmpty: Boolean get() = edges.isEmpty()
    fun at(start: Int, end: Int): List<LearnedPhrase> = edges[key(start, end)].orEmpty()
    fun maximumEnd(start: Int): Int = maximumEnds.getOrElse(start) { 0 }
    fun anyPhrase(predicate: (LearnedPhrase) -> Boolean): Boolean = edges.values.any { values -> values.any(predicate) }

    companion object {
        val EMPTY = UserPinyinLattice(emptyMap(), IntArray(0))
        private fun key(start: Int, end: Int) = start * (PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH + 1) + end

        fun from(query: String, matches: List<UserPinyinMatch>): UserPinyinLattice {
            if (matches.isEmpty()) return EMPTY
            val ends = IntArray(query.length)
            val grouped = LinkedHashMap<Int, MutableList<LearnedPhrase>>()
            matches.forEach { match ->
                val phrase = match.phrase
                if (match.start !in query.indices || match.end !in match.start + 1..query.length ||
                    !query.regionMatches(match.start, phrase.fullPinyin, 0, phrase.fullPinyin.length) ||
                    !phrase.rankingBoost.isFinite() || phrase.text.isEmpty() ||
                    phrase.initials.length != phrase.text.codePointCount(0, phrase.text.length)
                ) return@forEach
                var offset = 0
                while (offset < phrase.text.length) {
                    val cp = phrase.text.codePointAt(offset)
                    if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return@forEach
                    offset += Character.charCount(cp)
                }
                grouped.getOrPut(key(match.start, match.end)) { ArrayList() }.add(phrase)
                ends[match.start] = maxOf(ends[match.start], match.end)
            }
            return if (grouped.isEmpty()) EMPTY else UserPinyinLattice(grouped, ends)
        }
    }
}
