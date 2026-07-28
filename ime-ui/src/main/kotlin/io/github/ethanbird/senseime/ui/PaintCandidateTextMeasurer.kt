package io.github.ethanbird.senseime.ui

import android.graphics.Paint

/** Android adapter for the candidate text-measurement seam. */
internal class PaintCandidateTextMeasurer : CandidateTextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun measure(text: String, textSizePx: Float): Float {
        if (paint.textSize != textSizePx) paint.textSize = textSizePx
        return paint.measureText(text)
    }
}
