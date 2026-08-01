package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/**
 * Internal scene primitives shared by layout, rendering and hit testing.
 *
 * Keeping these types outside [SenseKeyboardView] lets the Android View remain
 * the lifecycle host while layout/render modules evolve independently.
 */
internal enum class KeyStyle {
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

internal enum class ScrollPanel {
    EMOJI,
    SYMBOL_CATEGORIES,
    SYMBOL_VALUES,
}

internal enum class Icon {
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

/**
 * Exactly one semantic action for a rendered key.
 *
 * Actions are allocated only when a scene is rebuilt. Touch and draw paths
 * retain the same [Key] and [KeyAction] instances for the scene lifetime.
 */
internal sealed interface KeyAction {
    val keyCode: Int
        get() = 0

    data class EmitKey(
        override val keyCode: Int,
    ) : KeyAction

    data class CommitText(
        val text: String,
    ) : KeyAction

    data class Clipboard(
        val action: KeyboardClipboardAction,
        val index: Int = -1,
    ) : KeyAction

    data class Editor(
        val action: KeyboardEditorAction,
    ) : KeyAction

    data class SelectEmojiCategory(
        val index: Int,
    ) : KeyAction

    data class SelectSymbolCategory(
        val index: Int,
    ) : KeyAction

    data object None : KeyAction
}

/**
 * Scene-stable identity for a physical key.
 *
 * Bounds and labels are deliberately excluded: both can change with viewport,
 * Shift and font configuration while the physical owner remains the same.
 */
internal data class PhysicalKeyId(
    val surface: KeyboardSkillPhysicalOwner.Surface,
    val panelToken: String?,
    val signature: KeyboardSkillPhysicalOwner.Signature,
    val occurrence: Int,
) {
    fun toSkillOwner(): KeyboardSkillPhysicalOwner = KeyboardSkillPhysicalOwner(
        surface = surface,
        panelToken = panelToken,
        signature = signature,
        occurrence = occurrence,
    )

    fun matches(owner: KeyboardSkillPhysicalOwner): Boolean =
        surface == owner.surface &&
            panelToken == owner.panelToken &&
            signature == owner.signature &&
            occurrence == owner.occurrence
}

/**
 * One rendered key in content coordinates.
 *
 * Scrollable panel keys intentionally retain content-space bounds; drawing and
 * hit testing apply the current panel offset. This keeps the scene stable while
 * a drag/fling is in progress.
 */
internal class Key(
    val label: String,
    val action: KeyAction,
    val bounds: RectF,
    /** Secondary text painted on the key cap; it has no gesture semantics. */
    val visualLegend: String? = null,
    /** Text committed by an upward flick; it need not be rendered as a legend. */
    val swipeOutput: String? = null,
    val style: KeyStyle = KeyStyle.LETTER,
    val icon: Icon? = null,
    val secondaryLabel: String? = null,
    val scrollPanel: ScrollPanel? = null,
) {
    /** Primitive hot-path projection; reading a key code never allocates. */
    val code: Int = action.keyCode

    internal var physicalId: PhysicalKeyId? = null
}

internal data class VisibleCandidate(
    val sourceIndex: Int,
    val bounds: KeyboardRect,
    val textAnchor: Float,
)

internal enum class CandidateControl {
    EXPAND,
    COLLAPSE,
    PREVIOUS_PAGE,
    NEXT_PAGE,
}

internal data class CandidateControlSlot(
    val control: CandidateControl,
    val bounds: KeyboardRect,
    val enabled: Boolean = true,
)

internal data class CandidatePageCacheKey(
    val generation: Long,
    val viewWidth: Int,
    val viewHeight: Int,
)

internal sealed interface FrozenTouchTarget {
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
