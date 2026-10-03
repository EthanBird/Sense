package io.github.ethanbird.senseime.ui

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.ceil
import kotlin.math.floor

/** Virtual candidate nodes use the same hit/activation path as touch, without materializing Views. */
internal class CandidateExploreByTouchHelper(
    private val host: View,
    private val scene: CandidateScene,
    private val shown: () -> Boolean,
    private val items: () -> List<AccessibleCandidate>,
    private val activate: (AccessibleCandidate) -> Boolean,
    private val canScroll: (Boolean) -> Boolean,
    private val scroll: (Boolean) -> Boolean,
) : ExploreByTouchHelper(host) {
    private val manager = host.context.getSystemService(AccessibilityManager::class.java)
    private var notificationPosted = false
    private var lastBuild = Long.MIN_VALUE
    private var lastRevision = Long.MIN_VALUE
    private var lastReady = false
    private var lastShown = false
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var clickedItem: AccessibleCandidate? = null
    private val notify = Runnable {
        notificationPosted = false
        val visible = items()
        val focused = accessibilityFocusedVirtualViewId
        if (focused != INVALID_ID && visible.none { it.id == focused }) {
            getAccessibilityNodeProvider(host)?.performAction(focused, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        }
        val keyboardFocused = keyboardFocusedVirtualViewId
        if (keyboardFocused != INVALID_ID && visible.none { it.id == keyboardFocused }) clearKeyboardFocusForVirtualView(keyboardFocused)
        invalidateRoot()
    }

    // Coalesce draw/scroll changes; do not build a node tree or emit an event per animation frame.
    fun sceneChanged() {
        if (manager?.isEnabled != true) return
        val visible = shown()
        if (lastBuild == scene.sceneBuildCount && lastRevision == scene.candidateRevision &&
            lastReady == scene.candidatesReady && lastShown == visible &&
            lastX == scene.scrollOffset && lastY == scene.expandedScrollOffset) return
        lastBuild = scene.sceneBuildCount
        lastRevision = scene.candidateRevision
        lastReady = scene.candidatesReady
        lastShown = visible
        lastX = scene.scrollOffset
        lastY = scene.expandedScrollOffset
        if (notificationPosted) return
        notificationPosted = true
        host.postDelayed(notify, 80L)
    }

    fun detach() { host.removeCallbacks(notify); notificationPosted = false }

    override fun getVirtualViewAt(x: Float, y: Float): Int =
        items().firstOrNull { it.bounds.contains(x, y) }?.id ?: INVALID_ID

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        items().forEach { virtualViewIds += it.id }
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val item = items().firstOrNull { it.id == virtualViewId }
        if (item == null) {
            node.contentDescription = "候选已更新"
            node.setBoundsInParent(Rect(0, 0, 0, 0))
            node.isEnabled = false
            node.isFocusable = false
            node.isVisibleToUser = false
            return
        }
        node.text = item.text
        node.contentDescription = item.description
        node.className = "android.widget.Button"
        node.setBoundsInParent(Rect(floor(item.bounds.left).toInt(), floor(item.bounds.top).toInt(),
            ceil(item.bounds.right).toInt(), ceil(item.bounds.bottom).toInt()))
        node.isEnabled = true
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || !host.isShown || !host.isEnabled) return false
        val item = items().firstOrNull { it.id == virtualViewId } ?: return false
        if (!activate(item)) return false
        // A commit can synchronously replace the batch. Preserve only its event label,
        // never its old action, so speech feedback names the selected word rather than
        // announcing the stale-node placeholder.
        clickedItem = item
        try {
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        } finally {
            clickedItem = null
        }
        sceneChanged()
        return true
    }

    override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
        val clicked = clickedItem?.takeIf { it.id == virtualViewId && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
            ?: return
        event.text.clear()
        event.text.add(clicked.text)
        event.contentDescription = clicked.description
        event.isEnabled = true
        event.className = "android.widget.Button"
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        super.onPopulateNodeForHost(node)
        node.isScrollable = canScroll(true) || canScroll(false)
        if (canScroll(true)) node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
        if (canScroll(false)) node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        val forward = when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> true
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> false
            else -> return super.performAccessibilityAction(host, action, args)
        }
        if (!host.isShown || !host.isEnabled || !canScroll(forward) || !scroll(forward)) return false
        sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        sceneChanged()
        return true
    }
}
