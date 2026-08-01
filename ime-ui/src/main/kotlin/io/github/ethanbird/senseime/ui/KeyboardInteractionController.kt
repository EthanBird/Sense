package io.github.ethanbird.senseime.ui

import android.content.Context
import android.graphics.RectF
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Single internal action boundary. [SenseKeyboardView] adapts its eight public
 * listener properties to one instance of this sink.
 */
internal interface KeyboardInteractionActionSink {
    fun onKey(code: Int)
    fun onCandidate(revision: Long, sourceIndex: Int)
    fun onText(text: String)
    fun onClipboardAction(action: KeyboardClipboardAction, index: Int)
    fun onEditorAction(action: KeyboardEditorAction)
    fun onSettingsAction()
    fun onT9SideSymbolSettings() = Unit
    fun onT9PinyinChoiceSelected(revision: Long, index: Int) = Unit
    fun onInputSchemeSelected(choice: KeyboardInputSchemeChoice) = Unit
    fun onAiHoldStarted(generation: Long)
    fun onAiHoldCancelled(generation: Long)
    fun onAiStopRequested(generation: Long)
    fun onSkillSelection(request: KeyboardSkillSelection)
}

/**
 * Mutable keyboard projection owned by the View. The interaction controller
 * asks for semantic changes through this narrow host rather than reaching into
 * public listeners or renderer implementation details.
 */
internal interface KeyboardInteractionHost {
    val interactionContext: Context
    val interactionWidth: Int
    val interactionHeight: Int
    val interactionIsShown: Boolean
    val interactionScene: MutableKeyboardScene
    val interactionCandidatePanel: CandidatePanel
    var interactionClipboardItems: List<String>
    val interactionAiGeometry: MutableAiSurfaceRenderGeometry
    val interactionFontScale: Float

    var interactionPanel: KeyboardPanel
    var interactionPrimaryMode: PrimaryKeyboardMode
    var interactionInputSchemeChoice: KeyboardInputSchemeChoice
    var interactionEmojiGroupIndex: Int
    var interactionSymbolCategoryIndex: Int
    var interactionClipboardPageIndex: Int

    val interactionEditorHasSelection: Boolean
    val interactionEditorSelectionMode: Boolean
    val interactionEditorCanPaste: Boolean
    val interactionChineseMode: Boolean

    fun interactionShowsCandidates(): Boolean
    fun interactionCandidatesTakeToolbar(): Boolean
    fun interactionCandidateToolbarSuppressed(): Boolean
    fun interactionChromeBottom(): Float
    fun interactionRelayoutCandidates()
    fun interactionRebuildKeys()
    fun interactionSetPanel(panel: KeyboardPanel)
    fun interactionPerformClick()
    fun interactionAnnounce(message: String)
    fun interactionReadContentDescription(): CharSequence?
    fun interactionWriteContentDescription(value: CharSequence)
}

/**
 * Deep interaction component for the custom keyboard.
 *
 * It owns MotionEvent orchestration, frozen targets, repeat/FIFO scheduling,
 * candidate and panel scrolling, AI hold/lock, and Skill picker ownership.
 * Scene construction and Canvas drawing remain outside this class.
 */
internal class KeyboardInteractionController(
    private val host: KeyboardInteractionHost,
    private val density: Float,
    private val metrics: KeyboardMetrics,
    private val scaledTouchSlop: Float,
    private val scheduler: KeyboardFrameScheduler,
    private val clock: KeyboardInteractionClock,
    private val haptics: KeyboardHaptics,
    private val actions: KeyboardInteractionActionSink,
) : KeyboardHitTestHost,
    PanelScrollHost,
    KeyboardActionEffects,
    KeyboardGestureEffects {
    private val touchReducer = TouchInputReducer<FrozenTouchTarget>(
        swipeThreshold = dp(22f),
        maximumHorizontalDrift = dp(34f),
    )
    private val pressedTargets = SparseArray<FrozenTouchTarget>(4)
    private val backspaceRepeatSession = BackspaceRepeatSession()
    private val tapGesturePolicy = TouchInputReducer.GesturePolicy.tapOnly()
    private val pageScrollGesturePolicy = TouchInputReducer.GesturePolicy.verticalScroll(
        touchSlop = scaledTouchSlop,
        verticalDominanceRatio = VERTICAL_GESTURE_DOMINANCE,
    )
    private val horizontalScrollGesturePolicy = TouchInputReducer.GesturePolicy.horizontalScroll(
        touchSlop = scaledTouchSlop,
        horizontalDominanceRatio = VERTICAL_GESTURE_DOMINANCE,
    )
    private val hitTester = KeyboardHitTester(
        host = this,
        tapPolicy = tapGesturePolicy,
        verticalScrollPolicy = pageScrollGesturePolicy,
        horizontalScrollPolicy = horizontalScrollGesturePolicy,
    )
    private val panelScroll = PanelScrollController(
        context = host.interactionContext,
        minimumFlingVelocity = ViewConfiguration.get(host.interactionContext)
            .scaledMinimumFlingVelocity.toFloat(),
        maximumFlingVelocity = ViewConfiguration.get(host.interactionContext)
            .scaledMaximumFlingVelocity.toFloat(),
        host = this,
    )
    private val actionDispatcher = KeyboardActionDispatcher(
        host = host,
        scheduler = scheduler,
        actions = actions,
        effects = this,
    )
    private val gestureCoordinator = KeyboardGestureCoordinator(
        host = host,
        density = density,
        metrics = metrics,
        scaledTouchSlop = scaledTouchSlop,
        scheduler = scheduler,
        clock = clock,
        haptics = haptics,
        actions = actions,
        effects = this,
    )

    private var candidateSettleStartedAtMillis = 0L
    private var candidateSettleStartOffset = 0f
    private var candidateSettleTargetOffset = 0f

    val activeKeyboardSkill: ActiveKeyboardSkill?
        get() = gestureCoordinator.activeKeyboardSkill
    val activeSkillSourceKey: Key?
        get() = gestureCoordinator.activeSkillSourceKey
    val skillPickerOptions: KeyboardSkillOptions?
        get() = gestureCoordinator.pickerOptions
    val skillPickerOptionBounds: Array<RectF>
        get() = gestureCoordinator.pickerOptionBounds
    val skillFeedbackMessage: String?
        get() = gestureCoordinator.feedbackMessage
    val aiSurfaceState: AiSurfaceState?
        get() = gestureCoordinator.aiSurfaceState
    val aiLockProgress: Float
        get() = gestureCoordinator.aiLockProgress
    val aiLocked: Boolean
        get() = gestureCoordinator.aiLocked
    val skillPickerSourceBounds: RectF
        get() = gestureCoordinator.pickerSourceBounds
    val skillPickerVisible: Boolean
        get() = gestureCoordinator.pickerVisible
    val highlightedSkillDirection: KeyboardSkillDirection?
        get() = gestureCoordinator.highlightedDirection
    val aiStopPressed: Boolean
        get() = gestureCoordinator.aiStopPressed
    val hasAuroraSibling: Boolean
        get() = gestureCoordinator.hasAuroraSibling

    fun initialize() {
        gestureCoordinator.initialize()
    }

    private val candidateSettleRunnable = object : Runnable {
        override fun run() {
            val elapsed = clock.uptimeMillis() - candidateSettleStartedAtMillis
            val fraction = elapsed.toFloat() / CANDIDATE_SETTLE_DURATION_MILLIS.toFloat()
            val next = CandidateStripScrollPhysics.easeOutCubic(
                start = candidateSettleStartOffset,
                target = candidateSettleTargetOffset,
                fraction = fraction,
            )
            if (candidatePanel.moveTo(next)) invalidateCandidateViewport()
            if (fraction < 1f) scheduler.postOnAnimation(this)
        }
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val pointerId = backspaceRepeatSession.activePointerId() ?: return
            if (!touchReducer.isPressed(pointerId)) return
            val target = touchReducer.target(pointerId) as? FrozenTouchTarget.KeyValue ?: return
            if (actionDispatcher.deleteRepeatTarget(target.key) == null) return
            actionDispatcher.dispatchDelete(target.key)
            val held = backspaceRepeatSession.heldMillis(clock.uptimeMillis())
            scheduler.postDelayed(this, BackspaceRepeatPolicy.intervalMillis(held))
        }
    }

    fun updateKeyboardSkills(
        bindings: List<KeyboardSkillBinding>,
        active: ActiveKeyboardSkill?,
    ) = gestureCoordinator.updateKeyboardSkills(bindings, active)

    fun updateActiveKeyboardSkill(active: ActiveKeyboardSkill?) =
        gestureCoordinator.updateActiveKeyboardSkill(active)

    fun attachActiveSkillAuroraOverlay(overlay: ActiveSkillAuroraOverlayView?) =
        gestureCoordinator.attachOverlay(overlay)

    fun rejectPendingSkillSelection(requestToken: Long) =
        gestureCoordinator.rejectPendingSkillSelection(requestToken)

    fun showSkillFeedback(message: String) = gestureCoordinator.showFeedback(message)

    fun clearSkillFeedback() = gestureCoordinator.clearFeedback()

    fun updateAiSurface(
        generation: Long,
        phase: AiSurfacePhase,
        preview: String,
        statusText: String,
        activities: List<AiSurfaceActivity>,
    ): Boolean = gestureCoordinator.updateAiSurface(
        generation,
        phase,
        preview,
        statusText,
        activities,
    )

    fun appendAiStreamPreview(
        generation: Long,
        delta: String,
        phase: AiSurfacePhase,
    ): Boolean = gestureCoordinator.appendAiStreamPreview(generation, delta, phase)

    fun exitAiSurface(generation: Long): Boolean =
        gestureCoordinator.exitAiSurface(generation)

    fun onSceneRebuilt() = gestureCoordinator.onSceneRebuilt()

    fun onDrawCompleted(frameRequests: Int) =
        gestureCoordinator.onDrawCompleted(frameRequests)

    fun onAttached() = gestureCoordinator.onAttached()

    fun onDetached() {
        cancelAllTouches()
        scheduler.remove(candidateSettleRunnable)
        gestureCoordinator.onDetached()
        actionDispatcher.detach()
    }

    fun onConfigurationChanged() {
        cancelOrdinaryTouches()
    }

    fun onSizeChanged() {
        cancelAllTouches()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (aiSurfaceState != null) return gestureCoordinator.handleAiSurfaceTouch(event)
        panelScroll.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                handlePointerDown(event, event.actionIndex, isPrimary = true)

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!gestureCoordinator.pickerVisible) {
                    handlePointerDown(event, event.actionIndex, isPrimary = false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { pointerIndex ->
                    val pointerId = event.getPointerId(pointerIndex)
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    gestureCoordinator.movePointer(pointerId, x, y, event.eventTime)
                    if (gestureCoordinator.pickerOwns(pointerId)) return@repeat
                    if (gestureCoordinator.blocksOrdinaryPointer(pointerId)) return@repeat
                    if (candidatePanel.ownsDrag(pointerId)) {
                        val candidateMoveFlags = candidatePanel.moveDragFlags(
                            pointerId = pointerId,
                            x = x,
                            y = y,
                            eventTimeMillis = event.eventTime,
                        )
                        if (
                            candidateMoveFlags and
                                CandidateStripScrollState.MOVE_DRAG_LATCHED != 0
                        ) {
                            val clearedPressedState = pressedTargets.indexOfKey(pointerId) >= 0
                            touchReducer.cancel(pointerId)
                            pressedTargets.remove(pointerId)
                            val clearedOtherPressedState =
                                cancelOtherCandidateStripTouches(pointerId)
                            if (
                                candidateMoveFlags and
                                CandidateStripScrollState.MOVE_CHANGED != 0 ||
                                clearedPressedState ||
                                clearedOtherPressedState
                            ) {
                                invalidateCandidateViewport()
                            }
                            return@repeat
                        }
                    }
                    val target = touchReducer.target(pointerId) ?: return@repeat
                    val moveFlags = touchReducer.onMoveFlags(
                        pointerId = pointerId,
                        x = x,
                        y = y,
                        insideTapTarget = hitTester.isInsideTapTarget(target, x, y),
                        policy = target.gesturePolicy,
                        insideGestureBounds =
                            hitTester.isInsideUpwardGestureBoundary(target, y),
                    )
                    val scrollPanel = scrollPanelFor(target)
                    val scrollLatched =
                        moveFlags and TouchInputReducer.MOVE_SCROLL_LATCHED != 0
                    if (scrollLatched && scrollPanel != null) {
                        val coordinate = scrollCoordinate(scrollPanel, x, y)
                        panelScroll.acquireForLatchedPointer(
                            pointerId = pointerId,
                            panel = scrollPanel,
                            coordinate = coordinate,
                            event = event,
                        )
                    }
                    if (
                        scrollLatched &&
                        scrollPanel != null &&
                        pointerId == panelScroll.activePointerId &&
                        scrollPanel == panelScroll.activePanel
                    ) {
                        val currentCoordinate = scrollCoordinate(scrollPanel, x, y)
                        val previousCoordinate = panelScroll.previousPointerCoordinate(
                            pointerId,
                            currentCoordinate,
                        )
                        if (
                            scrollStateFor(scrollPanel).scrollBy(
                                previousCoordinate - currentCoordinate,
                            )
                        ) {
                            invalidateScrollPanel(scrollPanel)
                        }
                        panelScroll.rememberPointerCoordinate(pointerId, currentCoordinate)
                        if (panelScroll.latch(pointerId, scrollPanel)) {
                            cancelOtherPanelTouches(pointerId, scrollPanel)
                            invalidateScrollPanel(scrollPanel)
                        }
                    } else if (
                        scrollLatched &&
                        panelScroll.isLatched &&
                        pointerId != panelScroll.activePointerId
                    ) {
                        panelScroll.rememberPointerCoordinate(
                            pointerId,
                            scrollPanel?.let { scrollCoordinate(it, x, y) } ?: y,
                        )
                    }
                    if (
                        moveFlags and (
                            TouchInputReducer.MOVE_CANCELED or
                                TouchInputReducer.MOVE_TAP_SUPPRESSED
                            ) != 0
                    ) {
                        pressedTargets.remove(pointerId)
                        invalidateTouchTarget(target)
                        if (backspaceRepeatSession.owns(pointerId)) {
                            stopBackspaceRepeat(pointerId)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> handlePointerUp(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> {
                cancelAllTouches()
                scheduler.invalidate()
                return true
            }
        }
        return true
    }

    private fun handlePointerDown(
        event: MotionEvent,
        pointerIndex: Int,
        isPrimary: Boolean,
    ): Boolean {
        if (isPrimary) {
            val fullResetRequired =
                aiSurfaceState != null || gestureCoordinator.pickerVisible
            invalidatePressedTargets()
            cancelAllTouches()
            if (fullResetRequired) scheduler.invalidate()
        }
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val target = hitTester.targetAt(x, y) ?: return true
        val pointerId = event.getPointerId(pointerIndex)
        if (isPrimary) {
            touchReducer.onPrimaryDown(pointerId, target, x, y)
        } else {
            touchReducer.onDown(pointerId, target, x, y)
        }
        pressedTargets.put(pointerId, target)
        if (isCollapsedCandidateScrollTarget(target)) {
            stopCandidateSettle()
            candidatePanel.beginDrag(
                pointerId = pointerId,
                x = x,
                y = y,
                eventTimeMillis = event.eventTime,
            )
        }
        scrollPanelFor(target)?.let { panel ->
            panelScroll.start(
                pointerId = pointerId,
                panel = panel,
                coordinate = scrollCoordinate(panel, x, y),
                event = event,
            )
        }
        val key = (target as? FrozenTouchTarget.KeyValue)?.key
        if (key != null && actionDispatcher.deleteRepeatTarget(key) != null) {
            actionDispatcher.dispatchDelete(key)
            startBackspaceRepeat(pointerId)
        }
        gestureCoordinator.beginPointer(pointerId, key, x, y, event.eventTime)
        if (
            target !is FrozenTouchTarget.CandidatePageArea &&
            target !is FrozenTouchTarget.CandidateStripArea &&
            target !is FrozenTouchTarget.PanelScrollArea
        ) {
            haptics.perform(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidateTouchTarget(target)
        return true
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int): Boolean {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (gestureCoordinator.finishAiPointer(pointerId, event.eventTime)) {
            touchReducer.cancel(pointerId)
            pressedTargets.remove(pointerId)
            stopBackspaceRepeat(pointerId)
            panelScroll.forgetPointer(pointerId)
            scheduler.invalidate()
            return true
        }
        val skillFlags = gestureCoordinator.finishSkillPointer(pointerId, x, y)
        val skillOwned =
            skillFlags and KeyboardGestureCoordinator.FINISH_SKILL_OWNED != 0
        val skillConsumed =
            skillFlags and KeyboardGestureCoordinator.FINISH_SKILL_CONSUMED != 0
        if (skillConsumed) {
            touchReducer.cancel(pointerId)
            pressedTargets.remove(pointerId)
            stopBackspaceRepeat(pointerId)
            panelScroll.forgetPointer(pointerId)
            scheduler.invalidate()
            return true
        }

        val originalTarget = touchReducer.target(pointerId)
        val candidateSettle = candidatePanel.finishDrag(
            pointerId = pointerId,
            x = x,
            y = y,
            eventTimeMillis = event.eventTime,
            fastFlingVelocity = dp(CANDIDATE_FAST_FLING_VELOCITY_DP_PER_SECOND),
        )
        var activationGesture: TouchInputReducer.Gesture? = null
        val activationTarget: FrozenTouchTarget?
        if (candidateSettle?.dragged == true) {
            touchReducer.cancel(pointerId)
            activationTarget = null
        } else {
            activationTarget = touchReducer.target(pointerId)
            if (activationTarget != null) {
                activationGesture = touchReducer.onUpGesture(
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    insideTapTarget = hitTester.isInsideTapTarget(activationTarget, x, y),
                    policy = activationTarget.gesturePolicy,
                    insideGestureBounds =
                        hitTester.isInsideUpwardGestureBoundary(activationTarget, y),
                )
            }
        }
        candidateSettle?.let { settle ->
            if (settle.dragged || settle.animate) startCandidateSettle(settle)
        }
        panelScroll.finish(
            pointerId = pointerId,
            panel = originalTarget?.let(::scrollPanelFor),
            shouldFling = activationGesture != null &&
                activationGesture != TouchInputReducer.Gesture.TAP,
        )
        pressedTargets.remove(pointerId)
        stopBackspaceRepeat(pointerId)
        panelScroll.forgetPointer(pointerId)
        if (skillOwned) {
            scheduler.invalidate()
        } else if (originalTarget != null) {
            invalidateTouchTarget(originalTarget)
        }
        if (activationTarget != null && activationGesture != null) {
            actionDispatcher.activate(activationTarget, activationGesture)
            if (
                activationGesture == TouchInputReducer.Gesture.TAP &&
                activationTarget !is FrozenTouchTarget.CandidatePageArea &&
                activationTarget !is FrozenTouchTarget.CandidateStripArea &&
                activationTarget !is FrozenTouchTarget.PanelScrollArea
            ) {
                host.interactionPerformClick()
            }
        }
        return true
    }

    private fun invalidatePressedTargets() {
        repeat(pressedTargets.size()) { index ->
            invalidateTouchTarget(pressedTargets.valueAt(index))
        }
    }

    fun cancelAllTouches() = gestureCoordinator.cancelAllTouches()

    /**
     * Invalidates candidate targets frozen by an earlier publication without
     * disturbing simultaneous physical-key pointers.
     */
    fun cancelCandidatePointers() {
        var changed = false
        for (index in pressedTargets.size() - 1 downTo 0) {
            val target = pressedTargets.valueAt(index)
            if (!target.isCandidatePointerTarget()) continue
            val pointerId = pressedTargets.keyAt(index)
            touchReducer.cancel(pointerId)
            pressedTargets.removeAt(index)
            panelScroll.forgetPointer(pointerId)
            changed = true
        }
        if (changed) invalidateCandidateViewport()
    }

    /** Cancels only frozen targets whose left-rail spelling changed after an async decode. */
    fun cancelT9PinyinRailPointers() {
        var changed = false
        for (index in pressedTargets.size() - 1 downTo 0) {
            val target = pressedTargets.valueAt(index)
            if (!target.isT9PinyinRailPointerTarget()) continue
            val pointerId = pressedTargets.keyAt(index)
            touchReducer.cancel(pointerId)
            pressedTargets.removeAt(index)
            panelScroll.finish(
                pointerId = pointerId,
                panel = ScrollPanel.T9_LEFT_RAIL,
                shouldFling = false,
            )
            panelScroll.forgetPointer(pointerId)
            invalidateTouchTarget(target)
            changed = true
        }
        if (changed) scheduler.postInvalidateOnAnimation()
    }

    fun cancelOrdinaryTouches() {
        gestureCoordinator.cancelSkillGesture()
        clearOrdinaryPointerCore()
    }

    override fun clearOrdinaryInputFromGesture() {
        gestureCoordinator.cancelSkillGesture()
        clearOrdinaryPointerCore()
    }

    override fun suspendPointerInputForSkillPicker() {
        clearOrdinaryPointerCore()
    }

    override fun flushQueuedKeysBeforeAi() {
        actionDispatcher.flushKeys()
    }

    override fun canStartSkillGesture(key: Key): Boolean =
        actionDispatcher.canStartSkillGesture(key)

    private fun clearOrdinaryPointerCore() {
        touchReducer.cancelAll()
        pressedTargets.clear()
        panelScroll.clear()
        stopBackspaceRepeat()
        stopCandidateSettle()
        candidatePanel.cancelAllDrags()?.let { settle ->
            if (candidatePanel.moveTo(settle.targetOffset)) invalidateCandidateViewport()
        }
    }

    fun collapseCandidates() {
        stopCandidateSettle()
        candidatePanel.collapse(
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = host.interactionCandidateToolbarSuppressed(),
            fontScale = host.interactionFontScale,
        )
    }

    override fun stopCandidateSettle() {
        scheduler.remove(candidateSettleRunnable)
    }

    override fun stopPanelFling() {
        panelScroll.stopFling()
    }

    private fun startCandidateSettle(settle: CandidateStripScrollState.Settle) {
        stopCandidateSettle()
        if (!settle.animate) {
            if (candidatePanel.moveTo(settle.targetOffset)) invalidateCandidateViewport()
            return
        }
        candidateSettleStartOffset = candidatePanel.scrollOffset
        candidateSettleTargetOffset = settle.targetOffset
        candidateSettleStartedAtMillis = clock.uptimeMillis()
        scheduler.postOnAnimation(candidateSettleRunnable)
    }

    private fun invalidateCandidateViewport() {
        val bounds = candidatePanel.collapsedViewportBounds
        if (bounds == null) {
            scheduler.postInvalidateOnAnimation()
            return
        }
        scheduler.postInvalidateOnAnimation(
            bounds.left.toInt().coerceAtLeast(0),
            bounds.top.toInt().coerceAtLeast(0),
            (bounds.right + 1f).toInt().coerceAtMost(width),
            (bounds.bottom + 1f).toInt().coerceAtMost(height),
        )
    }

    private fun invalidateTouchTarget(target: FrozenTouchTarget) {
        when (target) {
            is FrozenTouchTarget.KeyValue -> {
                val panel = target.key.scrollPanel
                if (panel == null) {
                    invalidateDirtyBounds(target.bounds)
                } else {
                    invalidateScrollPanel(panel)
                }
            }

            is FrozenTouchTarget.CandidateStripArea -> invalidateCandidateViewport()
            is FrozenTouchTarget.PanelScrollArea -> invalidateScrollPanel(target.panel)
            else -> invalidateDirtyBounds(target.bounds)
        }
    }

    private fun invalidateDirtyBounds(bounds: RectF) {
        if (bounds.isEmpty) return
        val padding = dp(2f)
        scheduler.postInvalidateOnAnimation(
            (bounds.left - padding).toInt().coerceAtLeast(0),
            (bounds.top - padding).toInt().coerceAtLeast(0),
            (bounds.right + padding + 1f).toInt().coerceAtMost(width),
            (bounds.bottom + padding + 1f).toInt().coerceAtMost(height),
        )
    }

    private fun cancelOtherPanelTouches(
        ownerPointerId: Int,
        panel: ScrollPanel,
    ) {
        for (index in pressedTargets.size() - 1 downTo 0) {
            val pointerId = pressedTargets.keyAt(index)
            val target = pressedTargets.valueAt(index)
            if (pointerId != ownerPointerId && scrollPanelFor(target) == panel) {
                touchReducer.cancel(pointerId)
                pressedTargets.removeAt(index)
                panelScroll.forgetPointer(pointerId)
            }
        }
    }

    private fun cancelOtherCandidateStripTouches(ownerPointerId: Int): Boolean {
        var changed = false
        for (index in pressedTargets.size() - 1 downTo 0) {
            val pointerId = pressedTargets.keyAt(index)
            val target = pressedTargets.valueAt(index)
            val isCandidateTarget =
                target is FrozenTouchTarget.CandidateValue ||
                    target is FrozenTouchTarget.CandidateStripArea
            if (pointerId != ownerPointerId && isCandidateTarget) {
                touchReducer.cancel(pointerId)
                pressedTargets.removeAt(index)
                changed = true
            }
        }
        return changed
    }

    private fun startBackspaceRepeat(pointerId: Int) {
        if (!backspaceRepeatSession.tryStart(pointerId, clock.uptimeMillis())) return
        scheduler.remove(backspaceRepeatRunnable)
        scheduler.postDelayed(backspaceRepeatRunnable, BackspaceRepeatPolicy.INITIAL_DELAY_MS)
    }

    private fun stopBackspaceRepeat(pointerId: Int? = null) {
        if (pointerId != null && !backspaceRepeatSession.stop(pointerId)) return
        if (pointerId == null) backspaceRepeatSession.clear()
        scheduler.remove(backspaceRepeatRunnable)
    }

    private fun scrollPanelFor(target: FrozenTouchTarget): ScrollPanel? = when (target) {
        is FrozenTouchTarget.PanelScrollArea -> target.panel
        is FrozenTouchTarget.KeyValue -> target.key.scrollPanel
        else -> null
    }

    fun panelViewportBounds(panel: ScrollPanel): RectF? = when (panel) {
        ScrollPanel.EMOJI -> scene.emojiGridBounds
        ScrollPanel.EMOJI_CATEGORIES -> scene.emojiCategoryBounds
        ScrollPanel.SYMBOL_CATEGORIES -> scene.symbolCategoryBounds
        ScrollPanel.SYMBOL_VALUES -> scene.symbolGridBounds
        ScrollPanel.T9_LEFT_RAIL -> scene.t9LeftRailBounds
    }

    private fun isCollapsedCandidateScrollTarget(target: FrozenTouchTarget): Boolean =
        candidatePanel.canStartCollapsedDrag() &&
            (
                target is FrozenTouchTarget.CandidateValue ||
                    target is FrozenTouchTarget.CandidateStripArea
                )

    private fun gesturePolicyForKey(key: Key): TouchInputReducer.GesturePolicy = when {
        key.scrollPanel?.axis == ScrollAxis.HORIZONTAL -> horizontalScrollGesturePolicy
        key.scrollPanel != null || key.style == KeyStyle.CARD -> pageScrollGesturePolicy
        key.swipeOutput != null ->
            TouchInputReducer.GesturePolicy.upwardFlick(
                minimumDistance = KeyboardGestureThresholds.upwardFlickDistance(
                    minimumDistance = dp(12f),
                    keyHeight = key.bounds.height(),
                ),
                verticalDominanceRatio = VERTICAL_GESTURE_DOMINANCE,
                requirePointerExit = key.style != KeyStyle.T9_PRIMARY,
            )

        else -> tapGesturePolicy
    }

    fun computeScroll() {
        panelScroll.computeScroll()
    }

    fun isCandidatePressed(sourceIndex: Int): Boolean {
        for (index in 0 until pressedTargets.size()) {
            val target = pressedTargets.valueAt(index)
            if (
                target is FrozenTouchTarget.CandidateValue &&
                target.revision == candidatePanel.candidateRevision &&
                target.sourceIndex == sourceIndex
            ) {
                return true
            }
        }
        return false
    }

    fun isCandidateControlPressed(control: CandidateControl): Boolean {
        for (index in 0 until pressedTargets.size()) {
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.CandidateControlValue && target.value == control) {
                return true
            }
        }
        return false
    }

    fun isKeyPressed(key: Key): Boolean {
        for (index in 0 until pressedTargets.size()) {
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.KeyValue && target.key === key) return true
        }
        return false
    }

    fun isKeyEnabled(key: Key): Boolean = actionDispatcher.isKeyEnabled(key)

    override val hitTestKeys: List<Key>
        get() = keys
    override val hitTestCandidatePanel: CandidatePanel
        get() = candidatePanel
    override val hitTestKeyGap: Float
        get() = metrics.keyGap
    override val hitTestTouchSlop: Float
        get() = scaledTouchSlop

    override fun showsCandidatesForHitTest(): Boolean = host.interactionShowsCandidates()

    override fun isKeyEnabledForHitTest(key: Key): Boolean = isKeyEnabled(key)

    override fun panelViewportBoundsForHitTest(panel: ScrollPanel): RectF? =
        panelViewportBounds(panel)

    override fun scrollStateForHitTest(panel: ScrollPanel): ContinuousVerticalScrollState =
        scrollStateFor(panel)

    override fun gesturePolicyForHitTest(key: Key): TouchInputReducer.GesturePolicy =
        gesturePolicyForKey(key)

    override fun scrollStateFor(panel: ScrollPanel): ContinuousVerticalScrollState =
        when (panel) {
            ScrollPanel.EMOJI -> scene.emojiScrollState
            ScrollPanel.EMOJI_CATEGORIES -> scene.emojiCategoryScrollState
            ScrollPanel.SYMBOL_CATEGORIES -> scene.symbolCategoryScrollState
            ScrollPanel.SYMBOL_VALUES -> scene.symbolGridScrollState
            ScrollPanel.T9_LEFT_RAIL -> scene.t9LeftRailScrollState
        }

    private fun scrollCoordinate(panel: ScrollPanel, x: Float, y: Float): Float =
        if (panel.axis == ScrollAxis.HORIZONTAL) x else y

    override fun invalidateScrollPanel(panel: ScrollPanel) {
        val bounds = panelViewportBounds(panel)
        if (bounds == null) {
            scheduler.postInvalidateOnAnimation()
            return
        }
        scheduler.postInvalidateOnAnimation(
            bounds.left.toInt().coerceAtLeast(0),
            bounds.top.toInt().coerceAtLeast(0),
            (bounds.right + 1f).toInt().coerceAtMost(width),
            (bounds.bottom + 1f).toInt().coerceAtMost(height),
        )
    }

    private val width: Int
        get() = host.interactionWidth
    private val height: Int
        get() = host.interactionHeight
    private val scene: MutableKeyboardScene
        get() = host.interactionScene
    private val keys: List<Key>
        get() = scene.keys
    private val candidatePanel: CandidatePanel
        get() = host.interactionCandidatePanel
    private fun dp(value: Float): Float = value * density

    private companion object {
        const val VERTICAL_GESTURE_DOMINANCE = 1.15f
        const val CANDIDATE_FAST_FLING_VELOCITY_DP_PER_SECOND = 720f
        const val CANDIDATE_SETTLE_DURATION_MILLIS = 180L
    }
}
