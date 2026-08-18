package io.github.ethanbird.senseime.core

/** Small exact overlay for important modern vocabulary missing from the pinned Frost snapshot. */
object CuratedLexicalCandidateCatalog {
    private data class Entry(
        val pinyin: String,
        val initials: String,
        val text: String,
    )

    private val entries = listOf(
        Entry("zhinengti", "znt", "智能体"),
    )
    private val entriesByQuery: Map<String, Entry> = buildMap {
        CuratedLexicalCandidateCatalog.entries.forEach { entry ->
            put(entry.pinyin, entry)
            put(entry.initials, entry)
        }
    }

    fun merge(
        composing: String,
        primary: List<Candidate>,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val query = PinyinSyllableSegmenter.normalize(composing)
        val entry = entriesByQuery[query] ?: return primary.take(limit)
        val existingIndex = primary.indexOfFirst { it.text == entry.text }
        if (existingIndex in 0..PREFERRED_INDEX) return primary.take(limit)
        val existing = primary.getOrNull(existingIndex)
        val result = primary.filterNot { it.text == entry.text }.toMutableList()
        // Preserve adaptive/user evidence when it already exists; the product overlay only fills
        // a missing vocabulary gap or gives a deep base-composed row a bounded doorway.
        val candidate = existing?.takeIf {
            it.matchKind == CandidateMatchKind.USER_FULL ||
                it.matchKind == CandidateMatchKind.USER_INITIALS
        } ?: Candidate(
            text = entry.text,
            score = result.firstOrNull()?.score ?: 0f,
            canonicalPinyin = entry.pinyin,
            canonicalInitials = entry.initials,
            matchKind = if (query == entry.pinyin) {
                CandidateMatchKind.BASE_EXACT
            } else {
                CandidateMatchKind.BASE_INITIALS
            },
        )
        result.add(minOf(PREFERRED_INDEX, result.size), candidate)
        return result.take(limit)
    }

    private const val PREFERRED_INDEX = 1
}
