package io.github.ethanbird.senseime.service

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import io.github.ethanbird.senseime.agent.ui.AgentMessageRole
import io.github.ethanbird.senseime.agent.ui.AgentMessageUi
import io.github.ethanbird.senseime.agent.ui.AgentUiActions
import io.github.ethanbird.senseime.agent.ui.AgentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SenseImeRootLayoutRobolectricTest {
    @Test
    fun agentPageCanBeAttachedMeasuredAndDrawnInsideAWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = SenseImeRootLayout(activity)
        activity.setContentView(root)
        root.setImeWindowVisible(true)

        root.showAgent(
            state = AgentUiState(
                loaded = true,
                status = "就绪",
                messages = listOf(
                    AgentMessageUi("hello", AgentMessageRole.ASSISTANT, "Sense Agent 已就绪"),
                ),
            ),
            actions = AgentUiActions(),
            composing = false,
        )
        shadowOf(Looper.getMainLooper()).idle()

        val width = 1080
        val height = root.layoutParams.height
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        root.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))

        assertEquals(ImeFrontMode.AGENT_READING, root.mode)
        assertTrue(height > 0)
    }
}
