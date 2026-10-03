package io.github.ethanbird.senseime.service

import android.app.Activity
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.ui.KeyCodes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinyinSeparatorServiceTest {
    @Test fun separatorStaysInTheLiveComposingTransactionUntilExplicitEnter() {
        val service = Robolectric.buildService(SenseInputMethodService::class.java).get()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val editor = EditText(activity)
        val connection = object : BaseInputConnection(editor, true) { override fun getEditable() = editor.editableText }
        InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(service, connection)
        fun key(code: Int) = service.javaClass.getDeclaredMethod("handleKey", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, code)
        fun state() = service.javaClass.getDeclaredField("composition").apply { isAccessible = true }.get(service) as PinyinComposition
        try {
            "xi'".forEach { key(it.code) }
            assertEquals("xi'", state().remainingPinyin)
            assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
            "an".forEach { key(it.code) }
            assertEquals("xi'an", state().remainingPinyin)
            key(KeyCodes.ENTER)
            assertEquals("xi'an", editor.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            assertTrue(state().visibleText.isEmpty())
        } finally { activity.finish() }
    }
}
