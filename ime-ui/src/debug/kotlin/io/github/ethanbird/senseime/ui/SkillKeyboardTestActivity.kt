package io.github.ethanbird.senseime.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Debug-only host for exercising the real Canvas View, Android MotionEvents,
 * accessibility events and Window frame metrics without involving an editor
 * or the IME service. This class and Activity never enter release artifacts.
 */
class SkillKeyboardTestActivity : Activity() {
    lateinit var keyboardSurface: SenseKeyboardSurface
        private set
    lateinit var keyboardView: SenseKeyboardView
        private set
    lateinit var skillAuroraOverlay: ActiveSkillAuroraOverlayView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        keyboardSurface = SenseKeyboardSurface(this)
        keyboardView = keyboardSurface.keyboardView
        skillAuroraOverlay = keyboardSurface.skillAuroraOverlay
        val landscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val height = (
            KeyboardLayoutContract.preferredKeyboardHeightDp(landscape) *
                resources.displayMetrics.density
        ).toInt()
        root.addView(
            keyboardSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                height,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
    }
}
