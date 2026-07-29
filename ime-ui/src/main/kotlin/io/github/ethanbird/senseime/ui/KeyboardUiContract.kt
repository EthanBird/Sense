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
 *
 * T9 is deliberately not exposed by the current product surface. The value is
 * present so a T9 [KeyboardLetterLayout] adapter can be registered and selected
 * without changing the View, renderer or touch pipeline.
 */
enum class PrimaryKeyboardMode {
    QWERTY,
    T9,
}
