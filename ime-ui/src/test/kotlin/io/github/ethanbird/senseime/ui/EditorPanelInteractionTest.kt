package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorPanelInteractionTest {
    @Test
    fun ordinaryAndEditorDeleteShareTheRepeatPipeline() {
        assertEquals(
            DeleteRepeatTarget.KEY,
            DeleteRepeatTargetPolicy.resolve(
                keyCode = KeyCodes.DELETE,
                editorActionIsDelete = false,
            ),
        )
        assertEquals(
            DeleteRepeatTarget.EDITOR,
            DeleteRepeatTargetPolicy.resolve(
                keyCode = 0,
                editorActionIsDelete = true,
            ),
        )
    }

    @Test
    fun nonDeleteEditorActionsNeverStartARepeatStream() {
        assertNull(
            DeleteRepeatTargetPolicy.resolve(
                keyCode = 0,
                editorActionIsDelete = false,
            ),
        )
        assertNull(
            DeleteRepeatTargetPolicy.resolve(
                keyCode = KeyCodes.SPACE,
                editorActionIsDelete = false,
            ),
        )
    }

    @Test
    fun realDeleteKeyWinsIfBothChannelsAreAccidentallyPopulated() {
        assertEquals(
            DeleteRepeatTarget.KEY,
            DeleteRepeatTargetPolicy.resolve(
                keyCode = KeyCodes.DELETE,
                editorActionIsDelete = true,
            ),
        )
    }
}
