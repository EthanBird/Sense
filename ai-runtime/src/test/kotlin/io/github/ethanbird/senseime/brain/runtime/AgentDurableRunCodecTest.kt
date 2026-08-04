package io.github.ethanbird.senseime.brain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentDurableRunCodecTest {
    @Test
    fun roundTripsRunningAndCompletedRecords() {
        val running = AgentDurableRunRecord(
            requestId = "request-1",
            generation = 9,
            userMessage = "继续后台执行，并保留终端输出",
            userCreatedAtEpochMs = 1234,
        )
        assertEquals(running, AgentDurableRunCodec.decode(AgentDurableRunCodec.encode(running)))

        val completed = running.copy(
            outcome = AgentDurableRunOutcome.ANSWER,
            payload = "任务完成\n结果已保存",
        )
        assertEquals(
            completed,
            AgentDurableRunCodec.decode(AgentDurableRunCodec.encode(completed)),
        )
    }
}
