package io.github.ethanbird.senseime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Android lifecycle and public-API facade for the keyboard.
 *
 * Scene construction, rendering, and interaction each live in a dedicated
 * component. This View owns only host projection state and preserves the
 * historical listener surface.
 */
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

        fun onAiResultAction(generation: Long, action: AiResultActionType) = Unit
    }

    /** Compatibility facades for callers compiled against the original API. */
    enum class Panel(internal val contract: KeyboardPanel) {
        LETTERS(KeyboardPanel.LETTERS),
        NUMBERS(KeyboardPanel.NUMBERS),
        TOOLBOX(KeyboardPanel.TOOLBOX),
        SYMBOLS(KeyboardPanel.SYMBOLS),
        EMOJI(KeyboardPanel.EMOJI),
        CLIPBOARD(KeyboardPanel.CLIPBOARD),
        EDITOR(KeyboardPanel.EDITOR),
        VOICE(KeyboardPanel.VOICE),
        INPUT_SCHEMES(KeyboardPanel.INPUT_SCHEMES),
    }

    enum class ClipboardAction(internal val contract: KeyboardClipboardAction) {
        CLEAR(KeyboardClipboardAction.CLEAR),
        DELETE(KeyboardClipboardAction.DELETE),
        REFRESH(KeyboardClipboardAction.REFRESH),
        ;

        internal companion object {
            fun fromContract(action: KeyboardClipboardAction): ClipboardAction =
                entries[action.ordinal]
        }
    }

    enum class EditorAction(internal val contract: KeyboardEditorAction) {
        BACK(KeyboardEditorAction.BACK),
        UP(KeyboardEditorAction.UP),
        LEFT(KeyboardEditorAction.LEFT),
        TOGGLE_SELECTION(KeyboardEditorAction.TOGGLE_SELECTION),
        RIGHT(KeyboardEditorAction.RIGHT),
        DOWN(KeyboardEditorAction.DOWN),
        DELETE(KeyboardEditorAction.DELETE),
        COPY(KeyboardEditorAction.COPY),
        CUT(KeyboardEditorAction.CUT),
        PASTE(KeyboardEditorAction.PASTE),
        HOME(KeyboardEditorAction.HOME),
        SELECT_ALL(KeyboardEditorAction.SELECT_ALL),
        END(KeyboardEditorAction.END),
        ;

        internal companion object {
            fun fromContract(action: KeyboardEditorAction): EditorAction =
                entries[action.ordinal]
        }
    }

    var keyListener: KeyListener? = null
    var candidateListener: ((revision: Long, sourceIndex: Int) -> Unit)? = null
    var associationDismissListener: (() -> Unit)? = null
    var textListener: ((text: String) -> Unit)? = null
    var clipboardActionListener: ((action: ClipboardAction, index: Int) -> Unit)? = null
    var editorActionListener: ((action: EditorAction) -> Unit)? = null
    var settingsActionListener: (() -> Unit)? = null
    var agentActionListener: (() -> Unit)? = null
    var t9SideSymbolSettingsListener: (() -> Unit)? = null
    var t9PinyinChoiceSelectionListener: T9PinyinChoiceSelectionListener? = null
    var inputSchemeSelectionListener: KeyboardInputSchemeSelectionListener? = null
    var aiHoldListener: AiHoldListener? = null
    var skillSelectionListener: KeyboardSkillSelectionListener? = null

    private val density = resources.displayMetrics.density
    private val metrics = KeyboardMetrics.fromDensity(density)
    private val keyboardScene = MutableKeyboardScene()
    private val sceneBuilder = KeyboardSceneBuilder(metrics)
    private val scaledTouchSlop =
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val palette = KeyboardPalette(
        nightMode = resources.configuration.isNightMode(),
    )
    private val keyboardRenderer = KeyboardRenderer(
        density = density,
        fontScale = resources.configuration.fontScale,
        metrics = metrics,
        palette = palette,
    )
    private val aiRenderGeometry = MutableAiSurfaceRenderGeometry()
    private val candidatePanel = CandidatePanel(
        metrics = metrics,
        touchSlop = scaledTouchSlop,
        textMeasurer = PaintCandidateTextMeasurer(),
    )
    private val voiceWaveformBuffer = VoiceWaveformBuffer()

    private var clipboardItems: List<String> = emptyList()
    private var voiceSurfaceState: VoiceSurfaceState? = null
    private var emojiGroupIndex = 0
    private var symbolCategoryIndex = 0
    private var clipboardPageIndex = 0
    private var editorHasSelection = false
    private var editorSelectionMode = false
    private var editorCanPaste = false
    private var shifted = false
    private var capsLocked = false
    private var chineseMode = true
    private var panel = KeyboardPanel.LETTERS
    private var primaryKeyboardMode = PrimaryKeyboardMode.QWERTY
    private var primaryKeyboardLegendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS
    private var selectedInputSchemeChoice = KeyboardInputSchemeChoice.PINYIN_QWERTY
    private var t9CompositionActive = false
    private var t9PinyinChoiceRevision = 0L
    private var t9PinyinChoices: List<T9PinyinChoice> = emptyList()
    private var t9SideSymbols: List<String> = T9SideSymbolPolicy.DEFAULT_SYMBOLS
    private var keyboardSizeProfile = KeyboardSizeProfile.DEFAULT
    private var renderPassCount = 0L

    private val frameScheduler = ViewKeyboardFrameScheduler(this)
    private val interactionActions = object : KeyboardInteractionActionSink {
        override fun onKey(code: Int) {
            keyListener?.onKey(code)
        }

        override fun onCandidate(revision: Long, sourceIndex: Int) {
            candidateListener?.invoke(revision, sourceIndex)
        }

        override fun onCandidateDismiss() {
            associationDismissListener?.invoke()
        }

        override fun onText(text: String) {
            textListener?.invoke(text)
        }

        override fun onClipboardAction(action: KeyboardClipboardAction, index: Int) {
            clipboardActionListener?.invoke(ClipboardAction.fromContract(action), index)
        }

        override fun onEditorAction(action: KeyboardEditorAction) {
            editorActionListener?.invoke(EditorAction.fromContract(action))
        }

        override fun onSettingsAction() {
            settingsActionListener?.invoke()
        }

        override fun onAgentAction() {
            agentActionListener?.invoke()
        }

        override fun onT9SideSymbolSettings() {
            t9SideSymbolSettingsListener?.invoke()
        }

        override fun onT9PinyinChoiceSelected(revision: Long, index: Int) {
            t9PinyinChoiceSelectionListener?.onT9PinyinChoiceSelected(revision, index)
        }

        override fun onInputSchemeSelected(choice: KeyboardInputSchemeChoice) {
            inputSchemeSelectionListener?.onInputSchemeSelected(choice)
        }

        override fun onAiHoldStarted(generation: Long) {
            aiHoldListener?.onAiHoldStarted(generation)
        }

        override fun onAiHoldCancelled(generation: Long) {
            aiHoldListener?.onAiHoldCancelled(generation)
        }

        override fun onAiStopRequested(generation: Long) {
            aiHoldListener?.onAiStopRequested(generation)
        }

        override fun onAiResultAction(generation: Long, action: AiResultActionType) {
            aiHoldListener?.onAiResultAction(generation, action)
        }

        override fun onSkillSelection(request: KeyboardSkillSelection) {
            skillSelectionListener?.onSkillSelection(request)
        }
    }

    private val interactionHost = object : KeyboardInteractionHost {
        override val interactionContext: Context
            get() = context
        override val interactionWidth: Int
            get() = width
        override val interactionHeight: Int
            get() = height
        override val interactionIsShown: Boolean
            get() = isShown
        override val interactionScene: MutableKeyboardScene
            get() = keyboardScene
        override val interactionCandidatePanel: CandidatePanel
            get() = candidatePanel
        override var interactionClipboardItems: List<String>
            get() = clipboardItems
            set(value) {
                clipboardItems = value
            }
        override val interactionAiGeometry: MutableAiSurfaceRenderGeometry
            get() = aiRenderGeometry
        override val interactionFontScale: Float
            get() = resources.configuration.fontScale
        override var interactionPanel: KeyboardPanel
            get() = panel
            set(value) {
                panel = value
            }
        override var interactionPrimaryMode: PrimaryKeyboardMode
            get() = primaryKeyboardMode
            set(value) {
                primaryKeyboardMode = value
            }
        override var interactionInputSchemeChoice: KeyboardInputSchemeChoice
            get() = selectedInputSchemeChoice
            set(value) {
                selectedInputSchemeChoice = value
            }
        override var interactionEmojiGroupIndex: Int
            get() = emojiGroupIndex
            set(value) {
                emojiGroupIndex = value
            }
        override var interactionSymbolCategoryIndex: Int
            get() = symbolCategoryIndex
            set(value) {
                symbolCategoryIndex = value
            }
        override var interactionClipboardPageIndex: Int
            get() = clipboardPageIndex
            set(value) {
                clipboardPageIndex = value
            }
        override val interactionEditorHasSelection: Boolean
            get() = editorHasSelection
        override val interactionEditorSelectionMode: Boolean
            get() = editorSelectionMode
        override val interactionEditorCanPaste: Boolean
            get() = editorCanPaste
        override val interactionChineseMode: Boolean
            get() = chineseMode

        override fun interactionShowsCandidates(): Boolean = showsCandidates()

        override fun interactionCandidatesTakeToolbar(): Boolean = candidateTakesToolbar()

        override fun interactionCandidateToolbarSuppressed(): Boolean =
            isCandidateToolbarSuppressedByPanel()

        override fun interactionChromeBottom(): Float = keyboardChromeBottom()

        override fun interactionRelayoutCandidates() {
            relayoutCandidates()
        }

        override fun interactionRebuildKeys() {
            rebuildKeys(width, height)
        }

        override fun interactionSetPanel(panel: KeyboardPanel) {
            setPanel(panel)
        }

        override fun interactionPerformClick() {
            performClick()
        }

        override fun interactionAnnounce(message: String) {
            announceForAccessibility(message)
        }

        override fun interactionReadContentDescription(): CharSequence? = contentDescription

        override fun interactionWriteContentDescription(value: CharSequence) {
            contentDescription = value
        }
    }

    private val interaction = KeyboardInteractionController(
        host = interactionHost,
        density = density,
        metrics = metrics,
        scaledTouchSlop = scaledTouchSlop,
        scheduler = frameScheduler,
        clock = SystemKeyboardInteractionClock,
        haptics = ViewKeyboardHaptics(this),
        actions = interactionActions,
    )

    private val rendererState = object : KeyboardRendererState {
        override val viewWidth: Int
            get() = width
        override val viewHeight: Int
            get() = height
        override val panel: KeyboardPanel
            get() = this@SenseKeyboardView.panel
        override val scene: KeyboardScene
            get() = keyboardScene
        override val candidates: CandidateScene
            get() = candidatePanel
        override val candidatesTakeToolbar: Boolean
            get() = candidateTakesToolbar()
        override val chromeBottom: Float
            get() = keyboardChromeBottom()
        override val collapsedCandidateBottom: Float
            get() = this@SenseKeyboardView.collapsedCandidateBottom()
        override val aiSurface: AiSurfaceState?
            get() = interaction.aiSurfaceState
        override val aiLockProgress: Float
            get() = interaction.aiLockProgress
        override val aiLocked: Boolean
            get() = interaction.aiLocked
        override val aiGeometry: AiSurfaceRenderGeometry
            get() = aiRenderGeometry
        override val aiStopPressed: Boolean
            get() = interaction.aiStopPressed
        override val aiPressedResultAction: AiResultActionType?
            get() = interaction.aiPressedResultAction
        override val voiceSurface: VoiceSurfaceState?
            get() = voiceSurfaceState
        override val voiceWaveformBuffer: VoiceWaveformBuffer
            get() = this@SenseKeyboardView.voiceWaveformBuffer
        override val skillFeedbackMessage: String?
            get() = interaction.skillFeedbackMessage
        override val activeSkillSourceKey: Key?
            get() = interaction.activeSkillSourceKey
        override val activeKeyboardSkill: ActiveKeyboardSkill?
            get() = interaction.activeKeyboardSkill
        override val hasAuroraSibling: Boolean
            get() = interaction.hasAuroraSibling
        override val skillPickerVisible: Boolean
            get() = interaction.skillPickerVisible
        override val skillPickerSourceBounds: RectF
            get() = interaction.skillPickerSourceBounds
        override val skillPickerOptions: KeyboardSkillOptions?
            get() = interaction.skillPickerOptions
        override val skillPickerOptionBounds: Array<RectF>
            get() = interaction.skillPickerOptionBounds
        override val highlightedSkillDirection: KeyboardSkillDirection?
            get() = interaction.highlightedSkillDirection
        override val emojiGroupIndex: Int
            get() = this@SenseKeyboardView.emojiGroupIndex
        override val symbolCategoryIndex: Int
            get() = this@SenseKeyboardView.symbolCategoryIndex
        override val editorSelectionMode: Boolean
            get() = this@SenseKeyboardView.editorSelectionMode
        override val shifted: Boolean
            get() = this@SenseKeyboardView.shifted
        override val capsLocked: Boolean
            get() = this@SenseKeyboardView.capsLocked

        override fun isCandidatePressed(sourceIndex: Int): Boolean =
            interaction.isCandidatePressed(sourceIndex)

        override fun isCandidateControlPressed(control: CandidateControl): Boolean =
            interaction.isCandidateControlPressed(control)

        override fun isKeyPressed(key: Key): Boolean = interaction.isKeyPressed(key)

        override fun isKeyEnabled(key: Key): Boolean = interaction.isKeyEnabled(key)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        interaction.initialize()
    }

    fun updateComposition(revision: Long, text: String) {
        updateCandidateUi(
            revision,
            text,
            values = null,
            t9Choices = null,
            association = false,
        )
    }

    fun updateComposing(revision: Long, text: String, values: List<String>) {
        updateCandidateUi(revision, text, values, t9Choices = null, association = false)
    }

    /** Replaces the toolbar with a dismissible, short-lived next-word strip. */
    fun updateAssociations(revision: Long, values: List<String>) {
        updateCandidateUi(
            revision,
            text = "",
            values = values,
            t9Choices = emptyList(),
            association = true,
        )
    }

    /** Atomically publishes the candidate strip and T9 segmentation rail for one revision. */
    fun updateT9Composing(
        revision: Long,
        text: String,
        values: List<String>?,
        choices: List<T9PinyinChoice>,
    ) {
        updateCandidateUi(revision, text, values, t9Choices = choices, association = false)
    }

    private fun updateCandidateUi(
        revision: Long,
        text: String,
        values: List<String>?,
        t9Choices: List<T9PinyinChoice>?,
        association: Boolean,
    ) {
        val nextT9CompositionActive =
            primaryKeyboardMode == PrimaryKeyboardMode.T9 && text.isNotEmpty()
        val t9CompositionStateChanged = t9CompositionActive != nextT9CompositionActive
        if (t9CompositionStateChanged) t9CompositionActive = nextT9CompositionActive
        val nextT9Choices = when {
            text.isEmpty() -> emptyList()
            t9Choices != null -> t9Choices.take(MAX_T9_PINYIN_CHOICES).toList()
            primaryKeyboardMode == PrimaryKeyboardMode.T9 &&
                t9PinyinChoiceRevision != revision -> emptyList()
            else -> t9PinyinChoices
        }
        val revisionChanged = t9PinyinChoiceRevision != revision
        val listChanged = t9PinyinChoices != nextT9Choices
        val t9ChoicesChanged = listChanged || (nextT9Choices.isNotEmpty() && revisionChanged)
        if (revisionChanged) t9PinyinChoiceRevision = revision
        if (t9ChoicesChanged) {
            t9PinyinChoices = nextT9Choices
        }
        val t9SceneChanged =
            (t9ChoicesChanged || t9CompositionStateChanged) &&
                panel == KeyboardPanel.LETTERS &&
                primaryKeyboardMode == PrimaryKeyboardMode.T9
        val nextCandidates = values ?: if (text.isEmpty()) emptyList() else null
        val nextCandidatesReady = nextCandidates != null
        val candidatePointersChanged = candidatePanel.association != association ||
            CandidatePointerFence.shouldCancel(
                previousReady = candidatePanel.candidatesReady,
                previousCandidates = candidatePanel.candidates,
                nextReady = nextCandidatesReady,
                nextCandidates = nextCandidates,
            )
        if (t9SceneChanged) {
            interaction.cancelT9PinyinRailPointers()
            keyboardScene.t9LeftRailScrollState.reset()
        }
        if (candidatePointersChanged) {
            interaction.cancelCandidatePointers()
        }
        val change = candidatePanel.publish(
            revision = revision,
            text = text,
            values = values,
            viewWidth = width,
            viewHeight = height,
            editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
            fontScale = resources.configuration.fontScale,
            association = association,
        )
        if (change.cancelSettle) interaction.stopCandidateSettle()
        if (change.requiresKeySceneRebuild || t9SceneChanged) rebuildKeys(width, height)
        invalidate()
    }

    /** Publishes revision-bound segmentation paths into the scrollable T9 left rail. */
    fun updateT9PinyinChoices(revision: Long, choices: List<T9PinyinChoice>) {
        val snapshot = choices.take(MAX_T9_PINYIN_CHOICES).toList()
        if (t9PinyinChoiceRevision == revision && t9PinyinChoices == snapshot) return
        t9PinyinChoiceRevision = revision
        t9PinyinChoices = snapshot
        if (panel == KeyboardPanel.LETTERS && primaryKeyboardMode == PrimaryKeyboardMode.T9) {
            interaction.cancelT9PinyinRailPointers()
            keyboardScene.t9LeftRailScrollState.reset()
            rebuildKeys(width, height)
            invalidate()
        }
    }

    /** Injects the persisted idle symbols; the settings affordance is appended by the UI. */
    fun setT9SideSymbols(symbols: List<String>) {
        val normalized = T9SideSymbolPolicy.normalize(symbols)
        if (t9SideSymbols == normalized) return
        t9SideSymbols = normalized
        if (
            panel == KeyboardPanel.LETTERS &&
            primaryKeyboardMode == PrimaryKeyboardMode.T9 &&
            !t9CompositionActive
        ) {
            interaction.cancelT9PinyinRailPointers()
            keyboardScene.t9LeftRailScrollState.reset()
            rebuildKeys(width, height)
            invalidate()
        }
    }

    fun setShifted(value: Boolean) {
        setShiftState(shifted = value, capsLocked = false)
    }

    /** Publishes one-shot/caps-lock visual state atomically. */
    fun setShiftState(shifted: Boolean, capsLocked: Boolean) {
        val nextShifted = shifted || capsLocked
        if (this.shifted == nextShifted && this.capsLocked == capsLocked) return
        this.shifted = nextShifted
        this.capsLocked = capsLocked
        rebuildKeys(width, height)
        invalidate()
    }

    fun setChineseMode(value: Boolean) {
        setInputPresentation(value, primaryKeyboardMode, primaryKeyboardLegendMode)
    }

    /**
     * Selects the primary letter geometry at a scene boundary.
     *
     * Active pointers are cancelled before replacing the topology so a touch
     * frozen against the old layout never dispatches through the new one.
     */
    fun setPrimaryKeyboardMode(value: PrimaryKeyboardMode) {
        setInputPresentation(chineseMode, value, primaryKeyboardLegendMode)
    }

    fun setPrimaryKeyboardLegendMode(value: PrimaryKeyboardLegendMode) {
        setInputPresentation(chineseMode, primaryKeyboardMode, value)
    }

    /** Atomically replaces geometry and legends, avoiding an intermediate mismatched scene. */
    fun setPrimaryKeyboardPresentation(
        mode: PrimaryKeyboardMode,
        legendMode: PrimaryKeyboardLegendMode,
    ) {
        setInputPresentation(chineseMode, mode, legendMode)
    }

    /**
     * Installs language policy, geometry and legends as one immutable scene transition.
     *
     * A Chinese T9 to English QWERTY switch therefore cancels the active pointer once and builds
     * one final scene; it never materializes an intermediate English T9 or stale-root scene.
     */
    fun setInputPresentation(
        chinese: Boolean,
        mode: PrimaryKeyboardMode,
        legendMode: PrimaryKeyboardLegendMode,
    ) {
        val nextChoice = KeyboardInputSchemeChoice.fromPresentation(mode, legendMode)
        val choiceChanged = selectedInputSchemeChoice != nextChoice
        val clearT9Choices = mode != PrimaryKeyboardMode.T9 && t9PinyinChoices.isNotEmpty()
        val clearT9Composition = mode != PrimaryKeyboardMode.T9 && t9CompositionActive
        if (
            chineseMode == chinese &&
            primaryKeyboardMode == mode &&
            primaryKeyboardLegendMode == legendMode &&
            !choiceChanged &&
            !clearT9Choices &&
            !clearT9Composition
        ) {
            return
        }
        val languageChanged = chineseMode != chinese
        interaction.cancelAllTouches()
        chineseMode = chinese
        primaryKeyboardMode = mode
        primaryKeyboardLegendMode = legendMode
        selectedInputSchemeChoice = nextChoice
        if (clearT9Choices) t9PinyinChoices = emptyList()
        if (clearT9Composition) t9CompositionActive = false
        if (languageChanged) interaction.collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    /** Calibrates selection highlighting when the host starts directly in English mode. */
    fun setSelectedInputSchemeChoice(value: KeyboardInputSchemeChoice) {
        if (selectedInputSchemeChoice == value) return
        selectedInputSchemeChoice = value
        if (panel == KeyboardPanel.INPUT_SCHEMES) {
            interaction.cancelAllTouches()
            rebuildKeys(width, height)
            invalidate()
        }
    }

    fun setPanel(value: Panel) = setPanel(value.contract)

    fun setPanel(value: KeyboardPanel) {
        val wasExpanded = candidatePanel.expanded
        if (panel == value && !wasExpanded) return
        interaction.cancelAllTouches()
        if (panel == KeyboardPanel.VOICE && value != KeyboardPanel.VOICE) {
            clearVoiceSurfaceState()
        }
        panel = value
        if (candidatePanel.association && value != KeyboardPanel.LETTERS) {
            associationDismissListener?.invoke()
        }
        if (value == KeyboardPanel.EMOJI) keyboardScene.emojiScrollState.reset()
        if (value == KeyboardPanel.SYMBOLS) {
            symbolCategoryIndex = 0
            keyboardScene.symbolCategoryScrollState.reset()
            keyboardScene.symbolGridScrollState.reset()
        }
        interaction.collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    fun updateKeyboardSkills(
        bindings: List<KeyboardSkillBinding>,
        active: ActiveKeyboardSkill?,
    ) = interaction.updateKeyboardSkills(bindings, active)

    fun updateActiveKeyboardSkill(active: ActiveKeyboardSkill?) =
        interaction.updateActiveKeyboardSkill(active)

    fun activeKeyboardSkill(): ActiveKeyboardSkill? = interaction.activeKeyboardSkill

    internal fun attachActiveSkillAuroraOverlay(overlay: ActiveSkillAuroraOverlayView?) =
        interaction.attachActiveSkillAuroraOverlay(overlay)

    fun rejectPendingSkillSelection(requestToken: Long) =
        interaction.rejectPendingSkillSelection(requestToken)

    internal fun activeSkillSourceBoundsForTesting(): RectF? =
        interaction.activeSkillSourceKey?.bounds?.let(::RectF)

    internal fun renderPassCountForTesting(): Long = renderPassCount

    internal fun keySceneBuildCountForTesting(): Long = keyboardScene.buildCount

    internal fun panelKeysForTesting(): List<Key> = keyboardScene.keys
        .subList(keyboardScene.panelKeyStart, keyboardScene.panelKeyEndExclusive)
        .toList()

    internal fun toolbarKeysForTesting(): List<Key> = keyboardScene.keys
        .subList(keyboardScene.toolbarKeyStart, keyboardScene.toolbarKeyEndExclusive)
        .toList()

    internal fun panelForTesting(): KeyboardPanel = panel

    internal fun candidateSceneBuildCountForTesting(): Long = candidatePanel.sceneBuildCount

    internal fun scrollViewportBoundsForTesting(panel: ScrollPanel): RectF? =
        interaction.panelViewportBounds(panel)?.let(::RectF)

    internal fun scrollOffsetForTesting(panel: ScrollPanel): Float =
        interaction.scrollStateFor(panel).offset

    internal fun candidateViewportBoundsForTesting(): RectF? =
        candidatePanel.collapsedViewportBounds?.toRectF()

    internal fun candidateScrollOffsetForTesting(): Float = candidatePanel.scrollOffset

    internal fun candidateMaximumOffsetForTesting(): Float =
        candidatePanel.maximumScrollOffset

    internal fun aiStopBoundsForTesting(): RectF? {
        val bounds = aiRenderGeometry.stopBounds
        return if (bounds.isEmpty) {
            null
        } else {
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    internal fun skillPickerOptionBoundsForTesting(
        direction: KeyboardSkillDirection,
    ): RectF? {
        val bounds = interaction.skillPickerOptionBounds[direction.ordinal]
        return if (bounds.isEmpty) null else RectF(bounds)
    }

    internal fun candidateSourceIndexAtForTesting(x: Float, y: Float): Int? =
        (
            candidatePanel.hitTest(
                x = x,
                y = y,
                visible = showsCandidates(),
            ) as? CandidateHit.Value
            )?.sourceIndex

    fun showSkillFeedback(message: String) = interaction.showSkillFeedback(message)

    fun clearSkillFeedback() = interaction.clearSkillFeedback()

    fun showVoiceSurface(initialState: VoiceSurfaceState) {
        require(initialState.sessionId > 0L)
        interaction.cancelAllTouches()
        clearVoiceSurfaceState()
        voiceSurfaceState = initialState
        voiceWaveformBuffer.clear()
        panel = KeyboardPanel.VOICE
        interaction.collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

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
        if (panel == KeyboardPanel.VOICE) panel = KeyboardPanel.LETTERS
        interaction.cancelOrdinaryTouches()
        relayoutCandidates()
        rebuildKeys(width, height)
        invalidate()
        return true
    }

    fun isVoiceSurfaceActive(): Boolean =
        panel == KeyboardPanel.VOICE && voiceSurfaceState != null

    fun activeVoiceSessionId(): Long? = voiceSurfaceState?.sessionId

    private fun clearVoiceSurfaceState() {
        voiceSurfaceState = null
        voiceWaveformBuffer.clear()
        keyboardScene.voiceWaveformBounds.setEmpty()
    }

    fun showClipboard(values: List<String>) {
        interaction.cancelAllTouches()
        clipboardItems = values
        clipboardPageIndex = 0
        panel = KeyboardPanel.CLIPBOARD
        interaction.collapseCandidates()
        rebuildKeys(width, height)
        invalidate()
    }

    fun updateClipboard(values: List<String>) {
        clipboardItems = values
        val pageCount =
            ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) /
                CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        clipboardPageIndex = clipboardPageIndex.coerceAtMost(pageCount - 1)
        if (panel == KeyboardPanel.CLIPBOARD) {
            rebuildKeys(width, height)
            invalidate()
        }
    }

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
        if (panel == KeyboardPanel.EDITOR) invalidate()
    }

    fun updateAiSurface(
        generation: Long,
        phase: AiSurfacePhase,
        preview: String,
        statusText: String = "",
        activities: List<AiSurfaceActivity> =
            interaction.aiSurfaceState?.activities.orEmpty(),
        resultActions: List<AiSurfaceResultAction> =
            interaction.aiSurfaceState?.resultActions.orEmpty(),
    ): Boolean = interaction.updateAiSurface(
        generation = generation,
        phase = phase,
        preview = preview,
        statusText = statusText,
        activities = activities,
        resultActions = resultActions,
    )

    fun appendAiStreamPreview(
        generation: Long,
        delta: String,
        phase: AiSurfacePhase = AiSurfacePhase.STREAMING,
    ): Boolean = interaction.appendAiStreamPreview(generation, delta, phase)

    fun exitAiSurface(generation: Long): Boolean = interaction.exitAiSurface(generation)

    fun isAiSurfaceActive(): Boolean = interaction.aiSurfaceState != null

    fun activeAiGeneration(): Long? = interaction.aiSurfaceState?.generation

    fun acceptsAssociationPresentation(): Boolean =
        panel == KeyboardPanel.LETTERS &&
            interaction.aiSurfaceState == null &&
            voiceSurfaceState == null

    fun setKeyboardSizeProfile(profile: KeyboardSizeProfile) {
        if (keyboardSizeProfile == profile) return
        keyboardSizeProfile = profile
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = keyboardSizeProfile.preferredHeightPx(
            isLandscape =
                resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE,
            density = density,
        )
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        interaction.onSizeChanged()
        keyboardRenderer.updateSurface(w, h, resources.configuration.fontScale)
        relayoutCandidates(w, h)
        rebuildKeys(w, h)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        interaction.onConfigurationChanged()
        palette.update(newConfig.isNightMode())
        keyboardRenderer.updateSurface(width, height, newConfig.fontScale)
        relayoutCandidates()
        rebuildKeys(width, height)
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderPassCount += 1L
        interaction.onDrawCompleted(keyboardRenderer.draw(canvas, rendererState))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        interaction.onTouchEvent(event)

    override fun computeScroll() {
        super.computeScroll()
        interaction.computeScroll()
    }

    override fun onDetachedFromWindow() {
        interaction.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        interaction.onAttached()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rebuildKeys(viewWidth: Int, viewHeight: Int) {
        sceneBuilder.rebuildInto(
            request = KeyboardSceneRequest(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                panel = panel,
                primaryMode = primaryKeyboardMode,
                candidatesTakeToolbar = candidateTakesToolbar(),
                candidateExpanded = candidatePanel.expanded,
                shifted = shifted,
                chineseMode = chineseMode,
                emojiGroupIndex = emojiGroupIndex,
                symbolCategoryIndex = symbolCategoryIndex,
                clipboardItems = clipboardItems,
                clipboardPageIndex = clipboardPageIndex,
                voiceSurfaceState = voiceSurfaceState,
                fontScale = resources.configuration.fontScale,
                primaryLegendMode = primaryKeyboardLegendMode,
                t9CompositionActive = t9CompositionActive,
                t9PinyinChoiceRevision = t9PinyinChoiceRevision,
                t9PinyinChoices = t9PinyinChoices,
                t9SideSymbols = t9SideSymbols,
                selectedInputSchemeChoice = selectedInputSchemeChoice,
            ),
            target = keyboardScene,
        )
        interaction.onSceneRebuilt()
    }

    private fun Configuration.isNightMode(): Boolean =
        uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun candidateTakesToolbar(): Boolean =
        candidatePanel.takesToolbar(isCandidateToolbarSuppressedByPanel())

    private fun showsCandidates(): Boolean =
        panel != KeyboardPanel.EDITOR &&
            panel != KeyboardPanel.VOICE &&
            panel != KeyboardPanel.INPUT_SCHEMES

    private fun isCandidateToolbarSuppressedByPanel(): Boolean =
        panel == KeyboardPanel.EDITOR ||
            panel == KeyboardPanel.VOICE ||
            panel == KeyboardPanel.INPUT_SCHEMES

    private fun relayoutCandidates(
        viewWidth: Int = width,
        viewHeight: Int = height,
    ): CandidateChange = candidatePanel.relayout(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        editorPanelVisible = isCandidateToolbarSuppressedByPanel(),
        fontScale = resources.configuration.fontScale,
    )

    private fun keyboardChromeBottom(): Float = KeyboardLayoutContract.topChromeBottom(
        candidateHeight = metrics.candidateHeight,
        toolbarHeight = metrics.toolbarHeight,
        candidatesTakeToolbar = candidateTakesToolbar(),
        editorPanelVisible = panel == KeyboardPanel.EDITOR,
    )

    private fun collapsedCandidateBottom(): Float =
        KeyboardLayoutContract.collapsedCandidateBottom(
            candidateHeight = metrics.candidateHeight,
            toolbarHeight = metrics.toolbarHeight,
            takesToolbar = candidateTakesToolbar(),
        )

    private fun KeyboardRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private companion object {
        const val CLIPBOARD_ITEMS_PER_PAGE = 3
        const val MAX_T9_PINYIN_CHOICES = 8
    }
}

/**
 * Candidate touches freeze a source index on DOWN. A readiness transition or a
 * content replacement invalidates that index even when the decoder reuses the
 * same composition revision.
 */
internal object CandidatePointerFence {
    fun shouldCancel(
        previousReady: Boolean,
        previousCandidates: List<String>,
        nextReady: Boolean,
        nextCandidates: List<String>?,
    ): Boolean =
        previousReady != nextReady ||
            (nextCandidates != null && previousCandidates != nextCandidates)
}
