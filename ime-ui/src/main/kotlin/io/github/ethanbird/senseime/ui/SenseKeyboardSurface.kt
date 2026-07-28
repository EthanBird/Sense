package io.github.ethanbird.senseime.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Production keyboard host with a separately recorded active-Skill Aurora.
 *
 * The overlay is a non-interactive sibling above the keyboard. Keeping the
 * animation out of [SenseKeyboardView.onDraw] prevents every Aurora frame from
 * rebuilding candidates, labels and every physical key.
 */
class SenseKeyboardSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    val keyboardView = SenseKeyboardView(context)
    val skillAuroraOverlay = ActiveSkillAuroraOverlayView(context)

    init {
        addView(
            keyboardView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            skillAuroraOverlay,
            LayoutParams(
                0,
                0,
            ),
        )
        keyboardView.attachActiveSkillAuroraOverlay(skillAuroraOverlay)
    }

    /**
     * Propagates the real IME window lifecycle to the independently animated layer.
     *
     * Input method windows are intentionally non-focusable on Android, so
     * `View.hasWindowFocus()` cannot represent whether the keyboard is on screen.
     */
    fun setImeWindowVisible(visible: Boolean) {
        skillAuroraOverlay.setHostRenderingEnabled(visible)
    }
}
