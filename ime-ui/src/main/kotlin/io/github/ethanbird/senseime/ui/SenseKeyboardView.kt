package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.sin

class SenseKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface KeyListener {
        fun onKey(code: Int)
    }

    interface AiHoldListener {
        fun onAiHoldStarted(generation: Long)

        fun onAiHoldCancelled(generation: Long)

        fun onAiStopRequested(generation: Long) {
            onAiHoldCancelled(generation)
        }
    }

    enum class Panel {
        LETTERS,
        NUMBERS,
        TOOLBOX,
        SYMBOLS,
        EMOJI,
        CLIPBOARD,
        EDITOR,
        VOICE,
    }

    enum class ClipboardAction {
        CLEAR,
        DELETE,
        REFRESH,
    }

    enum class EditorAction {
        BACK,
        UP,
        LEFT,
        TOGGLE_SELECTION,
        RIGHT,
        DOWN,
        DELETE,
        COPY,
        CUT,
        PASTE,
        HOME,
        SELECT_ALL,
        END,
    }

    var keyListener: KeyListener? = null
    var candidateListener: ((revision: Long, sourceIndex: Int) -> Unit)? = null
    var textListener: ((text: String) -> Unit)? = null
    var clipboardActionListener: ((action: ClipboardAction, index: Int) -> Unit)? = null
    var editorActionListener: ((action: EditorAction) -> Unit)? = null
    var settingsActionListener: (() -> Unit)? = null
    var aiHoldListener: AiHoldListener? = null
    var skillSelectionListener: KeyboardSkillSelectionListener? = null

    private val density = resources.displayMetrics.density
    private val metrics = KeyboardMetrics.fromDensity(density)
    private val primaryLayout = KeyboardPrimaryLayout(metrics)
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics = Paint.FontMetrics()
    private val iconPainter = KeyboardIconPainter(
        density = density,
        fontScale = resources.configuration.fontScale,
    )
    private val sharedPath = Path()
    private val palette = KeyboardPalette(
        nightMode = resources.configuration.isNightMode(),
    )
    private val keys = mutableListOf<Key>()
    private val candidatePanel = CandidatePanel(
        metrics = metrics,
        touchSlop = scaledTouchSlop,
        textMeasurer = PaintCandidateTextMeasurer(),
    )
    private var clipboardItems: List<String> = emptyList()
    private val touchReducer = TouchInputReducer<FrozenTouchTarget>(
        swipeThreshold = dp(22f),
        maximumHorizontalDrift = dp(34f),
    )
    private val keyEventQueue = KeyEventQueue(initialCapacity = 64)
    private val pressedTargets = SparseArray<FrozenTouchTarget>(4)
    private val panelPointerYs = SparseArray<Float>(2)
    private var activePanelPointerId = NO_POINTER
    private var activePanelScroll: ScrollPanel? = null
    private var activePanelScrollLatched = false
    private var panelVelocityTracker: VelocityTracker? = null
    private val panelScroller = OverScroller(context)
    private var flingingPanel: ScrollPanel? = null
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var keyDispatchPosted = false
    private val backspaceRepeatSession = BackspaceRepeatSession()
    private val aiHoldGestureSession = AiHoldGestureSession(
        maximumStationaryDistance = maxOf(scaledTouchSlop, dp(10f)),
        lockDistance = dp(AiSurfaceContract.LOCK_DISTANCE_DP),
    )
    private var scheduledAiHoldPointerId = NO_POINTER
    private var scheduledAiHoldGeneration = 0L
    private val skillSelectionDistance = maxOf(dp(24f), scaledTouchSlop * 1.8f)
    private val skillGestureSession = KeyboardSkillGestureSession(
        maximumStationaryDistance = maxOf(scaledTouchSlop, dp(10f)),
        selectionDistance = skillSelectionDistance,
    )
    private val skillHapticGate = KeyboardSkillHapticGate()
    private var skillBindings = KeyboardSkillBindingSet.EMPTY
    private var activeKeyboardSkill: ActiveKeyboardSkill? = null
    private var activeSkillSourceKey: Key? = null
    private var activeSkillAuroraOverlay: ActiveSkillAuroraOverlayView? = null
    private var skillVisualOwnerState = KeyboardSkillVisualOwnerState()
    private var scheduledSkillPointerId = NO_POINTER
    private var scheduledSkillGeneration = 0L
    private val skillPickerSourceBounds = RectF()
    private var skillPickerSourceOwner: KeyboardSkillPhysicalOwner? = null
    private var skillPickerOptions: KeyboardSkillOptions? = null
    private var skillPickerLayout: KeyboardSkillPickerLayout? = null
    private val skillPickerOptionBounds =
        Array(KeyboardSkillDirection.entries.size) { RectF() }
    private val skillPickerVisibleLabels =
        arrayOfNulls<String>(KeyboardSkillDirection.entries.size)
    private val skillAnimationDirtyBounds = RectF()
    private var skillAnimationFrameScheduled = false
    private var renderPassCount = 0L
    private var keySceneBuildCount = 0L
    private var toolbarKeyStart = 0
    private var toolbarKeyEndExclusive = 0
    private var panelKeyStart = 0
    private var panelKeyEndExclusive = 0
    private var systemBarKeyStart = 0
    private var systemBarKeyEndExclusive = 0
    private val skillAuroraMatrix = Matrix()
    private val skillAuroraPath = Path()
    private var skillAuroraShader: Shader? = null
    private var skillFeedbackMessage: String? = null
    private val clearSkillFeedbackRunnable = Runnable {
        if (skillFeedbackMessage != null) {
            skillFeedbackMessage = null
            updateSkillAccessibilityDescription()
            invalidate()
        }
    }
    private var aiSurfaceState: AiSurfaceState? = null
    private val aiPreviewLineLayoutCache = AiPreviewLineLayoutCache()
    private val aiStopBounds = RectF()
    private var aiStopPointerId = NO_POINTER
    private var voiceSurfaceState: VoiceSurfaceState? = null
    private val voiceWaveformBuffer = VoiceWaveformBuffer()
    private val voiceWaveformSamples = FloatArray(voiceWaveformBuffer.capacity)
    private val voiceWaveformBounds = RectF()
    private var voiceWaveformShader: Shader? = null
    private var voiceStatusCenterY = 0f
    private var voiceTranscriptCenterY = 0f
    private var emojiGroupIndex = 0
    private val emojiScrollState = ContinuousVerticalScrollState()
    private var emojiGridBounds: RectF? = null
    private var symbolCategoryIndex = 0
    private val symbolCategoryScrollState = ContinuousVerticalScrollState()
    private val symbolGridScrollState = ContinuousVerticalScrollState()
    private var symbolCategoryBounds: RectF? = null
    private var symbolGridBounds: RectF? = null
    private var clipboardPageIndex = 0
    private var clipboardPageLabel = ""
    private var editorHasSelection = false
    private var editorSelectionMode = false
    private var editorCanPaste = false
    private var editorMainBounds: RectF? = null
    private var editorBottomTop = 0f
    private var editorBottomSeparators = FloatArray(0)
    private var shifted = false
    private var chineseMode = true
    private var panel = Panel.LETTERS
    private var keyboardSizeProfile = KeyboardSizeProfile.DEFAULT
    private var backgroundShader: Shader? = null

    private val candidateHeight = metrics.candidateHeight
    private val toolbarHeight = metrics.toolbarHeight
    private val systemBarHeight = metrics.systemBarHeight
    private val keyGap = metrics.keyGap
    private val horizontalPadding = metrics.horizontalPadding
    private val keyRadius = metrics.keyRadius
    private val candidateControlWidth = metrics.candidateControlWidth
    private val expandedCandidatePagerHeight = metrics.expandedCandidatePagerHeight
    private val tapGesturePolicy = TouchInputReducer.GesturePolicy.tapOnly()
    private val pageScrollGesturePolicy = TouchInputReducer.GesturePolicy.verticalScroll(
        touchSlop = scaledTouchSlop,
        verticalDominanceRatio = VERTICAL_GESTURE_DOMINANCE,
    )
    private var candidateSettleStartedAtMillis = 0L
    private var candidateSettleStartOffset = 0f
    private var candidateSettleTargetOffset = 0f
    private val candidateSettleRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - candidateSettleStartedAtMillis
            val fraction = elapsed.toFloat() / CANDIDATE_SETTLE_DURATION_MILLIS.toFloat()
            val next = CandidateStripScrollPhysics.easeOutCubic(
                start = candidateSettleStartOffset,
                target = candidateSettleTargetOffset,
                fraction = fraction,
            )
            if (candidatePanel.moveTo(next)) {
                invalidateCandidateViewport()
            }
            if (fraction < 1f) postOnAnimation(this)
        }
    }
    private val keyDispatchRunnable = Runnable {
        keyDispatchPosted = false
        while (true) {
            val code = keyEventQueue.poll() ?: break
            keyListener?.onKey(code)
        }
    }
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val pointerId = backspaceRepeatSession.activePointerId() ?: return
            if (!touchReducer.isPressed(pointerId)) return
            val target = touchReducer.target(pointerId) as? FrozenTouchTarget.KeyValue ?: return
            if (deleteRepeatTarget(target.key) == null) return
            dispatchDelete(target.key)
            val held = backspaceRepeatSession.heldMillis(SystemClock.uptimeMillis())
            postDelayed(this, BackspaceRepeatPolicy.intervalMillis(held))
        }
    }
    private val aiHoldActivationRunnable = object : Runnable {
        override fun run() {
            val pointerId = scheduledAiHoldPointerId
            if (pointerId == NO_POINTER) return
            val generation = scheduledAiHoldGeneration
            val now = SystemClock.uptimeMillis()
            val outcome = aiHoldGestureSession.tryActivate(
                pointerId = pointerId,
                expectedGeneration = generation,
                nowMillis = now,
            )
            if (outcome == AiHoldGestureSession.Outcome.ACTIVATED) {
                scheduledAiHoldPointerId = NO_POINTER
                scheduledAiHoldGeneration = 0L
                beginAiSurface(generation)
                return
            }
            val remaining = aiHoldGestureSession.millisUntilActivation(now)
            if (
                remaining > 0L &&
                aiHoldGestureSession.armedGeneration() == generation
            ) {
                postDelayed(this, remaining)
            }
        }
    }
    private val skillActivationRunnable = object : Runnable {
        override fun run() {
            val pointerId = scheduledSkillPointerId
            if (pointerId == NO_POINTER) return
            val generation = scheduledSkillGeneration
            val now = SystemClock.uptimeMillis()
            val outcome = skillGestureSession.tryActivate(
                pointerId = pointerId,
                expectedGeneration = generation,
                nowMillis = now,
            )
            if (outcome == KeyboardSkillGestureSession.Outcome.PICKER_SHOWN) {
                scheduledSkillPointerId = NO_POINTER
                scheduledSkillGeneration = 0L
                suspendOrdinaryInputForSkillPicker()
                rebuildSkillPickerLayout()
                skillHapticGate.reset()
                updateSkillAccessibilityDescription()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                skillPickerOptions?.let { options ->
                    announceSkillAccessibility(
                        KeyboardSkillAccessibilityText.pickerOpened(options),
                    )
                }
                invalidate()
                return
            }
            val remaining = skillGestureSession.millisUntilActivation(now)
            if (
                remaining > 0L &&
                skillGestureSession.armedGeneration() == generation
            ) {
                postDelayed(this, remaining)
            }
        }
    }
    private val skillAnimationRunnable = object : Runnable {
        override fun run() {
            skillAnimationFrameScheduled = false
            if (!shouldAnimateSkills()) return
            updateSkillAnimationDirtyBounds()
            if (skillAnimationDirtyBounds.isEmpty) return
            val margin = dp(4f)
            invalidate(
                (skillAnimationDirtyBounds.left - margin).toInt().coerceAtLeast(0),
                (skillAnimationDirtyBounds.top - margin).toInt().coerceAtLeast(0),
                (skillAnimationDirtyBounds.right + margin + 1f).toInt().coerceAtMost(width),
                (skillAnimationDirtyBounds.bottom + margin + 1f).toInt().coerceAtMost(height),
            )
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateSkillAccessibilityDescription()
    }

    /**
     * Updates only the active composition. The last ready candidate batch stays
     * visible but non-interactive until [updateComposing] supplies the matching
     * revision, avoiding an empty pending frame.
     */
    fun updateComposition(revision: Long, text: String) {
        updateCandidateUi(revision, text, values = null)
    }

    /** Atomically publishes a ready candidate batch for [revision]. */
    fun updateComposing(revision: Long, text: String, values: List<String>) {
        updateCandidateUi(revision, text, values)
    }

    private fun updateCandidateUi(revision: Long, text: String, values: List<String>?) {
        val change = candidatePanel.publish(
            revision = revision,
            text = text,
            values = values,
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
            fontScale = resources.configuration.fontScale,
        )
        if (change.cancelSettle) stopCandidateSettle()
        if (change.requiresKeySceneRebuild) rebuildKeys(width, height)
        invalidate()
    }

    fun setShifted(value: Boolean) {
        if (shifted == value) return
        shifted = value
        rebuildKeys(width, height)
        invalidate()
    }

    fun setChineseMode(value: Boolean) {
        if (chineseMode == value) return
        cancelAllTouches()
        chineseMode = value
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    fun setPanel(value: Panel) {
        val wasExpanded = candidatePanel.expanded
        if (panel == value && !wasExpanded) return
        cancelAllTouches()
        if (panel == Panel.VOICE && value != Panel.VOICE) clearVoiceSurfaceState()
        panel = value
        if (value == Panel.EMOJI) emojiScrollState.reset()
        if (value == Panel.SYMBOLS) {
            symbolCategoryIndex = 0
            symbolCategoryScrollState.reset()
            symbolGridScrollState.reset()
        }
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    /**
     * Atomically replaces the keyboard projection of Skill bindings and its
     * active visual owner. Persisting either value remains the host's job.
     */
    fun updateKeyboardSkills(
        bindings: List<KeyboardSkillBinding>,
        active: ActiveKeyboardSkill?,
    ) {
        cancelSkillGesture()
        val previous = activeKeyboardSkill
        val previousLabel = skillLabel(previous, skillBindings)
        val nextBindings = KeyboardSkillBindingSet.from(bindings)
        val currentLabel = skillLabel(active, nextBindings)
        skillBindings = nextBindings
        activeKeyboardSkill = active
        skillVisualOwnerState = KeyboardSkillVisualOwnerPolicy.project(
            skillVisualOwnerState,
            active,
        )
        resolveActiveSkillSourceKey()
        updateSkillAccessibilityDescription()
        invalidate()
        KeyboardSkillAccessibilityText.activeChanged(
            previousSkillId = previous?.skillId,
            previousLabel = previousLabel,
            currentSkillId = active?.skillId,
            currentLabel = currentLabel,
        )?.let(::announceSkillAccessibility)
    }

    /** Updates only the active aurora after an external settings/tool change. */
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
        updateSkillAccessibilityDescription()
        invalidate()
        KeyboardSkillAccessibilityText.activeChanged(
            previousSkillId = previous?.skillId,
            previousLabel = previousLabel,
            currentSkillId = active?.skillId,
            currentLabel = currentLabel,
        )?.let(::announceSkillAccessibility)
    }

    fun activeKeyboardSkill(): ActiveKeyboardSkill? = activeKeyboardSkill

    /**
     * Moves the long-lived active-Skill animation into a sibling render layer.
     *
     * A raw [SenseKeyboardView] keeps the in-view fallback for backwards
     * compatibility. Production hosts attach exactly one overlay so an Aurora
     * frame never re-records the entire keyboard display list.
     */
    internal fun attachActiveSkillAuroraOverlay(overlay: ActiveSkillAuroraOverlayView?) {
        if (activeSkillAuroraOverlay === overlay) {
            publishActiveSkillAurora()
            return
        }
        activeSkillAuroraOverlay?.updateBounds(null, keyRadius)
        activeSkillAuroraOverlay = overlay
        publishActiveSkillAurora()
        invalidate()
    }

    /**
     * Rejects only the matching provisional physical owner. A late failure from an older
     * selection cannot erase the source hint of a newer gesture.
     */
    fun rejectPendingSkillSelection(requestToken: Long) {
        val next = KeyboardSkillVisualOwnerPolicy.reject(
            skillVisualOwnerState,
            requestToken,
        )
        if (next == skillVisualOwnerState) return
        skillVisualOwnerState = next
        resolveActiveSkillSourceKey()
        invalidate()
    }

    /** Package-visible device-test observation; no mutable View-owned bounds escape. */
    internal fun activeSkillSourceBoundsForTesting(): RectF? =
        activeSkillSourceKey?.bounds?.let(::RectF)

    /** Monotonic parent-render observation used by the API 36 isolation gate. */
    internal fun renderPassCountForTesting(): Long = renderPassCount

    internal fun keySceneBuildCountForTesting(): Long = keySceneBuildCount

    internal fun candidateSceneBuildCountForTesting(): Long = candidatePanel.sceneBuildCount

    internal fun scrollViewportBoundsForTesting(panel: ScrollPanel): RectF? =
        panelViewportBounds(panel)?.let(::RectF)

    internal fun scrollOffsetForTesting(panel: ScrollPanel): Float =
        scrollStateFor(panel).offset

    internal fun candidateViewportBoundsForTesting(): RectF? =
        candidatePanel.collapsedViewportBounds?.toRectF()

    internal fun candidateScrollOffsetForTesting(): Float =
        candidatePanel.scrollOffset

    internal fun candidateMaximumOffsetForTesting(): Float =
        candidatePanel.maximumScrollOffset

    internal fun candidateSourceIndexAtForTesting(x: Float, y: Float): Int? =
        (candidatePanel.hitTest(x = x, y = y, visible = showsCandidates()) as? CandidateHit.Value)
            ?.sourceIndex

    /**
     * Shows a bounded, transient keyboard-owned failure chip and exposes the same message through
     * the View accessibility description/announcement. The host uses this after an asynchronous
     * Skill mutation or projection read fails, so a failed selection is never silent.
     */
    fun showSkillFeedback(message: String) {
        val bounded = message.trim().take(SKILL_FEEDBACK_MAX_CHARS)
        if (bounded.isEmpty()) return
        removeCallbacks(clearSkillFeedbackRunnable)
        skillFeedbackMessage = bounded
        updateSkillAccessibilityDescription()
        invalidate()
        post {
            if (skillFeedbackMessage == bounded && isShown) {
                announceSkillAccessibility(bounded)
            }
        }
        postDelayed(clearSkillFeedbackRunnable, SKILL_FEEDBACK_DURATION_MILLIS)
    }

    fun clearSkillFeedback() {
        if (skillFeedbackMessage == null) return
        removeCallbacks(clearSkillFeedbackRunnable)
        skillFeedbackMessage = null
        updateSkillAccessibilityDescription()
        invalidate()
    }

    fun showVoiceSurface(initialState: VoiceSurfaceState) {
        require(initialState.sessionId > 0L)
        cancelAllTouches()
        clearVoiceSurfaceState()
        voiceSurfaceState = initialState
        voiceWaveformBuffer.clear()
        panel = Panel.VOICE
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    /**
     * Publishes a newer frame for the active speech session.
     *
     * Session and revision checks are local as well as in the controller, so a late binder callback
     * cannot resurrect or overwrite the next editor's voice surface.
     */
    fun updateVoiceSurface(nextState: VoiceSurfaceState): Boolean {
        val current = voiceSurfaceState ?: return false
        if (!VoiceSurfaceUpdatePolicy.accepts(current, nextState)) return false
        val phaseChanged = current.phase != nextState.phase
        voiceSurfaceState = nextState
        if (nextState.phase == VoiceSurfacePhase.LISTENING) {
            voiceWaveformBuffer.append(nextState.waveformLevel)
        }
        if (phaseChanged) rebuildKeys(width, height)
        invalidate()
        return true
    }

    fun exitVoiceSurface(sessionId: Long): Boolean {
        val current = voiceSurfaceState ?: return false
        if (current.sessionId != sessionId) return false
        clearVoiceSurfaceState()
        if (panel == Panel.VOICE) panel = Panel.LETTERS
        cancelOrdinaryTouches()
        relayoutCandidates()
        rebuildKeys(width, height)
        invalidate()
        return true
    }

    fun isVoiceSurfaceActive(): Boolean =
        panel == Panel.VOICE && voiceSurfaceState != null

    fun activeVoiceSessionId(): Long? = voiceSurfaceState?.sessionId

    private fun clearVoiceSurfaceState() {
        voiceSurfaceState = null
        voiceWaveformBuffer.clear()
        voiceWaveformBounds.setEmpty()
    }

    fun showClipboard(values: List<String>) {
        cancelAllTouches()
        clipboardItems = values
        clipboardPageIndex = 0
        panel = Panel.CLIPBOARD
        collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    fun updateClipboard(values: List<String>) {
        clipboardItems = values
        val pageCount = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE)
            .coerceAtLeast(1)
        clipboardPageIndex = clipboardPageIndex.coerceAtMost(pageCount - 1)
        if (panel == Panel.CLIPBOARD) {
            rebuildKeys(width, height)
            invalidate()
        }
    }

    /**
     * Compatibility shim for callers compiled against the M7 editor surface.
     * New code should update host selection and selection-extension mode
     * independently through [setEditorSelectionState].
     */
    fun setEditorSelectionActive(value: Boolean) {
        setEditorSelectionState(
            hasSelection = value,
            selectionMode = value,
            canPaste = editorCanPaste,
        )
    }

    fun setEditorSelectionState(
        hasSelection: Boolean,
        selectionMode: Boolean,
        canPaste: Boolean = editorCanPaste,
    ) {
        if (
            editorHasSelection == hasSelection &&
            editorSelectionMode == selectionMode &&
            editorCanPaste == canPaste
        ) {
            return
        }
        editorHasSelection = hasSelection
        editorSelectionMode = selectionMode
        editorCanPaste = canPaste
        // Labels, enabled states and colors are resolved at draw/hit-test time.
        // Keeping the stable Key objects preserves a held pointer and its repeat
        // stream when the host reports a selection update.
        if (panel == Panel.EDITOR) invalidate()
    }

    /**
     * Replaces the current stream preview. Updates from a released/cancelled
     * generation are ignored so a late provider frame cannot resurrect AI UI.
     */
    fun updateAiSurface(
        generation: Long,
        phase: AiSurfacePhase,
        preview: String,
        statusText: String = "",
        activities: List<AiSurfaceActivity> = aiSurfaceState?.activities.orEmpty(),
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
        aiSurfaceState = current.copy(
            phase = phase,
            preview = stablePreview,
            statusText = statusText.ifBlank {
                current.statusText.ifBlank { defaultAiStatus(phase) }
            },
            activities = activities.takeLast(AiSurfaceContract.MAX_VISIBLE_ACTIVITIES),
        )
        invalidate()
        return true
    }

    /**
     * Allocation-bounded convenience path for token/delta streaming.
     */
    fun appendAiStreamPreview(
        generation: Long,
        delta: String,
        phase: AiSurfacePhase = AiSurfacePhase.STREAMING,
    ): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        aiSurfaceState = current.copy(
            phase = phase,
            preview = AiSurfaceContract.appendBounded(current.preview, delta),
            statusText = current.statusText.ifBlank { defaultAiStatus(phase) },
        )
        invalidate()
        return true
    }

    /**
     * Host-controlled terminal exit. Pointer UP/CANCEL invokes the cancellation
     * callback and exits automatically; this method is for a completed/error
     * flow that the host has already settled.
     */
    fun exitAiSurface(generation: Long): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        aiHoldGestureSession.cancelAll()
        clearScheduledAiHold()
        aiSurfaceState = null
        aiStopPointerId = NO_POINTER
        aiStopBounds.setEmpty()
        relayoutCandidates()
        rebuildKeys(width, height)
        invalidate()
        return true
    }

    fun isAiSurfaceActive(): Boolean = aiSurfaceState != null

    fun activeAiGeneration(): Long? = aiSurfaceState?.generation

    fun setKeyboardSizeProfile(profile: KeyboardSizeProfile) {
        if (keyboardSizeProfile == profile) return
        keyboardSizeProfile = profile
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = keyboardSizeProfile.preferredHeightPx(
            isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            density = density,
        )
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelAllTouches()
        rebuildSurfaceShaders(w, h)
        relayoutCandidates(w, h)
        rebuildKeys(w, h)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelOrdinaryTouches()
        iconPainter.updateFontScale(newConfig.fontScale)
        val themeChanged = palette.update(newConfig.isNightMode())
        if (themeChanged) {
            rebuildSurfaceShaders(width, height)
        }
        relayoutCandidates()
        rebuildKeys(width, height)
        requestLayout()
        invalidate()
    }

    private fun rebuildSurfaceShaders(w: Int, h: Int) {
        val safeWidth = w.coerceAtLeast(1)
        val safeHeight = h.coerceAtLeast(1)
        backgroundShader = LinearGradient(
            0f,
            0f,
            0f,
            safeHeight.toFloat(),
            color(0xFFF1F5FA.toInt(), 0xFF171819.toInt()),
            color(0xFFE6EDF6.toInt(), 0xFF111213.toInt()),
            Shader.TileMode.CLAMP,
        )
        voiceWaveformShader = LinearGradient(
            dp(22f),
            0f,
            maxOf(dp(23f), safeWidth - dp(22f)),
            0f,
            intArrayOf(
                color(0xFF20C7EE.toInt(), 0xFF34D9FF.toInt()),
                color(0xFF557EF7.toInt(), 0xFF7A89FF.toInt()),
                color(0xFFA24DF4.toInt(), 0xFFC05CFF.toInt()),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        skillAuroraShader = SweepGradient(
            0f,
            0f,
            intArrayOf(
                color(0xFF16E6D4.toInt(), 0xFF43F1E1.toInt()),
                color(0xFF4F7CFF.toInt(), 0xFF7593FF.toInt()),
                color(0xFFD355FF.toInt(), 0xFFE477FF.toInt()),
                color(0xFFFF5CB6.toInt(), 0xFFFF72C2.toInt()),
                color(0xFF77F4A8.toInt(), 0xFF8CFFC0.toInt()),
                color(0xFF16E6D4.toInt(), 0xFF43F1E1.toInt()),
            ),
            null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderPassCount += 1L
        drawBackground(canvas)
        val aiState = aiSurfaceState
        if (aiState != null) {
            drawAiSurface(canvas, aiState)
            drawSkillFeedback(canvas)
            return
        }
        if (panel == Panel.VOICE) {
            drawVoiceSurface(canvas)
            drawKeys(canvas)
            drawSkillFeedback(canvas)
            return
        }
        if (panel == Panel.EDITOR) {
            drawEditorHeader(canvas)
            drawEditorPanelBackground(canvas)
        } else {
            drawCandidates(canvas)
            if (!candidatePanel.expanded) {
                if (panel == Panel.CLIPBOARD) drawClipboardHeader(canvas)
                if (panel == Panel.SYMBOLS) drawSymbolPanelBackground(canvas)
                if (panel == Panel.TOOLBOX) drawToolboxPanelBackground(canvas)
            }
        }
        drawKeys(canvas)
        drawSkillFeedback(canvas)
    }

    private fun drawSkillFeedback(canvas: Canvas) {
        val message = skillFeedbackMessage ?: return
        val horizontalInset = dp(12f)
        val top = keyboardChromeBottom() + dp(7f)
        val bounds = RectF(
            horizontalInset,
            top,
            width.toFloat() - horizontalInset,
            top + dp(34f),
        )
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = 246
        paint.color = color(0xFFFDEBED.toInt(), 0xFF49262B.toInt())
        canvas.drawRoundRect(bounds, dp(10f), dp(10f), paint)
        paint.alpha = 255
        paint.color = color(0xFF9F2836.toInt(), 0xFFFFA3AC.toInt())
        paint.textSize = sp(11.5f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            ellipsizeToWidth(message, bounds.width() - dp(20f)),
            bounds.centerX(),
            bounds.centerY(),
        )
    }

    private fun drawAiSurface(canvas: Canvas, state: AiSurfaceState) {
        val keyRegionTop = keyboardChromeBottom()
        if (height.toFloat() <= keyRegionTop + systemBarHeight) return
        val surface = AiSurfaceContract.bounds(
            keyboardHeight = height.toFloat(),
            keyRegionTop = keyRegionTop,
            systemBarHeight = systemBarHeight,
        )
        val accent = when (state.phase) {
            AiSurfacePhase.STARTING -> color(0xFF5B72E8.toInt(), 0xFF9C8CFF.toInt())
            AiSurfacePhase.STREAMING -> color(0xFF3F7CEA.toInt(), 0xFF9C8CFF.toInt())
            AiSurfacePhase.COMPLETE -> color(0xFF26845A.toInt(), 0xFF71D9A8.toInt())
            AiSurfacePhase.ERROR -> color(0xFFD14D58.toInt(), 0xFFFF8A93.toInt())
        }

        paint.style = Paint.Style.FILL
        paint.color = color(0x26FFFFFF, 0x1AFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), keyRegionTop, paint)
        paint.color = accent
        canvas.drawCircle(dp(17f), keyRegionTop / 2f, dp(4f), paint)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = sp(13.5f)
        val status = state.statusText.ifBlank { defaultAiStatus(state.phase) }
        drawCenteredText(
            canvas,
            ellipsizeToWidth(status, width - dp(41f)),
            dp(29f),
            keyRegionTop / 2f,
        )

        val card = RectF(
            horizontalPadding,
            surface.top + dp(7f),
            width - horizontalPadding,
            surface.bottom - dp(7f),
        )
        paint.color = color(0xD8FFFFFF.toInt(), 0xFF252627.toInt())
        canvas.drawRoundRect(card, dp(13f), dp(13f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x185B72E8, 0x309C8CFF)
        canvas.drawRoundRect(card, dp(13f), dp(13f), paint)
        paint.style = Paint.Style.FILL

        val timelineBottom = drawAiActivityTimeline(
            canvas = canvas,
            state = state,
            card = card,
            accent = accent,
        )
        val preview = state.preview
        if (preview.isNotEmpty() && timelineBottom < card.bottom - dp(25f)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF748096.toInt(), 0xFFAEB3BE.toInt())
            paint.textSize = sp(10.5f)
            paint.textAlign = Paint.Align.LEFT
            drawCenteredText(
                canvas,
                "输出预览",
                card.left + dp(14f),
                timelineBottom + dp(11f),
            )
            drawAiPreviewText(
                canvas = canvas,
                text = preview,
                left = card.left + dp(14f),
                top = timelineBottom + dp(22f),
                right = card.right - dp(14f),
                bottom = card.bottom - dp(14f),
            )
        } else if (preview.isEmpty() && timelineBottom < card.bottom - dp(28f)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF78859A.toInt(), 0xFF9FA4AF.toInt())
            paint.textSize = sp(11.5f)
            paint.textAlign = Paint.Align.LEFT
            val waiting = when (state.phase) {
                AiSurfacePhase.STARTING -> "正在建立会话，稍后将在这里显示结果"
                AiSurfacePhase.STREAMING -> "Agent 仍在工作；可继续按住，或上滑锁定后松手"
                AiSurfacePhase.COMPLETE -> "本次任务没有需要展示的替换文字"
                AiSurfacePhase.ERROR -> "已保留最后状态，输入框未被未经校验的结果覆盖"
            }
            drawCenteredText(
                canvas,
                ellipsizeToWidth(waiting, card.width() - dp(28f)),
                card.left + dp(14f),
                timelineBottom + dp(18f),
            )
        }

        drawAiLockAffordance(canvas, state, accent)
    }

    private fun drawAiActivityTimeline(
        canvas: Canvas,
        state: AiSurfaceState,
        card: RectF,
        accent: Int,
    ): Float {
        val fallback = AiSurfaceActivity(
            id = "fallback",
            title = state.statusText.ifBlank { defaultAiStatus(state.phase) },
            state = when (state.phase) {
                AiSurfacePhase.COMPLETE -> AiSurfaceActivityState.COMPLETED
                AiSurfacePhase.ERROR -> AiSurfaceActivityState.FAILED
                else -> AiSurfaceActivityState.RUNNING
            },
        )
        val maximumRows = if (state.preview.isEmpty()) 4 else 3
        val activities = state.activities.ifEmpty { listOf(fallback) }.takeLast(maximumRows)
        val left = card.left + dp(14f)
        val right = card.right - dp(14f)
        val rowHeight = dp(if (state.preview.isEmpty()) 34f else 31f)
        var top = card.top + dp(8f)
        var hasRunning = false
        val pulse = ((SystemClock.uptimeMillis() % 1_200L) / 1_200f)
        activities.forEachIndexed { index, activity ->
            val centerY = top + rowHeight / 2f
            val markerX = left + dp(5f)
            when (activity.state) {
                AiSurfaceActivityState.RUNNING -> {
                    hasRunning = true
                    paint.style = Paint.Style.FILL
                    paint.color = color(0x205B72E8, 0x309C8CFF)
                    canvas.drawCircle(
                        markerX,
                        centerY,
                        dp(4.5f + 2f * kotlin.math.sin(pulse * Math.PI).toFloat()),
                        paint,
                    )
                    paint.color = accent
                    canvas.drawCircle(markerX, centerY, dp(2.8f), paint)
                }
                AiSurfaceActivityState.COMPLETED -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(1.5f, density * 1.3f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = color(0xFF3D9A70.toInt(), 0xFF74D9AA.toInt())
                    canvas.drawCircle(markerX, centerY, dp(5f), paint)
                    sharedPath.reset()
                    sharedPath.moveTo(markerX - dp(2.5f), centerY)
                    sharedPath.lineTo(markerX - dp(0.5f), centerY + dp(2f))
                    sharedPath.lineTo(markerX + dp(3f), centerY - dp(2.5f))
                    canvas.drawPath(sharedPath, paint)
                    paint.strokeCap = Paint.Cap.BUTT
                }
                AiSurfaceActivityState.FAILED -> {
                    paint.style = Paint.Style.FILL
                    paint.color = color(0xFFD14D58.toInt(), 0xFFFF8A93.toInt())
                    canvas.drawCircle(markerX, centerY, dp(5f), paint)
                    paint.color = Color.WHITE
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = sp(8.5f)
                    drawCenteredText(canvas, "!", markerX, centerY)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF26344A.toInt(), 0xFFE9EBEF.toInt())
            paint.textSize = sp(12.5f)
            paint.textAlign = Paint.Align.LEFT
            val detailWidth = if (activity.detail.isBlank()) 0f else dp(54f)
            drawCenteredText(
                canvas,
                ellipsizeToWidth(activity.title, right - left - dp(18f) - detailWidth),
                left + dp(16f),
                centerY,
            )
            if (activity.detail.isNotBlank()) {
                paint.color = color(0xFF7B8798.toInt(), 0xFFA7ABB4.toInt())
                paint.textSize = sp(10.5f)
                paint.textAlign = Paint.Align.RIGHT
                drawCenteredText(canvas, activity.detail, right, centerY)
            }
            if (index < activities.lastIndex) {
                paint.color = color(0x0F172033, 0x12FFFFFF)
                canvas.drawRect(left + dp(16f), top + rowHeight - 1f, right, top + rowHeight, paint)
            }
            top += rowHeight
        }
        if (hasRunning) postInvalidateDelayed(120L)
        return top + dp(2f)
    }

    private fun drawVoiceSurface(canvas: Canvas) {
        val state = voiceSurfaceState ?: return
        val contentBottom = height - systemBarHeight
        if (contentBottom <= candidateHeight) return

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color(0x14FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), candidateHeight, paint)
        paint.color = color(0xFF263247.toInt(), 0xFFF1F2F5.toInt())
        paint.textSize = sp(15.5f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "语音转文字", dp(15f), candidateHeight / 2f)

        val providerLeft = dp(106f)
        val providerRight = minOf(width - dp(58f), providerLeft + dp(126f))
        paint.color = color(0x15557EF7, 0x28557EF7)
        canvas.drawRoundRect(
            providerLeft,
            dp(9f),
            providerRight,
            candidateHeight - dp(9f),
            dp(12f),
            dp(12f),
            paint,
        )
        paint.color = color(0xFF52627B.toInt(), 0xFFBFC4CE.toInt())
        paint.textSize = sp(10.5f)
        paint.textAlign = Paint.Align.CENTER
        val providerSave = canvas.save()
        canvas.clipRect(providerLeft + dp(6f), 0f, providerRight - dp(6f), candidateHeight)
        drawCenteredText(canvas, state.providerName, (providerLeft + providerRight) / 2f, candidateHeight / 2f)
        canvas.restoreToCount(providerSave)

        paint.color = color(0x1820C7EE, 0x1620C7EE)
        canvas.drawCircle(width * 0.18f, candidateHeight + dp(92f), dp(76f), paint)
        paint.color = color(0x16A24DF4, 0x18A24DF4)
        canvas.drawCircle(width * 0.82f, candidateHeight + dp(126f), dp(88f), paint)

        val statusY = voiceStatusCenterY.takeIf { it > candidateHeight }
            ?: (candidateHeight + dp(30f))
        paint.color = when (state.phase) {
            VoiceSurfacePhase.ERROR -> color(0xFFC23E4A.toInt(), 0xFFFF8F98.toInt())
            else -> color(0xFF516078.toInt(), 0xFFC2C6CF.toInt())
        }
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.CENTER
        val statusSave = canvas.save()
        canvas.clipRect(dp(16f), candidateHeight, width - dp(16f), statusY + dp(17f))
        drawCenteredText(canvas, state.statusText, width / 2f, statusY)
        canvas.restoreToCount(statusSave)

        val visibleText = state.visibleText.ifBlank {
            when (state.phase) {
                VoiceSurfacePhase.STARTING -> "正在准备麦克风…"
                VoiceSurfacePhase.LISTENING -> "请开始说话"
                VoiceSurfacePhase.PROCESSING -> "正在整理识别结果"
                VoiceSurfacePhase.ERROR -> "可重试或返回键盘"
            }
        }
        paint.color = color(0xFF192337.toInt(), 0xFFF2F3F6.toInt())
        paint.textSize = sp(16f)
        paint.textAlign = Paint.Align.CENTER
        val transcriptY = voiceTranscriptCenterY.takeIf { it > statusY }
            ?: (statusY + dp(34f))
        val transcriptSave = canvas.save()
        canvas.clipRect(dp(22f), transcriptY - dp(16f), width - dp(22f), transcriptY + dp(16f))
        drawCenteredText(canvas, visibleText, width / 2f, transcriptY)
        canvas.restoreToCount(transcriptSave)

        drawVoiceWaveform(canvas, state)
        if (
            state.phase == VoiceSurfacePhase.STARTING ||
            state.phase == VoiceSurfacePhase.LISTENING ||
            state.phase == VoiceSurfacePhase.PROCESSING
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawVoiceWaveform(canvas: Canvas, state: VoiceSurfaceState) {
        if (voiceWaveformBounds.isEmpty) return
        val sampleCount = voiceWaveformBuffer.copyInto(voiceWaveformSamples)
        var hasRealSignal = false
        repeat(sampleCount) { index ->
            if (voiceWaveformSamples[index] > 0.015f) hasRealSignal = true
        }
        val barCount = VoiceWaveformBuffer.DEFAULT_CAPACITY
        val step = voiceWaveformBounds.width() / (barCount - 1).coerceAtLeast(1)
        val nowPhase = (SystemClock.uptimeMillis() % 1_800L) / 1_800f
        val centerY = voiceWaveformBounds.centerY()
        val maximumHalfHeight = voiceWaveformBounds.height() * 0.46f

        paint.shader = voiceWaveformShader
        paint.strokeCap = Paint.Cap.ROUND
        drawVoiceWaveformPass(
            canvas = canvas,
            state = state,
            sampleCount = sampleCount,
            hasRealSignal = hasRealSignal,
            barCount = barCount,
            step = step,
            nowPhase = nowPhase,
            centerY = centerY,
            maximumHalfHeight = maximumHalfHeight,
            strokeWidth = dp(5.5f),
            alpha = 46,
        )
        drawVoiceWaveformPass(
            canvas = canvas,
            state = state,
            sampleCount = sampleCount,
            hasRealSignal = hasRealSignal,
            barCount = barCount,
            step = step,
            nowPhase = nowPhase,
            centerY = centerY,
            maximumHalfHeight = maximumHalfHeight,
            strokeWidth = max(1.5f, density * 1.4f),
            alpha = 235,
        )
        paint.alpha = 255
        paint.shader = null
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawVoiceWaveformPass(
        canvas: Canvas,
        state: VoiceSurfaceState,
        sampleCount: Int,
        hasRealSignal: Boolean,
        barCount: Int,
        step: Float,
        nowPhase: Float,
        centerY: Float,
        maximumHalfHeight: Float,
        strokeWidth: Float,
        alpha: Int,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.alpha = alpha
        repeat(barCount) { index ->
            val normalizedX = index / (barCount - 1f)
            val envelope = 0.45f + (1f - kotlin.math.abs(normalizedX * 2f - 1f)) * 0.55f
            val rawLevel = if (hasRealSignal && sampleCount > 0) {
                val sampleIndex = ((sampleCount - 1) * normalizedX).toInt()
                voiceWaveformSamples[sampleIndex]
            } else {
                val wave = (sin(index * 0.41f + nowPhase * 6.28318f) + 1f) * 0.5f
                val phaseScale = when (state.phase) {
                    VoiceSurfacePhase.STARTING -> 0.18f
                    VoiceSurfacePhase.LISTENING -> 0.27f
                    VoiceSurfacePhase.PROCESSING -> 0.13f
                    VoiceSurfacePhase.ERROR -> 0.04f
                }
                0.05f + wave * phaseScale
            }
            val halfHeight = maxOf(dp(1.5f), rawLevel * envelope * maximumHalfHeight)
            val x = voiceWaveformBounds.left + index * step
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint)
        }
    }

    private fun drawAiLockAffordance(canvas: Canvas, state: AiSurfaceState, accent: Int) {
        val barTop = height - systemBarHeight
        val centerY = barTop + systemBarHeight / 2f
        if (state.locked) {
            val pill = RectF(
                dp(14f),
                barTop + dp(7f),
                width - dp(68f),
                height - dp(7f),
            )
            paint.style = Paint.Style.FILL
            paint.color = color(0x165B72E8, 0x269C8CFF)
            canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint)
            paint.color = accent
            canvas.drawCircle(pill.left + dp(18f), centerY, dp(4f), paint)
            paint.color = color(0xFF42526A.toInt(), 0xFFE1E3E8.toInt())
            paint.textSize = sp(12.5f)
            paint.textAlign = Paint.Align.LEFT
            drawCenteredText(canvas, "AI 已锁定 · 可松手", pill.left + dp(31f), centerY)

            aiStopBounds.set(
                width - dp(58f),
                barTop + dp(5f),
                width - dp(10f),
                height - dp(5f),
            )
            val pressed = aiStopPointerId != NO_POINTER
            paint.color = if (pressed) {
                color(0xFFE34B58.toInt(), 0xFFFF6D78.toInt())
            } else {
                color(0x22D14D58, 0x36FF7C86)
            }
            canvas.drawRoundRect(
                aiStopBounds,
                aiStopBounds.height() / 2f,
                aiStopBounds.height() / 2f,
                paint,
            )
            paint.color = color(0xFFD14D58.toInt(), 0xFFFFA1A8.toInt())
            val square = dp(if (pressed) 11f else 10f)
            canvas.drawRoundRect(
                RectF(
                    aiStopBounds.centerX() - square / 2f,
                    aiStopBounds.centerY() - square / 2f,
                    aiStopBounds.centerX() + square / 2f,
                    aiStopBounds.centerY() + square / 2f,
                ),
                dp(2f),
                dp(2f),
                paint,
            )
            return
        }

        aiStopBounds.setEmpty()
        val progress = AiSurfaceContract.lockVisualProgress(state.lockProgress)
        val pillWidth = dp(150f)
        val pill = RectF(
            width / 2f - pillWidth / 2f,
            barTop + dp(7f),
            width / 2f + pillWidth / 2f,
            height - dp(7f),
        )
        paint.style = Paint.Style.FILL
        paint.color = color(0x145B72E8, 0x229C8CFF)
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint)
        val arrowX = pill.left + dp(22f)
        val arrowLift = dp(7f) * progress
        paint.color = accent
        paint.strokeWidth = max(1.8f * density, 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            arrowX,
            centerY + dp(5f) - arrowLift,
            arrowX,
            centerY - dp(6f) - arrowLift,
            paint,
        )
        canvas.drawLine(
            arrowX,
            centerY - dp(6f) - arrowLift,
            arrowX - dp(4f),
            centerY - dp(2f) - arrowLift,
            paint,
        )
        canvas.drawLine(
            arrowX,
            centerY - dp(6f) - arrowLift,
            arrowX + dp(4f),
            centerY - dp(2f) - arrowLift,
            paint,
        )
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = color(0xFF667085.toInt(), 0xFFCACCD2.toInt())
        paint.textSize = sp(12.5f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(
            canvas,
            if (progress >= 0.72f) "继续上滑即可锁定" else "上滑锁定 · 松开取消",
            pill.left + dp(39f),
            centerY,
        )
    }

    private fun drawAiPreviewText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (right <= left || bottom <= top) return
        val saveCount = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        paint.color = color(0xFF182235.toInt(), 0xFFF1F2F5.toInt())
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.LEFT
        paint.getFontMetrics(fontMetrics)
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) + dp(5f)
        aiPreviewLineLayoutCache.ensure(
            text = text,
            width = right - left,
            textSize = paint.textSize,
        ) { value, start, end, maximumWidth ->
            paint.breakText(value, start, end, true, maximumWidth, null)
        }
        val maxLines = maxOf(1, ((bottom - top) / lineHeight).toInt())
        val firstLine = maxOf(0, aiPreviewLineLayoutCache.lineCount - maxLines)
        var baseline = top - fontMetrics.ascent
        for (lineIndex in firstLine until aiPreviewLineLayoutCache.lineCount) {
            if (baseline + fontMetrics.descent > bottom) break
            val start = aiPreviewLineLayoutCache.startAt(lineIndex)
            val end = aiPreviewLineLayoutCache.endAt(lineIndex)
            if (end > start) canvas.drawText(text, start, end, left, baseline, paint)
            baseline += lineHeight
        }
        canvas.restoreToCount(saveCount)
    }

    private fun ellipsizeToWidth(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val available = maxOf(0f, maxWidth - paint.measureText(ellipsis))
        var count = paint.breakText(text, true, available, null)
        if (
            count in 1 until text.length &&
            text[count - 1].isHighSurrogate() &&
            text[count].isLowSurrogate()
        ) {
            count--
        }
        return text.take(count.coerceAtLeast(0)).trimEnd() + ellipsis
    }

    private fun drawBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = color(0x18000000, 0x2A000000)
        canvas.drawRect(0f, height - systemBarHeight, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawCandidates(canvas: Canvas) {
        if (candidatePanel.expanded) {
            drawExpandedCandidates(canvas)
            return
        }
        if (candidatePanel.composing.isBlank() && candidatePanel.candidates.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = color(0x22FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), collapsedCandidateBottom(), paint)

        val headerSpec = CandidatePresentationPolicy.headerSpec(
            composing = candidatePanel.composing,
            hasCandidates = candidatePanel.candidates.isNotEmpty(),
        )
        if (headerSpec?.role == CandidatePresentationPolicy.HeaderRole.COMPOSING) {
            paint.color = color(0xFF667085.toInt(), 0xFF8F949E.toInt())
            paint.textSize = sp(headerSpec.textSizeSp)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                candidatePanel.composing,
                0,
                minOf(12, candidatePanel.composing.length),
                dp(headerSpec.xDp),
                dp(headerSpec.yDp),
                paint,
            )
        }

        if (candidatePanel.candidates.isEmpty()) return

        drawVisibleCandidates(canvas)
        candidatePanel.controls.forEach { drawCandidateControl(canvas, it) }
    }

    private fun drawExpandedCandidates(canvas: Canvas) {
        val systemBarTop = height - systemBarHeight
        paint.style = Paint.Style.FILL
        paint.color = color(0xF2EDF3FA.toInt(), 0xF2161718.toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), systemBarTop, paint)

        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(0f, candidateHeight - max(1f, density), width.toFloat(), candidateHeight, paint)

        paint.color = color(0xFF596579.toInt(), 0xFFB8BBC2.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val headerRight = width - candidateControlWidth
        val saveCount = canvas.save()
        canvas.clipRect(dp(14f), 0f, headerRight, candidateHeight)
        drawCenteredText(
            canvas,
            if (candidatePanel.composing.isBlank()) "候选" else candidatePanel.composing,
            dp(14f),
            candidateHeight / 2f,
        )
        canvas.restoreToCount(saveCount)

        drawVisibleCandidates(canvas)

        val pagerTop = systemBarTop - expandedCandidatePagerHeight
        paint.color = color(0x16000000, 0x24FFFFFF)
        canvas.drawRect(0f, pagerTop, width.toFloat(), pagerTop + max(1f, density), paint)
        paint.color = color(0xFF667085.toInt(), 0xFF9B9EA5.toInt())
        paint.textSize = sp(12f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            candidatePanel.pageLabel,
            width / 2f,
            pagerTop + expandedCandidatePagerHeight / 2f,
        )
        candidatePanel.controls.forEach { drawCandidateControl(canvas, it) }
    }

    private fun drawVisibleCandidates(canvas: Canvas) {
        if (candidatePanel.expanded) {
            for (candidate in candidatePanel.visibleCandidates) {
                drawCandidateValue(canvas, candidate, textSizeSp = 17f)
            }
            return
        }

        val viewport = candidatePanel.collapsedViewportBounds ?: return
        val offset = candidatePanel.scrollOffset
        val visibleContentLeft = viewport.left + offset
        val visibleContentRight = viewport.right + offset
        val saveCount = canvas.save()
        canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom)
        canvas.translate(-offset, 0f)
        var candidateIndex = candidatePanel.firstCandidateEndingAfter(visibleContentLeft)
        val textSize = if (candidateTakesToolbar()) 19f else 17f
        while (candidateIndex < candidatePanel.visibleCandidates.size) {
            val candidate = candidatePanel.visibleCandidates[candidateIndex]
            if (candidate.bounds.left >= visibleContentRight) break
            drawCandidateValue(canvas, candidate, textSizeSp = textSize)
            candidateIndex += 1
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawCandidateValue(
        canvas: Canvas,
        candidate: VisibleCandidate,
        textSizeSp: Float,
    ) {
        val text = candidatePanel.candidates.getOrNull(candidate.sourceIndex) ?: return
        if (isCandidatePressed(candidate)) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x294F7CF5, 0x505E63D8)
            canvas.drawRoundRect(
                candidate.bounds.left,
                candidate.bounds.top,
                candidate.bounds.right,
                candidate.bounds.bottom,
                dp(7f),
                dp(7f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(textSizeSp)
        paint.textAlign = Paint.Align.LEFT
        val saveCount = canvas.save()
        canvas.clipRect(
            candidate.bounds.left,
            candidate.bounds.top,
            candidate.bounds.right,
            candidate.bounds.bottom,
        )
        drawCenteredText(
            canvas,
            text,
            candidate.textAnchor,
            candidate.bounds.centerY + dp(2f),
        )
        canvas.restoreToCount(saveCount)
    }

    private fun drawCandidateControl(canvas: Canvas, slot: CandidateControlSlot) {
        val pressed = isCandidateControlPressed(slot)
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(
                slot.bounds.left,
                slot.bounds.top,
                slot.bounds.right,
                slot.bounds.bottom,
                dp(9f),
                dp(9f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = if (slot.enabled) {
            color(0xFF586477.toInt(), 0xFFAAAEB6.toInt())
        } else {
            color(0x55586477, 0x55AAAEB6)
        }
        paint.textSize = sp(20f)
        paint.textAlign = Paint.Align.CENTER
        val label = when (slot.control) {
            CandidateControl.EXPAND -> "⌄"
            CandidateControl.COLLAPSE -> "⌃"
            CandidateControl.PREVIOUS_PAGE -> "‹"
            CandidateControl.NEXT_PAGE -> "›"
        }
        drawCenteredText(canvas, label, slot.bounds.centerX, slot.bounds.centerY)
    }

    private fun drawClipboardHeader(canvas: Canvas) {
        val headerTop = keyboardChromeBottom()
        paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "剪贴板", dp(14f), headerTop + dp(19f))
        if (clipboardPageLabel.isNotEmpty()) {
            paint.color = color(0xFF748094.toInt(), 0xFF92969E.toInt())
            paint.textSize = sp(10f)
            drawCenteredText(canvas, clipboardPageLabel, dp(62f), headerTop + dp(19f))
        }
    }

    private fun drawEditorHeader(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color(0x16FFFFFF, 0x0AFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), candidateHeight, paint)
        paint.color = color(0xFF344054.toInt(), 0xFFE7E9EE.toInt())
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "文字编辑", dp(14f), candidateHeight / 2f)
        paint.color = color(0x1F172033, 0x35FFFFFF)
        canvas.drawRect(0f, candidateHeight - max(1f, density), width.toFloat(), candidateHeight, paint)
    }

    private fun drawEditorPanelBackground(canvas: Canvas) {
        val bounds = editorMainBounds ?: return
        paint.style = Paint.Style.FILL
        paint.color = color(0xCFFFFFFF.toInt(), 0xFF252628.toInt())
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x17172033, 0x2AFFFFFF)
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.FILL
        paint.color = color(0x12172033, 0x22FFFFFF)
        val divider = max(1f, density)
        canvas.drawRect(bounds.left, editorBottomTop, bounds.right, editorBottomTop + divider, paint)
        editorBottomSeparators.forEach { x ->
            canvas.drawRect(x, editorBottomTop, x + divider, bounds.bottom, paint)
        }
    }

    private fun drawKeys(canvas: Canvas) {
        var index = 0
        while (index < keys.size) {
            val scrollPanel = keys[index].scrollPanel
            if (scrollPanel == null) {
                drawKey(canvas, keys[index])
                index += 1
                continue
            }
            val runStart = index
            while (index < keys.size && keys[index].scrollPanel == scrollPanel) {
                index += 1
            }
            drawScrollableKeyRun(
                canvas = canvas,
                panel = scrollPanel,
                startIndex = runStart,
                endIndex = index,
            )
        }
        drawSkillPicker(canvas)
        if (shouldAnimateSkills()) {
            scheduleSkillAnimationFrame()
        } else if (skillAnimationFrameScheduled) {
            removeCallbacks(skillAnimationRunnable)
            skillAnimationFrameScheduled = false
        }
    }

    private fun drawScrollableKeyRun(
        canvas: Canvas,
        panel: ScrollPanel,
        startIndex: Int,
        endIndex: Int,
    ) {
        val viewport = panelViewportBounds(panel) ?: return
        val offset = scrollStateFor(panel).offset
        val saveCount = canvas.save()
        canvas.clipRect(viewport)
        canvas.translate(0f, -offset)
        val visibleContentTop = viewport.top + offset
        val visibleContentBottom = viewport.bottom + offset
        var low = startIndex
        var high = endIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (keys[middle].bounds.bottom <= visibleContentTop) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        for (index in low until endIndex) {
            val key = keys[index]
            if (key.bounds.top >= visibleContentBottom) break
            drawKey(canvas, key)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val pressed = isKeyPressed(key)
        when (key.style) {
            KeyStyle.TOOL -> drawToolKey(canvas, key, pressed)
            KeyStyle.SYSTEM -> drawSystemKey(canvas, key, pressed)
            KeyStyle.CARD -> drawCardKey(canvas, key, pressed)
            KeyStyle.EMOJI -> drawEmojiKey(canvas, key, pressed)
            KeyStyle.CATEGORY -> drawCategoryKey(canvas, key, pressed)
            KeyStyle.SYMBOL -> drawSymbolKey(canvas, key, pressed)
            KeyStyle.SYMBOL_CATEGORY -> drawSymbolCategoryKey(canvas, key, pressed)
            KeyStyle.RAIL -> drawRailKey(canvas, key, pressed)
            KeyStyle.EDITOR_DIRECTION -> drawEditorDirectionKey(canvas, key, pressed)
            KeyStyle.EDITOR_PRIMARY -> drawEditorPrimaryKey(canvas, key, pressed)
            KeyStyle.EDITOR_ACTION -> drawEditorActionKey(canvas, key, pressed)
            KeyStyle.VOICE_PRIMARY -> drawVoicePrimaryKey(canvas, key, pressed)
            KeyStyle.TOOLBOX_CARD -> drawToolboxCard(canvas, key, pressed)
            else -> drawKeyboardKey(canvas, key, pressed)
        }
        if (
            isActiveSkillSource(key) &&
            key.style != KeyStyle.LETTER &&
            key.style != KeyStyle.ACTION &&
            activeSkillAuroraOverlay == null
        ) {
            drawSkillAuroraOverlay(canvas, key.bounds, foregroundOnly = true)
            drawActiveSkillMarker(canvas, key.bounds)
        } else if (
            isActiveSkillSource(key) &&
            key.style != KeyStyle.LETTER &&
            key.style != KeyStyle.ACTION
        ) {
            drawActiveSkillMarker(canvas, key.bounds)
        }
    }

    private fun drawToolboxCard(canvas: Canvas, key: Key, pressed: Boolean) {
        val accent = when (key.icon) {
            Icon.SYMBOLS -> color(0xFF4D78EA.toInt(), 0xFF8BA8FF.toInt())
            Icon.EDITOR -> color(0xFF2E8E9E.toInt(), 0xFF5DD2E3.toInt())
            Icon.VOICE -> color(0xFF8B5BE8.toInt(), 0xFFB99AFF.toInt())
            Icon.CLIPBOARD -> color(0xFFE17B42.toInt(), 0xFFFFA66F.toInt())
            Icon.EMOJI -> color(0xFFCC8B25.toInt(), 0xFFFFC561.toInt())
            else -> color(0xFF4C6F9D.toInt(), 0xFF95A9C6.toInt())
        }
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x145B7DF0, 0x286D61D8)
            canvas.drawRoundRect(key.bounds, dp(16f), dp(16f), paint)
        }
        val tileSize = minOf(dp(54f), key.bounds.width() - dp(14f), key.bounds.height() - dp(30f))
        val tile = RectF(
            key.bounds.centerX() - tileSize / 2f,
            key.bounds.top + dp(5f),
            key.bounds.centerX() + tileSize / 2f,
            key.bounds.top + dp(5f) + tileSize,
        )
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            accent
        } else {
            color(0xB8FFFFFF.toInt(), 0xFF292B2E.toInt())
        }
        canvas.drawRoundRect(tile, dp(17f), dp(17f), paint)

        val iconBounds = RectF(
            tile.left + dp(12f),
            tile.top + dp(12f),
            tile.right - dp(12f),
            tile.bottom - dp(12f),
        )
        key.icon?.let { icon ->
            drawIcon(
                canvas = canvas,
                icon = icon,
                bounds = iconBounds,
                tint = if (pressed) {
                    Color.WHITE
                } else {
                    accent
                },
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF354257.toInt(), 0xFFE3E5EA.toInt())
        paint.textSize = sp(12.5f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            key.label,
            key.bounds.centerX(),
            minOf(key.bounds.bottom - dp(7f), tile.bottom + dp(17f)),
        )
    }

    private fun drawToolboxPanelBackground(canvas: Canvas) {
        val top = keyboardChromeBottom()
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF344258.toInt(), 0xFFE7E9ED.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        drawCenteredText(canvas, "工具箱", horizontalPadding, top + dp(20f))
        paint.color = color(0xFF8290A4.toInt(), 0xFF989DA7.toInt())
        paint.textSize = sp(10.5f)
        paint.textAlign = Paint.Align.RIGHT
        drawCenteredText(
            canvas,
            "长按空格唤醒 AI",
            width - horizontalPadding,
            top + dp(20f),
        )
    }

    private fun drawVoicePrimaryKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val enabled = isKeyEnabled(key)
        paint.style = Paint.Style.FILL
        paint.shader = if (enabled) voiceWaveformShader else null
        paint.alpha = when {
            !enabled -> 255
            pressed -> 195
            else -> 245
        }
        if (!enabled) {
            paint.color = color(0xFFD7DEE9.toInt(), 0xFF303238.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(14f), dp(14f), paint)
        paint.shader = null
        paint.alpha = 255
        paint.color = if (enabled) {
            Color.WHITE
        } else {
            color(0xFF788397.toInt(), 0xFF989DA7.toInt())
        }
        paint.textSize = sp(16.5f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawKeyboardKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else if (key.style == KeyStyle.ACTION) {
            color(0xFFCED6E1.toInt(), 0xFF242526.toInt())
        } else {
            color(0xEFFFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, keyRadius, keyRadius, paint)
        if (isActiveSkillSource(key) && activeSkillAuroraOverlay == null) {
            drawSkillAuroraOverlay(canvas, key.bounds, foregroundOnly = false)
        }

        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.WHITE else color(0xFF111827.toInt(), 0xFFF6F7F9.toInt())
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, paint.color)
        } else {
            paint.textSize = sp(if (key.label.length > 2) 13f else 20f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY() + if (key.hint == null) 0f else dp(3f))
        }

        key.hint?.let { hint ->
            paint.color = color(0xFF7C8799.toInt(), 0xFF83868D.toInt())
            paint.textSize = sp(8.5f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(hint, key.bounds.centerX(), key.bounds.top + dp(10f), paint)
        }
        if (isActiveSkillSource(key)) {
            drawActiveSkillMarker(canvas, key.bounds)
        }
    }

    private fun isActiveSkillSource(key: Key): Boolean =
        key === activeSkillSourceKey

    private fun hasVisibleActiveSkillSource(): Boolean =
        activeSkillSourceKey != null

    /**
     * Resolve exactly one physical key. Several panels can simultaneously
     * contain controls with the same semantic code (for example a toolbar and
     * a panel BACK key); drawing by code alone would light both.
     */
    private fun resolveActiveSkillSourceKey() {
        activeSkillSourceKey = findActiveSkillSourceKey()
        publishActiveSkillAurora()
    }

    private fun findActiveSkillSourceKey(): Key? {
        val active = activeKeyboardSkill ?: return null
        val binding = skillBindings.binding(active.sourceKeyCode, active.direction)
            ?.takeIf { it.skillId == active.skillId }
            ?: return null
        skillVisualOwnerState.confirmed?.let { confirmed ->
            if (confirmed.active == active) {
                // A known owner may be temporarily hidden. Never migrate its Aurora to another
                // duplicate semantic key; restore it only when the same physical descriptor
                // becomes visible again.
                return keyForPhysicalOwner(confirmed.owner)
            }
        }
        var toolbarFallback: Key? = null
        for (index in keys.indices) {
            val key = keys[index]
            if (key.code != binding.keyCode || !canStartSkillGesture(key)) continue
            if (key.style != KeyStyle.TOOL) {
                return key
            }
            if (toolbarFallback == null) toolbarFallback = key
        }
        return toolbarFallback
    }

    private fun publishActiveSkillAurora() {
        val overlay = activeSkillAuroraOverlay ?: return
        val bounds = activeSkillSourceKey
            ?.takeIf { aiSurfaceState == null }
            ?.bounds
        overlay.updateBounds(bounds, keyRadius)
    }

    private fun physicalOwnerFor(key: Key): KeyboardSkillPhysicalOwner? {
        var sourceIndex = -1
        for (index in keys.indices) {
            if (keys[index] === key) {
                sourceIndex = index
                break
            }
        }
        if (sourceIndex < 0) return null
        val surface = surfaceForKeyIndex(sourceIndex) ?: return null
        val signature = key.physicalSkillSignature()
        val range = keyRange(surface)
        var occurrence = 0
        for (index in range.first until sourceIndex) {
            if (keys[index].physicalSkillSignature() == signature) occurrence++
        }
        return KeyboardSkillPhysicalOwner(
            surface = surface,
            panelToken = panel.name.takeIf {
                surface == KeyboardSkillPhysicalOwner.Surface.PANEL
            },
            signature = signature,
            occurrence = occurrence,
        )
    }

    private fun keyForPhysicalOwner(owner: KeyboardSkillPhysicalOwner): Key? {
        if (
            owner.surface == KeyboardSkillPhysicalOwner.Surface.PANEL &&
            owner.panelToken != panel.name
        ) {
            return null
        }
        val range = keyRange(owner.surface)
        var occurrence = 0
        for (index in range) {
            val key = keys[index]
            if (key.physicalSkillSignature() != owner.signature) continue
            if (occurrence == owner.occurrence) return key
            occurrence++
        }
        return null
    }

    private fun Key.physicalSkillSignature(): KeyboardSkillPhysicalOwner.Signature =
        KeyboardSkillPhysicalOwner.Signature(
            keyCode = code,
            styleToken = style.name,
            iconToken = icon?.name,
            editorActionToken = editorAction?.name,
            clipboardActionToken = clipboardAction?.name,
        )

    private fun surfaceForKeyIndex(index: Int): KeyboardSkillPhysicalOwner.Surface? = when {
        index >= toolbarKeyStart && index < toolbarKeyEndExclusive ->
            KeyboardSkillPhysicalOwner.Surface.TOOLBAR
        index >= panelKeyStart && index < panelKeyEndExclusive ->
            KeyboardSkillPhysicalOwner.Surface.PANEL
        index >= systemBarKeyStart && index < systemBarKeyEndExclusive ->
            KeyboardSkillPhysicalOwner.Surface.SYSTEM_BAR
        else -> null
    }

    private fun keyRange(surface: KeyboardSkillPhysicalOwner.Surface): IntRange {
        val (start, endExclusive) = when (surface) {
            KeyboardSkillPhysicalOwner.Surface.TOOLBAR ->
                toolbarKeyStart to toolbarKeyEndExclusive
            KeyboardSkillPhysicalOwner.Surface.PANEL ->
                panelKeyStart to panelKeyEndExclusive
            KeyboardSkillPhysicalOwner.Surface.SYSTEM_BAR ->
                systemBarKeyStart to systemBarKeyEndExclusive
        }
        return start until endExclusive
    }

    private fun shouldAnimateSkills(): Boolean =
        KeyboardSkillAnimationPolicy.shouldAnimate(
            activeSkill = activeKeyboardSkill.takeIf {
                activeSkillAuroraOverlay == null && hasVisibleActiveSkillSource()
            },
            pickerVisible = skillGestureSession.isPickerVisible(),
            isShown = isShown,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        )

    private fun scheduleSkillAnimationFrame() {
        if (skillAnimationFrameScheduled) return
        skillAnimationFrameScheduled = true
        postOnAnimationDelayed(
            skillAnimationRunnable,
            KeyboardSkillAnimationPolicy.FRAME_INTERVAL_MILLIS,
        )
    }

    private fun updateSkillAnimationDirtyBounds() {
        skillAnimationDirtyBounds.setEmpty()
        if (activeSkillAuroraOverlay == null) {
            activeSkillSourceKey?.let { skillAnimationDirtyBounds.set(it.bounds) }
        }
        if (skillGestureSession.isPickerVisible()) {
            if (skillAnimationDirtyBounds.isEmpty) {
                skillAnimationDirtyBounds.set(skillPickerSourceBounds)
            } else {
                skillAnimationDirtyBounds.union(skillPickerSourceBounds)
            }
            for (slot in skillPickerOptionBounds) {
                if (!slot.isEmpty) skillAnimationDirtyBounds.union(slot)
            }
        }
    }

    private fun skillAnimationPhase(): Float =
        if (ValueAnimator.areAnimatorsEnabled()) {
            val durationScale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ValueAnimator.getDurationScale().takeIf { it.isFinite() && it > 0f } ?: 1f
            } else {
                1f
            }
            val period = (SKILL_AURORA_PERIOD_MILLIS * durationScale)
                .toLong()
                .coerceAtLeast(1L)
            (SystemClock.uptimeMillis() % period).toFloat() / period.toFloat()
        } else {
            0f
        }

    /**
     * A cached sweep shader is transformed instead of recreated each frame.
     * Only matrix coefficients, paint alpha and the reusable path are mutated.
     */
    private fun drawSkillAuroraOverlay(
        canvas: Canvas,
        bounds: RectF,
        foregroundOnly: Boolean,
    ) {
        val shader = skillAuroraShader ?: return
        val phase = skillAnimationPhase()
        val scale = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1f)
        skillAuroraMatrix.reset()
        skillAuroraMatrix.setScale(scale, scale)
        skillAuroraMatrix.postRotate(phase * 360f)
        skillAuroraMatrix.postTranslate(bounds.centerX(), bounds.centerY())
        shader.setLocalMatrix(skillAuroraMatrix)

        val pulse = ((sin(phase * TWO_PI) + 1f) * 0.5f)
        paint.style = Paint.Style.FILL
        paint.shader = shader
        paint.alpha = if (foregroundOnly) {
            (46f + pulse * 24f).toInt()
        } else {
            (92f + pulse * 42f).toInt()
        }
        canvas.drawRoundRect(bounds, keyRadius, keyRadius, paint)

        skillAuroraPath.reset()
        skillAuroraPath.addRoundRect(bounds, keyRadius, keyRadius, Path.Direction.CW)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (foregroundOnly) 1.25f else 1.7f)
        paint.alpha = (175f + pulse * 65f).toInt()
        canvas.drawPath(skillAuroraPath, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawActiveSkillMarker(canvas: Canvas, bounds: RectF) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(
            bounds.right - dp(6.5f),
            bounds.top + dp(6.5f),
            dp(2.4f),
            paint,
        )
        paint.color = color(0xFF6A5BFF.toInt(), 0xFFB88AFF.toInt())
        canvas.drawCircle(
            bounds.right - dp(6.5f),
            bounds.top + dp(6.5f),
            dp(1.35f),
            paint,
        )
    }

    private fun drawSkillPicker(canvas: Canvas) {
        if (!skillGestureSession.isPickerVisible()) return
        val options = skillPickerOptions ?: return
        if (skillPickerSourceBounds.isEmpty) return
        val layout = skillPickerLayout ?: return
        val sourceX = skillPickerSourceBounds.centerX()
        val sourceY = skillPickerSourceBounds.centerY()

        // Populate reusable geometry first so connector lines sit below chips.
        for (direction in KeyboardSkillDirection.entries) {
            val slot = skillPickerOptionBounds[direction.ordinal]
            val geometry = layout.slot(direction)
            if (options.binding(direction) == null || geometry == null) {
                slot.setEmpty()
                continue
            }
            slot.set(
                geometry.bounds.left,
                geometry.bounds.top,
                geometry.bounds.right,
                geometry.bounds.bottom,
            )
        }

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(density, dp(1.2f))
        paint.color = color(0x664E7DFF, 0x999D82FF.toInt())
        for (direction in KeyboardSkillDirection.entries) {
            val slot = skillPickerOptionBounds[direction.ordinal]
            if (!slot.isEmpty) {
                canvas.drawLine(sourceX, sourceY, slot.centerX(), slot.centerY(), paint)
            }
        }

        val phase = skillAnimationPhase()
        for (direction in KeyboardSkillDirection.entries) {
            val binding = options.binding(direction) ?: continue
            val slot = skillPickerOptionBounds[direction.ordinal]
            val highlighted = skillGestureSession.highlightedDirection() == direction
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
            paint.color = when {
                highlighted -> color(0xEE4F70F3.toInt(), 0xEE735FDE.toInt())
                activeKeyboardSkill?.skillId == binding.skillId ->
                    color(0xEEDAF3F1.toInt(), 0xEE31343B.toInt())
                else -> color(0xF5FFFFFF.toInt(), 0xF52A2B2F.toInt())
            }
            canvas.drawRoundRect(slot, dp(13f), dp(13f), paint)

            val shader = skillAuroraShader
            if (shader != null) {
                skillAuroraMatrix.reset()
                skillAuroraMatrix.setScale(slot.width(), slot.height())
                skillAuroraMatrix.postRotate(phase * 360f + direction.ordinal * 40f)
                skillAuroraMatrix.postTranslate(slot.centerX(), slot.centerY())
                shader.setLocalMatrix(skillAuroraMatrix)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(if (highlighted) 2.1f else 1.1f)
                paint.shader = shader
                paint.alpha = if (highlighted) 255 else 180
                canvas.drawRoundRect(slot, dp(13f), dp(13f), paint)
                paint.shader = null
                paint.alpha = 255
            }

            paint.style = Paint.Style.FILL
            paint.color = when {
                highlighted -> Color.WHITE
                else -> color(0xFF172033.toInt(), 0xFFF4F5F8.toInt())
            }
            paint.textSize = sp(11.5f)
            paint.textAlign = Paint.Align.CENTER
            val saveCount = canvas.save()
            canvas.clipRect(slot)
            val visibleLabel = skillPickerVisibleLabels[direction.ordinal] ?: binding.label
            drawCenteredText(canvas, visibleLabel, slot.centerX(), slot.centerY())
            canvas.restoreToCount(saveCount)
        }
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun KeyboardSkillDirection.arrow(): String = when (this) {
        KeyboardSkillDirection.UP -> "↑"
        KeyboardSkillDirection.RIGHT -> "→"
        KeyboardSkillDirection.DOWN -> "↓"
        KeyboardSkillDirection.LEFT -> "←"
    }

    private fun skillLabel(
        active: ActiveKeyboardSkill?,
        bindings: KeyboardSkillBindingSet,
    ): String? = active?.let { activeSkill ->
        bindings.binding(activeSkill.sourceKeyCode, activeSkill.direction)
            ?.takeIf { it.skillId == activeSkill.skillId }
            ?.label
    }

    private fun announceSkillAccessibility(message: String) {
        if (isShown) announceForAccessibility(message)
    }

    private fun updateSkillAccessibilityDescription() {
        val active = activeKeyboardSkill
        val base = KeyboardSkillAccessibilityText.keyboardContentDescription(
            activeSkillId = active?.skillId,
            activeLabel = skillLabel(active, skillBindings),
            pickerOptions = skillPickerOptions.takeIf {
                skillGestureSession.isPickerVisible()
            },
            highlightedDirection = skillGestureSession.highlightedDirection(),
        )
        val next = skillFeedbackMessage?.let { "$base，提示：$it" } ?: base
        if (contentDescription?.toString() != next) {
            contentDescription = next
        }
    }

    private fun ellipsizeSkillLabel(value: String, maximumWidth: Float): String {
        if (paint.measureText(value) <= maximumWidth) return value
        val ellipsis = "…"
        val available = (maximumWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        var count = paint.breakText(value, true, available, null)
        if (
            count in 1 until value.length &&
            Character.isHighSurrogate(value[count - 1]) &&
            Character.isLowSurrogate(value[count])
        ) {
            count--
        }
        return value.take(count) + ellipsis
    }

    private fun drawToolKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(9f), dp(9f), paint)
        }
        val iconColor = color(0xFF586477.toInt(), 0xFFB6BAC2.toInt())
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, iconColor)
        } else {
            paint.color = iconColor
            paint.textSize = sp(if (key.label.length > 1) 14f else 19f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        }
    }

    private fun drawSystemKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x244F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(12f), dp(12f), paint)
        }
        drawIcon(
            canvas = canvas,
            icon = if (key.code == KeyCodes.SWITCH_INPUT_METHOD) Icon.KEYBOARD else Icon.CLIPBOARD,
            bounds = key.bounds,
            tint = color(0xFF39465B.toInt(), 0xFFE1E3E8.toInt()),
        )
    }

    private fun drawCardKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0x335B7DF0, 0x556D61D8)
        } else {
            color(0xBFFFFFFF.toInt(), 0xFF292A2C.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(11f), dp(11f), paint)
        paint.color = color(0xFF263247.toInt(), 0xFFF0F1F4.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val x = key.bounds.left + dp(11f)
        val saveCount = canvas.save()
        canvas.clipRect(
            x,
            key.bounds.top + dp(3f),
            key.bounds.right - dp(40f),
            key.bounds.bottom - dp(3f),
        )
        drawCenteredText(canvas, key.label, x, key.bounds.centerY() - if (key.secondaryLabel != null) dp(8f) else 0f)
        key.secondaryLabel?.let { secondLine ->
            paint.color = color(0xFF6B7484.toInt(), 0xFF9B9EA5.toInt())
            drawCenteredText(canvas, secondLine, x, key.bounds.centerY() + dp(10f))
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawEmojiKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x255B7DF0, 0x456D61D8)
            canvas.drawCircle(key.bounds.centerX(), key.bounds.centerY(), minOf(key.bounds.width(), key.bounds.height()) * 0.42f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF5F5F7.toInt())
        paint.textSize = sp(25f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawCategoryKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val selected = key.clipboardIndex == emojiGroupIndex
        if (selected || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x224F7CF5, 0x385E63D8)
            canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        }
        paint.color = if (selected) color(0xFF4F6FE8.toInt(), 0xFFC0B8FF.toInt()) else color(0xFF647084.toInt(), 0xFFA4A8B0.toInt())
        paint.textSize = sp(16f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawSymbolPanelBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color(0xB8FFFFFF.toInt(), 0xFF252627.toInt())
        symbolCategoryBounds?.let { canvas.drawRoundRect(it, dp(8f), dp(8f), paint) }
        symbolGridBounds?.let { canvas.drawRoundRect(it, dp(8f), dp(8f), paint) }
    }

    private fun drawSymbolCategoryKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val selected = key.clipboardIndex == symbolCategoryIndex
        if (selected || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = if (selected) {
                color(0xFFCFD8E6.toInt(), 0xFF3B3D40.toInt())
            } else {
                color(0x804F7CF5.toInt(), 0x705E63D8)
            }
            canvas.drawRoundRect(key.bounds, dp(6f), dp(6f), paint)
        }
        paint.color = if (selected) {
            color(0xFF152033.toInt(), 0xFFF4F4F6.toInt())
        } else {
            color(0xFF5B6678.toInt(), 0xFFB8BBC2.toInt())
        }
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawSymbolKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0x405B7DF0, 0x556D61D8)
        } else {
            color(0x66FFFFFF, 0x10FFFFFF)
        }
        canvas.drawRect(key.bounds, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x18172033, 0x24FFFFFF)
        canvas.drawRect(key.bounds, paint)
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF5F5F7.toInt())
        paint.textSize = sp(
            when {
                key.label.length <= 2 -> 20f
                key.label.length <= 5 -> 15f
                else -> 10.5f
            },
        )
        paint.textAlign = Paint.Align.CENTER
        val textSave = canvas.save()
        canvas.clipRect(
            key.bounds.left + dp(2f),
            key.bounds.top,
            key.bounds.right - dp(2f),
            key.bounds.bottom,
        )
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        canvas.restoreToCount(textSave)
    }

    private fun drawRailKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xE6FFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(5f), dp(5f), paint)
        paint.color = if (pressed) Color.WHITE else color(0xFF1C2433.toInt(), 0xFFF3F4F7.toInt())
        paint.textSize = sp(17f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
    }

    private fun drawEditorPrimaryKey(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed || editorSelectionMode) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xE8F5F7FB.toInt(), 0xFF303134.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        if (!pressed && !editorSelectionMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0x30172033, 0x45FFFFFF)
            canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        }
        paint.color = if (pressed || editorSelectionMode) {
            Color.WHITE
        } else {
            color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        }
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            if (editorSelectionMode) "取消选择" else "开始选择",
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
        paint.style = Paint.Style.FILL
    }

    private fun drawEditorActionKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val enabled = isKeyEnabled(key)
        paint.style = Paint.Style.FILL
        paint.color = when {
            !enabled -> color(0x5CE1E6EE, 0x66303336)
            pressed -> color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
            else -> color(0xD9E2E8F1.toInt(), 0xFF303134.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(8f), dp(8f), paint)
        if (enabled && !pressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0x15172033, 0x24FFFFFF)
            canvas.drawRoundRect(key.bounds, dp(8f), dp(8f), paint)
        }
        paint.style = Paint.Style.FILL
        val tint = when {
            !enabled -> color(0x66717B8C, 0x668C9098)
            pressed -> Color.WHITE
            else -> color(0xFF344054.toInt(), 0xFFE8EAF0.toInt())
        }
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, tint)
        } else {
            paint.color = tint
            paint.textSize = sp(14.5f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        }
    }

    private fun drawEditorDirectionKey(canvas: Canvas, key: Key, pressed: Boolean) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x255B7DF0, 0x456D61D8)
            canvas.drawRoundRect(key.bounds, dp(9f), dp(9f), paint)
        }
        val tint = color(0xFF5D687A.toInt(), 0xFFB8BBC2.toInt())
        if (key.icon != null) {
            drawIcon(canvas, key.icon, key.bounds, tint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
            paint.textSize = sp(16f)
            paint.textAlign = Paint.Align.CENTER
            drawCenteredText(canvas, key.label, key.bounds.centerX(), key.bounds.centerY())
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Icon, bounds: RectF, tint: Int) {
        iconPainter.draw(canvas, icon, bounds, tint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float) {
        paint.getFontMetrics(fontMetrics)
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        keySceneBuildCount += 1L
        keys.clear()
        activeSkillSourceKey = null
        toolbarKeyStart = 0
        toolbarKeyEndExclusive = 0
        panelKeyStart = 0
        panelKeyEndExclusive = 0
        systemBarKeyStart = 0
        systemBarKeyEndExclusive = 0
        emojiGridBounds = null
        symbolCategoryBounds = null
        symbolGridBounds = null
        editorMainBounds = null
        editorBottomSeparators = FloatArray(0)
        voiceWaveformBounds.setEmpty()
        voiceStatusCenterY = 0f
        voiceTranscriptCenterY = 0f
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (!candidatePanel.expanded) {
            if (
                !candidateTakesToolbar() &&
                panel != Panel.EDITOR &&
                panel != Panel.VOICE
            ) {
                toolbarKeyStart = keys.size
                primaryLayout.appendToolbar(viewWidth, keys)
                toolbarKeyEndExclusive = keys.size
            }
            panelKeyStart = keys.size
            when (panel) {
                Panel.LETTERS -> primaryLayout.appendLetters(
                    request = KeyboardLetterLayoutRequest(
                        viewWidth = viewWidth,
                        viewHeight = viewHeight,
                        chromeBottom = keyboardChromeBottom(),
                        shifted = shifted,
                        chineseMode = chineseMode,
                        swipeMode = swipeCharacterMode(),
                    ),
                    output = keys,
                )

                Panel.NUMBERS -> primaryLayout.appendNumbers(
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    chromeBottom = keyboardChromeBottom(),
                    chineseMode = chineseMode,
                    output = keys,
                )

                Panel.TOOLBOX -> primaryLayout.appendToolbox(
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    chromeBottom = keyboardChromeBottom(),
                    output = keys,
                )

                Panel.SYMBOLS -> layoutSymbols(viewWidth, viewHeight)
                Panel.EMOJI -> layoutEmoji(viewWidth, viewHeight)
                Panel.CLIPBOARD -> layoutClipboard(viewWidth, viewHeight)
                Panel.EDITOR -> layoutEditor(viewWidth, viewHeight)
                Panel.VOICE -> layoutVoice(viewWidth, viewHeight)
            }
            panelKeyEndExclusive = keys.size
        }
        systemBarKeyStart = keys.size
        primaryLayout.appendSystemBar(viewWidth, viewHeight, keys)
        systemBarKeyEndExclusive = keys.size
        resolveActiveSkillSourceKey()
    }

    private fun layoutSymbols(viewWidth: Int, viewHeight: Int) {
        val top = keyboardChromeBottom() + dp(4f)
        val bottom = viewHeight - systemBarHeight - dp(6f)
        val railWidth = minOf(dp(82f), viewWidth * 0.23f)
        val actionHeight = dp(42f)
        val railRight = horizontalPadding + railWidth
        if (bottom <= top) return
        if (bottom - top <= actionHeight + keyGap) {
            keys += Key(
                label = "返回",
                code = KeyCodes.LETTERS,
                bounds = RectF(horizontalPadding, top, viewWidth - horizontalPadding, bottom),
                style = KeyStyle.RAIL,
            )
            return
        }
        val categoryBottom = bottom - actionHeight - keyGap
        symbolCategoryBounds = RectF(horizontalPadding, top, railRight, categoryBottom)
        symbolGridBounds = RectF(
            railRight + keyGap,
            top,
            viewWidth - horizontalPadding,
            bottom,
        )

        val categoryViewport = categoryBottom - top
        val categoryHeight = dp(43f)
        symbolCategoryScrollState.configure(
            contentExtent = SymbolCatalog.categories.size * categoryHeight,
            viewportExtent = categoryViewport,
        )
        SymbolCatalog.categories.forEachIndexed { index, category ->
            val itemTop = top + index * categoryHeight
            val itemBottom = itemTop + categoryHeight
            keys += Key(
                label = category.label,
                code = 0,
                bounds = RectF(horizontalPadding, itemTop, railRight, itemBottom),
                style = KeyStyle.SYMBOL_CATEGORY,
                clipboardIndex = index,
                scrollPanel = ScrollPanel.SYMBOL_CATEGORIES,
            )
        }
        keys += Key(
            label = "返回",
            code = KeyCodes.LETTERS,
            bounds = RectF(horizontalPadding, categoryBottom + keyGap, railRight, bottom),
            style = KeyStyle.RAIL,
        )

        symbolCategoryIndex = symbolCategoryIndex.coerceIn(0, SymbolCatalog.categories.lastIndex)
        val values = SymbolCatalog.categories[symbolCategoryIndex].values
        val grid = checkNotNull(symbolGridBounds)
        val columns = 4
        val itemWidth = grid.width() / columns
        val itemHeight = max(dp(49f), grid.height() / 5f)
        val contentRows = (values.size + columns - 1) / columns
        symbolGridScrollState.configure(
            contentExtent = contentRows * itemHeight,
            viewportExtent = grid.height(),
        )
        values.forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val itemTop = grid.top + row * itemHeight
            val itemBottom = itemTop + itemHeight
            keys += Key(
                label = text,
                code = 0,
                bounds = RectF(
                    grid.left + column * itemWidth,
                    itemTop,
                    grid.left + (column + 1) * itemWidth,
                    itemBottom,
                ),
                style = KeyStyle.SYMBOL,
                text = text,
                scrollPanel = ScrollPanel.SYMBOL_VALUES,
            )
        }
    }

    private fun layoutEmoji(viewWidth: Int, viewHeight: Int) {
        val top = keyboardChromeBottom() + dp(4f)
        val bottom = viewHeight - systemBarHeight - dp(6f)
        val categoryHeight = dp(29f)
        val actionHeight = dp(40f)
        val gridGap = dp(3f)
        if (bottom <= top) return
        if (bottom - top <= categoryHeight + actionHeight + gridGap * 2f) {
            layoutWeightedRow(
                items = EMOJI_ACTION_ROW,
                viewWidth = viewWidth,
                y = top,
                rowHeight = bottom - top,
            )
            return
        }
        val geometry = KeyboardLayoutContract.scrollableEmojiLayoutGeometry(
            contentTop = top,
            contentBottom = bottom,
            categoryHeight = categoryHeight,
            actionHeight = actionHeight,
            gridGap = gridGap,
        )
        val categorySlot = (viewWidth - horizontalPadding * 2) / EmojiCatalog.categories.size
        EmojiCatalog.categories.forEachIndexed { index, group ->
            keys += Key(
                group.icon,
                0,
                RectF(
                    horizontalPadding + index * categorySlot + dp(2f),
                    geometry.categoryTop,
                    horizontalPadding + (index + 1) * categorySlot - dp(2f),
                    geometry.categoryBottom,
                ),
                style = KeyStyle.CATEGORY,
                clipboardIndex = index,
            )
        }
        val columns = 7
        val itemWidth = (viewWidth - horizontalPadding * 2) / columns
        val viewportHeight = geometry.gridBottom - geometry.gridTop
        val itemHeight = max(dp(46f), viewportHeight / 3f)
        emojiGroupIndex = emojiGroupIndex.coerceIn(0, EmojiCatalog.categories.lastIndex)
        val values = EmojiCatalog.categories[emojiGroupIndex].values
        val contentRows = (values.size + columns - 1) / columns
        emojiScrollState.configure(contentRows * itemHeight, viewportHeight)
        emojiGridBounds = RectF(
            horizontalPadding,
            geometry.gridTop,
            viewWidth - horizontalPadding,
            geometry.gridBottom,
        )
        values.forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val itemTop = geometry.gridTop + row * itemHeight
            val itemBottom = itemTop + itemHeight
            keys += Key(
                text,
                0,
                RectF(
                    horizontalPadding + column * itemWidth,
                    itemTop,
                    horizontalPadding + (column + 1) * itemWidth,
                    itemBottom,
                ),
                style = KeyStyle.EMOJI,
                text = text,
                scrollPanel = ScrollPanel.EMOJI,
            )
        }
        layoutWeightedRow(
            items = EMOJI_ACTION_ROW,
            viewWidth = viewWidth,
            y = geometry.actionTop,
            rowHeight = geometry.actionBottom - geometry.actionTop,
        )
    }

    private fun layoutClipboard(viewWidth: Int, viewHeight: Int) {
        val headerTop = keyboardChromeBottom()
        val headerHeight = dp(36f)
        val headerIconWidth = dp(39f)
        keys += Key("", 0, RectF(viewWidth - headerIconWidth * 3, headerTop, viewWidth - headerIconWidth * 2, headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.REFRESH, clipboardAction = ClipboardAction.REFRESH)
        keys += Key("", 0, RectF(viewWidth - headerIconWidth * 2, headerTop, viewWidth - headerIconWidth, headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.CLEAR, clipboardAction = ClipboardAction.CLEAR)
        keys += Key("", KeyCodes.LETTERS, RectF(viewWidth - headerIconWidth, headerTop, viewWidth.toFloat(), headerTop + headerHeight), style = KeyStyle.TOOL, icon = Icon.BACK)
        val top = headerTop + headerHeight
        val bottom = viewHeight - systemBarHeight - dp(8f)
        if (
            bottom - top <= keyGap * (CLIPBOARD_ITEMS_PER_PAGE - 1) ||
            viewWidth.toFloat() <= horizontalPadding * 2f
        ) {
            clipboardPageLabel = ""
            return
        }
        if (clipboardItems.isEmpty()) {
            clipboardPageLabel = ""
            keys += Key(
                "暂无剪贴板文本  ·  复制文字后点刷新",
                0,
                RectF(horizontalPadding, top, viewWidth - horizontalPadding, bottom),
                style = KeyStyle.CARD,
            )
            return
        }
        val pageCount = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        clipboardPageIndex = clipboardPageIndex.coerceIn(0, pageCount - 1)
        clipboardPageLabel = if (pageCount > 1) "${clipboardPageIndex + 1}/$pageCount" else ""
        val pageStart = clipboardPageIndex * CLIPBOARD_ITEMS_PER_PAGE
        KeyboardLayoutContract.clipboardCardSlots(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            itemCount = clipboardItems.size,
            pageStart = pageStart,
            horizontalPadding = horizontalPadding,
            gap = keyGap,
            itemsPerPage = CLIPBOARD_ITEMS_PER_PAGE,
        ).forEach { slot ->
            val text = clipboardItems[slot.sourceIndex]
            val previewLines = clipboardPreviewLines(
                text = text,
                maximumWidth = slot.right - slot.left - dp(62f),
            )
            keys += Key(
                previewLines.first,
                0,
                RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = KeyStyle.CARD,
                text = text,
                secondaryLabel = previewLines.second,
            )
            keys += Key(
                "",
                0,
                RectF(slot.right - dp(31f), slot.top + dp(2f), slot.right - dp(2f), slot.top + dp(31f)),
                style = KeyStyle.TOOL,
                icon = Icon.CLEAR,
                clipboardAction = ClipboardAction.DELETE,
                clipboardIndex = slot.sourceIndex,
            )
        }
    }

    private fun clipboardPreviewLines(text: String, maximumWidth: Float): Pair<String, String?> {
        paint.textSize = sp(13f)
        return KeyboardLayoutContract.clipboardPreviewLines(
            text = text,
            maximumWidth = maximumWidth.coerceAtLeast(1f),
            measureText = { value -> paint.measureText(value) },
        )
    }

    private fun layoutEditor(viewWidth: Int, viewHeight: Int) {
        keys += Key(
            label = "",
            code = 0,
            bounds = RectF(viewWidth - dp(62f), 0f, viewWidth.toFloat(), candidateHeight),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
            editorAction = EditorAction.BACK,
        )
        val contentTop = candidateHeight + dp(7f)
        val contentBottom = viewHeight - systemBarHeight - dp(8f)
        if (
            contentBottom - contentTop <= keyGap * 5f ||
            viewWidth.toFloat() <= horizontalPadding * 2f + keyGap * 4f
        ) {
            return
        }
        val slots = KeyboardLayoutContract.editorLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = contentTop,
            contentBottom = contentBottom,
            horizontalPadding = horizontalPadding,
            gap = keyGap,
        )
        val railRoles = setOf(
            KeyboardLayoutContract.EditorKeyRole.DELETE,
            KeyboardLayoutContract.EditorKeyRole.COPY,
            KeyboardLayoutContract.EditorKeyRole.CUT,
            KeyboardLayoutContract.EditorKeyRole.PASTE,
        )
        val mainSlots = slots.filterNot { it.role in railRoles }
        editorMainBounds = RectF(
            mainSlots.minOf { it.left },
            mainSlots.minOf { it.top },
            mainSlots.maxOf { it.right },
            mainSlots.maxOf { it.bottom },
        )
        val bottomSlots = slots.filter {
            it.role == KeyboardLayoutContract.EditorKeyRole.HOME ||
                it.role == KeyboardLayoutContract.EditorKeyRole.SELECT_ALL ||
                it.role == KeyboardLayoutContract.EditorKeyRole.END
        }.sortedBy { it.left }
        editorBottomTop = bottomSlots.first().top
        editorBottomSeparators = bottomSlots.zipWithNext { left, right ->
            (left.right + right.left) / 2f
        }.toFloatArray()

        slots.forEach { slot ->
            val action = slot.role.toEditorAction()
            val icon = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.UP -> Icon.UP
                KeyboardLayoutContract.EditorKeyRole.LEFT -> Icon.BACK
                KeyboardLayoutContract.EditorKeyRole.RIGHT -> Icon.RIGHT
                KeyboardLayoutContract.EditorKeyRole.DOWN -> Icon.DOWN
                KeyboardLayoutContract.EditorKeyRole.DELETE -> Icon.DELETE
                KeyboardLayoutContract.EditorKeyRole.HOME -> Icon.HOME
                KeyboardLayoutContract.EditorKeyRole.END -> Icon.END
                else -> null
            }
            val label = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION -> "开始选择"
                KeyboardLayoutContract.EditorKeyRole.COPY -> "复制"
                KeyboardLayoutContract.EditorKeyRole.CUT -> "剪切"
                KeyboardLayoutContract.EditorKeyRole.PASTE -> "粘贴"
                KeyboardLayoutContract.EditorKeyRole.SELECT_ALL -> "全选"
                else -> ""
            }
            val style = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION -> KeyStyle.EDITOR_PRIMARY
                KeyboardLayoutContract.EditorKeyRole.DELETE,
                KeyboardLayoutContract.EditorKeyRole.COPY,
                KeyboardLayoutContract.EditorKeyRole.CUT,
                KeyboardLayoutContract.EditorKeyRole.PASTE -> KeyStyle.EDITOR_ACTION
                else -> KeyStyle.EDITOR_DIRECTION
            }
            keys += Key(
                label = label,
                code = 0,
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = style,
                icon = icon,
                editorAction = action,
            )
        }
    }

    private fun layoutVoice(viewWidth: Int, viewHeight: Int) {
        val state = voiceSurfaceState ?: return
        keys += Key(
            label = "",
            code = KeyCodes.VOICE_CANCEL,
            bounds = RectF(
                viewWidth - dp(58f),
                dp(3f),
                viewWidth - dp(5f),
                candidateHeight - dp(3f),
            ),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
        )
        val contentBottom = viewHeight - systemBarHeight
        if (contentBottom <= candidateHeight) return
        val geometry = KeyboardLayoutContract.voiceLayout(
            candidateHeight = candidateHeight,
            contentBottom = contentBottom,
            unit = density,
        )
        voiceStatusCenterY = geometry.statusCenterY
        voiceTranscriptCenterY = geometry.transcriptCenterY
        val buttonWidth = minOf(dp(296f), viewWidth - dp(54f))
        keys += Key(
            label = VoiceSurfaceControlPolicy.primaryLabel(state.phase),
            code = VoiceSurfaceControlPolicy.primaryKeyCode(state.phase),
            bounds = RectF(
                viewWidth / 2f - buttonWidth / 2f,
                geometry.primaryButtonTop,
                viewWidth / 2f + buttonWidth / 2f,
                geometry.primaryButtonBottom,
            ),
            style = KeyStyle.VOICE_PRIMARY,
        )
        voiceWaveformBounds.set(
            dp(28f),
            geometry.waveformTop,
            viewWidth - dp(28f),
            geometry.waveformBottom,
        )
    }

    private fun KeyboardLayoutContract.EditorKeyRole.toEditorAction(): EditorAction = when (this) {
        KeyboardLayoutContract.EditorKeyRole.UP -> EditorAction.UP
        KeyboardLayoutContract.EditorKeyRole.LEFT -> EditorAction.LEFT
        KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION -> EditorAction.TOGGLE_SELECTION
        KeyboardLayoutContract.EditorKeyRole.RIGHT -> EditorAction.RIGHT
        KeyboardLayoutContract.EditorKeyRole.DOWN -> EditorAction.DOWN
        KeyboardLayoutContract.EditorKeyRole.DELETE -> EditorAction.DELETE
        KeyboardLayoutContract.EditorKeyRole.COPY -> EditorAction.COPY
        KeyboardLayoutContract.EditorKeyRole.CUT -> EditorAction.CUT
        KeyboardLayoutContract.EditorKeyRole.PASTE -> EditorAction.PASTE
        KeyboardLayoutContract.EditorKeyRole.HOME -> EditorAction.HOME
        KeyboardLayoutContract.EditorKeyRole.SELECT_ALL -> EditorAction.SELECT_ALL
        KeyboardLayoutContract.EditorKeyRole.END -> EditorAction.END
    }

    private fun layoutWeightedRow(
        items: List<KeyboardLayoutContract.WeightedKey>,
        viewWidth: Int,
        y: Float,
        rowHeight: Float,
    ) {
        primaryLayout.appendWeightedRow(
            items = items,
            viewWidth = viewWidth,
            y = y,
            rowHeight = rowHeight,
            swipeMode = swipeCharacterMode(),
            output = keys,
            backToLettersIcon = Icon.BACK,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (aiSurfaceState != null) {
            return handleAiSurfaceTouch(event)
        }
        if (activePanelPointerId != NO_POINTER) {
            panelVelocityTracker?.addMovement(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handlePointerDown(event, event.actionIndex, isPrimary = true)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!skillGestureSession.isPickerVisible()) {
                    handlePointerDown(event, event.actionIndex, isPrimary = false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var invalidateWholeView = false
                repeat(event.pointerCount) { pointerIndex ->
                    val pointerId = event.getPointerId(pointerIndex)
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    if (
                        aiHoldGestureSession.move(pointerId, x, y) ==
                        AiHoldGestureSession.Outcome.ELIGIBILITY_CANCELLED
                    ) {
                        clearScheduledAiHold()
                    }
                    when (
                        skillGestureSession.move(
                            pointerId = pointerId,
                            x = x,
                            y = y,
                            pickerLayout = skillPickerLayout,
                        )
                    ) {
                        KeyboardSkillGestureSession.Outcome.ELIGIBILITY_CANCELLED -> {
                            invalidateWholeView =
                                invalidateWholeView || skillPickerLayout != null
                            clearScheduledSkillHold(clearPickerProjection = true)
                        }
                        KeyboardSkillGestureSession.Outcome.HIGHLIGHT_CHANGED -> {
                            invalidateWholeView = true
                            val direction = skillGestureSession.highlightedDirection()
                            updateSkillAccessibilityDescription()
                            if (
                                skillHapticGate.shouldEmit(
                                    event.eventTime,
                                    direction,
                                )
                            ) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            announceSkillAccessibility(
                                KeyboardSkillAccessibilityText.highlighted(
                                    direction = direction,
                                    binding = direction?.let { highlighted ->
                                        skillPickerOptions?.binding(highlighted)
                                    },
                                ),
                            )
                        }
                        else -> Unit
                    }
                    if (
                        skillGestureSession.isPickerVisible() &&
                        skillGestureSession.owns(pointerId)
                    ) {
                        return@repeat
                    }
                    if (skillGestureSession.blocksOrdinaryPointer(pointerId)) return@repeat
                    if (candidatePanel.ownsDrag(pointerId)) {
                        val candidateMove = candidatePanel.moveDrag(
                            pointerId = pointerId,
                            x = x,
                            y = y,
                            eventTimeMillis = event.eventTime,
                        )
                        if (candidateMove.dragLatched) {
                            val clearedPressedState =
                                pressedTargets.indexOfKey(pointerId) >= 0
                            touchReducer.cancel(pointerId)
                            pressedTargets.remove(pointerId)
                            val clearedOtherPressedState =
                                cancelOtherCandidateStripTouches(pointerId)
                            if (
                                candidateMove.changed ||
                                clearedPressedState ||
                                clearedOtherPressedState
                            ) {
                                invalidateCandidateViewport()
                            }
                            return@repeat
                        }
                    }
                    val target = touchReducer.target(pointerId) ?: return@repeat
                    val move = touchReducer.onMove(
                        pointerId = pointerId,
                        x = x,
                        y = y,
                        insideTapTarget = isInsideTapTarget(target, x, y),
                        policy = target.gesturePolicy,
                    )
                    val scrollPanel = scrollPanelFor(target)
                    if (move.verticalScrollLatched && scrollPanel != null) {
                        acquirePanelScrollForLatchedPointer(
                            pointerId = pointerId,
                            panel = scrollPanel,
                            y = y,
                            event = event,
                        )
                    }
                    if (
                        move.verticalScrollLatched &&
                        scrollPanel != null &&
                        pointerId == activePanelPointerId &&
                        scrollPanel == activePanelScroll
                    ) {
                        val newlyLatched = !activePanelScrollLatched
                        val currentY = y
                        val previousY = panelPointerYs[pointerId] ?: currentY
                        if (scrollStateFor(scrollPanel).scrollBy(previousY - currentY)) {
                            invalidateScrollPanel(scrollPanel)
                        }
                        activePanelScrollLatched = true
                        panelPointerYs.put(pointerId, currentY)
                        if (newlyLatched) {
                            cancelOtherPanelTouches(
                                ownerPointerId = pointerId,
                                panel = scrollPanel,
                            )
                            invalidateScrollPanel(scrollPanel)
                        }
                    } else if (
                        move.verticalScrollLatched &&
                        activePanelScrollLatched &&
                        pointerId != activePanelPointerId
                    ) {
                        // A secondary pointer can continue smoothly if the
                        // current owner lifts before it does.
                        panelPointerYs.put(pointerId, y)
                    }
                    if (move.canceled || move.tapSuppressed) {
                        pressedTargets.remove(pointerId)
                        invalidateTouchTarget(target)
                        if (backspaceRepeatSession.owns(pointerId)) stopBackspaceRepeat(pointerId)
                    }
                }
                if (invalidateWholeView) invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> {
                cancelAllTouches()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int, isPrimary: Boolean): Boolean {
        if (isPrimary) {
            val fullResetRequired =
                aiSurfaceState != null || skillPickerLayout != null
            invalidatePressedTargets()
            cancelAllTouches()
            if (fullResetRequired) invalidate()
        }
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val target = touchTargetAt(x, y) ?: return true
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
        scrollPanelFor(target)?.let { scrollPanel ->
            startPanelScroll(pointerId, scrollPanel, y, event)
        }
        val key = (target as? FrozenTouchTarget.KeyValue)?.key
        if (key != null && deleteRepeatTarget(key) != null) {
            dispatchDelete(key)
            startBackspaceRepeat(pointerId)
        }
        if (key?.code == KeyCodes.SPACE) {
            aiHoldGestureSession.begin(
                pointerId = pointerId,
                x = x,
                y = y,
                eventTimeMillis = event.eventTime,
            )?.let(::scheduleAiHold)
        } else if (key != null && canStartSkillGesture(key)) {
            val options = skillBindings.optionsForKey(key.code)
            if (options != null && options.count > 0) {
                skillGestureSession.begin(
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    eventTimeMillis = event.eventTime,
                    enabledDirectionMask = options.directionMask,
                )?.let { arm ->
                    skillPickerSourceBounds.set(key.bounds)
                    skillPickerSourceOwner = physicalOwnerFor(key)
                    skillPickerOptions = options
                    scheduleSkillHold(arm)
                }
            }
        }
        if (
            target !is FrozenTouchTarget.CandidatePageArea &&
            target !is FrozenTouchTarget.CandidateStripArea &&
            target !is FrozenTouchTarget.PanelScrollArea
        ) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidateTouchTarget(target)
        return true
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int): Boolean {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        when (aiHoldGestureSession.pointerUp(pointerId, event.eventTime)) {
            AiHoldGestureSession.Outcome.SHORT_TAP -> clearScheduledAiHold()
            AiHoldGestureSession.Outcome.HOLD_RELEASED -> {
                clearScheduledAiHold()
                touchReducer.cancel(pointerId)
                pressedTargets.remove(pointerId)
                stopBackspaceRepeat(pointerId)
                panelPointerYs.remove(pointerId)
                invalidate()
                return true
            }
            else -> Unit
        }
        val skillOwned = skillGestureSession.owns(pointerId)
        if (skillOwned) {
            /*
             * Android may deliver a final coordinate change only in ACTION_UP. Resolve it before
             * consuming the picker so the committed direction is never a stale MOVE highlight.
             * The commit haptic below is sufficient; do not emit an extra highlight tick here.
             */
            skillGestureSession.move(
                pointerId = pointerId,
                x = x,
                y = y,
                pickerLayout = skillPickerLayout,
            )
        }
        val skillFinish = skillGestureSession.pointerUp(pointerId)
        if (skillOwned) clearScheduledSkillHold(clearPickerProjection = false)
        if (skillFinish.consumed) {
            touchReducer.cancel(pointerId)
            pressedTargets.remove(pointerId)
            stopBackspaceRepeat(pointerId)
            panelPointerYs.remove(pointerId)
            skillFinish.direction?.let(::commitSkillSelection)
            clearSkillPickerProjection()
            invalidate()
            return true
        } else if (skillOwned) {
            clearSkillPickerProjection()
        }
        val originalTarget = touchReducer.target(pointerId)
        val candidateSettle = candidatePanel.finishDrag(
            pointerId = pointerId,
            x = x,
            y = y,
            eventTimeMillis = event.eventTime,
            fastFlingVelocity = dp(CANDIDATE_FAST_FLING_VELOCITY_DP_PER_SECOND),
        )
        val activation = if (candidateSettle?.dragged == true) {
            touchReducer.cancel(pointerId)
            null
        } else {
            val target = touchReducer.target(pointerId)
            target?.let {
                touchReducer.onUp(
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    insideTapTarget = isInsideTapTarget(it, x, y),
                    policy = it.gesturePolicy,
                )
            }
        }
        candidateSettle?.let { settle ->
            if (settle.dragged || settle.animate) {
                startCandidateSettle(settle)
            }
        }
        finishPanelScroll(
            pointerId = pointerId,
            panel = originalTarget?.let(::scrollPanelFor),
            shouldFling = activation?.gesture != null &&
                activation.gesture != TouchInputReducer.Gesture.TAP,
        )
        pressedTargets.remove(pointerId)
        stopBackspaceRepeat(pointerId)
        panelPointerYs.remove(pointerId)
        if (skillOwned) {
            invalidate()
        } else if (originalTarget != null) {
            invalidateTouchTarget(originalTarget)
        }
        if (activation != null) {
            activateTouchTarget(activation)
            if (
                activation.gesture == TouchInputReducer.Gesture.TAP &&
                activation.target !is FrozenTouchTarget.CandidatePageArea &&
                activation.target !is FrozenTouchTarget.CandidateStripArea &&
                activation.target !is FrozenTouchTarget.PanelScrollArea
            ) {
                performClick()
            }
        }
        return true
    }

    private fun touchTargetAt(x: Float, y: Float): FrozenTouchTarget? {
        candidatePanel.hitTest(x = x, y = y, visible = showsCandidates())?.let { hit ->
            return when (hit) {
                is CandidateHit.Control -> FrozenTouchTarget.CandidateControlValue(
                    value = hit.control,
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = tapGesturePolicy,
                )

                is CandidateHit.Value -> FrozenTouchTarget.CandidateValue(
                    revision = hit.revision,
                    sourceIndex = hit.sourceIndex,
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = if (hit.expanded) pageScrollGesturePolicy else tapGesturePolicy,
                )

                is CandidateHit.PageArea -> FrozenTouchTarget.CandidatePageArea(
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = pageScrollGesturePolicy,
                )

                is CandidateHit.StripArea -> FrozenTouchTarget.CandidateStripArea(
                    bounds = hit.bounds.toRectF(),
                    gesturePolicy = tapGesturePolicy,
                )
            }
        }
        for (index in keys.lastIndex downTo 0) {
            val key = keys[index]
            if (keyContainsPoint(key, x, y)) {
                // A disabled action owns its visible rectangle as a dead zone.
                // Falling through to gap resolution could otherwise turn a tap
                // near disabled COPY into the adjacent DELETE action.
                if (!isKeyEnabled(key)) return null
                return FrozenTouchTarget.KeyValue(
                    key = key,
                    gesturePolicy = gesturePolicyForKey(key),
                    bounds = screenHitBoundsForKey(key),
                )
            }
        }
        val nearestKeyIndex = KeyboardGapHitResolver.nearestIndex(
            x = x,
            y = y,
            maximumDistance = keyGap,
            targetCount = keys.size,
            isEligible = { index ->
                val key = keys[index]
                key.scrollPanel == null &&
                    key.style != KeyStyle.CARD &&
                    isKeyEnabled(key)
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
                gesturePolicy = gesturePolicyForKey(key),
                bounds = key.bounds,
            )
        }
        for (scrollPanel in ScrollPanel.entries) {
            val bounds = panelViewportBounds(scrollPanel) ?: continue
            if (bounds.contains(x, y)) {
                return FrozenTouchTarget.PanelScrollArea(
                    panel = scrollPanel,
                    bounds = RectF(bounds),
                    gesturePolicy = pageScrollGesturePolicy,
                )
            }
        }
        return null
    }

    private fun keyContainsPoint(key: Key, x: Float, y: Float): Boolean {
        val panel = key.scrollPanel ?: return key.bounds.contains(x, y)
        val viewport = panelViewportBounds(panel) ?: return false
        if (!viewport.contains(x, y)) return false
        val contentY = KeyboardScrollProjection.contentCoordinate(
            screenCoordinate = y,
            offset = scrollStateFor(panel).offset,
        )
        return key.bounds.contains(x, contentY)
    }

    private fun screenHitBoundsForKey(key: Key): RectF {
        val panel = key.scrollPanel ?: return key.bounds
        val viewport = panelViewportBounds(panel) ?: return key.bounds
        val offset = scrollStateFor(panel).offset
        return RectF(
            maxOf(key.bounds.left, viewport.left),
            maxOf(
                KeyboardScrollProjection.screenCoordinate(key.bounds.top, offset),
                viewport.top,
            ),
            minOf(key.bounds.right, viewport.right),
            minOf(
                KeyboardScrollProjection.screenCoordinate(key.bounds.bottom, offset),
                viewport.bottom,
            ),
        )
    }

    private fun scrollPanelFor(target: FrozenTouchTarget): ScrollPanel? = when (target) {
        is FrozenTouchTarget.PanelScrollArea -> target.panel
        is FrozenTouchTarget.KeyValue -> target.key.scrollPanel
        else -> null
    }

    private fun panelViewportBounds(panel: ScrollPanel): RectF? = when (panel) {
        ScrollPanel.EMOJI -> emojiGridBounds
        ScrollPanel.SYMBOL_CATEGORIES -> symbolCategoryBounds
        ScrollPanel.SYMBOL_VALUES -> symbolGridBounds
    }

    private fun scrollStateFor(panel: ScrollPanel): ContinuousVerticalScrollState = when (panel) {
        ScrollPanel.EMOJI -> emojiScrollState
        ScrollPanel.SYMBOL_CATEGORIES -> symbolCategoryScrollState
        ScrollPanel.SYMBOL_VALUES -> symbolGridScrollState
    }

    private fun isCollapsedCandidateScrollTarget(target: FrozenTouchTarget): Boolean =
        candidatePanel.canStartCollapsedDrag() &&
            (target is FrozenTouchTarget.CandidateValue || target is FrozenTouchTarget.CandidateStripArea)

    private fun gesturePolicyForKey(key: Key): TouchInputReducer.GesturePolicy = when {
        key.scrollPanel != null || key.style == KeyStyle.CARD -> pageScrollGesturePolicy
        SwipeCharacterMap.forKey(key.code, swipeCharacterMode()) != null -> TouchInputReducer.GesturePolicy.upwardFlick(
            minimumDistance = KeyboardGestureThresholds.upwardFlickDistance(
                minimumDistance = dp(12f),
                keyHeight = key.bounds.height(),
            ),
            verticalDominanceRatio = VERTICAL_GESTURE_DOMINANCE,
        )
        else -> tapGesturePolicy
    }

    private fun isInsideTapTarget(target: FrozenTouchTarget, x: Float, y: Float): Boolean {
        val bounds = target.bounds
        if (target !is FrozenTouchTarget.KeyValue) return bounds.contains(x, y)
        val hitSlop = maxOf(scaledTouchSlop, keyGap)
        return KeyboardGapHitResolver.containsWithSlop(
            x = x,
            y = y,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            slop = hitSlop,
        )
    }

    private fun activateTouchTarget(activation: TouchInputReducer.Activation<FrozenTouchTarget>) {
        when (val target = activation.target) {
            is FrozenTouchTarget.CandidateValue -> if (activation.gesture == TouchInputReducer.Gesture.TAP) {
                dispatchQueuedKeysNow()
                candidateListener?.invoke(target.revision, target.sourceIndex)
            } else if (candidatePanel.expanded) {
                scrollCandidatePage(if (activation.gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }
            is FrozenTouchTarget.CandidateControlValue -> if (activation.gesture == TouchInputReducer.Gesture.TAP) {
                activateCandidateControl(target.value)
            }
            is FrozenTouchTarget.CandidatePageArea -> if (activation.gesture != TouchInputReducer.Gesture.TAP) {
                scrollCandidatePage(if (activation.gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }
            is FrozenTouchTarget.CandidateStripArea -> Unit
            is FrozenTouchTarget.PanelScrollArea -> Unit
            is FrozenTouchTarget.KeyValue -> activateGesture(target.key, activation.gesture)
        }
    }

    private fun activateGesture(key: Key, gesture: TouchInputReducer.Gesture) {
        if (!isKeyEnabled(key)) return
        if (deleteRepeatTarget(key) != null) return // Repeatable DELETE is emitted immediately on DOWN.
        when {
            key.scrollPanel != null && gesture != TouchInputReducer.Gesture.TAP -> Unit
            key.style == KeyStyle.CARD && gesture != TouchInputReducer.Gesture.TAP && clipboardItems.isNotEmpty() -> {
                scrollClipboard(if (gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }
            gesture == TouchInputReducer.Gesture.SWIPE_UP && key.code > 0 -> {
                (key.hint ?: SwipeCharacterMap.forKey(key.code, swipeCharacterMode()))?.let {
                    dispatchQueuedKeysNow()
                    textListener?.invoke(it)
                }
            }
            gesture == TouchInputReducer.Gesture.TAP -> activateKey(key)
        }
    }

    private fun deleteRepeatTarget(key: Key): DeleteRepeatTarget? =
        DeleteRepeatTargetPolicy.resolve(
            keyCode = key.code,
            editorActionIsDelete = key.editorAction == EditorAction.DELETE,
        )

    /**
     * Space owns the AI hold gesture and DELETE owns immediate key-repeat.
     * Every other real, enabled key may expose configured Skill directions.
     */
    private fun canStartSkillGesture(key: Key): Boolean =
        KeyboardSkillKeyPolicy.supportsKeyCode(key.code) &&
            deleteRepeatTarget(key) == null &&
            key.style != KeyStyle.CARD &&
            key.style != KeyStyle.EMOJI &&
            key.style != KeyStyle.CATEGORY &&
            key.style != KeyStyle.SYMBOL &&
            key.style != KeyStyle.SYMBOL_CATEGORY &&
            key.scrollPanel == null &&
            isKeyEnabled(key)

    private fun dispatchDelete(key: Key) {
        when (deleteRepeatTarget(key)) {
            DeleteRepeatTarget.KEY -> enqueueKey(KeyCodes.DELETE)
            DeleteRepeatTarget.EDITOR -> {
                dispatchQueuedKeysNow()
                editorActionListener?.invoke(EditorAction.DELETE)
            }
            null -> Unit
        }
    }

    private fun isKeyEnabled(key: Key): Boolean {
        if (key.style == KeyStyle.VOICE_PRIMARY && key.code == 0) return false
        return when (key.editorAction) {
            EditorAction.COPY,
            EditorAction.CUT -> editorHasSelection

            EditorAction.PASTE -> editorCanPaste
            else -> true
        }
    }

    private fun activateCandidateControl(control: CandidateControl) {
        val change = candidatePanel.activate(
            control = control,
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
            fontScale = resources.configuration.fontScale,
        )
        if (change.cancelSettle) stopCandidateSettle()
        if (change.requiresKeySceneRebuild) rebuildKeys(width, height)
        invalidate()
    }

    private fun isCandidatePressed(candidate: VisibleCandidate): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (
                target is FrozenTouchTarget.CandidateValue &&
                target.revision == candidatePanel.candidateRevision &&
                target.sourceIndex == candidate.sourceIndex
            ) {
                return true
            }
        }
        return false
    }

    private fun isCandidateControlPressed(slot: CandidateControlSlot): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.CandidateControlValue && target.value == slot.control) return true
        }
        return false
    }

    private fun isKeyPressed(key: Key): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.KeyValue && target.key === key) return true
        }
        return false
    }

    private fun invalidatePressedTargets() {
        repeat(pressedTargets.size()) { index ->
            invalidateTouchTarget(pressedTargets.valueAt(index))
        }
    }

    private fun cancelAllTouches() {
        val activeGeneration = aiSurfaceState?.generation
        val aiOutcome = aiHoldGestureSession.cancelAll()
        clearScheduledAiHold()
        aiSurfaceState = null
        publishActiveSkillAurora()
        aiStopPointerId = NO_POINTER
        aiStopBounds.setEmpty()
        cancelOrdinaryTouches()
        if (
            aiOutcome == AiHoldGestureSession.Outcome.ACTIVE_CANCELLED &&
            activeGeneration != null
        ) {
            aiHoldListener?.onAiHoldCancelled(activeGeneration)
        }
    }

    private fun cancelOrdinaryTouches() {
        cancelSkillGesture()
        touchReducer.cancelAll()
        pressedTargets.clear()
        panelPointerYs.clear()
        clearPanelPointer()
        stopPanelFling()
        stopBackspaceRepeat()
        stopCandidateSettle()
        candidatePanel.cancelAllDrags()?.let { settle ->
            if (candidatePanel.moveTo(settle.targetOffset)) {
                invalidateCandidateViewport()
            }
        }
    }

    /**
     * The visible picker has exclusive ownership of the MotionEvent stream.
     * Secondary pointers that were already down are discarded, and later
     * pointer-down events remain dead until the owner commits or cancels.
     */
    private fun suspendOrdinaryInputForSkillPicker() {
        aiHoldGestureSession.cancelAll()
        clearScheduledAiHold()
        touchReducer.cancelAll()
        pressedTargets.clear()
        panelPointerYs.clear()
        clearPanelPointer()
        stopPanelFling()
        stopBackspaceRepeat()
        stopCandidateSettle()
        candidatePanel.cancelAllDrags()?.let { settle ->
            if (candidatePanel.moveTo(settle.targetOffset)) {
                invalidateCandidateViewport()
            }
        }
    }

    private fun scheduleAiHold(arm: AiHoldGestureSession.Arm) {
        scheduledAiHoldPointerId = arm.pointerId
        scheduledAiHoldGeneration = arm.generation
        removeCallbacks(aiHoldActivationRunnable)
        val delay = (arm.activationAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        postDelayed(aiHoldActivationRunnable, delay)
    }

    private fun clearScheduledAiHold() {
        scheduledAiHoldPointerId = NO_POINTER
        scheduledAiHoldGeneration = 0L
        removeCallbacks(aiHoldActivationRunnable)
    }

    private fun scheduleSkillHold(arm: KeyboardSkillGestureSession.Arm) {
        scheduledSkillPointerId = arm.pointerId
        scheduledSkillGeneration = arm.generation
        removeCallbacks(skillActivationRunnable)
        val delay = (arm.activationAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        postDelayed(skillActivationRunnable, delay)
    }

    private fun clearScheduledSkillHold(clearPickerProjection: Boolean) {
        scheduledSkillPointerId = NO_POINTER
        scheduledSkillGeneration = 0L
        removeCallbacks(skillActivationRunnable)
        if (clearPickerProjection) clearSkillPickerProjection()
    }

    private fun clearSkillPickerProjection() {
        skillPickerSourceBounds.setEmpty()
        skillPickerSourceOwner = null
        skillPickerOptions = null
        skillPickerLayout = null
        for (slot in skillPickerOptionBounds) {
            slot.setEmpty()
        }
        skillPickerVisibleLabels.fill(null)
        skillHapticGate.reset()
        updateSkillAccessibilityDescription()
    }

    private fun rebuildSkillPickerLayout() {
        val options = skillPickerOptions
        if (
            options == null ||
            skillPickerSourceBounds.isEmpty ||
            width <= 0 ||
            height <= 0
        ) {
            skillPickerLayout = null
            return
        }
        val horizontalInset = minOf(horizontalPadding, width * 0.1f)
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
        skillPickerLayout = KeyboardSkillPickerGeometry.layout(
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            source = KeyboardSkillPickerBounds(
                left = skillPickerSourceBounds.left,
                top = skillPickerSourceBounds.top,
                right = skillPickerSourceBounds.right,
                bottom = skillPickerSourceBounds.bottom,
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
            val geometry = skillPickerLayout?.slot(direction)
            val target = skillPickerOptionBounds[direction.ordinal]
            if (geometry == null) {
                target.setEmpty()
                skillPickerVisibleLabels[direction.ordinal] = null
            } else {
                target.set(
                    geometry.bounds.left,
                    geometry.bounds.top,
                    geometry.bounds.right,
                    geometry.bounds.bottom,
                )
                paint.textSize = sp(11.5f)
                skillPickerVisibleLabels[direction.ordinal] =
                    options.binding(direction)?.let { binding ->
                        ellipsizeSkillLabel(
                            "${direction.arrow()} ${binding.label}",
                            (target.width() - dp(12f)).coerceAtLeast(1f),
                        )
                    }
            }
        }
    }

    private fun cancelSkillGesture() {
        skillGestureSession.cancelAll()
        clearScheduledSkillHold(clearPickerProjection = true)
    }

    private fun commitSkillSelection(direction: KeyboardSkillDirection) {
        val binding = skillPickerOptions?.binding(direction) ?: return
        val listener = skillSelectionListener ?: return
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
        val physicalOwner = when (action) {
            KeyboardSkillToggleAction.ACTIVATE -> checkNotNull(skillPickerSourceOwner) {
                "Visible Skill picker lost its physical source owner"
            }
            KeyboardSkillToggleAction.DEACTIVATE -> null
        }
        skillVisualOwnerState = KeyboardSkillVisualOwnerPolicy.request(
            skillVisualOwnerState,
            KeyboardSkillPendingVisualOwner(
                requestToken = requestToken,
                expectedActive = expectedActive,
                owner = physicalOwner,
            ),
        )
        /*
         * Do not light an optimistic aurora here. Space hold freezes the authoritative
         * in-memory catalog in the IME service; showing a pending selection as active would let
         * the UI claim Skill B while the next run still receives Skill A. The background
         * mutation publishes updateKeyboardSkills only after its fsync/atomic CURRENT commit.
         */
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener.onSkillSelection(
            KeyboardSkillSelection(
                binding = binding,
                action = action,
                requestToken = requestToken,
            ),
        )
    }

    private fun beginAiSurface(generation: Long) {
        cancelOrdinaryTouches()
        // Preserve the established FIFO boundary: any key already committed
        // before the hold activation must reach the editor before it snapshots.
        dispatchQueuedKeysNow()
        aiSurfaceState = AiSurfaceState(
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
            lockProgress = 0f,
            locked = false,
        )
        publishActiveSkillAurora()
        aiStopPointerId = NO_POINTER
        aiStopBounds.setEmpty()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        aiHoldListener?.onAiHoldStarted(generation)
    }

    private fun handleAiSurfaceTouch(event: MotionEvent): Boolean {
        val state = aiSurfaceState ?: return true
        if (state.locked) {
            return handleLockedAiSurfaceTouch(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { pointerIndex ->
                    val pointerId = event.getPointerId(pointerIndex)
                    if (!aiHoldGestureSession.owns(pointerId)) return@repeat
                    val outcome = aiHoldGestureSession.move(
                        pointerId,
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    )
                    val locked = aiHoldGestureSession.isLocked()
                    aiSurfaceState = aiSurfaceState?.copy(
                        lockProgress = aiHoldGestureSession.lockProgress(),
                        locked = locked,
                    )
                    if (outcome == AiHoldGestureSession.Outcome.LOCKED) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                    if (
                        outcome == AiHoldGestureSession.Outcome.LOCK_PROGRESS ||
                        locked
                    ) {
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (aiHoldGestureSession.owns(pointerId)) {
                    when (aiHoldGestureSession.pointerUp(pointerId, event.eventTime)) {
                        AiHoldGestureSession.Outcome.ACTIVE_CANCELLED -> {
                            exitAndCancelAi(forceStop = false)
                            performClick()
                        }
                        AiHoldGestureSession.Outcome.LOCKED_RELEASED -> {
                            aiSurfaceState = aiSurfaceState?.copy(
                                lockProgress = 1f,
                                locked = true,
                            )
                            invalidate()
                        }
                        else -> Unit
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                exitAndCancelAi(forceStop = false)
            }
            MotionEvent.ACTION_DOWN -> {
                // A fresh stream means the owner terminal event was lost.
                exitAndCancelAi(forceStop = false)
            }
        }
        return true
    }

    private fun handleLockedAiSurfaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                if (aiStopBounds.contains(event.getX(index), event.getY(index))) {
                    aiStopPointerId = event.getPointerId(index)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerId = aiStopPointerId
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)
                    if (
                        index < 0 ||
                        !aiStopBounds.contains(event.getX(index), event.getY(index))
                    ) {
                        aiStopPointerId = NO_POINTER
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (aiHoldGestureSession.owns(pointerId)) {
                    // Locking transfers authority from the physical pointer to the local
                    // generation token. Releasing the original finger must not cancel the run,
                    // but the state machine should no longer retain that pointer as its owner.
                    aiHoldGestureSession.pointerUp(pointerId, event.eventTime)
                }
                if (pointerId == aiStopPointerId) {
                    val activate = aiStopBounds.contains(event.getX(index), event.getY(index))
                    aiStopPointerId = NO_POINTER
                    if (activate) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        exitAndCancelAi(forceStop = true)
                        performClick()
                    } else {
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Android cancelled the physical gesture stream (for example
                // because a parent/window intercepted it). The locked AI run is
                // generation-owned and must continue, while the stale pointer
                // owner must be detached so a reused pointer id cannot affect it.
                aiHoldGestureSession.releaseLockedPointerOwnership()
                aiStopPointerId = NO_POINTER
                invalidate()
            }
        }
        return true
    }

    private fun exitAndCancelAi(forceStop: Boolean) {
        val generation = aiSurfaceState?.generation ?: return
        aiHoldGestureSession.cancelAll()
        clearScheduledAiHold()
        aiSurfaceState = null
        aiStopPointerId = NO_POINTER
        aiStopBounds.setEmpty()
        cancelOrdinaryTouches()
        relayoutCandidates()
        rebuildKeys(width, height)
        invalidate()
        if (forceStop) {
            aiHoldListener?.onAiStopRequested(generation)
        } else {
            aiHoldListener?.onAiHoldCancelled(generation)
        }
    }

    private fun defaultAiStatus(phase: AiSurfacePhase): String = when (phase) {
        AiSurfacePhase.STARTING -> "先思 AI · 正在思考"
        AiSurfacePhase.STREAMING -> "先思 AI · 正在生成"
        AiSurfacePhase.COMPLETE -> "先思 AI · 已完成"
        AiSurfacePhase.ERROR -> "先思 AI · 出现错误"
    }

    private fun collapseCandidates() {
        stopCandidateSettle()
        candidatePanel.collapse(
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
            fontScale = resources.configuration.fontScale,
        )
    }

    private fun activateKey(key: Key) {
        key.editorAction?.let { action ->
            dispatchQueuedKeysNow()
            editorActionListener?.invoke(action)
            return
        }
        key.clipboardAction?.let { action ->
            activateClipboardAction(action, key.clipboardIndex)
            return
        }
        if (key.style == KeyStyle.CATEGORY) {
            stopPanelFling()
            emojiGroupIndex = key.clipboardIndex.coerceIn(0, EmojiCatalog.categories.lastIndex)
            emojiScrollState.reset()
            rebuildKeys(width, height)
            invalidate()
            return
        }
        if (key.style == KeyStyle.SYMBOL_CATEGORY) {
            stopPanelFling()
            symbolCategoryIndex = key.clipboardIndex.coerceIn(0, SymbolCatalog.categories.lastIndex)
            symbolGridScrollState.reset()
            rebuildKeys(width, height)
            invalidate()
            return
        }
        key.text?.let {
            dispatchQueuedKeysNow()
            textListener?.invoke(it)
            if (panel == Panel.CLIPBOARD) setPanel(Panel.LETTERS)
            return
        }
        if (key.code == 0) return
        val toolboxRoute = if (key.code < 0) {
            KeyboardLayoutContract.toolboxActivationRoute(key.code)
        } else {
            null
        }
        toolboxRoute?.let { route ->
            dispatchQueuedKeysNow()
            when (route) {
                KeyboardLayoutContract.ToolboxActivationRoute.SYMBOLS_PANEL ->
                    setPanel(Panel.SYMBOLS)
                KeyboardLayoutContract.ToolboxActivationRoute.EMOJI_PANEL ->
                    setPanel(Panel.EMOJI)
                KeyboardLayoutContract.ToolboxActivationRoute.SERVICE_ACTION ->
                    enqueueKey(key.code)
                KeyboardLayoutContract.ToolboxActivationRoute.SETTINGS_CALLBACK ->
                    settingsActionListener?.invoke()
            }
            return
        }
        when (key.code) {
            KeyCodes.LETTERS -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.LETTERS)
            }
            KeyCodes.NUMBERS -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.NUMBERS)
            }
            KeyCodes.TOOLBOX -> {
                dispatchQueuedKeysNow()
                setPanel(Panel.TOOLBOX)
            }
            else -> enqueueKey(key.code)
        }
    }

    private fun activateClipboardAction(action: ClipboardAction, index: Int) {
        when (action) {
            ClipboardAction.CLEAR -> {
                clipboardItems = emptyList()
                clipboardPageIndex = 0
            }
            ClipboardAction.DELETE -> if (index in clipboardItems.indices) {
                clipboardItems = clipboardItems.filterIndexed { itemIndex, _ -> itemIndex != index }
                val pages = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
                clipboardPageIndex = clipboardPageIndex.coerceAtMost(pages - 1)
            }
            ClipboardAction.REFRESH -> Unit
        }
        clipboardActionListener?.invoke(action, index)
        rebuildKeys(width, height)
        invalidate()
    }

    private fun scrollCandidatePage(delta: Int) {
        val sceneBuildsBefore = candidatePanel.sceneBuildCount
        candidatePanel.page(
            delta = delta,
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
            fontScale = resources.configuration.fontScale,
        )
        if (candidatePanel.sceneBuildCount != sceneBuildsBefore) invalidate()
    }

    private fun startCandidateSettle(settle: CandidateStripScrollState.Settle) {
        stopCandidateSettle()
        if (!settle.animate) {
            if (candidatePanel.moveTo(settle.targetOffset)) {
                invalidateCandidateViewport()
            }
            return
        }
        candidateSettleStartOffset = candidatePanel.scrollOffset
        candidateSettleTargetOffset = settle.targetOffset
        candidateSettleStartedAtMillis = SystemClock.uptimeMillis()
        postOnAnimation(candidateSettleRunnable)
    }

    private fun stopCandidateSettle() {
        removeCallbacks(candidateSettleRunnable)
    }

    private fun invalidateCandidateViewport() {
        val bounds = candidatePanel.collapsedViewportBounds
        if (bounds == null) {
            postInvalidateOnAnimation()
            return
        }
        postInvalidateOnAnimation(
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

            is FrozenTouchTarget.CandidateStripArea ->
                invalidateCandidateViewport()

            is FrozenTouchTarget.PanelScrollArea ->
                invalidateScrollPanel(target.panel)

            else -> invalidateDirtyBounds(target.bounds)
        }
    }

    private fun invalidateDirtyBounds(bounds: RectF) {
        if (bounds.isEmpty) return
        val padding = dp(2f)
        postInvalidateOnAnimation(
            (bounds.left - padding).toInt().coerceAtLeast(0),
            (bounds.top - padding).toInt().coerceAtLeast(0),
            (bounds.right + padding + 1f).toInt().coerceAtMost(width),
            (bounds.bottom + padding + 1f).toInt().coerceAtMost(height),
        )
    }

    private fun startPanelScroll(
        pointerId: Int,
        panel: ScrollPanel,
        y: Float,
        event: MotionEvent,
    ) {
        panelPointerYs.put(pointerId, y)
        if (activePanelPointerId != NO_POINTER) return
        stopPanelFling()
        activePanelPointerId = pointerId
        activePanelScroll = panel
        activePanelScrollLatched = false
        panelVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun acquirePanelScrollForLatchedPointer(
        pointerId: Int,
        panel: ScrollPanel,
        y: Float,
        event: MotionEvent,
    ) {
        if (pointerId == activePanelPointerId && panel == activePanelScroll) return
        if (activePanelPointerId != NO_POINTER && activePanelScrollLatched) return

        panelVelocityTracker?.recycle()
        activePanelPointerId = pointerId
        activePanelScroll = panel
        activePanelScrollLatched = false
        panelVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        if (panelPointerYs.indexOfKey(pointerId) < 0) {
            panelPointerYs.put(pointerId, y)
        }
    }

    private fun cancelOtherPanelTouches(
        ownerPointerId: Int,
        panel: ScrollPanel,
    ) {
        for (index in pressedTargets.size() - 1 downTo 0) {
            val pointerId = pressedTargets.keyAt(index)
            val target = pressedTargets.valueAt(index)
            if (
                pointerId != ownerPointerId &&
                scrollPanelFor(target) == panel
            ) {
                touchReducer.cancel(pointerId)
                pressedTargets.removeAt(index)
                panelPointerYs.remove(pointerId)
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

    private fun finishPanelScroll(
        pointerId: Int,
        panel: ScrollPanel?,
        shouldFling: Boolean,
    ) {
        if (pointerId != activePanelPointerId) return
        val activePanel = activePanelScroll
        val tracker = panelVelocityTracker
        if (shouldFling && panel != null && panel == activePanel && tracker != null) {
            tracker.computeCurrentVelocity(1_000, maximumFlingVelocity)
            val velocity = KineticScrollPolicy.contentVelocity(
                fingerVelocity = tracker.getYVelocity(pointerId),
                minimumFlingVelocity = minimumFlingVelocity,
                maximumFlingVelocity = maximumFlingVelocity,
            )
            if (velocity != 0) startPanelFling(panel, velocity)
        }
        clearPanelPointer()
    }

    private fun startPanelFling(panel: ScrollPanel, velocity: Int) {
        val state = scrollStateFor(panel)
        if (state.maximumOffset <= 0f) return
        panelScroller.forceFinished(true)
        flingingPanel = panel
        panelScroller.fling(
            0,
            state.offset.toInt(),
            0,
            velocity,
            0,
            0,
            0,
            state.maximumOffset.toInt(),
        )
        invalidateScrollPanel(panel)
    }

    override fun computeScroll() {
        super.computeScroll()
        val panel = flingingPanel ?: return
        if (panelScroller.computeScrollOffset()) {
            scrollStateFor(panel).scrollTo(panelScroller.currY.toFloat())
            invalidateScrollPanel(panel)
        } else {
            flingingPanel = null
        }
    }

    private fun stopPanelFling() {
        if (!panelScroller.isFinished) panelScroller.forceFinished(true)
        flingingPanel = null
    }

    private fun invalidateScrollPanel(panel: ScrollPanel) {
        val bounds = panelViewportBounds(panel)
        if (bounds == null) {
            postInvalidateOnAnimation()
            return
        }
        postInvalidateOnAnimation(
            bounds.left.toInt().coerceAtLeast(0),
            bounds.top.toInt().coerceAtLeast(0),
            (bounds.right + 1f).toInt().coerceAtMost(width),
            (bounds.bottom + 1f).toInt().coerceAtMost(height),
        )
    }

    private fun clearPanelPointer() {
        panelVelocityTracker?.recycle()
        panelVelocityTracker = null
        activePanelPointerId = NO_POINTER
        activePanelScroll = null
        activePanelScrollLatched = false
    }

    private fun scrollClipboard(delta: Int) {
        val pageCount = ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) / CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        clipboardPageIndex = (clipboardPageIndex + delta).coerceIn(0, pageCount - 1)
        rebuildKeys(width, height)
        invalidate()
    }

    private fun enqueueKey(code: Int) {
        keyEventQueue.offer(code)
        if (!keyDispatchPosted) {
            keyDispatchPosted = true
            post(keyDispatchRunnable)
        }
    }

    private fun dispatchQueuedKeysNow() {
        if (keyEventQueue.pendingCount == 0) return
        removeCallbacks(keyDispatchRunnable)
        keyDispatchPosted = false
        while (true) {
            val code = keyEventQueue.poll() ?: break
            keyListener?.onKey(code)
        }
    }

    private fun startBackspaceRepeat(pointerId: Int) {
        if (!backspaceRepeatSession.tryStart(pointerId, SystemClock.uptimeMillis())) return
        removeCallbacks(backspaceRepeatRunnable)
        postDelayed(backspaceRepeatRunnable, BackspaceRepeatPolicy.INITIAL_DELAY_MS)
    }

    private fun stopBackspaceRepeat(pointerId: Int? = null) {
        if (pointerId == null) {
            backspaceRepeatSession.clear()
        } else if (!backspaceRepeatSession.stop(pointerId)) {
            return
        }
        removeCallbacks(backspaceRepeatRunnable)
    }

    override fun onDetachedFromWindow() {
        cancelAllTouches()
        activeSkillAuroraOverlay?.updateBounds(null, keyRadius)
        removeCallbacks(keyDispatchRunnable)
        removeCallbacks(candidateSettleRunnable)
        removeCallbacks(skillAnimationRunnable)
        removeCallbacks(clearSkillFeedbackRunnable)
        skillFeedbackMessage = null
        updateSkillAccessibilityDescription()
        skillAnimationFrameScheduled = false
        keyEventQueue.clear()
        keyDispatchPosted = false
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        publishActiveSkillAurora()
        activeSkillAuroraOverlay?.refreshMotionPreference()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun color(light: Int, dark: Int): Int = palette.color(light, dark)

    private fun Configuration.isNightMode(): Boolean =
        uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun candidateTakesToolbar(): Boolean =
        candidatePanel.takesToolbar(isCandidateToolbarSuppressedByPanel())

    private fun showsCandidates(): Boolean =
        panel != Panel.EDITOR && panel != Panel.VOICE

    private fun isCandidateToolbarSuppressedByPanel(): Boolean =
        panel == Panel.EDITOR || panel == Panel.VOICE

    private fun relayoutCandidates(
        viewWidth: Int = width,
        viewHeight: Int = height,
    ): CandidateChange = candidatePanel.relayout(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
        fontScale = resources.configuration.fontScale,
    )

    /** Toolbar and collapsed candidates replace each other inside one fixed row. */
    private fun keyboardChromeBottom(): Float = KeyboardLayoutContract.topChromeBottom(
        candidateHeight = candidateHeight,
        toolbarHeight = toolbarHeight,
        candidatesTakeToolbar = candidateTakesToolbar(),
        editorPanelVisible = panel == Panel.EDITOR,
    )

    private fun collapsedCandidateBottom(): Float = KeyboardLayoutContract.collapsedCandidateBottom(
        candidateHeight = candidateHeight,
        toolbarHeight = toolbarHeight,
        takesToolbar = candidateTakesToolbar(),
    )

    private fun swipeCharacterMode(): SwipeCharacterMode =
        if (chineseMode) SwipeCharacterMode.CHINESE else SwipeCharacterMode.ENGLISH

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * density * resources.configuration.fontScale

    private fun KeyboardRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private companion object {
        val SKILL_SELECTION_REQUEST_TOKENS = KeyboardSkillRequestTokenSource()
        val EMOJI_ACTION_ROW = listOf(
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.LETTERS,
                weight = 1.05f,
                action = true,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.SPACE,
                weight = 3.3f,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.DELETE,
                weight = 1.05f,
                action = true,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.ENTER,
                weight = 1.05f,
                action = true,
            ),
        )
        const val CLIPBOARD_ITEMS_PER_PAGE = 3
        const val VERTICAL_GESTURE_DOMINANCE = 1.15f
        const val CANDIDATE_FAST_FLING_VELOCITY_DP_PER_SECOND = 720f
        const val CANDIDATE_SETTLE_DURATION_MILLIS = 180L
        const val SKILL_AURORA_PERIOD_MILLIS = 4_800L
        const val SKILL_FEEDBACK_DURATION_MILLIS = 4_000L
        const val SKILL_FEEDBACK_MAX_CHARS = 120
        const val TWO_PI = 6.2831855f
        const val NO_POINTER = -1
    }
}
