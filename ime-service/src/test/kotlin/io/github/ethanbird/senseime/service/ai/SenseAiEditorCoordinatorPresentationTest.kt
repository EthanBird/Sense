package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.service.ai.editor.EditorAiTestFixtures
import io.github.ethanbird.senseime.service.ai.editor.EditorCompletionPreviewPolicy
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchPlan
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class SenseAiEditorCoordinatorPresentationTest {
    @Test
    fun noChangeCompletionUsesFreshGuardedEditorTextInsteadOfStaleStreamDraft() {
        val snapshot = EditorAiTestFixtures.fullSnapshot(text = "输入框中的权威文本")
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot = snapshot,
                patch = EditorAiTestFixtures.noChangePatch(snapshot),
            ),
        )
        val plan = EditorPatchPlanner.plan(guarded, sdkInt = 34)

        assertEquals(EditorPatchPlan.NoChange, plan)
        assertEquals(
            "输入框中的权威文本",
            EditorCompletionPreviewPolicy.resolve(plan, guarded.liveEditor),
        )
    }

    @Test
    fun replaceCompletionUsesTheLocallyPlannedExpectedEditorState() {
        val snapshot = EditorAiTestFixtures.fullSnapshot(text = "旧文本")
        val guarded = EditorAiTestFixtures.accepted(
            EditorAiTestFixtures.guardInput(
                snapshot = snapshot,
                patch = EditorAiTestFixtures.replacePatch(snapshot, text = "新文本"),
            ),
        )
        val plan = EditorPatchPlanner.plan(guarded, sdkInt = 34)

        assertEquals(
            "新文本",
            EditorCompletionPreviewPolicy.resolve(plan, guarded.liveEditor),
        )
    }
}
