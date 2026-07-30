package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.UserLearningEvidence
import java.util.ArrayDeque

internal data class ProgressiveLearning(
    val rawInput: String,
    val candidate: Candidate,
    val evidence: UserLearningEvidence,
)

/**
 * Transactional learning trail for progressive prefix selections.
 *
 * Prefix taps only append provisional evidence. [snapshotForCommit] freezes a
 * detached immutable batch before the service calls into InputConnection; that
 * batch survives a synchronous editor callback clearing the live composition.
 */
internal class ProgressiveLearningQueue {
    private val pending = ArrayDeque<ProgressiveLearning>()

    fun add(learning: ProgressiveLearning) {
        pending.addLast(learning)
    }

    fun rollbackLast(): ProgressiveLearning? = pending.pollLast()

    fun snapshotForCommit(): List<ProgressiveLearning> = pending.toList()

    fun clear() {
        pending.clear()
    }
}
