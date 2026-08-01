package io.github.ethanbird.senseime.ui

import android.graphics.RectF

internal interface KeyboardHitTestHost {
    val hitTestKeys: List<Key>
    val hitTestCandidatePanel: CandidatePanel
    val hitTestKeyGap: Float
    val hitTestTouchSlop: Float

    fun showsCandidatesForHitTest(): Boolean
    fun isKeyEnabledForHitTest(key: Key): Boolean
    fun panelViewportBoundsForHitTest(panel: ScrollPanel): RectF?
    fun scrollStateForHitTest(panel: ScrollPanel): ContinuousVerticalScrollState
    fun gesturePolicyForHitTest(key: Key): TouchInputReducer.GesturePolicy
}

/**
 * Resolves one immutable DOWN target. MOVE/UP deliberately never call this
 * class, preserving frozen-target semantics for chords and out-of-order
 * pointer streams.
 */
internal class KeyboardHitTester(
    private val host: KeyboardHitTestHost,
    private val tapPolicy: TouchInputReducer.GesturePolicy,
    private val verticalScrollPolicy: TouchInputReducer.GesturePolicy,
    private val horizontalScrollPolicy: TouchInputReducer.GesturePolicy,
) {
    fun targetAt(x: Float, y: Float): FrozenTouchTarget? {
        host.hitTestCandidatePanel.hitTest(
            x = x,
            y = y,
            visible = host.showsCandidatesForHitTest(),
        )?.let { hit ->
            return when (hit) {
                is CandidateHit.Control -> FrozenTouchTarget.CandidateControlValue(
                    value = hit.control,
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = tapPolicy,
                )

                is CandidateHit.Value -> FrozenTouchTarget.CandidateValue(
                    revision = hit.revision,
                    sourceIndex = hit.sourceIndex,
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = if (hit.expanded) verticalScrollPolicy else tapPolicy,
                )

                is CandidateHit.PageArea -> FrozenTouchTarget.CandidatePageArea(
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = verticalScrollPolicy,
                )

                is CandidateHit.StripArea -> FrozenTouchTarget.CandidateStripArea(
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = tapPolicy,
                )
            }
        }

        val keys = host.hitTestKeys
        for (index in keys.lastIndex downTo 0) {
            val key = keys[index]
            if (!containsKey(key, x, y)) continue
            // A disabled action owns its visible rectangle as a dead zone.
            if (!host.isKeyEnabledForHitTest(key)) return null
            return FrozenTouchTarget.KeyValue(
                key = key,
                gesturePolicy = host.gesturePolicyForHitTest(key),
                bounds = screenBoundsForKey(key),
            )
        }

        val nearestKeyIndex = KeyboardGapHitResolver.nearestIndex(
            x = x,
            y = y,
            maximumDistance = host.hitTestKeyGap,
            targetCount = keys.size,
            isEligible = { index ->
                val key = keys[index]
                key.scrollPanel == null &&
                    key.style != KeyStyle.CARD &&
                    host.isKeyEnabledForHitTest(key)
            },
            left = { index -> keys[index].bounds.left },
            top = { index -> keys[index].bounds.top },
            right = { index -> keys[index].bounds.right },
            bottom = { index -> keys[index].bounds.bottom },
        )
        if (nearestKeyIndex != KeyboardGapHitResolver.NONE) {
            val key = keys[nearestKeyIndex]
            return FrozenTouchTarget.KeyValue(
                key = key,
                gesturePolicy = host.gesturePolicyForHitTest(key),
                bounds = key.bounds,
            )
        }

        for (panel in ScrollPanel.entries) {
            val bounds = host.panelViewportBoundsForHitTest(panel) ?: continue
            if (bounds.contains(x, y)) {
                return FrozenTouchTarget.PanelScrollArea(
                    panel = panel,
                    bounds = RectF(bounds),
                    gesturePolicy = if (panel.axis == ScrollAxis.HORIZONTAL) {
                        horizontalScrollPolicy
                    } else {
                        verticalScrollPolicy
                    },
                )
            }
        }
        return null
    }

    fun isInsideTapTarget(target: FrozenTouchTarget, x: Float, y: Float): Boolean {
        val bounds = target.bounds
        if (target !is FrozenTouchTarget.KeyValue) return bounds.contains(x, y)
        return KeyboardGapHitResolver.containsWithSlop(
            x = x,
            y = y,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            slop = maxOf(host.hitTestTouchSlop, host.hitTestKeyGap),
        )
    }

    /** False only after crossing the frozen key's top edge in the upward-flick direction. */
    fun isInsideUpwardGestureBoundary(target: FrozenTouchTarget, y: Float): Boolean =
        y >= target.bounds.top

    private fun containsKey(key: Key, x: Float, y: Float): Boolean {
        val panel = key.scrollPanel ?: return key.bounds.contains(x, y)
        val viewport = host.panelViewportBoundsForHitTest(panel) ?: return false
        val offset = host.scrollStateForHitTest(panel).offset
        if (panel.axis == ScrollAxis.HORIZONTAL) {
            return KeyboardHitTestGeometry.projectedHorizontalKeyContains(
                x = x,
                y = y,
                keyLeft = key.bounds.left,
                keyTop = key.bounds.top,
                keyRight = key.bounds.right,
                keyBottom = key.bounds.bottom,
                viewportLeft = viewport.left,
                viewportTop = viewport.top,
                viewportRight = viewport.right,
                viewportBottom = viewport.bottom,
                scrollOffset = offset,
            )
        }
        return KeyboardHitTestGeometry.projectedKeyContains(
            x = x,
            y = y,
            keyLeft = key.bounds.left,
            keyTop = key.bounds.top,
            keyRight = key.bounds.right,
            keyBottom = key.bounds.bottom,
            viewportLeft = viewport.left,
            viewportTop = viewport.top,
            viewportRight = viewport.right,
            viewportBottom = viewport.bottom,
            scrollOffset = offset,
        )
    }

    private fun screenBoundsForKey(key: Key): RectF {
        val panel = key.scrollPanel ?: return key.bounds
        val viewport = host.panelViewportBoundsForHitTest(panel) ?: return key.bounds
        val offset = host.scrollStateForHitTest(panel).offset
        if (panel.axis == ScrollAxis.HORIZONTAL) {
            return RectF(
                maxOf(key.bounds.left - offset, viewport.left),
                maxOf(key.bounds.top, viewport.top),
                minOf(key.bounds.right - offset, viewport.right),
                minOf(key.bounds.bottom, viewport.bottom),
            )
        }
        return RectF(
            maxOf(key.bounds.left, viewport.left),
            maxOf(key.bounds.top - offset, viewport.top),
            minOf(key.bounds.right, viewport.right),
            minOf(key.bounds.bottom - offset, viewport.bottom),
        )
    }

    private fun KeyboardRect.toRectF(): RectF = RectF(left, top, right, bottom)
}

/** Primitive geometry used by both production hit testing and local JVM tests. */
internal object KeyboardHitTestGeometry {
    fun projectedHorizontalKeyContains(
        x: Float,
        y: Float,
        keyLeft: Float,
        keyTop: Float,
        keyRight: Float,
        keyBottom: Float,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
        scrollOffset: Float,
    ): Boolean {
        if (
            x < viewportLeft || x >= viewportRight ||
            y < viewportTop || y >= viewportBottom
        ) {
            return false
        }
        val contentX = x + scrollOffset
        return contentX >= keyLeft && contentX < keyRight &&
            y >= keyTop && y < keyBottom
    }

    fun projectedKeyContains(
        x: Float,
        y: Float,
        keyLeft: Float,
        keyTop: Float,
        keyRight: Float,
        keyBottom: Float,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
        scrollOffset: Float,
    ): Boolean {
        if (
            x < viewportLeft || x >= viewportRight ||
            y < viewportTop || y >= viewportBottom
        ) {
            return false
        }
        val contentY = y + scrollOffset
        return x >= keyLeft && x < keyRight &&
            contentY >= keyTop && contentY < keyBottom
    }
}
