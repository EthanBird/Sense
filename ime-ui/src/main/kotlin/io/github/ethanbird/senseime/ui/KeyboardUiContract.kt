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
