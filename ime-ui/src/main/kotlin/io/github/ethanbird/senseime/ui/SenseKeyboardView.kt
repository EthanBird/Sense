package io.github.ethanbird.senseime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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

    private enum class KeyStyle {
        LETTER,
        ACTION,
        TOOL,
        SYSTEM,
        CARD,
        EMOJI,
        CATEGORY,
        SYMBOL,
        SYMBOL_CATEGORY,
        RAIL,
        EDITOR_DIRECTION,
        EDITOR_PRIMARY,
        EDITOR_ACTION,
        VOICE_PRIMARY,
        TOOLBOX_CARD,
    }

    private enum class ScrollPanel {
        EMOJI,
        SYMBOL_CATEGORIES,
        SYMBOL_VALUES,
    }

    private enum class Icon {
        TOOLS,
        SYMBOLS,
        KEYBOARD,
        EMOJI,
        EDITOR,
        VOICE,
        SETTINGS,
        HIDE,
        DELETE,
        ENTER,
        SHIFT,
        SPACE,
        BACK,
        CLEAR,
        REFRESH,
        CLIPBOARD,
        UP,
        DOWN,
        RIGHT,
        HOME,
        END,
    }

    private data class Key(
        val label: String,
        val code: Int,
        val bounds: RectF,
        val hint: String? = null,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
        val icon: Icon? = null,
        val clipboardAction: ClipboardAction? = null,
        val clipboardIndex: Int = -1,
        val secondaryLabel: String? = null,
        val editorAction: EditorAction? = null,
        val scrollPanel: ScrollPanel? = null,
    )

    private data class VisibleCandidate(
        val sourceIndex: Int,
        val bounds: RectF,
        val textAnchor: Float,
    )

    private enum class CandidateControl {
        EXPAND,
        COLLAPSE,
        PREVIOUS_PAGE,
        NEXT_PAGE,
    }

    private data class CandidateControlSlot(
        val control: CandidateControl,
        val bounds: RectF,
        val enabled: Boolean = true,
    )

    private data class CandidatePageCacheKey(
        val generation: Long,
        val viewWidth: Int,
        val viewHeight: Int,
    )

    private sealed interface FrozenTouchTarget {
        val bounds: RectF
        val gesturePolicy: TouchInputReducer.GesturePolicy

        data class CandidateValue(
            val revision: Long,
            val sourceIndex: Int,
            override val bounds: RectF,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
        ) : FrozenTouchTarget

        data class CandidateControlValue(
            val value: CandidateControl,
            override val bounds: RectF,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
        ) : FrozenTouchTarget

        data class CandidatePageArea(
            override val bounds: RectF,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
        ) : FrozenTouchTarget

        data class CandidateStripArea(
            override val bounds: RectF,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
        ) : FrozenTouchTarget

        data class PanelScrollArea(
            val panel: ScrollPanel,
            override val bounds: RectF,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
        ) : FrozenTouchTarget

        data class KeyValue(
            val key: Key,
            override val gesturePolicy: TouchInputReducer.GesturePolicy,
            override val bounds: RectF = key.bounds,
        ) : FrozenTouchTarget
    }

    var keyListener: KeyListener? = null
    var candidateListener: ((revision: Long, sourceIndex: Int) -> Unit)? = null
    var textListener: ((text: String) -> Unit)? = null
    var clipboardActionListener: ((action: ClipboardAction, index: Int) -> Unit)? = null
    var editorActionListener: ((action: EditorAction) -> Unit)? = null
    var settingsActionListener: (() -> Unit)? = null
    var aiHoldListener: AiHoldListener? = null

    private val density = resources.displayMetrics.density
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics = Paint.FontMetrics()
    private val iconPath = Path()
    private val keys = mutableListOf<Key>()
    private val visibleCandidates = mutableListOf<VisibleCandidate>()
    private val candidateControls = mutableListOf<CandidateControlSlot>()
    private var candidatePages: List<KeyboardLayoutContract.CandidatePage> = emptyList()
    private var candidates: List<String> = emptyList()
    private var candidateMeasuredWidths = FloatArray(0)
    private var candidateGeneration = 0L
    private var candidatePageCacheKey: CandidatePageCacheKey? = null
    private val candidateStripLayoutCache = CandidateStripLayoutCache()
    private var expandedCandidateGridBounds: RectF? = null
    private var collapsedCandidateViewportBounds: RectF? = null
    private var collapsedCandidateLayout: CandidateStripGeometry.Layout? = null
    private var candidateStripConfiguredLayout: CandidateStripGeometry.Layout? = null
    private var clipboardItems: List<String> = emptyList()
    private var composing: String = ""
    private var compositionRevision: Long = 0L
    private var candidateRevision: Long = 0L
    private var candidatePageIndex = 0
    private var candidatePageLabel = "1 / 1"
    private var candidatesExpanded = false
    private val candidateStripScrollState = CandidateStripScrollState(
        touchSlop = scaledTouchSlop,
    )
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
    private var backgroundShader: Shader? = null

    private val candidateHeight = dp(45f)
    private val toolbarHeight = dp(42f)
    private val systemBarHeight = dp(52f)
    private val keyGap = dp(5f)
    private val horizontalPadding = dp(6f)
    private val keyRadius = dp(8f)
    private val candidateTextInset = dp(9f)
    private val candidateGap = dp(3f)
    private val candidateMinimumWidth = dp(44f)
    private val candidateControlWidth = dp(44f)
    private val expandedCandidateRowHeight = dp(42f)
    private val expandedCandidatePagerHeight = dp(38f)
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
            if (candidateStripScrollState.moveTo(next)) {
                rebuildCandidateLayout(width, height)
                invalidate()
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

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
        updateCandidateUi(revision, text, values.toList())
    }

    private fun updateCandidateUi(revision: Long, text: String, values: List<String>?) {
        val wasTakeover = candidateTakesToolbar()
        val shouldReset = CandidatePresentationPolicy.shouldResetNavigation(
            previousRevision = compositionRevision,
            previousComposing = composing,
            nextRevision = revision,
            nextComposing = text,
        )
        val wasExpanded = candidatesExpanded
        if (shouldReset) {
            stopCandidateSettle()
            candidateStripScrollState.reset()
            candidatesExpanded = false
            candidatePageIndex = 0
        }
        val nextCandidates = when {
            values != null -> values
            text.isEmpty() -> emptyList()
            else -> null
        }
        if (nextCandidates != null && nextCandidates != candidates) {
            candidateMeasuredWidths = FloatArray(nextCandidates.size) { Float.NaN }
            candidateGeneration++
            candidatePages = emptyList()
            candidatePageCacheKey = null
            collapsedCandidateLayout = null
            candidateStripConfiguredLayout = null
            candidateStripLayoutCache.invalidate()
        }
        compositionRevision = revision
        composing = text
        if (nextCandidates != null) {
            candidateRevision = revision
            candidates = nextCandidates
        }
        rebuildCandidateLayout(width, height)
        if (wasExpanded != candidatesExpanded || wasTakeover != candidateTakesToolbar()) {
            rebuildKeys(width, height)
        }
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
        val wasExpanded = candidatesExpanded
        if (panel == value && !wasExpanded) return
        cancelAllTouches()
        collapseCandidates()
        if (panel == Panel.VOICE && value != Panel.VOICE) clearVoiceSurfaceState()
        panel = value
        if (value == Panel.EMOJI) emojiScrollState.reset()
        if (value == Panel.SYMBOLS) {
            symbolCategoryIndex = 0
            symbolCategoryScrollState.reset()
            symbolGridScrollState.reset()
        }
        rebuildKeys(width, height)
        invalidate()
    }

    fun showVoiceSurface(initialState: VoiceSurfaceState) {
        require(initialState.sessionId > 0L)
        cancelAllTouches()
        collapseCandidates()
        clearVoiceSurfaceState()
        voiceSurfaceState = initialState
        voiceWaveformBuffer.clear()
        panel = Panel.VOICE
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
        rebuildCandidateLayout(width, height)
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
    ): Boolean {
        val current = aiSurfaceState ?: return false
        if (current.generation != generation) return false
        aiSurfaceState = current.copy(
            phase = phase,
            preview = AiSurfaceContract.boundedPreview(preview),
            statusText = statusText.ifBlank { defaultAiStatus(phase) },
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
            statusText = defaultAiStatus(phase),
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
        rebuildCandidateLayout(width, height)
        rebuildKeys(width, height)
        invalidate()
        return true
    }

    fun isAiSurfaceActive(): Boolean = aiSurfaceState != null

    fun activeAiGeneration(): Long? = aiSurfaceState?.generation

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(
            KeyboardLayoutContract.preferredKeyboardHeightDp(
                isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            ),
        ).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelAllTouches()
        backgroundShader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            color(0xFFF1F5FA.toInt(), 0xFF171819.toInt()),
            color(0xFFE6EDF6.toInt(), 0xFF111213.toInt()),
            Shader.TileMode.CLAMP,
        )
        voiceWaveformShader = LinearGradient(
            dp(22f),
            0f,
            maxOf(dp(23f), w - dp(22f)),
            0f,
            intArrayOf(
                color(0xFF20C7EE.toInt(), 0xFF34D9FF.toInt()),
                color(0xFF557EF7.toInt(), 0xFF7A89FF.toInt()),
                color(0xFFA24DF4.toInt(), 0xFFC05CFF.toInt()),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        rebuildCandidateLayout(w, h)
        rebuildKeys(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        val aiState = aiSurfaceState
        if (aiState != null) {
            drawAiSurface(canvas, aiState)
            return
        }
        if (panel == Panel.VOICE) {
            drawVoiceSurface(canvas)
            drawKeys(canvas)
            return
        }
        if (panel == Panel.EDITOR) {
            drawEditorHeader(canvas)
            drawEditorPanelBackground(canvas)
        } else {
            drawCandidates(canvas)
            if (!candidatesExpanded) {
                if (panel == Panel.CLIPBOARD) drawClipboardHeader(canvas)
                if (panel == Panel.SYMBOLS) drawSymbolPanelBackground(canvas)
            }
        }
        drawKeys(canvas)
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

        val preview = state.preview.ifBlank {
            if (state.phase == AiSurfacePhase.STARTING) "正在读取输入框并准备任务…" else ""
        }
        if (preview.isNotEmpty()) {
            drawAiPreviewText(
                canvas = canvas,
                text = preview,
                left = card.left + dp(14f),
                top = card.top + dp(14f),
                right = card.right - dp(14f),
                bottom = card.bottom - dp(14f),
            )
        }

        drawAiLockAffordance(canvas, state, accent)
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
        if (candidatesExpanded) {
            drawExpandedCandidates(canvas)
            return
        }
        if (composing.isBlank() && candidates.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = color(0x22FFFFFF, 0x0FFFFFFF)
        canvas.drawRect(0f, 0f, width.toFloat(), collapsedCandidateBottom(), paint)

        val headerSpec = CandidatePresentationPolicy.headerSpec(
            composing = composing,
            hasCandidates = candidates.isNotEmpty(),
        )
        if (headerSpec?.role == CandidatePresentationPolicy.HeaderRole.COMPOSING) {
            paint.color = color(0xFF667085.toInt(), 0xFF8F949E.toInt())
            paint.textSize = sp(headerSpec.textSizeSp)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                composing,
                0,
                minOf(12, composing.length),
                dp(headerSpec.xDp),
                dp(headerSpec.yDp),
                paint,
            )
        }

        if (candidates.isEmpty()) return

        drawVisibleCandidates(canvas)
        candidateControls.forEach { drawCandidateControl(canvas, it) }
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
            if (composing.isBlank()) "候选" else composing,
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
            candidatePageLabel,
            width / 2f,
            pagerTop + expandedCandidatePagerHeight / 2f,
        )
        candidateControls.forEach { drawCandidateControl(canvas, it) }
    }

    private fun drawVisibleCandidates(canvas: Canvas) {
        val viewportSave = if (!candidatesExpanded) {
            collapsedCandidateViewportBounds?.let { bounds ->
                canvas.save().also { canvas.clipRect(bounds) }
            }
        } else {
            null
        }
        visibleCandidates.forEach { candidate ->
            val text = candidates.getOrNull(candidate.sourceIndex) ?: return@forEach
            if (isCandidatePressed(candidate)) {
                paint.style = Paint.Style.FILL
                paint.color = color(0x294F7CF5, 0x505E63D8)
                canvas.drawRoundRect(candidate.bounds, dp(7f), dp(7f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
            paint.textSize = sp(if (!candidatesExpanded && candidateTakesToolbar()) 19f else 17f)
            paint.textAlign = Paint.Align.LEFT
            val saveCount = canvas.save()
            canvas.clipRect(candidate.bounds)
            drawCenteredText(canvas, text, candidate.textAnchor, candidate.bounds.centerY() + dp(2f))
            canvas.restoreToCount(saveCount)
        }
        viewportSave?.let(canvas::restoreToCount)
    }

    private fun drawCandidateControl(canvas: Canvas, slot: CandidateControlSlot) {
        val pressed = isCandidateControlPressed(slot)
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(slot.bounds, dp(9f), dp(9f), paint)
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
        drawCenteredText(canvas, label, slot.bounds.centerX(), slot.bounds.centerY())
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
        keys.forEach { key ->
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
        }
    }

    private fun drawToolboxCard(canvas: Canvas, key: Key, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xC7FFFFFF.toInt(), 0xFF292A2C.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(13f), dp(13f), paint)
        if (!pressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0x14172033, 0x26FFFFFF)
            canvas.drawRoundRect(key.bounds, dp(13f), dp(13f), paint)
        }
        paint.style = Paint.Style.FILL

        val iconBounds = RectF(
            key.bounds.centerX() - dp(25f),
            key.bounds.top + dp(8f),
            key.bounds.centerX() + dp(25f),
            minOf(key.bounds.bottom - dp(22f), key.bounds.top + dp(52f)),
        )
        key.icon?.let { icon ->
            drawIcon(
                canvas = canvas,
                icon = icon,
                bounds = iconBounds,
                tint = if (pressed) {
                    Color.WHITE
                } else {
                    color(0xFF52637C.toInt(), 0xFFD1D4DC.toInt())
                },
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.WHITE else color(0xFF273449.toInt(), 0xFFF0F1F4.toInt())
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.CENTER
        drawCenteredText(
            canvas,
            key.label,
            key.bounds.centerX(),
            key.bounds.bottom - dp(16f),
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
        val clipBounds = emojiGridBounds ?: return
        val saveCount = canvas.save()
        canvas.clipRect(clipBounds)
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
        canvas.restoreToCount(saveCount)
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
        val bounds = symbolCategoryBounds ?: return
        val saveCount = canvas.save()
        canvas.clipRect(bounds)
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
        canvas.restoreToCount(saveCount)
    }

    private fun drawSymbolKey(canvas: Canvas, key: Key, pressed: Boolean) {
        val bounds = symbolGridBounds ?: return
        val saveCount = canvas.save()
        canvas.clipRect(bounds)
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
        canvas.restoreToCount(saveCount)
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
        val geometry = CanvasIconGeometry.resolve(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            density = density,
        )
        val cx = geometry.centerX
        val cy = geometry.centerY
        val unit = geometry.unit
        paint.shader = null
        paint.color = tint
        paint.strokeWidth = geometry.strokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        iconPath.reset()
        when (icon) {
            Icon.TOOLS -> {
                repeat(2) { row ->
                    repeat(2) { column ->
                        val x = cx + (column * 2 - 1) * unit * 4f
                        val y = cy + (row * 2 - 1) * unit * 4f
                        canvas.drawRoundRect(x - unit * 2f, y - unit * 2f, x + unit * 2f, y + unit * 2f, unit, unit, paint)
                    }
                }
            }
            Icon.SYMBOLS -> {
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sp(17f)
                paint.style = Paint.Style.FILL
                canvas.drawText("Ω", cx - unit * 3.2f, cy + unit * 3.4f, paint)
                paint.textSize = sp(12f)
                canvas.drawText("#", cx + unit * 4.2f, cy - unit * 2.2f, paint)
            }
            Icon.KEYBOARD -> {
                canvas.drawRoundRect(cx - unit * 8f, cy - unit * 6f, cx + unit * 8f, cy + unit * 6f, unit * 2f, unit * 2f, paint)
                repeat(3) { row ->
                    repeat(5) { column ->
                        canvas.drawCircle(cx - unit * 5.5f + column * unit * 2.75f, cy - unit * 3.5f + row * unit * 3f, unit * 0.45f, paint)
                    }
                }
                canvas.drawLine(cx - unit * 4f, cy + unit * 3.8f, cx + unit * 4f, cy + unit * 3.8f, paint)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(cx, cy, unit * 8f, paint)
                canvas.drawCircle(cx - unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                canvas.drawCircle(cx + unit * 2.8f, cy - unit * 2f, unit * 0.7f, paint)
                iconPath.moveTo(cx - unit * 3.5f, cy + unit * 2f)
                iconPath.quadTo(cx, cy + unit * 5.5f, cx + unit * 3.5f, cy + unit * 2f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.EDITOR -> {
                canvas.drawLine(cx, cy - unit * 7f, cx, cy + unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy - unit * 7f, cx + unit * 3f, cy - unit * 7f, paint)
                canvas.drawLine(cx - unit * 3f, cy + unit * 7f, cx + unit * 3f, cy + unit * 7f, paint)
                iconPath.moveTo(cx - unit * 5f, cy - unit * 2.5f)
                iconPath.lineTo(cx - unit * 7.5f, cy)
                iconPath.lineTo(cx - unit * 5f, cy + unit * 2.5f)
                iconPath.moveTo(cx + unit * 5f, cy - unit * 2.5f)
                iconPath.lineTo(cx + unit * 7.5f, cy)
                iconPath.lineTo(cx + unit * 5f, cy + unit * 2.5f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.VOICE -> {
                canvas.drawRoundRect(cx - unit * 3.2f, cy - unit * 7.5f, cx + unit * 3.2f, cy + unit * 2f, unit * 3.2f, unit * 3.2f, paint)
                iconPath.moveTo(cx - unit * 6f, cy)
                iconPath.quadTo(cx - unit * 5.5f, cy + unit * 6f, cx, cy + unit * 6f)
                iconPath.quadTo(cx + unit * 5.5f, cy + unit * 6f, cx + unit * 6f, cy)
                canvas.drawPath(iconPath, paint)
                canvas.drawLine(cx, cy + unit * 6f, cx, cy + unit * 9f, paint)
            }
            Icon.SETTINGS -> {
                canvas.drawCircle(cx, cy, unit * 3.2f, paint)
                repeat(8) { index ->
                    val angle = Math.PI * index / 4.0
                    val innerX = cx + kotlin.math.cos(angle).toFloat() * unit * 5.5f
                    val innerY = cy + kotlin.math.sin(angle).toFloat() * unit * 5.5f
                    val outerX = cx + kotlin.math.cos(angle).toFloat() * unit * 8f
                    val outerY = cy + kotlin.math.sin(angle).toFloat() * unit * 8f
                    canvas.drawLine(innerX, innerY, outerX, outerY, paint)
                }
            }
            Icon.HIDE -> {
                iconPath.moveTo(cx - unit * 7f, cy - unit * 2f)
                iconPath.lineTo(cx, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 7f, cy - unit * 2f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.DELETE -> {
                iconPath.moveTo(cx - unit * 8f, cy)
                iconPath.lineTo(cx - unit * 3f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 8f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 3f, cy + unit * 6f)
                iconPath.close()
                canvas.drawPath(iconPath, paint)
                canvas.drawLine(cx + unit, cy - unit * 2.8f, cx + unit * 5f, cy + unit * 2.8f, paint)
                canvas.drawLine(cx + unit * 5f, cy - unit * 2.8f, cx + unit, cy + unit * 2.8f, paint)
            }
            Icon.ENTER -> {
                iconPath.moveTo(cx + unit * 7f, cy - unit * 6f)
                iconPath.lineTo(cx + unit * 7f, cy + unit * 2f)
                iconPath.quadTo(cx + unit * 7f, cy + unit * 6f, cx + unit * 3f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 7f, cy + unit * 6f)
                iconPath.moveTo(cx - unit * 3f, cy + unit * 2f)
                iconPath.lineTo(cx - unit * 7f, cy + unit * 6f)
                iconPath.lineTo(cx - unit * 3f, cy + unit * 9f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.SHIFT -> {
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx, cy - unit * 7f)
                iconPath.lineTo(cx + unit * 7f, cy)
                iconPath.lineTo(cx + unit * 3.5f, cy)
                iconPath.lineTo(cx + unit * 3.5f, cy + unit * 7f)
                iconPath.lineTo(cx - unit * 3.5f, cy + unit * 7f)
                iconPath.lineTo(cx - unit * 3.5f, cy)
                iconPath.close()
                canvas.drawPath(iconPath, paint)
            }
            Icon.SPACE -> {
                iconPath.moveTo(cx - unit * 8f, cy + unit * 1f)
                iconPath.lineTo(cx - unit * 8f, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 5f)
                iconPath.lineTo(cx + unit * 8f, cy + unit * 1f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.BACK -> {
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx - unit * 2f, cy - unit * 5f)
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx - unit * 2f, cy + unit * 5f)
                iconPath.moveTo(cx - unit * 7f, cy)
                iconPath.lineTo(cx + unit * 7f, cy)
                canvas.drawPath(iconPath, paint)
            }
            Icon.UP -> {
                iconPath.moveTo(cx, cy - unit * 7f)
                iconPath.lineTo(cx - unit * 5f, cy - unit * 2f)
                iconPath.moveTo(cx, cy - unit * 7f)
                iconPath.lineTo(cx + unit * 5f, cy - unit * 2f)
                iconPath.moveTo(cx, cy - unit * 7f)
                iconPath.lineTo(cx, cy + unit * 7f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.DOWN -> {
                iconPath.moveTo(cx, cy + unit * 7f)
                iconPath.lineTo(cx - unit * 5f, cy + unit * 2f)
                iconPath.moveTo(cx, cy + unit * 7f)
                iconPath.lineTo(cx + unit * 5f, cy + unit * 2f)
                iconPath.moveTo(cx, cy + unit * 7f)
                iconPath.lineTo(cx, cy - unit * 7f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.RIGHT -> {
                iconPath.moveTo(cx + unit * 7f, cy)
                iconPath.lineTo(cx + unit * 2f, cy - unit * 5f)
                iconPath.moveTo(cx + unit * 7f, cy)
                iconPath.lineTo(cx + unit * 2f, cy + unit * 5f)
                iconPath.moveTo(cx + unit * 7f, cy)
                iconPath.lineTo(cx - unit * 7f, cy)
                canvas.drawPath(iconPath, paint)
            }
            Icon.HOME -> {
                canvas.drawLine(cx - unit * 7f, cy - unit * 7f, cx - unit * 7f, cy + unit * 7f, paint)
                iconPath.moveTo(cx - unit * 5f, cy)
                iconPath.lineTo(cx, cy - unit * 5f)
                iconPath.moveTo(cx - unit * 5f, cy)
                iconPath.lineTo(cx, cy + unit * 5f)
                iconPath.moveTo(cx - unit * 5f, cy)
                iconPath.lineTo(cx + unit * 7f, cy)
                canvas.drawPath(iconPath, paint)
            }
            Icon.END -> {
                canvas.drawLine(cx + unit * 7f, cy - unit * 7f, cx + unit * 7f, cy + unit * 7f, paint)
                iconPath.moveTo(cx + unit * 5f, cy)
                iconPath.lineTo(cx, cy - unit * 5f)
                iconPath.moveTo(cx + unit * 5f, cy)
                iconPath.lineTo(cx, cy + unit * 5f)
                iconPath.moveTo(cx + unit * 5f, cy)
                iconPath.lineTo(cx - unit * 7f, cy)
                canvas.drawPath(iconPath, paint)
            }
            Icon.CLEAR -> {
                canvas.drawRoundRect(cx - unit * 5f, cy - unit * 4f, cx + unit * 5f, cy + unit * 7f, unit, unit, paint)
                canvas.drawLine(cx - unit * 7f, cy - unit * 6f, cx + unit * 7f, cy - unit * 6f, paint)
                canvas.drawLine(cx - unit * 2.5f, cy - unit * 8f, cx + unit * 2.5f, cy - unit * 8f, paint)
            }
            Icon.REFRESH -> {
                iconPath.moveTo(cx + unit * 6f, cy - unit * 5f)
                iconPath.lineTo(cx + unit * 6f, cy - unit * 0.5f)
                iconPath.lineTo(cx + unit * 2f, cy - unit * 2f)
                iconPath.moveTo(cx + unit * 5f, cy - unit * 3f)
                iconPath.cubicTo(cx + unit, cy - unit * 8f, cx - unit * 7f, cy - unit * 5f, cx - unit * 7f, cy + unit)
                iconPath.cubicTo(cx - unit * 7f, cy + unit * 7f, cx + unit * 3f, cy + unit * 9f, cx + unit * 7f, cy + unit * 3f)
                canvas.drawPath(iconPath, paint)
            }
            Icon.CLIPBOARD -> {
                canvas.drawRoundRect(
                    cx - unit * 7f,
                    cy - unit * 6f,
                    cx + unit * 4f,
                    cy + unit * 5f,
                    unit * 1.5f,
                    unit * 1.5f,
                    paint,
                )
                canvas.drawRoundRect(
                    cx - unit * 3f,
                    cy - unit * 3f,
                    cx + unit * 8f,
                    cy + unit * 8f,
                    unit * 1.5f,
                    unit * 1.5f,
                    paint,
                )
                canvas.drawLine(cx, cy + unit, cx + unit * 5f, cy + unit, paint)
                canvas.drawLine(cx, cy + unit * 4f, cx + unit * 5f, cy + unit * 4f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float) {
        paint.getFontMetrics(fontMetrics)
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun rebuildCandidateLayout(viewWidth: Int, viewHeight: Int) {
        visibleCandidates.clear()
        candidateControls.clear()
        expandedCandidateGridBounds = null
        collapsedCandidateViewportBounds = null
        if (viewWidth <= 0) return

        val systemBarTop = viewHeight - systemBarHeight
        val gridTop = candidateHeight + dp(5f)
        val pagerTop = systemBarTop - expandedCandidatePagerHeight
        val gridBottom = pagerTop - dp(4f)
        val hasExpandedGridRoom = viewHeight > 0 && gridBottom - gridTop >= expandedCandidateRowHeight

        if (candidates.isEmpty()) {
            collapsedCandidateLayout = null
            candidateStripConfiguredLayout = null
            candidateStripLayoutCache.invalidate()
            candidateStripScrollState.configure(
                maximumOffset = 0f,
                viewportExtent = 0f,
                snapOffsets = listOf(0f),
            )
            if (candidatesExpanded && composing.isNotBlank() && hasExpandedGridRoom) {
                expandedCandidateGridBounds = RectF(0f, gridTop, viewWidth.toFloat(), gridBottom)
                candidatePageLabel = "…"
                addExpandedCandidateControls(
                    viewWidth = viewWidth,
                    systemBarTop = systemBarTop,
                    pagerTop = pagerTop,
                    previousEnabled = false,
                    nextEnabled = false,
                )
            } else {
                candidatesExpanded = false
                candidatePageIndex = 0
                candidatePageLabel = "1 / 1"
            }
            return
        }
        paint.textSize = sp(if (candidateTakesToolbar()) 19f else 17f)
        val stripCacheKey = CandidateStripLayoutCache.Key(
            generation = candidateGeneration,
            viewWidth = viewWidth,
            takesToolbar = candidateTakesToolbar(),
        )
        collapsedCandidateLayout = candidateStripLayoutCache.getOrBuild(stripCacheKey) {
            CandidateStripGeometry.layout(
                viewWidth = viewWidth.toFloat(),
                measuredTextWidths = List(candidates.size) { measureCandidateWidth(it) },
                padding = horizontalPadding,
                textInset = candidateTextInset,
                gap = candidateGap,
                minimumWidth = candidateMinimumWidth,
                overflowControlWidth = candidateControlWidth,
            )
        }
        val collapsed = checkNotNull(collapsedCandidateLayout)
        if (candidateStripConfiguredLayout !== collapsed) {
            candidateStripScrollState.configure(
                maximumOffset = collapsed.maximumOffset,
                viewportExtent = collapsed.viewportExtent,
                snapOffsets = collapsed.snapOffsets,
            )
            candidateStripConfiguredLayout = collapsed
        }

        if (!collapsed.hasOverflow) {
            candidatesExpanded = false
            candidatePageIndex = 0
        }

        val canExpand = collapsed.hasOverflow &&
            hasExpandedGridRoom

        if (candidatesExpanded && canExpand) {
            val cacheKey = CandidatePageCacheKey(candidateGeneration, viewWidth, viewHeight)
            if (candidatePageCacheKey != cacheKey) {
                candidatePages = KeyboardLayoutContract.pagedCandidateGrid(
                    viewWidth = viewWidth.toFloat(),
                    contentTop = gridTop,
                    contentBottom = gridBottom,
                    measuredTextWidths = List(candidates.size) { measureCandidateWidth(it) },
                    horizontalPadding = horizontalPadding,
                    textInset = candidateTextInset,
                    horizontalGap = candidateGap,
                    verticalGap = dp(4f),
                    minimumWidth = candidateMinimumWidth,
                    rowHeight = expandedCandidateRowHeight,
                )
                candidatePageCacheKey = cacheKey
            }
            if (candidatePages.isEmpty()) {
                candidatesExpanded = false
                candidatePageIndex = 0
            } else {
                expandedCandidateGridBounds = RectF(0f, gridTop, viewWidth.toFloat(), gridBottom)
                candidatePageIndex = candidatePageIndex.coerceIn(0, candidatePages.lastIndex)
                candidatePageLabel = "${candidatePageIndex + 1} / ${candidatePages.size}"
                candidatePages[candidatePageIndex].slots.forEach { slot ->
                    visibleCandidates += VisibleCandidate(
                        sourceIndex = slot.sourceIndex,
                        bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                        textAnchor = slot.textAnchor,
                    )
                }
                addExpandedCandidateControls(
                    viewWidth = viewWidth,
                    systemBarTop = systemBarTop,
                    pagerTop = pagerTop,
                    previousEnabled = candidatePageIndex > 0,
                    nextEnabled = candidatePageIndex < candidatePages.lastIndex,
                )
                return
            }
        } else if (candidatesExpanded) {
            candidatesExpanded = false
            candidatePageIndex = 0
        }

        val collapsedBottom = collapsedCandidateBottom()
        val top = if (composing.isBlank()) dp(3f) else dp(18f)
        collapsedCandidateViewportBounds = RectF(
            collapsed.viewportLeft,
            0f,
            collapsed.viewportRight,
            collapsedBottom,
        )
        collapsed.slots.forEachIndexed { sourceIndex, slot ->
            val shiftedLeft = slot.left - candidateStripScrollState.offset
            val shiftedRight = slot.right - candidateStripScrollState.offset
            if (shiftedRight <= collapsed.viewportLeft || shiftedLeft >= collapsed.viewportRight) {
                return@forEachIndexed
            }
            visibleCandidates += VisibleCandidate(
                sourceIndex = sourceIndex,
                bounds = RectF(
                    maxOf(shiftedLeft, collapsed.viewportLeft),
                    top,
                    minOf(shiftedRight, collapsed.viewportRight),
                    collapsedBottom - dp(3f),
                ),
                textAnchor = slot.textAnchor - candidateStripScrollState.offset,
            )
        }
        if (collapsed.hasOverflow && canExpand) {
            candidateControls += CandidateControlSlot(
                CandidateControl.EXPAND,
                RectF(viewWidth - candidateControlWidth, 0f, viewWidth.toFloat(), collapsedBottom),
                enabled = candidateRevision == compositionRevision,
            )
        }
    }

    private fun measureCandidateWidth(sourceIndex: Int): Float {
        val cached = candidateMeasuredWidths[sourceIndex]
        if (!cached.isNaN()) return cached
        return paint.measureText(candidates[sourceIndex]).also { measured ->
            candidateMeasuredWidths[sourceIndex] = measured
        }
    }

    private fun addExpandedCandidateControls(
        viewWidth: Int,
        systemBarTop: Float,
        pagerTop: Float,
        previousEnabled: Boolean,
        nextEnabled: Boolean,
    ) {
        candidateControls += CandidateControlSlot(
            CandidateControl.COLLAPSE,
            RectF(viewWidth - candidateControlWidth, 0f, viewWidth.toFloat(), candidateHeight),
        )
        val pagerButtonWidth = dp(68f)
        candidateControls += CandidateControlSlot(
            CandidateControl.PREVIOUS_PAGE,
            RectF(horizontalPadding, pagerTop, horizontalPadding + pagerButtonWidth, systemBarTop),
            enabled = previousEnabled,
        )
        candidateControls += CandidateControlSlot(
            CandidateControl.NEXT_PAGE,
            RectF(viewWidth - horizontalPadding - pagerButtonWidth, pagerTop, viewWidth - horizontalPadding, systemBarTop),
            enabled = nextEnabled,
        )
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        keys.clear()
        emojiGridBounds = null
        symbolCategoryBounds = null
        symbolGridBounds = null
        editorMainBounds = null
        editorBottomSeparators = FloatArray(0)
        voiceWaveformBounds.setEmpty()
        voiceStatusCenterY = 0f
        voiceTranscriptCenterY = 0f
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (!candidatesExpanded) {
            if (
                !candidateTakesToolbar() &&
                panel != Panel.EDITOR &&
                panel != Panel.VOICE
            ) {
                layoutToolbar(viewWidth)
            }
            when (panel) {
                Panel.LETTERS -> layoutLetters(viewWidth, viewHeight)
                Panel.NUMBERS -> layoutNumbers(viewWidth, viewHeight)
                Panel.TOOLBOX -> layoutToolbox(viewWidth, viewHeight)
                Panel.SYMBOLS -> layoutSymbols(viewWidth, viewHeight)
                Panel.EMOJI -> layoutEmoji(viewWidth, viewHeight)
                Panel.CLIPBOARD -> layoutClipboard(viewWidth, viewHeight)
                Panel.EDITOR -> layoutEditor(viewWidth, viewHeight)
                Panel.VOICE -> layoutVoice(viewWidth, viewHeight)
            }
        }
        layoutSystemBar(viewWidth, viewHeight)
    }

    private fun layoutToolbar(viewWidth: Int) {
        val items = listOf(
            Icon.TOOLS to KeyCodes.TOOLBOX,
            Icon.KEYBOARD to KeyCodes.LETTERS,
            Icon.EMOJI to KeyCodes.EMOJI,
            Icon.EDITOR to KeyCodes.EDITOR,
            Icon.VOICE to KeyCodes.VOICE,
            Icon.HIDE to KeyCodes.HIDE,
        )
        val slot = viewWidth / items.size.toFloat()
        items.forEachIndexed { index, (icon, code) ->
            keys += Key(
                "",
                code,
                RectF(index * slot + dp(5f), dp(3f), (index + 1) * slot - dp(5f), toolbarHeight - dp(3f)),
                style = KeyStyle.TOOL,
                icon = icon,
            )
        }
    }

    private fun layoutToolbox(viewWidth: Int, viewHeight: Int) {
        val top = keyboardChromeBottom() + dp(8f)
        val bottom = viewHeight - systemBarHeight - dp(8f)
        if (bottom <= top) return
        KeyboardLayoutContract.toolboxLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            horizontalPadding = horizontalPadding,
            horizontalGap = keyGap,
            verticalGap = keyGap,
        ).forEach { slot ->
            val icon = when (slot.item) {
                KeyboardLayoutContract.ToolboxItem.SYMBOLS -> Icon.SYMBOLS
                KeyboardLayoutContract.ToolboxItem.EDITOR -> Icon.EDITOR
                KeyboardLayoutContract.ToolboxItem.VOICE -> Icon.VOICE
                KeyboardLayoutContract.ToolboxItem.CLIPBOARD -> Icon.CLIPBOARD
                KeyboardLayoutContract.ToolboxItem.EMOJI -> Icon.EMOJI
                KeyboardLayoutContract.ToolboxItem.SETTINGS -> Icon.SETTINGS
            }
            keys += Key(
                label = slot.item.label,
                code = slot.item.keyCode,
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = KeyStyle.TOOLBOX_CARD,
                icon = icon,
            )
        }
    }

    private fun layoutLetters(viewWidth: Int, viewHeight: Int) {
        val (top, rowHeight) = keyboardGrid(viewHeight)
        layoutEqualRow(
            "qwertyuiop".map { character ->
                KeySpec(
                    KeyboardLayoutContract.letterLabel(character, chineseMode, shifted),
                    character.code,
                    SwipeCharacterMap.forKey(character.code, swipeCharacterMode()),
                )
            },
            top,
            rowHeight,
        )
        layoutEqualRow(
            "asdfghjkl".map { character ->
                KeySpec(
                    KeyboardLayoutContract.letterLabel(character, chineseMode, shifted),
                    character.code,
                    SwipeCharacterMap.forKey(character.code, swipeCharacterMode()),
                )
            },
            top + rowHeight + keyGap,
            rowHeight,
            dp(18f),
        )
        layoutWeightedRow(
            KeyboardLayoutContract.thirdLetterRow(shifted, chineseMode).map { it.toWeightedSpec() },
            top + 2 * (rowHeight + keyGap),
            rowHeight,
        )
        layoutBottomRow(top + 3 * (rowHeight + keyGap), rowHeight)
    }

    private fun layoutNumbers(viewWidth: Int, viewHeight: Int) {
        val top = keyboardChromeBottom() + dp(7f)
        val bottom = viewHeight - systemBarHeight - dp(7f)
        if (
            bottom - top <= keyGap * 4f ||
            viewWidth.toFloat() <= horizontalPadding * 2f + keyGap * 4f
        ) {
            return
        }
        KeyboardLayoutContract.numericPadLayout(
            viewWidth = viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            horizontalPadding = horizontalPadding,
            gap = keyGap,
            chineseMode = chineseMode,
        ).forEach { slot ->
            val item = slot.key
            val icon = when (item.code) {
                KeyCodes.DELETE -> Icon.DELETE
                KeyCodes.SPACE -> Icon.SPACE
                KeyCodes.ENTER -> Icon.ENTER
                else -> null
            }
            keys += Key(
                label = item.label,
                code = item.code,
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = when {
                    item.column == 0 && item.row < 4 -> KeyStyle.RAIL
                    item.code < 0 || icon != null -> KeyStyle.ACTION
                    else -> KeyStyle.LETTER
                },
                text = item.text,
                icon = icon,
            )
        }
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
            val itemTop = top + index * categoryHeight - symbolCategoryScrollState.offset
            val itemBottom = itemTop + categoryHeight
            if (itemBottom <= top || itemTop >= categoryBottom) return@forEachIndexed
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
            val itemTop = grid.top + row * itemHeight - symbolGridScrollState.offset
            val itemBottom = itemTop + itemHeight
            if (itemBottom <= grid.top || itemTop >= grid.bottom) return@forEachIndexed
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
                listOf(
                    WeightedSpec("", KeyCodes.LETTERS, 1.05f, KeyStyle.ACTION, icon = Icon.BACK),
                    WeightedSpec("", KeyCodes.SPACE, 3.3f, KeyStyle.LETTER, icon = Icon.SPACE),
                    WeightedSpec("", KeyCodes.DELETE, 1.05f, KeyStyle.ACTION, icon = Icon.DELETE),
                    WeightedSpec("", KeyCodes.ENTER, 1.05f, KeyStyle.ACTION, icon = Icon.ENTER),
                ),
                top,
                bottom - top,
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
            val itemTop = geometry.gridTop + row * itemHeight - emojiScrollState.offset
            val itemBottom = itemTop + itemHeight
            if (itemBottom <= geometry.gridTop || itemTop >= geometry.gridBottom) return@forEachIndexed
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
            listOf(
                WeightedSpec("", KeyCodes.LETTERS, 1.05f, KeyStyle.ACTION, icon = Icon.BACK),
                WeightedSpec("", KeyCodes.SPACE, 3.3f, KeyStyle.LETTER, icon = Icon.SPACE),
                WeightedSpec("", KeyCodes.DELETE, 1.05f, KeyStyle.ACTION, icon = Icon.DELETE),
                WeightedSpec("", KeyCodes.ENTER, 1.05f, KeyStyle.ACTION, icon = Icon.ENTER),
            ),
            geometry.actionTop,
            geometry.actionBottom - geometry.actionTop,
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

    private fun layoutBottomRow(y: Float, rowHeight: Float, returnLabel: String? = null) {
        layoutWeightedRow(
            KeyboardLayoutContract.functionRow(chineseMode, returnToLetters = returnLabel != null).map { it.toWeightedSpec() },
            y,
            rowHeight,
        )
    }

    private fun layoutSystemBar(viewWidth: Int, viewHeight: Int) {
        val top = viewHeight - systemBarHeight
        KeyboardLayoutContract.systemBar.forEach { item ->
            val bounds = when (item.side) {
                KeyboardLayoutContract.Side.LEFT -> RectF(dp(13f), top + dp(5f), dp(73f), viewHeight - dp(5f))
                KeyboardLayoutContract.Side.RIGHT -> RectF(viewWidth - dp(73f), top + dp(5f), viewWidth - dp(13f), viewHeight - dp(5f))
            }
            keys += Key("", item.code, bounds, style = KeyStyle.SYSTEM)
        }
    }

    private fun keyboardGrid(viewHeight: Int): Pair<Float, Float> {
        val top = keyboardChromeBottom() + dp(7f)
        val bottom = viewHeight - systemBarHeight - dp(7f)
        return top to (bottom - top - keyGap * 3) / 4f
    }

    private data class KeySpec(
        val label: String,
        val code: Int,
        val hint: String? = null,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
        val icon: Icon? = null,
    )

    private data class WeightedSpec(
        val label: String,
        val code: Int,
        val weight: Float,
        val style: KeyStyle = KeyStyle.LETTER,
        val text: String? = null,
        val hint: String? = null,
        val icon: Icon? = null,
    )

    private fun KeyboardLayoutContract.WeightedKey.toWeightedSpec(): WeightedSpec = WeightedSpec(
        label = if (code == KeyCodes.SHIFT || code == KeyCodes.DELETE || code == KeyCodes.SPACE || code == KeyCodes.ENTER) "" else label,
        code = code,
        weight = weight,
        style = if (action) KeyStyle.ACTION else KeyStyle.LETTER,
        hint = if (code > 0) SwipeCharacterMap.forKey(code, swipeCharacterMode()) else null,
        icon = when (code) {
            KeyCodes.SHIFT -> Icon.SHIFT
            KeyCodes.DELETE -> Icon.DELETE
            KeyCodes.SPACE -> Icon.SPACE
            KeyCodes.ENTER -> Icon.ENTER
            else -> null
        },
    )

    private fun layoutEqualRow(
        items: List<KeySpec>,
        y: Float,
        rowHeight: Float,
        extraInset: Float = 0f,
    ) {
        val left = horizontalPadding + extraInset
        val right = width - horizontalPadding - extraInset
        val itemWidth = (right - left - keyGap * (items.size - 1)) / items.size
        items.forEachIndexed { index, item ->
            val x = left + index * (itemWidth + keyGap)
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), item.hint, item.style, item.text, item.icon)
        }
    }

    private fun layoutWeightedRow(items: List<WeightedSpec>, y: Float, rowHeight: Float) {
        val totalWeight = items.sumOf { it.weight.toDouble() }.toFloat()
        val usable = width - horizontalPadding * 2 - keyGap * (items.size - 1)
        var x = horizontalPadding
        items.forEach { item ->
            val itemWidth = usable * item.weight / totalWeight
            keys += Key(item.label, item.code, RectF(x, y, x + itemWidth, y + rowHeight), hint = item.hint, style = item.style, text = item.text, icon = item.icon)
            x += itemWidth + keyGap
        }
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
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event, event.actionIndex, isPrimary = false)

            MotionEvent.ACTION_MOVE -> {
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
                    if (candidateStripScrollState.owns(pointerId)) {
                        val candidateMove = candidateStripScrollState.move(
                            pointerId = pointerId,
                            x = x,
                            y = y,
                            eventTimeMillis = event.eventTime,
                        )
                        if (candidateMove.dragLatched) {
                            touchReducer.cancel(pointerId)
                            pressedTargets.remove(pointerId)
                            cancelOtherCandidateStripTouches(pointerId)
                            if (candidateMove.changed) rebuildCandidateLayout(width, height)
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
                            rebuildKeys(width, height)
                        }
                        activePanelScrollLatched = true
                        panelPointerYs.put(pointerId, currentY)
                        if (newlyLatched) {
                            cancelOtherPanelTouches(
                                ownerPointerId = pointerId,
                                panel = scrollPanel,
                            )
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
                        if (backspaceRepeatSession.owns(pointerId)) stopBackspaceRepeat(pointerId)
                    }
                }
                invalidate()
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
            cancelAllTouches()
            invalidate()
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
            candidateStripScrollState.begin(
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
        }
        if (
            target !is FrozenTouchTarget.CandidatePageArea &&
            target !is FrozenTouchTarget.CandidateStripArea &&
            target !is FrozenTouchTarget.PanelScrollArea
        ) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
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
        val originalTarget = touchReducer.target(pointerId)
        val candidateSettle = candidateStripScrollState.finish(
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
        invalidate()
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
        if (showsCandidates()) {
            for (index in candidateControls.indices) {
                val slot = candidateControls[index]
                if (slot.enabled && slot.bounds.contains(x, y)) {
                    return FrozenTouchTarget.CandidateControlValue(
                        value = slot.control,
                        bounds = RectF(slot.bounds),
                        gesturePolicy = tapGesturePolicy,
                    )
                }
            }
        }
        if (showsCandidates() && candidateRevision == compositionRevision) {
            for (index in visibleCandidates.indices) {
                val candidate = visibleCandidates[index]
                if (candidate.bounds.contains(x, y)) {
                    return FrozenTouchTarget.CandidateValue(
                        revision = candidateRevision,
                        sourceIndex = candidate.sourceIndex,
                        bounds = RectF(candidate.bounds),
                        gesturePolicy = if (candidatesExpanded) pageScrollGesturePolicy else tapGesturePolicy,
                    )
                }
            }
        }
        if (!candidatesExpanded && showsCandidates()) {
            collapsedCandidateViewportBounds?.let { bounds ->
                if (
                    collapsedCandidateLayout?.hasOverflow == true &&
                    bounds.contains(x, y)
                ) {
                    return FrozenTouchTarget.CandidateStripArea(
                        bounds = RectF(bounds),
                        gesturePolicy = tapGesturePolicy,
                    )
                }
            }
        }
        if (showsCandidates()) {
            expandedCandidateGridBounds?.let { bounds ->
                if (bounds.contains(x, y)) {
                    return FrozenTouchTarget.CandidatePageArea(
                        bounds = RectF(bounds),
                        gesturePolicy = pageScrollGesturePolicy,
                    )
                }
            }
        }
        for (index in keys.lastIndex downTo 0) {
            val key = keys[index]
            val hitBounds = hitBoundsForKey(key)
            if (hitBounds.contains(x, y)) {
                // A disabled action owns its visible rectangle as a dead zone.
                // Falling through to gap resolution could otherwise turn a tap
                // near disabled COPY into the adjacent DELETE action.
                if (!isKeyEnabled(key)) return null
                return FrozenTouchTarget.KeyValue(
                    key = key,
                    gesturePolicy = gesturePolicyForKey(key),
                    bounds = hitBounds,
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

    private fun hitBoundsForKey(key: Key): RectF {
        val viewport = key.scrollPanel?.let(::panelViewportBounds) ?: return key.bounds
        return RectF(
            maxOf(key.bounds.left, viewport.left),
            maxOf(key.bounds.top, viewport.top),
            minOf(key.bounds.right, viewport.right),
            minOf(key.bounds.bottom, viewport.bottom),
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
        !candidatesExpanded &&
            collapsedCandidateLayout?.hasOverflow == true &&
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
            } else if (candidatesExpanded) {
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
        val expansionChanged = when (control) {
            CandidateControl.EXPAND -> {
                candidatesExpanded = true
                candidatePageIndex = 0
                true
            }

            CandidateControl.COLLAPSE -> {
                candidatesExpanded = false
                candidatePageIndex = 0
                true
            }

            CandidateControl.PREVIOUS_PAGE -> {
                candidatePageIndex = KeyboardLayoutContract.adjacentCandidatePage(
                    currentPage = candidatePageIndex,
                    pageCount = candidatePages.size,
                    delta = -1,
                )
                false
            }

            CandidateControl.NEXT_PAGE -> {
                candidatePageIndex = KeyboardLayoutContract.adjacentCandidatePage(
                    currentPage = candidatePageIndex,
                    pageCount = candidatePages.size,
                    delta = 1,
                )
                false
            }
        }
        rebuildCandidateLayout(width, height)
        if (expansionChanged) rebuildKeys(width, height)
        invalidate()
    }

    private fun isCandidatePressed(candidate: VisibleCandidate): Boolean {
        repeat(pressedTargets.size()) { index ->
            val target = pressedTargets.valueAt(index)
            if (target is FrozenTouchTarget.CandidateValue && target.revision == candidateRevision && target.sourceIndex == candidate.sourceIndex) {
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

    private fun cancelAllTouches() {
        val activeGeneration = aiSurfaceState?.generation
        val aiOutcome = aiHoldGestureSession.cancelAll()
        clearScheduledAiHold()
        aiSurfaceState = null
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
        touchReducer.cancelAll()
        pressedTargets.clear()
        panelPointerYs.clear()
        clearPanelPointer()
        stopPanelFling()
        stopBackspaceRepeat()
        stopCandidateSettle()
        candidateStripScrollState.cancelAll()?.let { settle ->
            if (candidateStripScrollState.moveTo(settle.targetOffset)) {
                rebuildCandidateLayout(width, height)
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
            lockProgress = 0f,
            locked = false,
        )
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
        rebuildCandidateLayout(width, height)
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
        candidatesExpanded = false
        candidatePageIndex = 0
        rebuildCandidateLayout(width, height)
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
        if (!candidatesExpanded || candidatePages.isEmpty()) return
        val nextPage = KeyboardLayoutContract.adjacentCandidatePage(
            currentPage = candidatePageIndex,
            pageCount = candidatePages.size,
            delta = delta,
        )
        if (nextPage == candidatePageIndex) return
        candidatePageIndex = nextPage
        rebuildCandidateLayout(width, height)
        invalidate()
    }

    private fun startCandidateSettle(settle: CandidateStripScrollState.Settle) {
        stopCandidateSettle()
        if (!settle.animate) {
            if (candidateStripScrollState.moveTo(settle.targetOffset)) {
                rebuildCandidateLayout(width, height)
                invalidate()
            }
            return
        }
        candidateSettleStartOffset = candidateStripScrollState.offset
        candidateSettleTargetOffset = settle.targetOffset
        candidateSettleStartedAtMillis = SystemClock.uptimeMillis()
        postOnAnimation(candidateSettleRunnable)
    }

    private fun stopCandidateSettle() {
        removeCallbacks(candidateSettleRunnable)
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

    private fun cancelOtherCandidateStripTouches(ownerPointerId: Int) {
        for (index in pressedTargets.size() - 1 downTo 0) {
            val pointerId = pressedTargets.keyAt(index)
            val target = pressedTargets.valueAt(index)
            val isCandidateTarget =
                target is FrozenTouchTarget.CandidateValue ||
                    target is FrozenTouchTarget.CandidateStripArea
            if (pointerId != ownerPointerId && isCandidateTarget) {
                touchReducer.cancel(pointerId)
                pressedTargets.removeAt(index)
            }
        }
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
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        super.computeScroll()
        val panel = flingingPanel ?: return
        if (panelScroller.computeScrollOffset()) {
            if (scrollStateFor(panel).scrollTo(panelScroller.currY.toFloat())) {
                rebuildKeys(width, height)
            }
            postInvalidateOnAnimation()
        } else {
            flingingPanel = null
        }
    }

    private fun stopPanelFling() {
        if (!panelScroller.isFinished) panelScroller.forceFinished(true)
        flingingPanel = null
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
        removeCallbacks(keyDispatchRunnable)
        removeCallbacks(candidateSettleRunnable)
        keyEventQueue.clear()
        keyDispatchPosted = false
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun color(light: Int, dark: Int): Int = if (isNightMode()) dark else light

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun candidateTakesToolbar(): Boolean = CandidatePresentationPolicy.takesToolbar(
        composing = composing,
        editorPanelVisible = panel == Panel.EDITOR || panel == Panel.VOICE,
    )

    private fun showsCandidates(): Boolean =
        panel != Panel.EDITOR && panel != Panel.VOICE

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

    private companion object {
        const val CLIPBOARD_ITEMS_PER_PAGE = 3
        const val VERTICAL_GESTURE_DOMINANCE = 1.15f
        const val CANDIDATE_FAST_FLING_VELOCITY_DP_PER_SECOND = 720f
        const val CANDIDATE_SETTLE_DURATION_MILLIS = 180L
        const val NO_POINTER = -1
    }
}
