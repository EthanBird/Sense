package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStreamPresentationTest {
    @Test
    fun descriptionSurvivesOrdinaryPreviewUpdates() {
        val presentation = AgentStreamPresentation(
            maxPreviewChars = 8,
            maxDescriptionChars = 8,
        )

        presentation.appendDescription("正在润色")
        presentation.appendPreview("第一段")
        presentation.appendPreview("第二段")

        assertEquals("正在润色", presentation.descriptionOr("fallback"))
        assertEquals("第一段第二段", presentation.preview)
    }

    @Test
    fun resetAtomicallyClearsDescriptionAndPreview() {
        val presentation = AgentStreamPresentation()
        presentation.appendDescription("第一次")
        presentation.appendPreview("旧结果")

        presentation.reset()

        assertEquals("", presentation.description)
        assertEquals("", presentation.preview)
        assertEquals("重试中", presentation.descriptionOr("重试中"))
    }

    @Test
    fun previewKeepsMovingAsABoundedTailWhileDescriptionKeepsItsHead() {
        val presentation = AgentStreamPresentation(
            maxPreviewChars = 4,
            maxDescriptionChars = 3,
        )

        presentation.appendPreview("123456")
        presentation.appendDescription("ABCDE")

        assertEquals("…456", presentation.preview)
        assertEquals("ABC", presentation.description)
    }

    @Test
    fun rollingPreviewNeverSplitsASurrogatePair() {
        val presentation = AgentStreamPresentation(maxPreviewChars = 4)

        presentation.appendPreview("1234")
        presentation.appendPreview("🐔")

        assertEquals("…4🐔", presentation.preview)
    }

    @Test
    fun retryReplacementAtomicallyReplacesThenAcceptsLaterDeltas() {
        val presentation = AgentStreamPresentation()
        presentation.appendDescription("旧说明")
        presentation.appendPreview("旧结果")

        presentation.replace(preview = "新结果", description = "新说明")
        presentation.appendPreview("继续")
        presentation.appendDescription("完成")

        assertEquals("新结果继续", presentation.preview)
        assertEquals("新说明完成", presentation.description)
    }

    @Test
    fun terminalAuthorityReplacesStaleRetryDraftWithTheValidatedResult() {
        val presentation = AgentStreamPresentation()
        presentation.appendDescription("重试中的旧说明")
        presentation.appendPreview("第一轮残留草稿")

        presentation.complete(authoritativePreview = "输入框权威文本")

        assertEquals("输入框权威文本", presentation.preview)
        assertEquals("", presentation.description)
    }

    @Test
    fun recoveryAndRepairStatusesAreExplicitAndOverrideExistingDescription() {
        val recovery = AiEvent.Status(
            requestId = "request",
            runGeneration = 1,
            phase = HarnessPhase.CONNECTING,
            label = "provider_recovering",
        )
        val repair = recovery.copy(label = "provider_repairing")

        assertEquals("连接中断，正在校准续接…", agentStatusLabel(recovery))
        assertEquals("响应格式需校准，正在修正…", agentStatusLabel(repair))
        assertEquals(
            "连接中断，正在校准续接…",
            agentStatusForPresentation(recovery, currentDescription = "旧说明"),
        )
        assertEquals(
            "响应格式需校准，正在修正…",
            agentStatusForPresentation(repair, currentDescription = "旧说明"),
        )
        assertTrue(recovery.label.isRecoveryStatusLabel())
        assertTrue(repair.label.isRecoveryStatusLabel())
    }
}
