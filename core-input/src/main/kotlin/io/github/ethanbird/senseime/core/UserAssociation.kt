package io.github.ethanbird.senseime.core

import kotlin.math.exp
import kotlin.math.ln

data class LearnedAssociation(
    val context: String,
    val nextText: String,
    val useCount: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    /** Runtime-only score populated by [UserAssociationLexicon.lookup]. */
    val rankingScore: Float = 0f,
)

interface UserAssociationLexicon : AutoCloseable {
    fun lookup(context: String, limit: Int): List<LearnedAssociation>
    fun record(context: String, nextText: String): LearnedAssociation
    override fun close() = Unit
}

class MemoryUserAssociationLexicon(
    initial: Collection<LearnedAssociation> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (LearnedAssociation) -> Unit = {},
    private val onRemove: (context: String, nextText: String) -> Unit = { _, _ -> },
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
    private val maximumPerContext: Int = DEFAULT_MAXIMUM_PER_CONTEXT,
) : UserAssociationLexicon {
    private val records = LinkedHashMap<Pair<String, String>, LearnedAssociation>()
    private val contextIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private var latestAssignedUsedAtMillis = Long.MIN_VALUE

    init {
        require(maximumRecords > 0)
        require(maximumPerContext > 0)
        initial.forEach(::restore)
        trimToBudgets()
    }

    @Synchronized
    override fun lookup(context: String, limit: Int): List<LearnedAssociation> {
        if (limit <= 0) return emptyList()
        val normalized = normalize(context)
        val keys = contextIndex[normalized].orEmpty()
        if (keys.isEmpty()) return emptyList()
        val now = clock()
        return keys.asSequence()
            .mapNotNull(records::get)
            .map { it.copy(rankingScore = associationScore(it, now)) }
            .sortedWith(
                compareByDescending<LearnedAssociation> { it.rankingScore }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastUsedAtMillis }
                    .thenBy { it.nextText },
            )
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun record(context: String, nextText: String): LearnedAssociation {
        val normalizedContext = normalize(context)
        val normalizedNext = normalize(nextText)
        require(normalizedContext.isNotEmpty() && normalizedNext.isNotEmpty())
        val key = normalizedContext to normalizedNext
        val previous = records[key]
        val now = nextUsedAtMillis()
        val value = LearnedAssociation(
            context = normalizedContext,
            nextText = normalizedNext,
            useCount = (previous?.useCount ?: 0) + 1,
            createdAtMillis = previous?.createdAtMillis ?: now,
            lastUsedAtMillis = now,
        )
        records[key] = value
        contextIndex.getOrPut(normalizedContext) { LinkedHashSet() } += key
        onRecord(value)
        trimToBudgets(normalizedContext)
        return value
    }

    private fun restore(value: LearnedAssociation) {
        val context = normalize(value.context)
        val next = normalize(value.nextText)
        if (context.isEmpty() || next.isEmpty() || value.useCount <= 0) return
        val restored = value.copy(
            context = context,
            nextText = next,
            createdAtMillis = value.createdAtMillis.coerceAtLeast(0L),
            lastUsedAtMillis = value.lastUsedAtMillis.coerceAtLeast(0L),
            rankingScore = 0f,
        )
        val key = context to next
        records[key] = restored
        contextIndex.getOrPut(context) { LinkedHashSet() } += key
        latestAssignedUsedAtMillis = maxOf(latestAssignedUsedAtMillis, restored.lastUsedAtMillis)
    }

    private fun trimToBudgets(changedContext: String? = null) {
        val contexts = changedContext?.let(::listOf) ?: contextIndex.keys.toList()
        contexts.forEach { context ->
            val keys = contextIndex[context] ?: return@forEach
            while (keys.size > maximumPerContext) {
                weakest(keys)?.let(::remove) ?: break
            }
        }
        while (records.size > maximumRecords) {
            weakest(records.keys)?.let(::remove) ?: break
        }
    }

    private fun weakest(keys: Collection<Pair<String, String>>): Pair<String, String>? {
        val now = clock()
        return keys.minWithOrNull(
            compareBy<Pair<String, String>> { records[it]?.let { value -> associationScore(value, now) } ?: -1f }
                .thenBy { records[it]?.lastUsedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.second },
        )
    }

    private fun remove(key: Pair<String, String>) {
        val value = records.remove(key) ?: return
        val keys = contextIndex[value.context] ?: return
        keys.remove(key)
        if (keys.isEmpty()) contextIndex.remove(value.context)
        onRemove(value.context, value.nextText)
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

    private fun normalize(value: String): String = value.trim()

    private fun associationScore(value: LearnedAssociation, now: Long): Float {
        val frequency = ln(value.useCount.coerceAtLeast(1).toDouble() + 1.0).toFloat()
        val age = (now - value.lastUsedAtMillis).coerceAtLeast(0L)
        val recency = exp(-age.toDouble() / RECENCY_TAU_MILLIS.toDouble()).toFloat()
        return frequency + recency * 2f
    }

    private companion object {
        const val DEFAULT_MAXIMUM_RECORDS = 10_000
        const val DEFAULT_MAXIMUM_PER_CONTEXT = 16
        const val RECENCY_TAU_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    }
}

enum class AssociationSuggestionSource {
    USER_HISTORY,
    STATIC_CHARACTER_BIGRAM,
}

data class AssociationSuggestion(
    val text: String,
    val score: Float,
    val source: AssociationSuggestionSource,
)

/** Local, zero-model-token next-word engine used only while composition is empty. */
class LocalAssociationEngine(
    private val userLexicon: UserAssociationLexicon,
    private val characterBigrams: CharacterBigramModel,
) {
    fun suggest(
        leftContext: String,
        limit: Int = DEFAULT_LIMIT,
        includeUserHistory: Boolean = true,
    ): List<AssociationSuggestion> {
        if (limit <= 0 || leftContext.isBlank()) return emptyList()
        val values = LinkedHashMap<String, AssociationSuggestion>()
        if (includeUserHistory) {
            contextSuffixes(leftContext).forEachIndexed { suffixRank, context ->
                userLexicon.lookup(context, limit * 2).forEach { learned ->
                    val suggestion = AssociationSuggestion(
                        text = learned.nextText,
                        score = USER_SOURCE_BONUS + learned.rankingScore - suffixRank * SUFFIX_PENALTY,
                        source = AssociationSuggestionSource.USER_HISTORY,
                    )
                    val previous = values[suggestion.text]
                    if (previous == null || suggestion.score > previous.score) {
                        values[suggestion.text] = suggestion
                    }
                }
            }
        }

        val lastCodePoint = leftContext.codePointBefore(leftContext.length)
        characterBigrams.successors(lastCodePoint, limit * 3).forEach { successor ->
            if (Character.UnicodeScript.of(successor.codePoint) != Character.UnicodeScript.HAN) {
                return@forEach
            }
            val text = String(Character.toChars(successor.codePoint))
            values.putIfAbsent(
                text,
                AssociationSuggestion(
                    text = text,
                    score = successor.score,
                    source = AssociationSuggestionSource.STATIC_CHARACTER_BIGRAM,
                ),
            )
        }
        return values.values.sortedWith(
            compareByDescending<AssociationSuggestion> { it.score }
                .thenBy { it.text },
        ).take(limit)
    }

    fun observe(context: String, nextText: String): LearnedAssociation? {
        if (!isAssociationUnit(context) || !isAssociationUnit(nextText)) return null
        return userLexicon.record(context, nextText)
    }

    private fun contextSuffixes(value: String): List<String> {
        val total = value.codePointCount(0, value.length)
        val maximum = minOf(total, MAX_CONTEXT_CODE_POINTS)
        return (maximum downTo 1).map { count ->
            value.substring(value.offsetByCodePoints(value.length, -count))
        }
    }

    private fun isAssociationUnit(value: String): Boolean {
        if (value.isBlank()) return false
        val count = value.codePointCount(0, value.length)
        if (count !in 1..MAX_ASSOCIATION_UNIT_CODE_POINTS) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_CONTEXT_CODE_POINTS = 8
        const val MAX_ASSOCIATION_UNIT_CODE_POINTS = 8
        // A fresh observation can lead a static edge, but a one-off association
        // naturally falls behind strong language-model evidence as recency fades.
        const val USER_SOURCE_BONUS = 0.75f
        const val SUFFIX_PENALTY = 0.05f
    }
}
