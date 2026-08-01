package io.github.ethanbird.senseime.service

/**
 * English keyboard Shift state, independent from the Android View lifecycle.
 *
 * [ONE_SHOT] uppercases exactly the next accepted alphabetic edit, while
 * [CAPS_LOCK] remains active until Shift is pressed again.
 */
internal enum class EnglishShiftState(
    val uppercase: Boolean,
    val capsLocked: Boolean,
) {
    LOWERCASE(uppercase = false, capsLocked = false),
    ONE_SHOT(uppercase = true, capsLocked = false),
    CAPS_LOCK(uppercase = true, capsLocked = true),
    ;

    fun onShiftPressed(): EnglishShiftState = when (this) {
        LOWERCASE -> ONE_SHOT
        ONE_SHOT -> CAPS_LOCK
        CAPS_LOCK -> LOWERCASE
    }

    fun afterAcceptedLetter(): EnglishShiftState = when (this) {
        ONE_SHOT -> LOWERCASE
        LOWERCASE,
        CAPS_LOCK,
        -> this
    }

    fun applyTo(character: Char): Char =
        if (uppercase) character.uppercaseChar() else character
}
