package io.github.ethanbird.senseime.service

import android.app.Activity
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentExternalEditorWriterTest {
    @Test
    fun insertsAnswerAtTheExternalEditorCursor() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val editable = SpannableStringBuilder("before after")
        Selection.setSelection(editable, 7)
        val connection = object : BaseInputConnection(View(activity), true) {
            override fun getEditable(): Editable = editable
        }

        assertTrue(AgentExternalEditorWriter.insert(connection, "Agent "))
        assertEquals("before Agent after", editable.toString())
    }

    @Test
    fun rejectsEmptyAnswerWithoutTouchingTheEditor() {
        assertFalse(AgentExternalEditorWriter.insert(null, "answer"))
        assertFalse(AgentExternalEditorWriter.insert(null, "  "))
    }
}
