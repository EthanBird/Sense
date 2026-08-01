package io.github.ethanbird.senseime.ui

import android.content.Context
import android.content.res.Configuration
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
    var keyboardSizeProfile: KeyboardSizeProfile = KeyboardSizeProfile.DEFAULT
        private set

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

    fun setKeyboardSizeProfile(profile: KeyboardSizeProfile) {
        if (keyboardSizeProfile == profile) return
        keyboardSizeProfile = profile
        keyboardView.setKeyboardSizeProfile(profile)
        applyPreferredHeightToParent()
    }

    fun setPrimaryKeyboardMode(mode: PrimaryKeyboardMode) {
        keyboardView.setPrimaryKeyboardMode(mode)
    }

    fun setPrimaryKeyboardLegendMode(mode: PrimaryKeyboardLegendMode) {
        keyboardView.setPrimaryKeyboardLegendMode(mode)
    }

    fun setPrimaryKeyboardPresentation(
        mode: PrimaryKeyboardMode,
        legendMode: PrimaryKeyboardLegendMode,
    ) {
        keyboardView.setPrimaryKeyboardPresentation(mode, legendMode)
    }

    fun preferredHeightPx(): Int = keyboardSizeProfile.preferredHeightPx(
        isLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        density = resources.displayMetrics.density,
    )

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreferredHeightToParent()
    }

    /**
     * The IME window gives this Surface an exact height. Updating only the
     * child View's desired size would therefore have no effect at runtime.
     */
    private fun applyPreferredHeightToParent() {
        val params = layoutParams
        if (params == null) {
            requestLayout()
            return
        }
        val targetHeight = preferredHeightPx()
        if (params.height == targetHeight) {
            requestLayout()
            return
        }
        params.height = targetHeight
        layoutParams = params
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
