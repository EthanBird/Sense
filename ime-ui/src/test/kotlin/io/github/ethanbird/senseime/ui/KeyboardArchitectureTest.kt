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
    fun `input scheme choices own their geometry and legend presentation`() {
        assertEquals(
            PrimaryKeyboardMode.T9 to PrimaryKeyboardLegendMode.SWIPE_HINTS,
            KeyboardInputSchemeChoice.PINYIN_T9.presentation,
        )
        assertEquals(
            PrimaryKeyboardMode.QWERTY to PrimaryKeyboardLegendMode.SWIPE_HINTS,
            KeyboardInputSchemeChoice.PINYIN_QWERTY.presentation,
        )
        assertEquals(
            PrimaryKeyboardMode.QWERTY to PrimaryKeyboardLegendMode.WUBI_86_ROOTS,
            KeyboardInputSchemeChoice.WUBI_86.presentation,
        )
        assertEquals(
            KeyboardInputSchemeChoice.WUBI_86,
            KeyboardInputSchemeChoice.fromPresentation(
                PrimaryKeyboardMode.QWERTY,
                PrimaryKeyboardLegendMode.WUBI_86_ROOTS,
            ),
        )
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
    fun `toolbar keyboard entry opens the scheme panel without losing physical identity`() {
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendToolbar(1080, output)

        val keyboard = output.single { it.icon == Icon.KEYBOARD }
        val action = keyboard.action as KeyAction.ShowPanel
        assertEquals(KeyboardPanel.INPUT_SCHEMES, action.panel)
        assertEquals(KeyCodes.LETTERS, keyboard.code)
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
        assertEquals(
            listOf("分词", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"),
            output.filter { it.code in '1'.code..'9'.code }.map(Key::label),
        )
        assertTrue(
            output.filter { it.code in '1'.code..'9'.code }.all { it.visualLegend == null },
        )
        assertTrue(output.any { it.code == KeyCodes.DELETE })
    }

    @Test
    fun `t9 keycaps expose only their letter group and no swipe output`() {
        val layout = KeyboardPrimaryLayout(
            metrics = KeyboardMetrics.fromDensity(1f),
        )
        val t9 = ArrayList<Key>()
        val qwerty = ArrayList<Key>()

        layout.appendLetters(PrimaryKeyboardMode.T9, letterRequest(), t9)
        layout.appendLetters(PrimaryKeyboardMode.QWERTY, letterRequest(), qwerty)

        val t9Two = t9.single { it.code == '2'.code }
        assertEquals("ABC", t9Two.label)
        assertEquals(null, t9Two.visualLegend)
        assertEquals(null, t9Two.swipeOutput)

        val qwertyQ = qwerty.single { it.code == 'q'.code }
        assertEquals("1", qwertyQ.visualLegend)
        assertEquals("1", qwertyQ.swipeOutput)
    }

    @Test
    fun `t9 left rail swaps punctuation for revision bound pinyin choices`() {
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.T9,
            letterRequest(
                t9PinyinChoiceRevision = 27L,
                t9PinyinChoices = listOf(
                    T9PinyinChoice("hun", "hun'shen'x's"),
                    T9PinyinChoice("hunshen", "hun'shen'xs"),
                ),
            ),
            output,
        )

        val choices = output.mapNotNull { key ->
            (key.action as? KeyAction.SelectT9PinyinChoice)?.let { action -> key to action }
        }
        assertEquals(listOf("hun", "hunshen"), choices.map { it.first.label })
        assertEquals(listOf("hun'shen'x's", "hun'shen'xs"), choices.map { it.first.visualLegend })
        assertEquals(listOf(27L, 27L), choices.map { it.second.revision })
        assertEquals(listOf(0, 1), choices.map { it.second.index })
        assertTrue(output.none { it.code == KeyCodes.COMMA || it.code == KeyCodes.PERIOD })
    }

    @Test
    fun `active t9 pending state never flashes punctuation or clickable empty slots`() {
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.T9,
            letterRequest(t9CompositionActive = true),
            output,
        )

        assertTrue(output.none { it.style == KeyStyle.T9_LEFT_RAIL })
        assertTrue(output.none { it.code == KeyCodes.COMMA || it.code == KeyCodes.PERIOD })
        assertTrue(output.none { it.action == KeyAction.None })
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
        t9PinyinChoiceRevision: Long = 0L,
        t9PinyinChoices: List<T9PinyinChoice> = emptyList(),
        t9CompositionActive: Boolean = t9PinyinChoices.isNotEmpty(),
    ) = KeyboardLetterLayoutRequest(
        viewWidth = 1080,
        viewHeight = 420,
        chromeBottom = 80f,
        shifted = false,
        chineseMode = true,
        swipeMode = SwipeCharacterMode.CHINESE,
        legendMode = legendMode,
        t9CompositionActive = t9CompositionActive,
        t9PinyinChoiceRevision = t9PinyinChoiceRevision,
        t9PinyinChoices = t9PinyinChoices,
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
