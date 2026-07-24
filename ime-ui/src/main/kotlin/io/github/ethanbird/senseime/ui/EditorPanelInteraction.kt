package io.github.ethanbird.senseime.ui

/**
 * Stable semantic target for the one accelerating delete stream owned by the
 * keyboard view. Editor-panel actions deliberately do not masquerade as key
 * codes, so repeatability must be resolved from both channels.
 */
internal enum class DeleteRepeatTarget {
    KEY,
    EDITOR,
}

internal object DeleteRepeatTargetPolicy {
    fun resolve(keyCode: Int, editorActionIsDelete: Boolean): DeleteRepeatTarget? = when {
        keyCode == KeyCodes.DELETE -> DeleteRepeatTarget.KEY
        editorActionIsDelete -> DeleteRepeatTarget.EDITOR
        else -> null
    }
}
