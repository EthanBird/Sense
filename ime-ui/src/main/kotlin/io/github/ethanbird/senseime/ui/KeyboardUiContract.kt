package io.github.ethanbird.senseime.ui

/**
 * Stable keyboard panels shared by the Android View, scene builders and IME host.
 *
 * Keeping this contract outside [SenseKeyboardView] prevents scene/model code
 * from depending on one concrete Android rendering adapter.
 */
enum class KeyboardPanel {
    LETTERS,
    NUMBERS,
    TOOLBOX,
    SYMBOLS,
    EMOJI,
    CLIPBOARD,
    EDITOR,
    VOICE,
    INPUT_SCHEMES,
}

enum class KeyboardClipboardAction {
    CLEAR,
    DELETE,
    REFRESH,
}

enum class KeyboardEditorAction {
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

/**
 * Runtime-selectable primary letter geometry.
 */
enum class PrimaryKeyboardMode {
    QWERTY,
    T9,
}

/** Secondary legends are visual policy and never change swipe output. */
enum class PrimaryKeyboardLegendMode {
    SWIPE_HINTS,
    WUBI_86_ROOTS,
}

/** UI-facing Chinese input scheme without taking an ime-config dependency. */
enum class KeyboardInputSchemeChoice(
    val primaryMode: PrimaryKeyboardMode,
    val legendMode: PrimaryKeyboardLegendMode,
) {
    PINYIN_T9(PrimaryKeyboardMode.T9, PrimaryKeyboardLegendMode.SWIPE_HINTS),
    PINYIN_QWERTY(PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.SWIPE_HINTS),
    WUBI_86(PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.WUBI_86_ROOTS),
    ;

    val presentation: Pair<PrimaryKeyboardMode, PrimaryKeyboardLegendMode>
        get() = primaryMode to legendMode

    companion object {
        fun fromPresentation(
            mode: PrimaryKeyboardMode,
            legendMode: PrimaryKeyboardLegendMode,
        ): KeyboardInputSchemeChoice = when {
            mode == PrimaryKeyboardMode.T9 -> PINYIN_T9
            legendMode == PrimaryKeyboardLegendMode.WUBI_86_ROOTS -> WUBI_86
            else -> PINYIN_QWERTY
        }
    }
}

fun interface KeyboardInputSchemeSelectionListener {
    fun onInputSchemeSelected(choice: KeyboardInputSchemeChoice)
}

/** One revision-bound segmentation path shown on the T9 left rail. */
data class T9PinyinChoice(
    val canonical: String,
    val preview: String = canonical,
) {
    init {
        require(canonical.isNotBlank())
    }
}

fun interface T9PinyinChoiceSelectionListener {
    fun onT9PinyinChoiceSelected(revision: Long, index: Int)
}
