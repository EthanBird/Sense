package io.github.ethanbird.senseime.core

import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

enum class WubiSelectionKind(
    internal val baseStrength: Float,
    internal val rankScale: Float,
    internal val maximumRankBonus: Float,
) {
    /** Space or punctuation accepted the current first candidate. */
    DEFAULT_ACCEPT(baseStrength = 0.18f, rankScale = 0.05f, maximumRankBonus = 0.15f),

    /** The user deliberately chose a candidate whose complete code was entered. */
    EXPLICIT_SELECTION(baseStrength = 1.75f, rankScale = 0.40f, maximumRankBonus = 1.25f),

    /** The user deliberately chose a completion before entering its complete code. */
    COMPLETION_SELECTION(baseStrength = 1.15f, rankScale = 0.25f, maximumRankBonus = 0.75f),
}

/**
 * Scheme-local selection evidence. A deeper explicit choice is more informative, while the
 * logarithmic and absolute caps prevent one event from producing an unbounded preference.
 */
data class WubiLearningEvidence(
    val kind: WubiSelectionKind,
    val selectedRank: Int = 0,
) {
    init {
        require(selectedRank >= 0)
    }

    val strength: Float
        get() {
            val boundedRank = selectedRank.coerceAtMost(MAX_EVIDENCE_RANK)
            val rankInformation = ln(boundedRank.toDouble() + 1.0).toFloat()
            return (
                kind.baseStrength +
                    (rankInformation * kind.rankScale).coerceAtMost(kind.maximumRankBonus)
                ).coerceAtMost(MAX_EVENT_STRENGTH)
        }

    companion object {
        const val MAX_EVENT_STRENGTH = 3f
        private const val MAX_EVIDENCE_RANK = 255

        val DEFAULT_ACCEPT = WubiLearningEvidence(WubiSelectionKind.DEFAULT_ACCEPT)
        val EXPLICIT_SELECTION = WubiLearningEvidence(WubiSelectionKind.EXPLICIT_SELECTION)
        val COMPLETION_SELECTION = WubiLearningEvidence(WubiSelectionKind.COMPLETION_SELECTION)
    }
}

enum class WubiNegativeFeedback(val strength: Float) {
    QUICK_DELETE(3.5f),
    IMMEDIATE_REPLACEMENT(4f),
    MANUAL_DEMOTION(2f),
}

data class LearnedWubiCandidate(
    /** Complete, canonical Wubi86 code. Prefixes are derived in memory and are never persisted. */
    val canonicalCode: String,
    val text: String,
    val useCount: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val positiveEvidence: Float = useCount.toFloat(),
    val negativeEvidence: Float = 0f,
    val lastPositiveEvidence: Float = LEGACY_WUBI_EVENT_EVIDENCE,
    val lastNegativeAtMillis: Long = 0L,
    /** Runtime-only score; durable storage persists the evidence fields instead. */
    val rankingBoost: Float = 0f,
)

sealed interface WubiUserLexiconMutation {
    data class Upsert(val candidate: LearnedWubiCandidate) : WubiUserLexiconMutation
    data class Delete(val canonicalCode: String, val text: String) : WubiUserLexiconMutation
}

data class WubiUserLexiconLimits(
    val maximumRecords: Int = 8_192,
    val maximumRecordsPerCanonicalCode: Int = 32,
    val maximumIndexedRecordsPerPrefix: Int = 128,
    val maximumTextLength: Int = 64,
) {
    init {
        require(maximumRecords > 0)
        require(maximumRecordsPerCanonicalCode > 0)
        require(maximumIndexedRecordsPerPrefix > 0)
        require(maximumTextLength > 0)
    }
}

interface WubiUserLexicon : AutoCloseable {
    /** Returns only records whose canonical code starts with [prefix]. */
    fun lookup(prefix: String, limit: Int): List<LearnedWubiCandidate>

    fun record(
        canonicalCode: String,
        text: String,
        evidence: WubiLearningEvidence = WubiLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedWubiCandidate

    fun demote(
        canonicalCode: String,
        text: String,
        feedback: WubiNegativeFeedback = WubiNegativeFeedback.MANUAL_DEMOTION,
    ): LearnedWubiCandidate?

    fun forget(canonicalCode: String, text: String): Boolean

    override fun close() = Unit
}

/**
 * Bounded Wubi-only preference index.
 *
 * Decode-time lookup is a synchronized in-memory hash lookup over at most
 * [WubiUserLexiconLimits.maximumIndexedRecordsPerPrefix] keys. SQLite or other durable storage is
 * represented only by the mutation callback, which is expected to enqueue an immutable snapshot.
 */
class MemoryWubiUserLexicon(
    initial: Collection<LearnedWubiCandidate> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMutation: (WubiUserLexiconMutation) -> Unit = {},
    private val limits: WubiUserLexiconLimits = WubiUserLexiconLimits(),
) : WubiUserLexicon {
    private data class Key(val canonicalCode: String, val text: String)

    private val records = LinkedHashMap<Key, LearnedWubiCandidate>()
    private val canonicalIndex = HashMap<String, MutableSet<Key>>()
    private val prefixIndex = HashMap<String, MutableSet<Key>>()
    private var prefixIndexReady = false
    private var latestAssignedUsedAtMillis = Long.MIN_VALUE

    init {
        initial.forEach(::restore)
        trimToBudgets()
        rebuildPrefixIndex()
        prefixIndexReady = true
    }

    @Synchronized
    override fun lookup(prefix: String, limit: Int): List<LearnedWubiCandidate> {
        if (limit <= 0) return emptyList()
        val normalized = normalizeWubiCode(prefix) ?: return emptyList()
        val keys = prefixIndex[normalized] ?: return emptyList()
        if (keys.isEmpty()) return emptyList()
        val now = clock()
        val ranking = learnedCandidateOrder(normalized)
        val selected = PriorityQueue<LearnedWubiCandidate>(
            minOf(limit, keys.size).coerceAtLeast(1),
            ranking.reversed(),
        )
        keys.forEach { key ->
            val candidate = records[key] ?: return@forEach
            val scored = candidate.copy(
                rankingBoost = WubiPersonalizationScoring.rankingBoost(candidate, now),
            )
            if (selected.size < limit) {
                selected += scored
            } else if (ranking.compare(scored, selected.peek()) < 0) {
                selected.poll()
                selected += scored
            }
        }
        return selected.toList().sortedWith(ranking)
    }

    @Synchronized
    override fun record(
        canonicalCode: String,
        text: String,
        evidence: WubiLearningEvidence,
    ): LearnedWubiCandidate {
        val code = requireWubiCode(canonicalCode)
        requireValidText(text)
        val key = Key(code, text)
        val previous = records[key]
        val now = nextUsedAtMillis()
        val eventStrength = evidence.strength
        val value = LearnedWubiCandidate(
            canonicalCode = code,
            text = text,
            useCount = previous?.useCount.saturatedIncrement(),
            createdAtMillis = previous?.createdAtMillis ?: now,
            lastUsedAtMillis = now,
            positiveEvidence = (
                previous
                    ?.let { WubiPersonalizationScoring.retainedPositiveEvidence(it, now) }
                    .orZero() + eventStrength
                ).coerceAtMost(WubiPersonalizationScoring.MAX_ACCUMULATED_EVIDENCE),
            negativeEvidence = previous
                ?.let { WubiPersonalizationScoring.retainedNegativeEvidence(it, now) }
                .orZero(),
            lastPositiveEvidence = eventStrength,
            lastNegativeAtMillis = previous
                ?.takeIf { it.lastNegativeAtMillis > 0L && it.negativeEvidence > 0f }
                ?.let { now }
                ?: 0L,
        )
        records[key] = value
        if (previous == null) {
            canonicalIndex.getOrPut(code) { LinkedHashSet() } += key
        }
        onMutation(WubiUserLexiconMutation.Upsert(value))
        trimToBudgets(changedCanonicalCode = code)
        if (records.containsKey(key)) addToPrefixIndex(key)
        return value
    }

    @Synchronized
    override fun demote(
        canonicalCode: String,
        text: String,
        feedback: WubiNegativeFeedback,
    ): LearnedWubiCandidate? {
        val code = normalizeWubiCode(canonicalCode) ?: return null
        val key = Key(code, text)
        val previous = records[key] ?: return null
        val now = nextUsedAtMillis()
        val value = previous.copy(
            negativeEvidence = (
                WubiPersonalizationScoring.retainedNegativeEvidence(previous, now) +
                    feedback.strength
                ).coerceAtMost(WubiPersonalizationScoring.MAX_ACCUMULATED_EVIDENCE),
            lastNegativeAtMillis = now,
            rankingBoost = 0f,
        )
        records[key] = value
        onMutation(WubiUserLexiconMutation.Upsert(value))
        prefixesOf(code).forEach(::rebuildPrefix)
        return value
    }

    @Synchronized
    override fun forget(canonicalCode: String, text: String): Boolean {
        val code = normalizeWubiCode(canonicalCode) ?: return false
        return removeRecord(Key(code, text))
    }

    private fun restore(candidate: LearnedWubiCandidate) {
        val code = normalizeWubiCode(candidate.canonicalCode) ?: return
        if (!isValidText(candidate.text)) return
        val restored = candidate.copy(
            canonicalCode = code,
            useCount = candidate.useCount.coerceAtLeast(1),
            createdAtMillis = candidate.createdAtMillis.coerceAtLeast(0L),
            lastUsedAtMillis = candidate.lastUsedAtMillis.coerceAtLeast(0L),
            positiveEvidence = candidate.positiveEvidence
                .takeIf { it.isFinite() && it >= 0f }
                ?.coerceAtMost(WubiPersonalizationScoring.MAX_ACCUMULATED_EVIDENCE)
                ?: candidate.useCount.coerceAtLeast(1).toFloat()
                    .coerceAtMost(WubiPersonalizationScoring.MAX_ACCUMULATED_EVIDENCE),
            negativeEvidence = candidate.negativeEvidence
                .takeIf { it.isFinite() && it >= 0f }
                ?.coerceAtMost(WubiPersonalizationScoring.MAX_ACCUMULATED_EVIDENCE)
                ?: 0f,
            lastPositiveEvidence = candidate.lastPositiveEvidence
                .takeIf { it.isFinite() && it >= 0f }
                ?.coerceAtMost(WubiLearningEvidence.MAX_EVENT_STRENGTH)
                ?: LEGACY_WUBI_EVENT_EVIDENCE,
            lastNegativeAtMillis = candidate.lastNegativeAtMillis.coerceAtLeast(0L),
            rankingBoost = 0f,
        )
        val key = Key(code, restored.text)
        records[key] = restored
        canonicalIndex.getOrPut(code) { LinkedHashSet() } += key
        latestAssignedUsedAtMillis = maxOf(latestAssignedUsedAtMillis, restored.lastUsedAtMillis)
    }

    private fun trimToBudgets(changedCanonicalCode: String? = null) {
        val canonicalCodes = changedCanonicalCode?.let(::listOf) ?: canonicalIndex.keys.toList()
        canonicalCodes.forEach { code ->
            while ((canonicalIndex[code]?.size ?: 0) > limits.maximumRecordsPerCanonicalCode) {
                val weakest = weakestKey(canonicalIndex[code].orEmpty()) ?: break
                removeRecord(weakest)
            }
        }
        while (records.size > limits.maximumRecords) {
            val weakest = weakestKey(records.keys) ?: break
            removeRecord(weakest)
        }
    }

    private fun rebuildPrefixIndex() {
        prefixIndex.clear()
        records.keys.forEach(::addToPrefixIndex)
    }

    private fun rebuildPrefix(prefix: String) {
        if (!prefixIndexReady) return
        val now = clock()
        val matching = records.keys
            .asSequence()
            .filter { it.canonicalCode.startsWith(prefix) }
            .sortedWith(
                compareByDescending<Key> {
                    records[it]?.let { candidate ->
                        WubiPersonalizationScoring.rankingBoost(candidate, now)
                    } ?: Float.NEGATIVE_INFINITY
                }
                    .thenByDescending { records[it]?.lastUsedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.canonicalCode }
                    .thenBy { it.text },
            )
            .take(limits.maximumIndexedRecordsPerPrefix)
            .toCollection(LinkedHashSet())
        if (matching.isEmpty()) prefixIndex.remove(prefix) else prefixIndex[prefix] = matching
    }

    private fun addToPrefixIndex(key: Key) {
        prefixesOf(key.canonicalCode).forEach { prefix ->
            val entries = prefixIndex.getOrPut(prefix) { LinkedHashSet() }
            entries += key
            if (entries.size > limits.maximumIndexedRecordsPerPrefix) {
                weakestKey(entries)?.let(entries::remove)
            }
            if (entries.isEmpty()) prefixIndex.remove(prefix)
        }
    }

    private fun removeRecord(key: Key): Boolean {
        val previous = records.remove(key) ?: return false
        val canonicalEntries = canonicalIndex[previous.canonicalCode]
        canonicalEntries?.remove(key)
        if (canonicalEntries?.isEmpty() == true) canonicalIndex.remove(previous.canonicalCode)
        val prefixes = prefixesOf(previous.canonicalCode).toList()
        prefixes.forEach { prefix ->
            val entries = prefixIndex[prefix] ?: return@forEach
            entries.remove(key)
            if (entries.isEmpty()) prefixIndex.remove(prefix)
        }
        onMutation(WubiUserLexiconMutation.Delete(previous.canonicalCode, previous.text))
        if (prefixIndexReady) prefixes.forEach(::rebuildPrefix)
        return true
    }

    private fun weakestKey(keys: Collection<Key>): Key? {
        val now = clock()
        return keys.minWithOrNull(
            compareBy<Key> {
                records[it]?.let { candidate ->
                    WubiPersonalizationScoring.rankingBoost(candidate, now)
                } ?: Float.NEGATIVE_INFINITY
            }
                .thenBy { records[it]?.lastUsedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.canonicalCode }
                .thenByDescending { it.text },
        )
    }

    private fun learnedCandidateOrder(prefix: String): Comparator<LearnedWubiCandidate> =
        compareByDescending<LearnedWubiCandidate> { it.rankingBoost }
            .thenByDescending { it.canonicalCode == prefix }
            .thenByDescending { it.useCount }
            .thenByDescending { it.lastUsedAtMillis }
            .thenBy { it.canonicalCode }
            .thenBy { it.text }

    private fun nextUsedAtMillis(): Long {
        val observed = clock().coerceAtLeast(0L)
        val assigned = when {
            latestAssignedUsedAtMillis == Long.MIN_VALUE -> observed
            observed > latestAssignedUsedAtMillis -> observed
            latestAssignedUsedAtMillis < Long.MAX_VALUE -> latestAssignedUsedAtMillis + 1L
            else -> Long.MAX_VALUE
        }
        latestAssignedUsedAtMillis = assigned
        return assigned
    }

    private fun requireWubiCode(value: String): String =
        requireNotNull(normalizeWubiCode(value)) { "Invalid canonical Wubi86 code" }

    private fun requireValidText(value: String) {
        require(isValidText(value)) { "Wubi candidate text is empty or too long" }
    }

    private fun isValidText(value: String): Boolean =
        value.isNotEmpty() && value.length <= limits.maximumTextLength

    private fun Int?.saturatedIncrement(): Int = when (this) {
        null -> 1
        Int.MAX_VALUE -> Int.MAX_VALUE
        else -> this + 1
    }

    private fun Float?.orZero(): Float = this ?: 0f
}

internal fun normalizeWubiCode(value: String): String? {
    if (value.length !in 1..WubiComposition.MAX_CODE_LENGTH) return null
    var changed = false
    val characters = CharArray(value.length)
    value.forEachIndexed { index, character ->
        val normalized = when (character) {
            in 'a'..'y' -> character
            in 'A'..'Y' -> (character.code + ('a'.code - 'A'.code)).toChar().also { changed = true }
            else -> return null
        }
        characters[index] = normalized
    }
    return if (changed) String(characters) else value
}

private fun prefixesOf(code: String): Sequence<String> =
    (1..code.length).asSequence().map { length -> code.take(length) }

private object WubiPersonalizationScoring {
    const val MAX_ACCUMULATED_EVIDENCE = 10_000f
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    private const val POSITIVE_HALF_LIFE_DAYS = 21.0
    private const val NEGATIVE_HALF_LIFE_DAYS = 7.0
    private const val LONG_TERM_FREQUENCY_FLOOR = 0.25f
    private const val MAX_FREQUENCY_BONUS = 2f
    private const val MAX_RECENCY_BONUS = 4f
    private const val MAX_RANKING_BOOST = 5.5f

    fun rankingBoost(candidate: LearnedWubiCandidate, nowMillis: Long): Float {
        val positive = candidate.positiveEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val negative = candidate.negativeEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val positiveRecency = decay(nowMillis, candidate.lastUsedAtMillis, POSITIVE_HALF_LIFE_DAYS)
        val negativeRecency = if (candidate.lastNegativeAtMillis > 0L) {
            decay(nowMillis, candidate.lastNegativeAtMillis, NEGATIVE_HALF_LIFE_DAYS)
        } else {
            0f
        }
        val retainedPositive = positive * (
            LONG_TERM_FREQUENCY_FLOOR + (1f - LONG_TERM_FREQUENCY_FLOOR) * positiveRecency
            )
        val frequencyBonus = (
            0.72f * ln(retainedPositive.toDouble() + 1.0).toFloat()
            ).coerceAtMost(MAX_FREQUENCY_BONUS)
        val lastEvent = candidate.lastPositiveEvidence
            .takeIf { it.isFinite() && it > 0f }
            ?: LEGACY_WUBI_EVENT_EVIDENCE
        val recencyBonus = (lastEvent * 1.3f)
            .coerceAtMost(MAX_RECENCY_BONUS) * positiveRecency
        val negativePenalty = (negative * negativeRecency * NEGATIVE_EVIDENCE_SCALE)
            .coerceAtMost(MAX_RANKING_BOOST)
        return (frequencyBonus + recencyBonus - negativePenalty)
            .coerceIn(-MAX_RANKING_BOOST, MAX_RANKING_BOOST)
    }

    fun retainedPositiveEvidence(candidate: LearnedWubiCandidate, nowMillis: Long): Float {
        val positive = candidate.positiveEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val recency = decay(nowMillis, candidate.lastUsedAtMillis, POSITIVE_HALF_LIFE_DAYS)
        return positive * (
            LONG_TERM_FREQUENCY_FLOOR + (1f - LONG_TERM_FREQUENCY_FLOOR) * recency
            )
    }

    fun retainedNegativeEvidence(candidate: LearnedWubiCandidate, nowMillis: Long): Float {
        if (candidate.lastNegativeAtMillis <= 0L) return 0f
        return candidate.negativeEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE) *
            decay(nowMillis, candidate.lastNegativeAtMillis, NEGATIVE_HALF_LIFE_DAYS)
    }

    private fun decay(nowMillis: Long, eventMillis: Long, halfLifeDays: Double): Float {
        if (eventMillis <= 0L) return 0f
        if (nowMillis <= eventMillis) return 1f
        val ageDays = (nowMillis.toDouble() - eventMillis.toDouble())
            .coerceAtLeast(0.0) / DAY_MILLIS.toDouble()
        return exp(-ageDays * LN_2 / halfLifeDays).toFloat().coerceIn(0f, 1f)
    }

    private const val NEGATIVE_EVIDENCE_SCALE = 1.15f
    private const val LN_2 = 0.6931471805599453
}

private const val LEGACY_WUBI_EVENT_EVIDENCE = 0.18f
