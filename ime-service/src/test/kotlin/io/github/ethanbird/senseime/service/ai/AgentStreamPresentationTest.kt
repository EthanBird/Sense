package io.github.ethanbird.senseime.service.ai

import org.junit.Assert.assertEquals
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
    fun presentationBuffersRemainBounded() {
        val presentation = AgentStreamPresentation(
            maxPreviewChars = 4,
            maxDescriptionChars = 3,
        )

        presentation.appendPreview("123456")
        presentation.appendDescription("ABCDE")

        assertEquals("1234", presentation.preview)
        assertEquals("ABC", presentation.description)
    }
}
