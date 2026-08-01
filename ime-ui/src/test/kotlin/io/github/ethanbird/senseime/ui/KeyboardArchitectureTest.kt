package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardArchitectureTest {
    @Test
    fun `legacy nested contracts remain source compatible`() {
        val panel: SenseKeyboardView.Panel = SenseKeyboardView.Panel.LETTERS
        val clipboardAction: SenseKeyboardView.ClipboardAction =
            SenseKeyboardView.ClipboardAction.DELETE
        val editorAction: SenseKeyboardView.EditorAction =
            SenseKeyboardView.EditorAction.PASTE

        assertSame(KeyboardPanel.LETTERS, panel.contract)
        assertSame(KeyboardClipboardAction.DELETE, clipboardAction.contract)
        assertSame(KeyboardEditorAction.PASTE, editorAction.contract)
    }

    @Test
    fun `primary mode dispatches to its registered layout adapter`() {
        val qwerty = RecordingLetterLayout()
        val t9 = RecordingLetterLayout()
        val layout = KeyboardPrimaryLayout(
            metrics = KeyboardMetrics.fromDensity(1f),
            qwertyLayout = qwerty,
            t9Layout = t9,
        )
        val request = letterRequest()
        val output = ArrayList<Key>()

        layout.appendLetters(PrimaryKeyboardMode.QWERTY, request, output)
        layout.appendLetters(PrimaryKeyboardMode.T9, request, output)

        assertEquals(1, qwerty.callCount)
        assertEquals(1, t9.callCount)
        assertSame(request, qwerty.lastRequest)
        assertSame(request, t9.lastRequest)
    }

    @Test
    fun `production primary layout owns a usable t9 adapter`() {
        val layout = KeyboardPrimaryLayout(
            metrics = KeyboardMetrics.fromDensity(1f),
        )
        val output = ArrayList<Key>()

        layout.appendLetters(PrimaryKeyboardMode.T9, letterRequest(), output)

        assertEquals(
            ('1'..'9').map(Char::code),
            output.map(Key::code).filter { it in '1'.code..'9'.code },
        )
        assertTrue(output.any { it.code == KeyCodes.DELETE })
    }

    @Test
    fun `visual legends do not become swipe output in t9`() {
        val layout = KeyboardPrimaryLayout(
            metrics = KeyboardMetrics.fromDensity(1f),
        )
        val t9 = ArrayList<Key>()
        val qwerty = ArrayList<Key>()

        layout.appendLetters(PrimaryKeyboardMode.T9, letterRequest(), t9)
        layout.appendLetters(PrimaryKeyboardMode.QWERTY, letterRequest(), qwerty)

        val t9Two = t9.single { it.code == '2'.code }
        assertEquals("ABC", t9Two.visualLegend)
        assertEquals(null, t9Two.swipeOutput)

        val qwertyQ = qwerty.single { it.code == 'q'.code }
        assertEquals("1", qwertyQ.visualLegend)
        assertEquals("1", qwertyQ.swipeOutput)
    }

    @Test
    fun `wubi legends cover the weighted third letter row without changing swipe output`() {
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.QWERTY,
            letterRequest(PrimaryKeyboardLegendMode.WUBI_86_ROOTS),
            output,
        )

        val z = output.single { it.code == 'z'.code }
        val m = output.single { it.code == 'm'.code }
        assertEquals("反查", z.visualLegend)
        assertEquals("山", m.visualLegend)
        assertEquals(
            SwipeCharacterMap.forKey('z'.code, SwipeCharacterMode.CHINESE),
            z.swipeOutput,
        )
        assertEquals(
            SwipeCharacterMap.forKey('m'.code, SwipeCharacterMode.CHINESE),
            m.swipeOutput,
        )
    }

    @Test
    fun `typed actions expose one allocation-free primitive key projection`() {
        val emit = KeyAction.EmitKey(KeyCodes.DELETE)
        val text = KeyAction.CommitText("hello")
        val editor = KeyAction.Editor(KeyboardEditorAction.COPY)

        assertEquals(KeyCodes.DELETE, emit.keyCode)
        assertEquals(0, text.keyCode)
        assertEquals(0, editor.keyCode)
        assertEquals("hello", text.text)
        assertSame(KeyboardEditorAction.COPY, editor.action)
    }

    @Test
    fun `physical key identity round trips without view dependency`() {
        val owner = KeyboardSkillPhysicalOwner(
            surface = KeyboardSkillPhysicalOwner.Surface.PANEL,
            panelToken = KeyboardPanel.LETTERS.name,
            signature = KeyboardSkillPhysicalOwner.Signature(
                keyCode = 'a'.code,
                styleToken = KeyStyle.LETTER.name,
                iconToken = null,
                editorActionToken = null,
                clipboardActionToken = null,
            ),
            occurrence = 1,
        )
        val id = PhysicalKeyId(
            surface = owner.surface,
            panelToken = owner.panelToken,
            signature = owner.signature,
            occurrence = owner.occurrence,
        )

        assertEquals(owner, id.toSkillOwner())
        assertEquals(true, id.matches(owner))
    }

    private fun letterRequest(
        legendMode: PrimaryKeyboardLegendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
    ) = KeyboardLetterLayoutRequest(
        viewWidth = 1080,
        viewHeight = 420,
        chromeBottom = 80f,
        shifted = false,
        chineseMode = true,
        swipeMode = SwipeCharacterMode.CHINESE,
        legendMode = legendMode,
    )

    private class RecordingLetterLayout : KeyboardLetterLayout {
        var callCount = 0
        var lastRequest: KeyboardLetterLayoutRequest? = null

        override fun appendKeys(
            request: KeyboardLetterLayoutRequest,
            metrics: KeyboardMetrics,
            output: MutableList<Key>,
        ) {
            callCount += 1
            lastRequest = request
        }
    }
}
