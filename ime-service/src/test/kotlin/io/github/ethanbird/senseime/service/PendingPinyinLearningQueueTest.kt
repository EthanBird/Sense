package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserSelectionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPinyinLearningQueueTest {
    @Test
    fun keepsNewestAllowedStartupSelectionsAndDrainsOnce() {
        val queue = PendingPinyinLearningQueue(maximumSize = 2)
        queue.add(value("cheng", "程"))
        queue.add(value("che", "彻"))
        queue.add(value("zhinengti", "智能体"))

        assertEquals(listOf("彻", "智能体"), queue.drain().map { it.candidate.text })
        assertEquals(0, queue.size())
        assertEquals(emptyList<PendingPinyinLearning>(), queue.drain())
    }

    private fun value(raw: String, text: String) = PendingPinyinLearning(
        rawInput = raw,
        candidate = Candidate(text = text, canonicalPinyin = raw),
        evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 0),
    )
}
