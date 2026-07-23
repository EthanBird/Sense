package io.github.ethanbird.senseime.ui

import kotlin.math.abs

/**
 * Content-space geometry for the collapsed candidate strip.
 *
 * Every candidate is laid out exactly once. The View translates these slots by
 * [maximumOffset] bounded scroll state and clips them to [viewportLeft]..
 * [viewportRight], while the expand button remains fixed outside that viewport.
 */
object CandidateStripGeometry {
    data class Layout(
        val slots: List<KeyboardLayoutContract.CandidateSlot>,
        val viewportLeft: Float,
        val viewportRight: Float,
        val maximumOffset: Float,
        val snapOffsets: List<Float>,
        val hasOverflow: Boolean,
    ) {
        val viewportExtent: Float
            get() = viewportRight - viewportLeft
    }

    fun layout(
        viewWidth: Float,
        measuredTextWidths: List<Float>,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
        overflowControlWidth: Float,
    ): Layout {
        require(viewWidth > 0f)
        require(padding >= 0f)
        require(textInset >= 0f)
        require(gap >= 0f)
        require(minimumWidth > 0f)
        require(overflowControlWidth >= 0f)

        val slots = ArrayList<KeyboardLayoutContract.CandidateSlot>(measuredTextWidths.size)
        var left = padding
        measuredTextWidths.forEach { measuredTextWidth ->
            val width = maxOf(minimumWidth, measuredTextWidth + textInset * 2f)
            slots += KeyboardLayoutContract.CandidateSlot(
                left = left,
                right = left + width,
                textAnchor = left + textInset,
            )
            left += width + gap
        }

        val contentRight = slots.lastOrNull()?.right ?: padding
        val fullViewportRight = (viewWidth - padding).coerceAtLeast(padding)
        val hasOverflow = contentRight > fullViewportRight
        val viewportRight = if (hasOverflow) {
            (viewWidth - padding - overflowControlWidth - gap).coerceAtLeast(padding)
        } else {
            fullViewportRight
        }
        val maximumOffset = (contentRight - viewportRight).coerceAtLeast(0f)
        val snapOffsets = buildList {
            add(0f)
            slots.forEach { slot ->
                add((slot.left - padding).coerceIn(0f, maximumOffset))
            }
            add(maximumOffset)
        }.distinct()

        return Layout(
            slots = slots,
            viewportLeft = padding,
            viewportRight = viewportRight,
            maximumOffset = maximumOffset,
            snapOffsets = snapOffsets,
            hasOverflow = hasOverflow,
        )
    }
}

/** Memoizes measured candidate geometry across every pixel of the same drag. */
class CandidateStripLayoutCache {
    data class Key(
        val generation: Long,
        val viewWidth: Int,
        val takesToolbar: Boolean,
    )

    private var cachedKey: Key? = null
    private var cachedLayout: CandidateStripGeometry.Layout? = null

    fun getOrBuild(
        key: Key,
        builder: () -> CandidateStripGeometry.Layout,
    ): CandidateStripGeometry.Layout {
        if (cachedKey == key) cachedLayout?.let { return it }
        return builder().also { layout ->
            cachedKey = key
            cachedLayout = layout
        }
    }

    fun invalidate() {
        cachedKey = null
        cachedLayout = null
    }
}

/**
 * Allocation-free drag and settle state for a horizontal candidate strip.
 *
 * Slow drags stay exactly where the finger leaves them. Fast drags move at most
 * one viewport and settle on a candidate boundary. Edge overdrag is resisted
 * and springs back into the legal range on release.
 */
class CandidateStripScrollState(
    private val touchSlop: Float,
    private val horizontalDominanceRatio: Float = 1.12f,
    private val edgeResistance: Float = 0.34f,
) {
    data class DragUpdate(
        val changed: Boolean,
        val dragLatched: Boolean,
    )

    data class Settle(
        val targetOffset: Float,
        val animate: Boolean,
        val fastFling: Boolean,
        val dragged: Boolean,
    )

    var offset: Float = 0f
        private set

    var maximumOffset: Float = 0f
        private set

    var viewportExtent: Float = 0f
        private set

    private var snapOffsets: List<Float> = listOf(0f)
    private var pointerId = NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastTimeMillis = 0L
    private var dragStartOffset = 0f
    private var rawOffset = 0f
    private var velocityX = 0f
    private var hasVelocitySample = false
    private var dragLatched = false

    init {
        require(touchSlop > 0f)
        require(horizontalDominanceRatio >= 1f)
        require(edgeResistance in 0f..1f)
    }

    fun configure(
        maximumOffset: Float,
        viewportExtent: Float,
        snapOffsets: List<Float>,
    ) {
        require(maximumOffset >= 0f)
        require(viewportExtent >= 0f)
        this.maximumOffset = maximumOffset
        this.viewportExtent = viewportExtent
        this.snapOffsets = snapOffsets
            .asSequence()
            .map { it.coerceIn(0f, maximumOffset) }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { listOf(0f) }
        if (pointerId == NONE) {
            offset = offset.coerceIn(0f, maximumOffset)
            rawOffset = offset
        }
    }

    fun reset(): Boolean {
        val changed = offset != 0f || pointerId != NONE
        offset = 0f
        rawOffset = 0f
        clearPointer()
        return changed
    }

    fun begin(pointerId: Int, x: Float, y: Float, eventTimeMillis: Long): Boolean {
        if (this.pointerId != NONE) return false
        this.pointerId = pointerId
        downX = x
        downY = y
        lastX = x
        lastTimeMillis = eventTimeMillis
        dragStartOffset = offset.coerceIn(0f, maximumOffset)
        rawOffset = dragStartOffset
        velocityX = 0f
        hasVelocitySample = false
        dragLatched = false
        return true
    }

    fun owns(pointerId: Int): Boolean = this.pointerId == pointerId

    fun move(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): DragUpdate {
        if (!owns(pointerId)) return DragUpdate(changed = false, dragLatched = false)

        if (!dragLatched) {
            val dx = x - downX
            val dy = y - downY
            if (
                abs(dx) >= touchSlop &&
                abs(dx) > abs(dy) * horizontalDominanceRatio
            ) {
                dragLatched = true
            } else {
                return DragUpdate(changed = false, dragLatched = false)
            }
        }

        val previousOffset = offset
        sampleAndMove(x, eventTimeMillis)
        return DragUpdate(
            changed = offset != previousOffset,
            dragLatched = true,
        )
    }

    fun finish(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
        fastFlingVelocity: Float,
    ): Settle? {
        if (!owns(pointerId)) return null
        require(fastFlingVelocity > 0f)

        if (dragLatched) sampleAndMove(x, eventTimeMillis)
        val wasLatched = dragLatched
        val releaseVelocityX = velocityX
        val startOffset = dragStartOffset
        clearPointer()

        if (!wasLatched) {
            return Settle(
                targetOffset = offset.coerceIn(0f, maximumOffset),
                animate = offset !in 0f..maximumOffset,
                fastFling = false,
                dragged = false,
            )
        }

        val fast = abs(releaseVelocityX) >= fastFlingVelocity
        val target = when {
            fast -> CandidateStripScrollPhysics.onePageSnapTarget(
                startOffset = startOffset,
                fingerVelocityX = releaseVelocityX,
                viewportExtent = viewportExtent,
                maximumOffset = maximumOffset,
                snapOffsets = snapOffsets,
            )

            else -> offset.coerceIn(0f, maximumOffset)
        }
        return Settle(
            targetOffset = target,
            animate = fast || target != offset,
            fastFling = fast,
            dragged = true,
        )
    }

    fun cancel(pointerId: Int): Settle? {
        if (!owns(pointerId)) return null
        val target = offset.coerceIn(0f, maximumOffset)
        val wasDragged = dragLatched
        clearPointer()
        return Settle(
            targetOffset = target,
            animate = target != offset,
            fastFling = false,
            dragged = wasDragged,
        )
    }

    fun cancelAll(): Settle? {
        if (pointerId == NONE) return null
        return cancel(pointerId)
    }

    fun moveTo(targetOffset: Float): Boolean {
        val next = targetOffset.coerceIn(0f, maximumOffset)
        if (next == offset) return false
        offset = next
        rawOffset = next
        return true
    }

    private fun sampleAndMove(x: Float, eventTimeMillis: Long) {
        val deltaX = x - lastX
        rawOffset -= deltaX
        offset = CandidateStripScrollPhysics.resistedOffset(
            rawOffset = rawOffset,
            maximumOffset = maximumOffset,
            viewportExtent = viewportExtent,
            resistance = edgeResistance,
        )

        val deltaTime = eventTimeMillis - lastTimeMillis
        if (deltaTime > 0L) {
            if (!(deltaX == 0f && deltaTime <= VELOCITY_PRESERVE_ON_UP_MILLIS)) {
                val instantVelocity = if (deltaTime > VELOCITY_STALE_AFTER_MILLIS) {
                    0f
                } else {
                    deltaX * 1_000f / deltaTime
                }
                velocityX = if (hasVelocitySample) {
                    velocityX * 0.25f + instantVelocity * 0.75f
                } else {
                    instantVelocity
                }
                hasVelocitySample = true
            }
        }
        lastX = x
        lastTimeMillis = eventTimeMillis
    }

    private fun clearPointer() {
        pointerId = NONE
        dragLatched = false
        velocityX = 0f
        hasVelocitySample = false
    }

    private companion object {
        const val NONE = -1
        const val VELOCITY_PRESERVE_ON_UP_MILLIS = 32L
        const val VELOCITY_STALE_AFTER_MILLIS = 80L
    }
}

object CandidateStripScrollPhysics {
    fun resistedOffset(
        rawOffset: Float,
        maximumOffset: Float,
        viewportExtent: Float,
        resistance: Float,
    ): Float {
        require(maximumOffset >= 0f)
        require(viewportExtent >= 0f)
        require(resistance in 0f..1f)
        val scale = viewportExtent.coerceAtLeast(1f)
        return when {
            rawOffset < 0f -> -rubberBand(-rawOffset, scale, resistance)
            rawOffset > maximumOffset ->
                maximumOffset + rubberBand(rawOffset - maximumOffset, scale, resistance)
            else -> rawOffset
        }
    }

    /**
     * A fast gesture advances no farther than one viewport and always lands on
     * a candidate start. Positive finger velocity moves to earlier content.
     */
    fun onePageSnapTarget(
        startOffset: Float,
        fingerVelocityX: Float,
        viewportExtent: Float,
        maximumOffset: Float,
        snapOffsets: List<Float>,
    ): Float {
        require(viewportExtent >= 0f)
        require(maximumOffset >= 0f)
        val start = startOffset.coerceIn(0f, maximumOffset)
        if (fingerVelocityX == 0f || maximumOffset == 0f) return start
        val snaps = snapOffsets
            .asSequence()
            .map { it.coerceIn(0f, maximumOffset) }
            .distinct()
            .sorted()
            .toList()
        if (snaps.isEmpty()) return start

        return if (fingerVelocityX < 0f) {
            val limit = (start + viewportExtent).coerceAtMost(maximumOffset)
            snaps.lastOrNull { it > start && it <= limit } ?: snaps.firstOrNull { it > start } ?: maximumOffset
        } else {
            val limit = (start - viewportExtent).coerceAtLeast(0f)
            snaps.firstOrNull { it < start && it >= limit } ?: snaps.lastOrNull { it < start } ?: 0f
        }
    }

    fun easeOutCubic(start: Float, target: Float, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val inverse = 1f - t
        val eased = 1f - inverse * inverse * inverse
        return start + (target - start) * eased
    }

    private fun rubberBand(distance: Float, scale: Float, resistance: Float): Float =
        distance * resistance / (1f + distance / scale)
}
