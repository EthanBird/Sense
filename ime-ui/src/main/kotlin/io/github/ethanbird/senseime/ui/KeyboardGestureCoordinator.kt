package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent

internal interface KeyboardGestureEffects {
    fun clearOrdinaryInputFromGesture()
    fun suspendPointerInputForSkillPicker()
    fun flushQueuedKeysBeforeAi()
    fun canStartSkillGesture(key: Key): Boolean
}

/**
 * Coordinates the two exclusive long-hold gestures and their visual state.
 *
 * AI and Skill share timing, pointer exclusivity, haptic, accessibility, and
 * delayed-callback concerns. Keeping them here prevents the MotionEvent router
 * from owning provider/session UI policy.
 */
internal class KeyboardGestureCoordinator(
    private val host: KeyboardInteractionHost,
    private val density: Float,
    private val metrics: KeyboardMetrics,
    scaledTouchSlop: Float,
    private val scheduler: KeyboardFrameScheduler,
    private val clock: KeyboardInteractionClock,
    private val haptics: KeyboardHaptics,
    private val actions: KeyboardInteractionActionSink,
    private val effects: KeyboardGestureEffects,
) {
    private val aiHoldSession = AiHoldGestureSession(
        maximumStationaryDistance = maxOf(scaledTouchSlop, dp(10f)),
        lockDistance = dp(AiSurfaceContract.LOCK_DISTANCE_DP),
    )
    private val skillSelectionDistance = maxOf(dp(24f), scaledTouchSlop * 1.8f)
    private val skillSession = KeyboardSkillGestureSession(
        maximumStationaryDistance = maxOf(scaledTouchSlop, dp(10f)),
        selectionDistance = skillSelectionDistance,
    )
    private val skillHapticGate = KeyboardSkillHapticGate()

    private var scheduledAiPointerId = NO_POINTER
    private var scheduledAiGeneration = 0L
    private var scheduledSkillPointerId = NO_POINTER
    private var scheduledSkillGeneration = 0L

    private var skillBindings = KeyboardSkillBindingSet.EMPTY
    var activeKeyboardSkill: ActiveKeyboardSkill? = null
        private set
    var activeSkillSourceKey: Key? = null
        private set
    private var activeSkillOverlay: ActiveSkillAuroraOverlayView? = null
    private var skillVisualOwnerState = KeyboardSkillVisualOwnerState()
    private val pickerSourceBoundsStorage = RectF()
    private var pickerSourceOwner: KeyboardSkillPhysicalOwner? = null
    var pickerOptions: KeyboardSkillOptions? = null
        private set
    private var pickerLayout: KeyboardSkillPickerLayout? = null
    val pickerOptionBounds: Array<RectF> =
        Array(KeyboardSkillDirection.entries.size) { RectF() }
    private val animationDirtyBounds = RectF()
    private var animationFrameScheduled = false

    var feedbackMessage: String? = null
        private set
    var aiSurfaceState: AiSurfaceState? = null
        private set
    var aiLockProgress: Float = 0f
        private set
    var aiLocked: Boolean = false
        private set
    private var aiStopPointerId = NO_POINTER

    val pickerSourceBounds: RectF
        get() = pickerSourceBoundsStorage
    val pickerVisible: Boolean
        get() = skillSession.isPickerVisible()
    val highlightedDirection: KeyboardSkillDirection?
        get() = skillSession.highlightedDirection()
    val aiStopPressed: Boolean
        get() = aiStopPointerId != NO_POINTER
    val hasAuroraSibling: Boolean
        get() = activeSkillOverlay != null

    private val aiActivationRunnable = object : Runnable {
        override fun run() {
            val pointerId = scheduledAiPointerId
            if (pointerId == NO_POINTER) return
            val generation = scheduledAiGeneration
            val now = clock.uptimeMillis()
            val outcome = aiHoldSession.tryActivate(
                pointerId = pointerId,
                expectedGeneration = generation,
                nowMillis = now,
            )
            if (outcome == AiHoldGestureSession.Outcome.ACTIVATED) {
                scheduledAiPointerId = NO_POINTER
                scheduledAiGeneration = 0L
                beginAiSurface(generation)
                return
            }
            val remaining = aiHoldSession.millisUntilActivation(now)
            if (remaining > 0L && aiHoldSession.armedGeneration() == generation) {
                scheduler.postDelayed(this, remaining)
            }
        }
    }

    private val skillActivationRunnable = object : Runnable {
        override fun run() {
            val pointerId = scheduledSkillPointerId
            if (pointerId == NO_POINTER) return
            val generation = scheduledSkillGeneration
            val now = clock.uptimeMillis()
            val outcome = skillSession.tryActivate(
                pointerId = pointerId,
                expectedGeneration = generation,
                nowMillis = now,
            )
            if (outcome == KeyboardSkillGestureSession.Outcome.PICKER_SHOWN) {
                scheduledSkillPointerId = NO_POINTER
                scheduledSkillGeneration = 0L
                aiHoldSession.cancelAll()
                clearScheduledAiHold()
                effects.suspendPointerInputForSkillPicker()
                rebuildPickerLayout()
                skillHapticGate.reset()
                updateAccessibilityDescription()
                haptics.perform(HapticFeedbackConstants.LONG_PRESS)
                pickerOptions?.let { options ->
                    announceAccessibility(
                        KeyboardSkillAccessibilityText.pickerOpened(options),
                    )
                }
                scheduler.invalidate()
                return
            }
            val remaining = skillSession.millisUntilActivation(now)
            if (remaining > 0L && skillSession.armedGeneration() == generation) {
                scheduler.postDelayed(this, remaining)
            }
        }
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            animationFrameScheduled = false
            if (!shouldAnimate()) return
            updateAnimationDirtyBounds()
            if (animationDirtyBounds.isEmpty) return
            val margin = dp(4f)
            scheduler.postInvalidateOnAnimation(
                (animationDirtyBounds.left - margin).toInt().coerceAtLeast(0),
                (animationDirtyBounds.top - margin).toInt().coerceAtLeast(0),
                (animationDirtyBounds.right + margin + 1f).toInt()
                    .coerceAtMost(host.interactionWidth),
                (animationDirtyBounds.bottom + margin + 1f).toInt()
                    .coerceAtMost(host.interactionHeight),
            )
        }
    }

    private val clearFeedbackRunnable = Runnable {
        if (feedbackMessage != null) {
            feedbackMessage = null
            updateAccessibilityDescription()
            scheduler.invalidate()
        }
    }

    fun initialize() {
        updateAccessibilityDescription()
    }

    fun beginPointer(
        pointerId: Int,
        key: Key?,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ) {
        if (key?.code == KeyCodes.SPACE) {
            aiHoldSession.begin(
                pointerId = pointerId,
                x = x,
                y = y,
                eventTimeMillis = eventTimeMillis,
            )?.let(::scheduleAiHold)
            return
        }
        if (key == null || !effects.canStartSkillGesture(key)) return
        val options = KeyboardBuiltInGesturePolicy.optionsForKey(
            keyCode = key.code,
            configured = skillBindings.optionsForKey(key.code),
        ) ?: return
        if (options.count <= 0) return
        skillSession.begin(
            pointerId = pointerId,
            x = x,
            y = y,
            eventTimeMillis = eventTimeMillis,
            enabledDirectionMask = options.directionMask,
        )?.let { arm ->
            pickerSourceBoundsStorage.set(key.bounds)
            pickerSourceOwner = host.interactionScene.physicalIdFor(key)?.toSkillOwner()
            pickerOptions = options
            scheduleSkillHold(arm)
        }
    }

    /**
     * Runs AI eligibility then Skill projection, preserving historical order.
     * Ordinary pointer blocking is queried separately to keep this result
     * allocation-free.
     */
    fun movePointer(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ) {
        if (
            aiHoldSession.move(pointerId, x, y) ==
            AiHoldGestureSession.Outcome.ELIGIBILITY_CANCELLED
        ) {
            clearScheduledAiHold()
        }
        when (
            skillSession.move(
                pointerId = pointerId,
                x = x,
                y = y,
                pickerLayout = pickerLayout,
            )
        ) {
            KeyboardSkillGestureSession.Outcome.ELIGIBILITY_CANCELLED -> {
                val hadProjection = pickerLayout != null
                clearScheduledSkillHold(clearProjection = true)
                if (hadProjection) scheduler.invalidate()
            }

            KeyboardSkillGestureSession.Outcome.HIGHLIGHT_CHANGED -> {
                val direction = skillSession.highlightedDirection()
                updateAccessibilityDescription()
                if (skillHapticGate.shouldEmit(eventTimeMillis, direction)) {
                    haptics.perform(HapticFeedbackConstants.CLOCK_TICK)
                }
                announceAccessibility(
                    KeyboardSkillAccessibilityText.highlighted(
                        direction = direction,
                        binding = direction?.let { pickerOptions?.binding(it) },
                    ),
                )
                scheduler.invalidate()
            }

            else -> Unit
        }
    }

    fun pickerOwns(pointerId: Int): Boolean =
        skillSession.isPickerVisible() && skillSession.owns(pointerId)

    fun blocksOrdinaryPointer(pointerId: Int): Boolean =
        skillSession.blocksOrdinaryPointer(pointerId)

    /**
     * Returns true when a threshold-crossed hold consumed the frozen ordinary
     * target. Short taps remain available to the pointer router.
     */
    fun finishAiPointer(pointerId: Int, eventTimeMillis: Long): Boolean =
        when (aiHoldSession.pointerUp(pointerId, eventTimeMillis)) {
            AiHoldGestureSession.Outcome.SHORT_TAP -> {
                clearScheduledAiHold()
                false
            }

            AiHoldGestureSession.Outcome.HOLD_RELEASED -> {
                clearScheduledAiHold()
                true
            }

            else -> false
        }

    /**
     * Final ACTION_UP coordinates are applied before selection. Result flags
     * encode prior ownership and consumption without allocating a command.
     */
    fun finishSkillPointer(
        pointerId: Int,
        x: Float,
        y: Float,
    ): Int {
        val owned = skillSession.owns(pointerId)
        if (owned) {
            skillSession.move(pointerId, x, y, pickerLayout)
        }
        val finish = skillSession.pointerUp(pointerId)
        if (owned) clearScheduledSkillHold(clearProjection = false)
        var flags = if (owned) FINISH_SKILL_OWNED else 0
        if (finish.consumed) {
            flags = flags or FINISH_SKILL_CONSUMED
            finish.direction?.let(::commitSkillSelection)
            clearPickerProjection()
        } else if (owned) {
            clearPickerProjection()
        }
        return flags
    }

    fun updateKeyboardSkills(
        bindings: List<KeyboardSkillBinding>,
        active: ActiveKeyboardSkill?,
    ) {
        val nextBindings = KeyboardSkillBindingSet.from(bindings)
        if (
            activeKeyboardSkill == active &&
            skillBindings.hasSameProjection(nextBindings)
        ) {
            return
        }
        cancelSkillGesture()
        val previous = activeKeyboardSkill
        val previousLabel = skillLabel(previous, skillBindings)
        val currentLabel = skillLabel(active, nextBindings)
        skillBindings = nextBindings
        activeKeyboardSkill = active
        skillVisualOwnerState = KeyboardSkillVisualOwnerPolicy.project(
            skillVisualOwnerState,
            active,
        )
        resolveActiveSkillSourceKey()
        updateAccessibilityDescription()
        scheduler.invalidate()
        KeyboardSkillAccessibilityText.activeChanged(
            previousSkillId = previous?.skillId,
            previousLabel = previousLabel,
            currentSkillId = active?.skillId,
            currentLabel = currentLabel,
        )?.let(::announceAccessibility)
    }

    fun updateActiveKeyboardSkill(active: ActiveKeyboardSkill?) {
        if (activeKeyboardSkill == active) return
        val previous = activeKeyboardSkill
        val previousLabel = skillLabel(previous, skillBindings)
        val currentLabel = skillLabel(active, skillBindings)
        activeKeyboardSkill = active
        skillVisualOwnerState = KeyboardSkillVisualOwnerPolicy.project(
            skillVisualOwnerState,
            active,
        )
        resolveActiveSkillSourceKey()
        updateAccessibilityDescription()
        scheduler.invalidate()
        KeyboardSkillAccessibilityText.activeChanged(
            previousSkillId = previous?.skillId,
            previousLabel = previousLabel,
            currentSkillId = active?.skillId,
            currentLabel = currentLabel,
        )?.let(::announceAccessibility)
    }

    fun attachOverlay(overlay: ActiveSkillAuroraOverlayView?) {
        if (activeSkillOverlay === overlay) {
            publishAurora()
            return
        }
        activeSkillOverlay?.updateBounds(null, metrics.keyRadius)
        activeSkillOverlay = overlay
        publishAurora()
        scheduler.invalidate()
    }

    fun rejectPendingSkillSelection(requestToken: Long) {
        val next = KeyboardSkillVisualOwnerPolicy.reject(
            skillVisualOwnerState,
            requestToken,
        )
        if (next == skillVisualOwnerState) return
        skillVisualOwnerState = next
        resolveActiveSkillSourceKey()
        scheduler.invalidate()
    }

    fun showFeedback(message: String) {
        val bounded = message.trim().take(FEEDBACK_MAX_CHARS)
        if (bounded.isEmpty()) return
        scheduler.remove(clearFeedbackRunnable)
        feedbackMessage = bounded
        updateAccessibilityDescription()
        scheduler.invalidate()
        scheduler.post(
            Runnable {
                if (feedbackMessage == bounded && host.interactionIsShown) {
                    announceAccessibility(bounded)
                }
            },
        )
        scheduler.postDelayed(clearFeedbackRunnable, FEEDBACK_DURATION_MILLIS)
    }

    fun clearFeedback() {
        if (feedbackMessage == null) return
        scheduler.remove(clearFeedbackRunnable)
        feedbackMessage = null
        updateAccessibilityDescription()
        scheduler.invalidate()
    }

    fun updateAiSurface(
        generation: Long,
        phase: AiSurfacePhase,
        preview: String,
        statusText: String,
        activities: List<AiSurfaceActivity>,
    ): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        val boundedPreview = AiSurfaceContract.boundedPreview(preview)
        val stablePreview = when {
            boundedPreview.isNotEmpty() -> boundedPreview
            current.preview.isNotEmpty() &&
                phase != AiSurfacePhase.COMPLETE &&
                phase != AiSurfacePhase.ERROR -> current.preview
            else -> boundedPreview
        }
        publishAiState(
            current.copy(
                phase = phase,
                preview = stablePreview,
                statusText = statusText.ifBlank {
                    current.statusText.ifBlank { defaultAiStatus(phase) }
                },
                activities = activities.takeLast(AiSurfaceContract.MAX_VISIBLE_ACTIVITIES),
                lockProgress = aiLockProgress,
                locked = aiLocked,
            ),
        )
        scheduler.invalidate()
        return true
    }

    fun appendAiStreamPreview(
        generation: Long,
        delta: String,
        phase: AiSurfacePhase,
    ): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        publishAiState(
            current.copy(
                phase = phase,
                preview = AiSurfaceContract.appendBounded(current.preview, delta),
                statusText = current.statusText.ifBlank { defaultAiStatus(phase) },
                lockProgress = aiLockProgress,
                locked = aiLocked,
            ),
        )
        scheduler.invalidate()
        return true
    }

    fun exitAiSurface(generation: Long): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        aiHoldSession.cancelAll()
        clearScheduledAiHold()
        publishAiState(null)
        aiStopPointerId = NO_POINTER
        host.interactionRelayoutCandidates()
        host.interactionRebuildKeys()
        scheduler.invalidate()
        return true
    }

    fun handleAiSurfaceTouch(event: MotionEvent): Boolean {
        if (aiSurfaceState == null) return true
        if (aiLocked) return handleLockedAiSurfaceTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { pointerIndex ->
                    val pointerId = event.getPointerId(pointerIndex)
                    if (!aiHoldSession.owns(pointerId)) return@repeat
                    val outcome = aiHoldSession.move(
                        pointerId,
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    )
                    val nextLocked = aiHoldSession.isLocked()
                    val nextProgress = aiHoldSession.lockProgress()
                    val visualChanged =
                        nextLocked != aiLocked || nextProgress != aiLockProgress
                    aiLocked = nextLocked
                    aiLockProgress = nextProgress
                    updateAiGeometry()
                    if (outcome == AiHoldGestureSession.Outcome.LOCKED) {
                        haptics.perform(HapticFeedbackConstants.CONFIRM)
                    }
                    if (
                        visualChanged &&
                        (
                            outcome == AiHoldGestureSession.Outcome.LOCK_PROGRESS ||
                                nextLocked
                            )
                    ) {
                        scheduler.invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (aiHoldSession.owns(pointerId)) {
                    when (aiHoldSession.pointerUp(pointerId, event.eventTime)) {
                        AiHoldGestureSession.Outcome.ACTIVE_CANCELLED -> {
                            exitAndCancelAi(forceStop = false)
                            host.interactionPerformClick()
                        }

                        AiHoldGestureSession.Outcome.LOCKED_RELEASED -> {
                            aiLockProgress = 1f
                            aiLocked = true
                            updateAiGeometry()
                            scheduler.invalidate()
                        }

                        else -> Unit
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> exitAndCancelAi(forceStop = false)
            MotionEvent.ACTION_DOWN -> exitAndCancelAi(forceStop = false)
        }
        return true
    }

    fun cancelAllTouches() {
        val activeGeneration = aiSurfaceState?.generation
        val aiOutcome = aiHoldSession.cancelAll()
        clearScheduledAiHold()
        publishAiState(null)
        publishAurora()
        aiStopPointerId = NO_POINTER
        effects.clearOrdinaryInputFromGesture()
        if (
            aiOutcome == AiHoldGestureSession.Outcome.ACTIVE_CANCELLED &&
            activeGeneration != null
        ) {
            actions.onAiHoldCancelled(activeGeneration)
        }
    }

    fun cancelSkillGesture() {
        skillSession.cancelAll()
        clearScheduledSkillHold(clearProjection = true)
    }

    fun onSceneRebuilt() {
        resolveActiveSkillSourceKey()
        updateAiGeometry()
    }

    fun onDrawCompleted(frameRequests: Int) {
        if (frameRequests and KeyboardFrameRequest.DELAYED_AI != 0) {
            scheduler.postInvalidateDelayed(120L)
        }
        if (frameRequests and KeyboardFrameRequest.NEXT_ANIMATION != 0) {
            scheduler.postInvalidateOnAnimation()
        }
        if (aiSurfaceState == null) {
            if (shouldAnimate()) {
                scheduleAnimationFrame()
            } else if (animationFrameScheduled) {
                scheduler.remove(animationRunnable)
                animationFrameScheduled = false
            }
        }
    }

    fun onAttached() {
        publishAurora()
        activeSkillOverlay?.refreshMotionPreference()
    }

    fun onDetached() {
        activeSkillOverlay?.updateBounds(null, metrics.keyRadius)
        scheduler.remove(animationRunnable)
        scheduler.remove(clearFeedbackRunnable)
        feedbackMessage = null
        updateAccessibilityDescription()
        animationFrameScheduled = false
    }

    private fun handleLockedAiSurfaceTouch(event: MotionEvent): Boolean {
        val stopBounds = host.interactionAiGeometry.stopBounds
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                if (stopBounds.contains(event.getX(index), event.getY(index))) {
                    aiStopPointerId = event.getPointerId(index)
                    haptics.perform(HapticFeedbackConstants.KEYBOARD_TAP)
                    scheduler.invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerId = aiStopPointerId
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)
                    if (
                        index < 0 ||
                        !stopBounds.contains(event.getX(index), event.getY(index))
                    ) {
                        aiStopPointerId = NO_POINTER
                        scheduler.invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (aiHoldSession.owns(pointerId)) {
                    aiHoldSession.pointerUp(pointerId, event.eventTime)
                }
                if (pointerId == aiStopPointerId) {
                    val activate = stopBounds.contains(
                        event.getX(index),
                        event.getY(index),
                    )
                    aiStopPointerId = NO_POINTER
                    if (activate) {
                        haptics.perform(HapticFeedbackConstants.CONFIRM)
                        exitAndCancelAi(forceStop = true)
                        host.interactionPerformClick()
                    } else {
                        scheduler.invalidate()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                aiHoldSession.releaseLockedPointerOwnership()
                aiStopPointerId = NO_POINTER
                scheduler.invalidate()
            }
        }
        return true
    }

    private fun beginAiSurface(generation: Long) {
        effects.clearOrdinaryInputFromGesture()
        effects.flushQueuedKeysBeforeAi()
        publishAiState(
            AiSurfaceState(
                generation = generation,
                phase = AiSurfacePhase.STARTING,
                preview = "",
                statusText = defaultAiStatus(AiSurfacePhase.STARTING),
                activities = listOf(
                    AiSurfaceActivity(
                        id = "activation",
                        title = "正在读取输入框并建立安全会话",
                    ),
                ),
            ),
        )
        publishAurora()
        aiStopPointerId = NO_POINTER
        haptics.perform(HapticFeedbackConstants.LONG_PRESS)
        scheduler.invalidate()
        actions.onAiHoldStarted(generation)
    }

    private fun exitAndCancelAi(forceStop: Boolean) {
        val generation = aiSurfaceState?.generation ?: return
        aiHoldSession.cancelAll()
        clearScheduledAiHold()
        publishAiState(null)
        aiStopPointerId = NO_POINTER
        effects.clearOrdinaryInputFromGesture()
        host.interactionRelayoutCandidates()
        host.interactionRebuildKeys()
        scheduler.invalidate()
        if (forceStop) {
            actions.onAiStopRequested(generation)
        } else {
            actions.onAiHoldCancelled(generation)
        }
    }

    private fun scheduleAiHold(arm: AiHoldGestureSession.Arm) {
        scheduledAiPointerId = arm.pointerId
        scheduledAiGeneration = arm.generation
        scheduler.remove(aiActivationRunnable)
        scheduler.postDelayed(
            aiActivationRunnable,
            (arm.activationAtMillis - clock.uptimeMillis()).coerceAtLeast(0L),
        )
    }

    private fun clearScheduledAiHold() {
        scheduledAiPointerId = NO_POINTER
        scheduledAiGeneration = 0L
        scheduler.remove(aiActivationRunnable)
    }

    private fun scheduleSkillHold(arm: KeyboardSkillGestureSession.Arm) {
        scheduledSkillPointerId = arm.pointerId
        scheduledSkillGeneration = arm.generation
        scheduler.remove(skillActivationRunnable)
        scheduler.postDelayed(
            skillActivationRunnable,
            (arm.activationAtMillis - clock.uptimeMillis()).coerceAtLeast(0L),
        )
    }

    private fun clearScheduledSkillHold(clearProjection: Boolean) {
        scheduledSkillPointerId = NO_POINTER
        scheduledSkillGeneration = 0L
        scheduler.remove(skillActivationRunnable)
        if (clearProjection) clearPickerProjection()
    }

    private fun clearPickerProjection() {
        pickerSourceBoundsStorage.setEmpty()
        pickerSourceOwner = null
        pickerOptions = null
        pickerLayout = null
        for (slot in pickerOptionBounds) slot.setEmpty()
        skillHapticGate.reset()
        updateAccessibilityDescription()
    }

    private fun rebuildPickerLayout() {
        val options = pickerOptions
        val width = host.interactionWidth
        val height = host.interactionHeight
        if (options == null || pickerSourceBoundsStorage.isEmpty || width <= 0 || height <= 0) {
            pickerLayout = null
            for (slot in pickerOptionBounds) slot.setEmpty()
            return
        }
        val horizontalInset = minOf(metrics.horizontalPadding, width * 0.1f)
        val verticalInset = minOf(dp(4f), height * 0.1f)
        val chipWidth = minOf(
            dp(76f),
            width * 0.24f,
            (width - horizontalInset * 2f).coerceAtLeast(1f),
        )
        val chipHeight = minOf(
            dp(36f),
            (height - verticalInset * 2f).coerceAtLeast(1f),
        )
        pickerLayout = KeyboardSkillPickerGeometry.layout(
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            source = KeyboardSkillPickerBounds(
                left = pickerSourceBoundsStorage.left,
                top = pickerSourceBoundsStorage.top,
                right = pickerSourceBoundsStorage.right,
                bottom = pickerSourceBoundsStorage.bottom,
            ),
            enabledDirectionMask = options.directionMask,
            chipWidth = chipWidth,
            chipHeight = chipHeight,
            horizontalRadius = maxOf(dp(82f), chipWidth + dp(6f)),
            verticalRadius = maxOf(dp(54f), chipHeight + dp(8f)),
            horizontalInset = horizontalInset,
            verticalInset = verticalInset,
            minimumReachDistance = skillSelectionDistance + dp(4f),
            gap = dp(4f),
        )
        for (direction in KeyboardSkillDirection.entries) {
            val geometry = pickerLayout?.slot(direction)
            val target = pickerOptionBounds[direction.ordinal]
            if (geometry == null) {
                target.setEmpty()
            } else {
                target.set(
                    geometry.bounds.left,
                    geometry.bounds.top,
                    geometry.bounds.right,
                    geometry.bounds.bottom,
                )
            }
        }
    }

    private fun commitSkillSelection(direction: KeyboardSkillDirection) {
        val binding = pickerOptions?.binding(direction) ?: return
        KeyboardBuiltInGesturePolicy.command(binding)?.let { command ->
            haptics.perform(HapticFeedbackConstants.KEYBOARD_TAP)
            actions.onKey(command.keyCode)
            return
        }
        val action = KeyboardSkillTogglePolicy.resolve(activeKeyboardSkill, binding)
        val requestToken = SKILL_SELECTION_REQUEST_TOKENS.next()
        val expectedActive = when (action) {
            KeyboardSkillToggleAction.ACTIVATE -> ActiveKeyboardSkill(
                skillId = binding.skillId,
                sourceKeyCode = binding.keyCode,
                direction = binding.direction,
            )

            KeyboardSkillToggleAction.DEACTIVATE -> null
        }
        val owner = when (action) {
            KeyboardSkillToggleAction.ACTIVATE -> checkNotNull(pickerSourceOwner) {
                "Visible Skill picker lost its physical source owner"
            }

            KeyboardSkillToggleAction.DEACTIVATE -> null
        }
        skillVisualOwnerState = KeyboardSkillVisualOwnerPolicy.request(
            skillVisualOwnerState,
            KeyboardSkillPendingVisualOwner(
                requestToken = requestToken,
                expectedActive = expectedActive,
                owner = owner,
            ),
        )
        haptics.perform(HapticFeedbackConstants.KEYBOARD_TAP)
        actions.onSkillSelection(
            KeyboardSkillSelection(binding, action, requestToken),
        )
    }

    private fun resolveActiveSkillSourceKey() {
        activeSkillSourceKey = findActiveSkillSourceKey()
        publishAurora()
    }

    private fun findActiveSkillSourceKey(): Key? {
        val active = activeKeyboardSkill ?: return null
        val binding = skillBindings.binding(active.sourceKeyCode, active.direction)
            ?.takeIf { it.skillId == active.skillId }
            ?: return null
        skillVisualOwnerState.confirmed?.let { confirmed ->
            if (confirmed.active == active) {
                return host.interactionScene.keyFor(confirmed.owner)
            }
        }
        var toolbarFallback: Key? = null
        for (key in host.interactionScene.keys) {
            if (key.code != binding.keyCode || !effects.canStartSkillGesture(key)) continue
            if (key.style != KeyStyle.TOOL) return key
            if (toolbarFallback == null) toolbarFallback = key
        }
        return toolbarFallback
    }

    private fun publishAurora() {
        val overlay = activeSkillOverlay ?: return
        val bounds = activeSkillSourceKey
            ?.takeIf { aiSurfaceState == null }
            ?.bounds
        overlay.updateBounds(bounds, metrics.keyRadius)
    }

    private fun shouldAnimate(): Boolean =
        KeyboardSkillAnimationPolicy.shouldAnimate(
            activeSkill = activeKeyboardSkill.takeIf {
                activeSkillOverlay == null && activeSkillSourceKey != null
            },
            pickerVisible = skillSession.isPickerVisible(),
            isShown = host.interactionIsShown,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )

    private fun scheduleAnimationFrame() {
        if (animationFrameScheduled) return
        animationFrameScheduled = true
        scheduler.postOnAnimationDelayed(
            animationRunnable,
            KeyboardSkillAnimationPolicy.FRAME_INTERVAL_MILLIS,
        )
    }

    private fun updateAnimationDirtyBounds() {
        animationDirtyBounds.setEmpty()
        if (activeSkillOverlay == null) {
            activeSkillSourceKey?.let { animationDirtyBounds.set(it.bounds) }
        }
        if (skillSession.isPickerVisible()) {
            if (animationDirtyBounds.isEmpty) {
                animationDirtyBounds.set(pickerSourceBoundsStorage)
            } else {
                animationDirtyBounds.union(pickerSourceBoundsStorage)
            }
            for (slot in pickerOptionBounds) {
                if (!slot.isEmpty) animationDirtyBounds.union(slot)
            }
        }
    }

    private fun skillLabel(
        active: ActiveKeyboardSkill?,
        bindings: KeyboardSkillBindingSet,
    ): String? = active?.let { activeSkill ->
        bindings.binding(activeSkill.sourceKeyCode, activeSkill.direction)
            ?.takeIf { it.skillId == activeSkill.skillId }
            ?.label
    }

    private fun announceAccessibility(message: String) {
        if (host.interactionIsShown) host.interactionAnnounce(message)
    }

    private fun updateAccessibilityDescription() {
        val active = activeKeyboardSkill
        val base = KeyboardSkillAccessibilityText.keyboardContentDescription(
            activeSkillId = active?.skillId,
            activeLabel = skillLabel(active, skillBindings),
            pickerOptions = pickerOptions.takeIf { skillSession.isPickerVisible() },
            highlightedDirection = skillSession.highlightedDirection(),
        )
        val next = feedbackMessage?.let { "$base，提示：$it" } ?: base
        if (host.interactionReadContentDescription()?.toString() != next) {
            host.interactionWriteContentDescription(next)
        }
    }

    private fun publishAiState(next: AiSurfaceState?) {
        aiSurfaceState = next
        aiLockProgress = next?.lockProgress ?: 0f
        aiLocked = next?.locked == true
        updateAiGeometry()
    }

    private fun updateAiGeometry() {
        host.interactionAiGeometry.update(
            viewWidth = host.interactionWidth,
            viewHeight = host.interactionHeight,
            keyRegionTop = host.interactionChromeBottom(),
            systemBarHeight = metrics.systemBarHeight,
            horizontalPadding = metrics.horizontalPadding,
            density = density,
            active = aiSurfaceState != null,
            locked = aiLocked,
        )
    }

    private fun dp(value: Float): Float = value * density

    companion object {
        const val FINISH_SKILL_OWNED = 1
        const val FINISH_SKILL_CONSUMED = 1 shl 1

        private val SKILL_SELECTION_REQUEST_TOKENS = KeyboardSkillRequestTokenSource()
        private const val FEEDBACK_DURATION_MILLIS = 4_000L
        private const val FEEDBACK_MAX_CHARS = 120
        private const val NO_POINTER = -1
    }
}
