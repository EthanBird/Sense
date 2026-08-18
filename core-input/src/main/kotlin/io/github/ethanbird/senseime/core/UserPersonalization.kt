package io.github.ethanbird.senseime.core

import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

enum class UserSelectionKind {
    /** Space, punctuation, or another implicit action accepted the current primary candidate. */
    DEFAULT_ACCEPT,

    /** The user deliberately selected a whole candidate from the candidate surface. */
    EXPLICIT_SELECTION,

    /** The user selected a leading segment while the rest of the pinyin remained composing. */
    PROGRESSIVE_SELECTION,

    /** The user confirmed a phrase assembled from multiple explicitly accepted segments. */
    COMPOSED_CONFIRM,
}

/**
 * Evidence strength is intentionally derived inside the lexicon rather than by the UI.
 *
 * [selectedRank] is zero-based in the decoder result. Selecting a deep candidate carries more
 * information than confirming rank zero, but the logarithmic cap prevents one event from creating
 * an unbounded score.
 */
data class UserLearningEvidence(
    val kind: UserSelectionKind,
    val selectedRank: Int = 0,
) {
    init {
        require(selectedRank >= 0)
    }

    companion object {
        val DEFAULT_ACCEPT = UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT)
        val EXPLICIT_SELECTION = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION)
        val PROGRESSIVE_SELECTION = UserLearningEvidence(UserSelectionKind.PROGRESSIVE_SELECTION)
        val COMPOSED_CONFIRM = UserLearningEvidence(UserSelectionKind.COMPOSED_CONFIRM)
    }
}

enum class UserNegativeFeedback {
    QUICK_DELETE,
    IMMEDIATE_REPLACEMENT,
    MANUAL_DEMOTION,
}

data class LearnedPhrase(
    val fullPinyin: String,
    val initials: String,
    val text: String,
    val useCount: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val aliases: Set<String> = emptySet(),
    /** Weighted positive observations. Legacy rows default to their historical use count. */
    val positiveEvidence: Float = useCount.toFloat(),
    val negativeEvidence: Float = 0f,
    /** Strength of the latest positive event, used by the bounded recency component. */
    val lastPositiveEvidence: Float = LEGACY_EVENT_EVIDENCE,
    val lastNegativeAtMillis: Long = 0L,
    /** Runtime-only bounded ranking adjustment populated by [UserLexicon.lookup]. */
    val rankingBoost: Float = 0f,
)

interface UserLexicon : AutoCloseable {
    fun lookup(code: String, limit: Int): List<LearnedPhrase>
    fun record(
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String> = emptySet(),
        evidence: UserLearningEvidence = UserLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedPhrase
    fun demote(
        fullPinyin: String,
        text: String,
        feedback: UserNegativeFeedback = UserNegativeFeedback.MANUAL_DEMOTION,
    ): LearnedPhrase?
    fun forget(fullPinyin: String, text: String): Boolean
    override fun close() = Unit
}

class MemoryUserLexicon(
    initial: Collection<LearnedPhrase> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (LearnedPhrase) -> Unit = {},
    private val onForget: (fullPinyin: String, text: String) -> Unit = { _, _ -> },
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
    private val maximumRecordsPerFullPinyin: Int = DEFAULT_MAXIMUM_RECORDS_PER_FULL_PINYIN,
    private val maximumAliasesPerRecord: Int = DEFAULT_MAXIMUM_ALIASES_PER_RECORD,
    private val maximumRecordsPerLookupCode: Int = DEFAULT_MAXIMUM_RECORDS_PER_LOOKUP_CODE,
) : UserLexicon {
    private val records = LinkedHashMap<Pair<String, String>, LearnedPhrase>()
    private val fullIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private val initialsIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private val aliasIndex = HashMap<String, MutableSet<Pair<String, String>>>()
    private var latestAssignedUsedAtMillis = Long.MIN_VALUE

    init {
        require(maximumRecords > 0)
        require(maximumRecordsPerFullPinyin > 0)
        require(maximumAliasesPerRecord > 0)
        require(maximumRecordsPerLookupCode > 0)
        initial.forEach(::restore)
        trimToBudgets()
    }

    @Synchronized
    override fun lookup(code: String, limit: Int): List<LearnedPhrase> {
        if (limit <= 0) return emptyList()
        val normalized = PinyinSyllableSegmenter.normalize(code)
        val fullMatches = fullIndex[normalized]
        val initialsMatches = initialsIndex[normalized]
        val aliasMatches = aliasIndex[normalized]
        if (fullMatches == null && initialsMatches == null && aliasMatches == null) {
            return emptyList()
        }
        val keys = LinkedHashSet<Pair<String, String>>()
        fullMatches?.let(keys::addAll)
        initialsMatches?.let(keys::addAll)
        aliasMatches?.let(keys::addAll)
        if (keys.isEmpty()) return emptyList()
        val now = clock()
        val ranking = compareByDescending<LearnedPhrase> { it.rankingBoost }
            .thenByDescending { it.fullPinyin == normalized }
            .thenByDescending { it.initials == normalized }
            .thenByDescending { it.useCount }
            .thenByDescending { it.lastUsedAtMillis }
            .thenBy { it.text }
        val selected = PriorityQueue<LearnedPhrase>(
            minOf(limit, keys.size).coerceAtLeast(1),
            ranking.reversed(),
        )
        keys.forEach { key ->
            val phrase = records[key] ?: return@forEach
            val scored = phrase.copy(
                rankingBoost = PersonalizationScoring.rankingBoost(phrase, now),
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
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String>,
        evidence: UserLearningEvidence,
    ): LearnedPhrase {
        val full = PinyinSyllableSegmenter.normalize(fullPinyin)
        val short = PinyinSyllableSegmenter.normalize(initials)
        require(full.isNotEmpty() && short.isNotEmpty() && text.isNotEmpty())
        val now = nextUsedAtMillis()
        val key = full to text
        val previous = records[key]
        val normalizedAliases = boundedAliases(
            full = full,
            initials = short,
            current = aliases,
            retained = previous?.aliases.orEmpty(),
        )
        val evidenceStrength = PersonalizationScoring.positiveEvidence(evidence)
        val value = LearnedPhrase(
            fullPinyin = full,
            initials = short,
            text = text,
            useCount = (previous?.useCount ?: 0) + 1,
            createdAtMillis = previous?.createdAtMillis ?: now,
            lastUsedAtMillis = now,
            aliases = normalizedAliases,
            positiveEvidence = (
                previous
                    ?.let { PersonalizationScoring.retainedPositiveEvidence(it, now) }
                    .orZero() + evidenceStrength
            ).coerceAtMost(PersonalizationScoring.MAX_ACCUMULATED_EVIDENCE),
            negativeEvidence = previous
                ?.let { PersonalizationScoring.retainedNegativeEvidence(it, now) }
                .orZero(),
            lastPositiveEvidence = evidenceStrength,
            lastNegativeAtMillis = previous
                ?.takeIf { it.lastNegativeAtMillis > 0L && it.negativeEvidence > 0f }
                ?.let { now }
                ?: 0L,
        )
        records[key] = value
        if (previous == null) {
            fullIndex.getOrPut(full) { LinkedHashSet() } += key
        } else if (previous.initials != short) {
            removeIndexEntry(initialsIndex, previous.initials, key)
        }
        previous?.aliases?.forEach { alias -> removeIndexEntry(aliasIndex, alias, key) }
        addBoundedIndexEntry(initialsIndex, short, key)
        normalizedAliases.forEach { alias ->
            addBoundedIndexEntry(aliasIndex, alias, key)
        }
        onRecord(value)
        trimToBudgets(full)
        return value
    }

    @Synchronized
    override fun demote(
        fullPinyin: String,
        text: String,
        feedback: UserNegativeFeedback,
    ): LearnedPhrase? {
        val full = PinyinSyllableSegmenter.normalize(fullPinyin)
        val key = full to text
        val previous = records[key] ?: return null
        val now = nextUsedAtMillis()
        val value = previous.copy(
            negativeEvidence = (
                PersonalizationScoring.retainedNegativeEvidence(previous, now) +
                    PersonalizationScoring.negativeEvidence(feedback)
            ).coerceAtMost(PersonalizationScoring.MAX_ACCUMULATED_EVIDENCE),
            lastNegativeAtMillis = now,
            rankingBoost = 0f,
        )
        records[key] = value
        onRecord(value)
        return value
    }

    @Synchronized
    override fun forget(fullPinyin: String, text: String): Boolean {
        val full = PinyinSyllableSegmenter.normalize(fullPinyin)
        val key = full to text
        return removeRecord(key)
    }

    private fun restore(value: LearnedPhrase) {
        val normalizedAliases = boundedAliases(
            full = value.fullPinyin,
            initials = value.initials,
            current = value.aliases,
            retained = emptySet(),
        )
        val restored = value.copy(
            aliases = normalizedAliases,
            positiveEvidence = sanitizedPositiveEvidence(value),
            negativeEvidence = sanitizedNegativeEvidence(value),
            lastPositiveEvidence = value.lastPositiveEvidence
                .takeIf { it.isFinite() && it >= 0f }
                ?: LEGACY_EVENT_EVIDENCE,
            lastNegativeAtMillis = value.lastNegativeAtMillis.coerceAtLeast(0L),
            rankingBoost = 0f,
        )
        val key = restored.fullPinyin to restored.text
        records[key] = restored
        fullIndex.getOrPut(restored.fullPinyin) { LinkedHashSet() } += key
        addBoundedIndexEntry(initialsIndex, restored.initials, key)
        restored.aliases.forEach { alias ->
            addBoundedIndexEntry(aliasIndex, alias, key)
        }
        latestAssignedUsedAtMillis = maxOf(latestAssignedUsedAtMillis, restored.lastUsedAtMillis)
    }

    private fun sanitizedPositiveEvidence(value: LearnedPhrase?): Float =
        value
            ?.positiveEvidence
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: value?.useCount?.coerceAtLeast(0)?.toFloat()
            ?: 0f

    private fun sanitizedNegativeEvidence(value: LearnedPhrase?): Float =
        value?.negativeEvidence?.takeIf { it.isFinite() && it >= 0f } ?: 0f

    private fun removeIndexEntry(
        index: MutableMap<String, MutableSet<Pair<String, String>>>,
        code: String,
        key: Pair<String, String>,
    ) {
        val entries = index[code] ?: return
        entries.remove(key)
        if (entries.isEmpty()) index.remove(code)
    }

    private fun addBoundedIndexEntry(
        index: MutableMap<String, MutableSet<Pair<String, String>>>,
        code: String,
        key: Pair<String, String>,
    ) {
        val entries = index.getOrPut(code) { LinkedHashSet() }
        entries += key
        if (entries.size > maximumRecordsPerLookupCode) {
            weakestKey(entries)?.let(entries::remove)
        }
        if (entries.isEmpty()) index.remove(code)
    }

    private fun boundedAliases(
        full: String,
        initials: String,
        current: Collection<String>,
        retained: Collection<String>,
    ): Set<String> {
        val result = LinkedHashSet<String>(maximumAliasesPerRecord)
        sequenceOf(current, retained).forEach { source ->
            source
                .asSequence()
                .map(PinyinSyllableSegmenter::normalize)
                .filter { it.isNotEmpty() && it != full && it != initials }
                .distinct()
                .sortedWith(compareBy<String> { it.length }.thenBy { it })
                .forEach { alias ->
                    if (result.size < maximumAliasesPerRecord) result += alias
                }
        }
        return result
    }

    @Synchronized
    internal fun indexedCandidateCount(code: String): Int {
        val normalized = PinyinSyllableSegmenter.normalize(code)
        val keys = HashSet<Pair<String, String>>()
        fullIndex[normalized]?.let(keys::addAll)
        initialsIndex[normalized]?.let(keys::addAll)
        aliasIndex[normalized]?.let(keys::addAll)
        return keys.size
    }

    private fun trimToBudgets(changedFullPinyin: String? = null) {
        val fullCodes = changedFullPinyin
            ?.let(::listOf)
            ?: fullIndex.keys.toList()
        fullCodes.forEach { full ->
            while ((fullIndex[full]?.size ?: 0) > maximumRecordsPerFullPinyin) {
                val weakest = weakestKey(fullIndex[full].orEmpty()) ?: break
                removeRecord(weakest)
            }
        }
        while (records.size > maximumRecords) {
            val weakest = weakestKey(records.keys) ?: break
            removeRecord(weakest)
        }
    }

    private fun weakestKey(keys: Collection<Pair<String, String>>): Pair<String, String>? {
        val now = clock()
        return keys.minWithOrNull(
            compareBy<Pair<String, String>> {
                records[it]?.let { phrase ->
                    PersonalizationScoring.rankingBoost(phrase, now)
                } ?: Float.NEGATIVE_INFINITY
            }
                .thenBy { records[it]?.lastUsedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.second },
        )
    }

    private fun removeRecord(key: Pair<String, String>): Boolean {
        val previous = records.remove(key) ?: return false
        removeIndexEntry(fullIndex, previous.fullPinyin, key)
        removeIndexEntry(initialsIndex, previous.initials, key)
        previous.aliases.forEach { alias -> removeIndexEntry(aliasIndex, alias, key) }
        onForget(previous.fullPinyin, previous.text)
        return true
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

    private fun Float?.orZero(): Float = this ?: 0f

    private companion object {
        const val DEFAULT_MAXIMUM_RECORDS = 10_000
        const val DEFAULT_MAXIMUM_RECORDS_PER_FULL_PINYIN = 64
        const val DEFAULT_MAXIMUM_ALIASES_PER_RECORD = 16
        const val DEFAULT_MAXIMUM_RECORDS_PER_LOOKUP_CODE = 128
    }
}

/**
 * Small, deterministic personalization model whose result shares the base decoder's log-score
 * domain. Frequency, recency, and negative evidence are independently bounded so neither an old
 * habit nor a single interaction can permanently dominate the language model.
 */
private object PersonalizationScoring {
    const val MAX_ACCUMULATED_EVIDENCE = 10_000f
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    private const val POSITIVE_HALF_LIFE_DAYS = 14.0
    private const val NEGATIVE_HALF_LIFE_DAYS = 7.0
    private const val LONG_TERM_FREQUENCY_FLOOR = 0.25f
    private const val MAX_FREQUENCY_BONUS = 1.7f
    private const val MAX_RECENCY_BONUS = 3.0f
    private const val MAX_PERSONALIZATION_BONUS = 4.5f

    fun positiveEvidence(evidence: UserLearningEvidence): Float {
        val rank = evidence.selectedRank.coerceAtMost(255)
        val rankInformation = ln(rank.toDouble() + 1.0).toFloat()
        return when (evidence.kind) {
            UserSelectionKind.DEFAULT_ACCEPT -> LEGACY_EVENT_EVIDENCE
            UserSelectionKind.EXPLICIT_SELECTION ->
                1.75f + (rankInformation * 0.4f).coerceAtMost(1.25f)
            UserSelectionKind.PROGRESSIVE_SELECTION ->
                0.65f + (rankInformation * 0.15f).coerceAtMost(0.45f)
            UserSelectionKind.COMPOSED_CONFIRM ->
                1.35f + (rankInformation * 0.2f).coerceAtMost(0.65f)
        }
    }

    fun negativeEvidence(feedback: UserNegativeFeedback): Float = when (feedback) {
        UserNegativeFeedback.QUICK_DELETE -> 3.5f
        UserNegativeFeedback.IMMEDIATE_REPLACEMENT -> 4f
        UserNegativeFeedback.MANUAL_DEMOTION -> 2f
    }

    fun rankingBoost(phrase: LearnedPhrase, nowMillis: Long): Float {
        val positive = phrase.positiveEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val negative = phrase.negativeEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val positiveRecency = decay(nowMillis, phrase.lastUsedAtMillis, POSITIVE_HALF_LIFE_DAYS)
        val negativeRecency = if (phrase.lastNegativeAtMillis > 0L) {
            decay(nowMillis, phrase.lastNegativeAtMillis, NEGATIVE_HALF_LIFE_DAYS)
        } else {
            0f
        }
        val retainedPositive = positive * (
            LONG_TERM_FREQUENCY_FLOOR + (1f - LONG_TERM_FREQUENCY_FLOOR) * positiveRecency
        )
        val netEvidence = retainedPositive.coerceAtLeast(0f)
        val frequencyBonus = (
            0.72f * ln(netEvidence.toDouble() + 1.0).toFloat()
        ).coerceAtMost(MAX_FREQUENCY_BONUS)
        val latestEvent = phrase.lastPositiveEvidence
            .takeIf { it.isFinite() && it > 0f }
            ?: LEGACY_EVENT_EVIDENCE
        val recencyBonus = (latestEvent * 1.3f)
            .coerceAtMost(MAX_RECENCY_BONUS) * positiveRecency
        val negativePenalty = (
            negative * negativeRecency * NEGATIVE_EVIDENCE_SCALE
        ).coerceAtMost(MAX_PERSONALIZATION_BONUS)
        return (frequencyBonus + recencyBonus - negativePenalty)
            .coerceIn(-MAX_PERSONALIZATION_BONUS, MAX_PERSONALIZATION_BONUS)
    }

    fun retainedPositiveEvidence(phrase: LearnedPhrase, nowMillis: Long): Float {
        val positive = phrase.positiveEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE)
        val recency = decay(nowMillis, phrase.lastUsedAtMillis, POSITIVE_HALF_LIFE_DAYS)
        return positive * (
            LONG_TERM_FREQUENCY_FLOOR + (1f - LONG_TERM_FREQUENCY_FLOOR) * recency
        )
    }

    fun retainedNegativeEvidence(phrase: LearnedPhrase, nowMillis: Long): Float {
        if (phrase.lastNegativeAtMillis <= 0L) return 0f
        return phrase.negativeEvidence.coerceIn(0f, MAX_ACCUMULATED_EVIDENCE) *
            decay(nowMillis, phrase.lastNegativeAtMillis, NEGATIVE_HALF_LIFE_DAYS)
    }

    private fun decay(nowMillis: Long, eventMillis: Long, halfLifeDays: Double): Float {
        if (eventMillis <= 0L) return 0f
        if (nowMillis <= eventMillis) return 1f
        val ageDays = (
            nowMillis.toDouble() - eventMillis.toDouble()
        ).coerceAtLeast(0.0) / DAY_MILLIS.toDouble()
        return exp(-ageDays * LN_2 / halfLifeDays).toFloat().coerceIn(0f, 1f)
    }

    private const val NEGATIVE_EVIDENCE_SCALE = 1.15f
    private const val LN_2 = 0.6931471805599453
}

private const val LEGACY_EVENT_EVIDENCE = 0.18f
