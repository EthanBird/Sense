package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.UserLearningEvidence

internal data class PendingPinyinLearning(
    val rawInput: String,
    val candidate: Candidate,
    val evidence: UserLearningEvidence,
)

/** Bounded startup buffer used until the persistent production decoder is published. */
internal class PendingPinyinLearningQueue(
    private val maximumSize: Int = 64,
) {
    private val values = ArrayDeque<PendingPinyinLearning>()

    init {
        require(maximumSize > 0)
    }

    fun add(value: PendingPinyinLearning) {
        if (values.size >= maximumSize) values.removeFirst()
        values.addLast(value)
    }

    fun drain(): List<PendingPinyinLearning> = buildList(values.size) {
        while (values.isNotEmpty()) add(values.removeFirst())
    }

    fun clear() = values.clear()

    fun size(): Int = values.size
}
