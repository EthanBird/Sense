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
 * One rendered key in content coordinates.
 *
 * Scrollable panel keys intentionally retain content-space bounds; drawing and
 * hit testing apply the current panel offset. This keeps the scene stable while
 * a drag/fling is in progress.
 */
internal data class Key(
    val label: String,
    val code: Int,
    val bounds: RectF,
    val hint: String? = null,
    val style: KeyStyle = KeyStyle.LETTER,
    val text: String? = null,
    val icon: Icon? = null,
    val clipboardAction: SenseKeyboardView.ClipboardAction? = null,
    val clipboardIndex: Int = -1,
    val secondaryLabel: String? = null,
    val editorAction: SenseKeyboardView.EditorAction? = null,
    val scrollPanel: ScrollPanel? = null,
)

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
