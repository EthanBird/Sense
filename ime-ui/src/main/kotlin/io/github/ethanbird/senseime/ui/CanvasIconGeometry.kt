package io.github.ethanbird.senseime.ui

import kotlin.math.max
import kotlin.math.min

/** Density-aware geometry for every icon drawn directly on the keyboard Canvas. */
object CanvasIconGeometry {
    class MutableMetrics {
        var centerX: Float = 0f
            private set
        var centerY: Float = 0f
            private set
        var unit: Float = 0f
            private set
        var strokeWidth: Float = 0f
            private set
        var frameLeft: Float = 0f
            private set
        var frameTop: Float = 0f
            private set
        var frameRight: Float = 0f
            private set
        var frameBottom: Float = 0f
            private set

        val frameWidth: Float
            get() = frameRight - frameLeft

        val frameHeight: Float
            get() = frameBottom - frameTop

        internal fun set(
            centerX: Float,
            centerY: Float,
            unit: Float,
            strokeWidth: Float,
            frameLeft: Float,
            frameTop: Float,
            frameRight: Float,
            frameBottom: Float,
        ) {
            this.centerX = centerX
            this.centerY = centerY
            this.unit = unit
            this.strokeWidth = strokeWidth
            this.frameLeft = frameLeft
            this.frameTop = frameTop
            this.frameRight = frameRight
            this.frameBottom = frameBottom
        }
    }

    data class Metrics(
        val centerX: Float,
        val centerY: Float,
        val unit: Float,
        val strokeWidth: Float,
        val frameLeft: Float,
        val frameTop: Float,
        val frameRight: Float,
        val frameBottom: Float,
    ) {
        val frameWidth: Float
            get() = frameRight - frameLeft

        val frameHeight: Float
            get() = frameBottom - frameTop
    }

    fun resolve(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        density: Float,
    ): Metrics {
        require(right > left)
        require(bottom > top)
        require(density > 0f)
        val minimumDimension = min(right - left, bottom - top)
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val unit = minimumDimension / 36f
        val halfExtent = unit * 9f
        return Metrics(
            centerX = centerX,
            centerY = centerY,
            unit = unit,
            strokeWidth = max(density * 0.5f, minimumDimension * 0.0525f),
            frameLeft = centerX - halfExtent,
            frameTop = centerY - halfExtent,
            frameRight = centerX + halfExtent,
            frameBottom = centerY + halfExtent,
        )
    }

    /**
     * Allocation-free variant for the per-frame renderer.
     */
    fun resolveInto(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        density: Float,
        out: MutableMetrics,
    ) {
        require(right > left)
        require(bottom > top)
        require(density > 0f)
        val minimumDimension = min(right - left, bottom - top)
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        // Paths stay inside +/-9 units, making the icon frame half the key's short edge.
        val unit = minimumDimension / 36f
        val halfExtent = unit * 9f
        out.set(
            centerX = centerX,
            centerY = centerY,
            unit = unit,
            strokeWidth = max(density * 0.5f, minimumDimension * 0.0525f),
            frameLeft = centerX - halfExtent,
            frameTop = centerY - halfExtent,
            frameRight = centerX + halfExtent,
            frameBottom = centerY + halfExtent,
        )
    }
}
