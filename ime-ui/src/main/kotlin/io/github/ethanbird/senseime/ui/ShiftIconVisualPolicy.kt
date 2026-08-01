package io.github.ethanbird.senseime.ui

internal enum class ShiftIconVisualState {
    OFF,
    ONE_SHOT,
    CAPS_LOCK,
}

/** Pure renderer projection for the service-owned three-state Shift machine. */
internal object ShiftIconVisualPolicy {
    fun resolve(shifted: Boolean, capsLocked: Boolean): ShiftIconVisualState = when {
        capsLocked -> ShiftIconVisualState.CAPS_LOCK
        shifted -> ShiftIconVisualState.ONE_SHOT
        else -> ShiftIconVisualState.OFF
    }
}
