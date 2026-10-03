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
class PinyinContextServiceTest {
    private fun withEditor(block: Harness.() -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try { Harness(activity).block() } finally { activity.finish() }
    }

    private class Harness(activity: Activity) {
        val service = Robolectric.buildService(SenseInputMethodService::class.java).get()
        val editor = EditText(activity)
        val requests = mutableListOf<Int>()
        var rejectComposition = false
        var failRead = false
        val connection = object : BaseInputConnection(editor, true) {
            override fun getEditable() = editor.editableText
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
                requests += n
                check(!failRead) { "Synthetic host read failure" }
                return super.getTextBeforeCursor(n, flags)
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                if (rejectComposition) false else super.setComposingText(text, newCursorPosition)
        }
        init {
            InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply {
                isAccessible = true
            }.set(service, connection)
        }
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun key(c: Int) = service.javaClass.getDeclaredMethod("handleKey", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, c)
        fun context() = service.javaClass.getDeclaredField("compositionLeftContext").apply { isAccessible = true }.get(service) as String
        fun composition() = service.javaClass.getDeclaredField("composition").apply { isAccessible = true }.get(service) as PinyinComposition
        fun text(value: String, cursor: Int = value.length) { editor.setText(value); editor.setSelection(cursor) }
    }

    @Test fun capturesTwoCompleteUnicodeScalarsOnceBeforeTheFirstComposingMutation() = withEditor {
        text("旧文𠀀好")
        "nihao".forEach { key(it.code) }
        assertEquals("𠀀好", context())
        assertEquals(listOf(4), requests)
        assertEquals("旧文𠀀好nihao", editor.text.toString())
    }

    @Test fun supplementaryPairRetainsBothCharactersAndNoOlderText() = withEditor {
        text("前文𠀀𠀁")
        key('n'.code)
        assertEquals("𠀀𠀁", context())
    }

    @Test fun selectedRangeUsesItsLeftEdgeRatherThanItsRightEdge() = withEditor {
        text("我很属于")
        editor.setSelection(2, 4)
        key('l'.code)
        assertEquals("我很", context())
        assertEquals("我很l", editor.text.toString())
    }

    @Test fun midTextCursorDoesNotReadTheSuffixAndResetRecapturesContext() = withEditor {
        text("我很属于", 2)
        key('l'.code)
        assertEquals("我很", context())
        key(KeyCodes.DELETE)
        assertTrue(composition().visibleText.isEmpty())
        text("属于")
        key('l'.code)
        assertEquals("属于", context())
        assertEquals(2, requests.size)
    }

    @Test fun hostFailureDoesNotReuseAnEarlierContext() = withEditor {
        text("我很")
        key('l'.code)
        key(KeyCodes.DELETE)
        failRead = true
        key('l'.code)
        assertEquals("", context())
        assertEquals("l", composition().remainingPinyin)
    }

    @Test fun rejectedMutationDoesNotAdvanceContextOrComposition() = withEditor {
        text("我很")
        rejectComposition = true
        key('l'.code)
        assertEquals("", context())
        assertEquals("", composition().visibleText)
        assertEquals("我很", editor.text.toString())
    }

    @Test fun forbiddenContextDoesNotEvenQueryTheHost() = withEditor {
        set("decodeContextAllowed", false)
        text("私人内容")
        key('l'.code)
        assertTrue(requests.isEmpty())
        assertEquals("", context())
    }

    @Test fun ordinaryAndPunctuationContextRemainLimitedToTwoScalars() = withEditor {
        text("之前我很。")
        key('l'.code)
        assertEquals("很。", context())
    }
}
