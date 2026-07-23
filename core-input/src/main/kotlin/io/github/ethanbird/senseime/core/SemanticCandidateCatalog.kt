package io.github.ethanbird.senseime.core

enum class SemanticCandidateKind {
    EMOJI,
    SYMBOL,
}

/**
 * Candidate plus UI-independent placement metadata.
 *
 * Semantic aliases are deliberately late additions: they should be reachable
 * in the first candidate strip without replacing the user's primary Chinese
 * word candidates.
 */
data class SemanticCandidateSuggestion(
    val candidate: Candidate,
    val kind: SemanticCandidateKind,
    val preferredInsertionIndex: Int,
)

/**
 * Small, curated pinyin-to-Emoji/symbol layer.
 *
 * This is not a replacement for the Chinese lexicon. Matching is exact and the
 * low score/late insertion metadata keeps decorative candidates out of the
 * primary language-model rank.
 */
object SemanticCandidateCatalog {
    private data class Alias(
        val pinyin: String,
        val text: String,
        val kind: SemanticCandidateKind,
    )

    private val aliases = listOf(
        // Explicit product requirements.
        Alias("ji", "🐔", SemanticCandidateKind.EMOJI),
        Alias("yao", "💊", SemanticCandidateKind.EMOJI),
        Alias("you", "🈶", SemanticCandidateKind.EMOJI),
        Alias("suo", "🔒", SemanticCandidateKind.EMOJI),
        Alias("oumu", "Ω", SemanticCandidateKind.SYMBOL),
        Alias("pai", "π", SemanticCandidateKind.SYMBOL),
        Alias("zuo", "←", SemanticCandidateKind.SYMBOL),
        Alias("you", "→", SemanticCandidateKind.SYMBOL),

        // Common concrete Emoji that remain useful without a large semantic model.
        Alias("xiao", "😂", SemanticCandidateKind.EMOJI),
        Alias("kaixin", "😊", SemanticCandidateKind.EMOJI),
        Alias("ku", "😭", SemanticCandidateKind.EMOJI),
        Alias("shengqi", "😡", SemanticCandidateKind.EMOJI),
        Alias("ai", "❤️", SemanticCandidateKind.EMOJI),
        Alias("zan", "👍", SemanticCandidateKind.EMOJI),
        Alias("guzhang", "👏", SemanticCandidateKind.EMOJI),
        Alias("jiayou", "💪", SemanticCandidateKind.EMOJI),
        Alias("qingzhu", "🎉", SemanticCandidateKind.EMOJI),
        Alias("shengri", "🎂", SemanticCandidateKind.EMOJI),
        Alias("liwu", "🎁", SemanticCandidateKind.EMOJI),
        Alias("huo", "🔥", SemanticCandidateKind.EMOJI),
        Alias("xingxing", "⭐", SemanticCandidateKind.EMOJI),
        Alias("taiyang", "☀️", SemanticCandidateKind.EMOJI),
        Alias("yueliang", "🌙", SemanticCandidateKind.EMOJI),
        Alias("yu", "🌧️", SemanticCandidateKind.EMOJI),
        Alias("xue", "❄️", SemanticCandidateKind.EMOJI),
        Alias("mao", "🐱", SemanticCandidateKind.EMOJI),
        Alias("gou", "🐶", SemanticCandidateKind.EMOJI),
        Alias("zhu", "🐷", SemanticCandidateKind.EMOJI),
        Alias("niu", "🐮", SemanticCandidateKind.EMOJI),
        Alias("yang", "🐑", SemanticCandidateKind.EMOJI),
        Alias("ma", "🐴", SemanticCandidateKind.EMOJI),
        Alias("tu", "🐰", SemanticCandidateKind.EMOJI),
        Alias("long", "🐉", SemanticCandidateKind.EMOJI),
        Alias("she", "🐍", SemanticCandidateKind.EMOJI),
        Alias("hou", "🐵", SemanticCandidateKind.EMOJI),
        Alias("pingguo", "🍎", SemanticCandidateKind.EMOJI),
        Alias("hanbao", "🍔", SemanticCandidateKind.EMOJI),
        Alias("kafei", "☕", SemanticCandidateKind.EMOJI),
        Alias("cha", "🍵", SemanticCandidateKind.EMOJI),
        Alias("che", "🚗", SemanticCandidateKind.EMOJI),
        Alias("feiji", "✈️", SemanticCandidateKind.EMOJI),
        Alias("huoche", "🚆", SemanticCandidateKind.EMOJI),
        Alias("shouji", "📱", SemanticCandidateKind.EMOJI),
        Alias("diannao", "💻", SemanticCandidateKind.EMOJI),
        Alias("qian", "💰", SemanticCandidateKind.EMOJI),

        // A restrained set of common mathematical/physical symbols.
        Alias("shang", "↑", SemanticCandidateKind.SYMBOL),
        Alias("xia", "↓", SemanticCandidateKind.SYMBOL),
        Alias("zuoyou", "↔", SemanticCandidateKind.SYMBOL),
        Alias("shangxia", "↕", SemanticCandidateKind.SYMBOL),
        Alias("dayu", ">", SemanticCandidateKind.SYMBOL),
        Alias("xiaoyu", "<", SemanticCandidateKind.SYMBOL),
        Alias("dengyu", "=", SemanticCandidateKind.SYMBOL),
        Alias("budengyu", "≠", SemanticCandidateKind.SYMBOL),
        Alias("yuedengyu", "≈", SemanticCandidateKind.SYMBOL),
        Alias("zhengfu", "±", SemanticCandidateKind.SYMBOL),
        Alias("cheng", "×", SemanticCandidateKind.SYMBOL),
        Alias("chu", "÷", SemanticCandidateKind.SYMBOL),
        Alias("genhao", "√", SemanticCandidateKind.SYMBOL),
        Alias("wujiong", "∞", SemanticCandidateKind.SYMBOL),
        Alias("qiuhe", "∑", SemanticCandidateKind.SYMBOL),
        Alias("jifen", "∫", SemanticCandidateKind.SYMBOL),
        Alias("shuyu", "∈", SemanticCandidateKind.SYMBOL),
        Alias("jiaoji", "∩", SemanticCandidateKind.SYMBOL),
        Alias("bingji", "∪", SemanticCandidateKind.SYMBOL),
        Alias("du", "°", SemanticCandidateKind.SYMBOL),
        Alias("sheshidu", "℃", SemanticCandidateKind.SYMBOL),
        Alias("qianfenhao", "‰", SemanticCandidateKind.SYMBOL),
    )

    private val byPinyin: Map<String, List<Alias>> = aliases.groupBy(Alias::pinyin)

    fun suggest(
        composing: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<SemanticCandidateSuggestion> {
        if (limit <= 0) return emptyList()
        val query = composing.lowercase()
        if (query.isEmpty() || query.any { it !in 'a'..'z' }) return emptyList()
        return byPinyin[query]
            .orEmpty()
            .asSequence()
            .distinctBy(Alias::text)
            .take(limit)
            .mapIndexed { index, alias ->
                SemanticCandidateSuggestion(
                    candidate = Candidate(
                        text = alias.text,
                        score = SEMANTIC_SCORE_BASE - index,
                        canonicalPinyin = query,
                        // BASE_PREFIX already has weak/non-primary ranking
                        // semantics and cannot be learned for non-Han output.
                        matchKind = CandidateMatchKind.BASE_PREFIX,
                    ),
                    kind = alias.kind,
                    preferredInsertionIndex = LATE_FIRST_ROW_INDEX,
                )
            }
            .toList()
    }

    private const val DEFAULT_LIMIT = 8
    private const val SEMANTIC_SCORE_BASE = -100f
    private const val LATE_FIRST_ROW_INDEX = 6
}

/** Deterministic interleaving that preserves every primary candidate's order. */
object SemanticCandidateMixer {
    fun merge(
        primary: List<Candidate>,
        semantic: List<SemanticCandidateSuggestion>,
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        if (semantic.isEmpty()) return primary.take(limit)

        val result = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        val primaryTexts = primary.asSequence().map(Candidate::text).toHashSet()
        val pending = semantic
            .asSequence()
            .filter { it.candidate.text !in primaryTexts }
            .groupBy { it.preferredInsertionIndex.coerceAtLeast(0) }
        val lastPrimaryIndex = minOf(primary.size, limit)
        for (index in 0..lastPrimaryIndex) {
            pending[index]?.forEach { suggestion ->
                if (result.size < limit && seen.add(suggestion.candidate.text)) {
                    result += suggestion.candidate
                }
            }
            if (index < lastPrimaryIndex) {
                val candidate = primary[index]
                if (result.size < limit && seen.add(candidate.text)) result += candidate
            }
        }
        // If the preferred position lies beyond the available primary list,
        // place the semantic value at the tail rather than dropping it.
        pending.asSequence()
            .filter { (index, _) -> index > lastPrimaryIndex }
            .sortedBy { (index, _) -> index }
            .flatMap { (_, values) -> values.asSequence() }
            .forEach { suggestion ->
                if (result.size < limit && seen.add(suggestion.candidate.text)) {
                    result += suggestion.candidate
                }
            }
        return result
    }
}
