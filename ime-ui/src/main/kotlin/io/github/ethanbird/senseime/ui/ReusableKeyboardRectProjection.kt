package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/** One mutable Android rectangle for hot paths projecting Android-free geometry. */
internal class ReusableKeyboardRectProjection {
    private val mutableBounds = RectF()

    fun project(bounds: KeyboardRect?): RectF? {
        if (bounds == null) {
            mutableBounds.left = 0f
            mutableBounds.top = 0f
            mutableBounds.right = 0f
            mutableBounds.bottom = 0f
            return null
        }
        mutableBounds.left = bounds.left
        mutableBounds.top = bounds.top
        mutableBounds.right = bounds.right
        mutableBounds.bottom = bounds.bottom
        return mutableBounds
    }
}
