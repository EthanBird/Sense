package io.github.ethanbird.senseime.ui

/**
 * Resolves a touch in the visual gap between keys without changing the key
 * rectangles used for drawing.
 *
 * The accessors keep this Android-free and the function is inline so a DOWN
 * event does not allocate temporary rectangles or candidate collections.
 */
internal object KeyboardGapHitResolver {
    const val NONE = -1

    inline fun nearestIndex(
        x: Float,
        y: Float,
        maximumDistance: Float,
        targetCount: Int,
        isEligible: (Int) -> Boolean,
        left: (Int) -> Float,
        top: (Int) -> Float,
        right: (Int) -> Float,
        bottom: (Int) -> Float,
    ): Int {
        require(maximumDistance >= 0f)
        require(targetCount >= 0)

        val maximumDistanceSquared = maximumDistance * maximumDistance
        var bestIndex = NONE
        var bestDistanceSquared = Float.POSITIVE_INFINITY
        var bestCenterDistanceSquared = Float.POSITIVE_INFINITY

        for (index in 0 until targetCount) {
            if (!isEligible(index)) continue
            val candidateLeft = left(index)
            val candidateTop = top(index)
            val candidateRight = right(index)
            val candidateBottom = bottom(index)
            if (candidateRight < candidateLeft || candidateBottom < candidateTop) continue

            val distanceSquared = squaredDistanceToRect(
                x = x,
                y = y,
                left = candidateLeft,
                top = candidateTop,
                right = candidateRight,
                bottom = candidateBottom,
            )
            if (distanceSquared > maximumDistanceSquared) continue

            val centerX = (candidateLeft + candidateRight) * 0.5f
            val centerY = (candidateTop + candidateBottom) * 0.5f
            val centerDeltaX = x - centerX
            val centerDeltaY = y - centerY
            val centerDistanceSquared =
                centerDeltaX * centerDeltaX + centerDeltaY * centerDeltaY

            if (
                distanceSquared < bestDistanceSquared ||
                (
                    distanceSquared == bestDistanceSquared &&
                        centerDistanceSquared < bestCenterDistanceSquared
                    ) ||
                (
                    distanceSquared == bestDistanceSquared &&
                        centerDistanceSquared == bestCenterDistanceSquared &&
                        (bestIndex == NONE || index < bestIndex)
                    )
            ) {
                bestIndex = index
                bestDistanceSquared = distanceSquared
                bestCenterDistanceSquared = centerDistanceSquared
            }
        }
        return bestIndex
    }

    fun squaredDistanceToRect(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        val deltaX = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val deltaY = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return deltaX * deltaX + deltaY * deltaY
    }

    fun containsWithSlop(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        slop: Float,
    ): Boolean {
        require(slop >= 0f)
        return x >= left - slop &&
            x <= right + slop &&
            y >= top - slop &&
            y <= bottom + slop
    }
}
