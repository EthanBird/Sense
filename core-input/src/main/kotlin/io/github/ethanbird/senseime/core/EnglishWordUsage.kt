package io.github.ethanbird.senseime.core

data class LearnedEnglishWord(
    val word: String,
    val useCount: Int,
    val lastUsedAtMillis: Long,
    val positiveEvidence: Float,
)

interface EnglishWordUsageStore {
    fun find(word: String): LearnedEnglishWord?

    fun record(
        word: String,
        evidence: UserLearningEvidence = UserLearningEvidence.EXPLICIT_SELECTION,
    ): LearnedEnglishWord

    companion object {
        val EMPTY = object : EnglishWordUsageStore {
            override fun find(word: String): LearnedEnglishWord? = null

            override fun record(
                word: String,
                evidence: UserLearningEvidence,
            ): LearnedEnglishWord = LearnedEnglishWord(word, 0, 0L, 0f)
        }
    }
}

class MemoryEnglishWordUsageStore(
    initial: Collection<LearnedEnglishWord> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (LearnedEnglishWord) -> Unit = {},
    private val maximumRecords: Int = 2_048,
) : EnglishWordUsageStore {
    private val records = LinkedHashMap<String, LearnedEnglishWord>()
    private var latestTimestamp = Long.MIN_VALUE

    init {
        require(maximumRecords > 0)
        initial
            .filter { it.word.isAsciiWord() && it.useCount > 0 }
            .sortedBy { it.lastUsedAtMillis }
            .takeLast(maximumRecords)
            .forEach { value ->
                val normalized = value.copy(
                    word = value.word.lowercase(),
                    positiveEvidence = value.positiveEvidence
                        .takeIf { it.isFinite() && it > 0f }
                        ?: value.useCount.toFloat(),
                )
                records[normalized.word] = normalized
                latestTimestamp = maxOf(latestTimestamp, normalized.lastUsedAtMillis)
            }
    }

    @Synchronized
    override fun find(word: String): LearnedEnglishWord? = records[word.lowercase()]

    @Synchronized
    override fun record(
        word: String,
        evidence: UserLearningEvidence,
    ): LearnedEnglishWord {
        val normalized = word.lowercase()
        require(normalized.isAsciiWord())
        val previous = records[normalized]
        val observed = clock()
        val timestamp = when {
            latestTimestamp == Long.MIN_VALUE || observed > latestTimestamp -> observed
            latestTimestamp < Long.MAX_VALUE -> latestTimestamp + 1
            else -> Long.MAX_VALUE
        }
        latestTimestamp = timestamp
        val strength = when (evidence.kind) {
            UserSelectionKind.DEFAULT_ACCEPT -> 0.5f
            UserSelectionKind.EXPLICIT_SELECTION -> 1.5f
            UserSelectionKind.PROGRESSIVE_SELECTION -> 0.75f
            UserSelectionKind.COMPOSED_CONFIRM -> 1.25f
        }
        val learned = LearnedEnglishWord(
            word = normalized,
            useCount = (previous?.useCount ?: 0) + 1,
            lastUsedAtMillis = timestamp,
            positiveEvidence = ((previous?.positiveEvidence ?: 0f) + strength).coerceAtMost(256f),
        )
        records[normalized] = learned
        if (records.size > maximumRecords) {
            records.minByOrNull { it.value.lastUsedAtMillis }?.key?.let(records::remove)
        }
        onRecord(learned)
        return learned
    }
}

internal fun String.isAsciiWord(): Boolean =
    length in 1..32 && all { it.lowercaseChar() in 'a'..'z' }
