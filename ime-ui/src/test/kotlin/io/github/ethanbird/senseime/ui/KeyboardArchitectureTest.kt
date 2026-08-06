package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardArchitectureTest {
    @Test
    fun `shift renderer projection distinguishes one shot from caps lock`() {
        assertEquals(ShiftIconVisualState.OFF, ShiftIconVisualPolicy.resolve(false, false))
        assertEquals(ShiftIconVisualState.ONE_SHOT, ShiftIconVisualPolicy.resolve(true, false))
        assertEquals(ShiftIconVisualState.CAPS_LOCK, ShiftIconVisualPolicy.resolve(true, true))
    }

    @Test
    fun `emoji category rail declares horizontal scroll projection`() {
        assertEquals(ScrollAxis.HORIZONTAL, ScrollPanel.EMOJI_CATEGORIES.axis)
        assertEquals(ScrollAxis.VERTICAL, ScrollPanel.EMOJI.axis)
        assertEquals(ScrollAxis.VERTICAL, ScrollPanel.T9_LEFT_RAIL.axis)
    }

    @Test
    fun `emoji category rail keeps fixed slots and minimally reveals selection`() {
        val state = ContinuousVerticalScrollState()
        val slot = EmojiCategoryRailPolicy.slotWidth(density = 1f)

        EmojiCategoryRailPolicy.configureAndReveal(
            state = state,
            itemCount = 11,
            selectedIndex = 10,
            viewportExtent = 322f,
            slotWidth = slot,
        )
        val afterLast = state.offset
        EmojiCategoryRailPolicy.configureAndReveal(
            state = state,
            itemCount = 11,
            selectedIndex = 9,
            viewportExtent = 322f,
            slotWidth = slot,
        )

        assertEquals(46f, slot, 0.001f)
        assertEquals(184f, afterLast, 0.001f)
        assertEquals(afterLast, state.offset, 0.001f)
    }

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
    fun `agent owns the exact centre toolbar slot`() {
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendToolbar(1_120, output)

        assertEquals(7, output.size)
        assertEquals(Icon.AGENT, output[3].icon)
        assertEquals(KeyCodes.AGENT, output[3].code)
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
        assertEquals(
            ('1'..'9').map(Char::toString),
            output.filter { it.code in '1'.code..'9'.code }.map(Key::visualLegend),
        )
        assertTrue(output.any { it.code == KeyCodes.DELETE })
    }

    @Test
    fun `t9 keycaps keep letters primary and expose digits as legends and upward output`() {
        val layout = KeyboardPrimaryLayout(
            metrics = KeyboardMetrics.fromDensity(1f),
        )
        val t9 = ArrayList<Key>()
        val qwerty = ArrayList<Key>()

        layout.appendLetters(PrimaryKeyboardMode.T9, letterRequest(), t9)
        layout.appendLetters(PrimaryKeyboardMode.QWERTY, letterRequest(), qwerty)

        val t9Two = t9.single { it.code == '2'.code }
        assertEquals("ABC", t9Two.label)
        assertEquals("2", t9Two.visualLegend)
        assertEquals("2", t9Two.swipeOutput)

        val qwertyQ = qwerty.single { it.code == 'q'.code }
        assertEquals("1", qwertyQ.visualLegend)
        assertEquals("1", qwertyQ.swipeOutput)
    }

    @Test
    fun `t9 digit key keeps one physical skill owner while exposing upward digit output`() {
        val keys = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.T9,
            letterRequest(),
            keys,
        )
        val two = keys.single { it.code == '2'.code }
        val scene = MutableKeyboardScene().apply {
            mutableKeys += two
            panelKeyStart = 0
            panelKeyEndExclusive = 1
            assignPhysicalKeyIds(KeyboardPanel.LETTERS)
        }
        val owner = requireNotNull(scene.physicalIdFor(two)).toSkillOwner()
        val binding = KeyboardSkillBinding(
            keyCode = '2'.code,
            direction = KeyboardSkillDirection.LEFT,
            skillId = "digit-two-skill",
            label = "数字技能",
        )
        val bindings = KeyboardSkillBindingSet.from(listOf(binding))

        assertEquals('2'.code, owner.signature.keyCode)
        assertEquals(KeyStyle.T9_PRIMARY.name, owner.signature.styleToken)
        assertEquals(binding, bindings.binding(two.code, KeyboardSkillDirection.LEFT))
        assertTrue(KeyboardSkillKeyPolicy.supportsKeyCode(two.code))
        assertEquals("2", two.swipeOutput)
    }

    @Test
    fun `idle t9 left rail is one contiguous scroll run with injected symbols and settings`() {
        val symbols = (1..24).map { "符$it" }
        val output = ArrayList<Key>()
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.T9,
            letterRequest(t9SideSymbols = symbols),
            output,
        )

        val rail = output.filter { it.style == KeyStyle.T9_LEFT_RAIL }
        assertEquals(symbols.size + 1, rail.size)
        assertEquals(symbols, rail.dropLast(1).map(Key::label))
        assertEquals("自定义设置", rail.last().label)
        assertEquals(KeyAction.OpenT9SideSymbolSettings, rail.last().action)
        assertTrue(rail.all { it.scrollPanel == ScrollPanel.T9_LEFT_RAIL })
        rail.zipWithNext().forEach { (current, next) ->
            assertEquals(current.bounds.bottom, next.bounds.top, 0.001f)
        }
    }

    @Test
    fun `t9 symbol injection trims deduplicates and remains bounded`() {
        val normalized = T9SideSymbolPolicy.normalize(
            listOf("  ，  ", "", "，", "abcdef", "😀😁😂🤣😃") +
                (1..40).map { "符$it" },
        )

        assertEquals("，", normalized.first())
        assertEquals("abcd", normalized[1])
        assertEquals(4, normalized[2].codePointCount(0, normalized[2].length))
        assertTrue(normalized.size <= T9SideSymbolPolicy.MAX_SYMBOLS)
        assertEquals(normalized.size, normalized.distinct().size)
        assertEquals(
            T9SideSymbolPolicy.DEFAULT_SYMBOLS,
            T9SideSymbolPolicy.normalize(emptyList()),
        )
    }

    @Test
    fun `t9 scene publishes a scrollable left rail viewport`() {
        val metrics = KeyboardMetrics.fromDensity(1f)
        val scene = MutableKeyboardScene()
        T9KeyboardLayout.configureLeftRailScene(
            request = letterRequest(t9SideSymbols = (1..24).map { "符$it" }),
            metrics = metrics,
            target = scene,
        )

        assertTrue(scene.t9LeftRailBounds != null)
        assertTrue(scene.t9LeftRailScrollState.maximumOffset > 0f)
        assertTrue(scene.t9LeftRailScrollState.scrollBy(48f))
        assertEquals(48f, scene.scrollOffset(ScrollPanel.T9_LEFT_RAIL), 0.001f)
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
    fun `t9 composition rail keeps eight scrollable segmentation choices`() {
        val output = ArrayList<Key>()
        val supplied = (0 until 10).map { index -> T9PinyinChoice("path$index", "p'a't'h$index") }
        KeyboardPrimaryLayout(KeyboardMetrics.fromDensity(1f)).appendLetters(
            PrimaryKeyboardMode.T9,
            letterRequest(t9PinyinChoices = supplied),
            output,
        )

        val rail = output.filter { it.style == KeyStyle.T9_LEFT_RAIL }
        assertEquals(8, rail.size)
        assertEquals(supplied.take(8).map(T9PinyinChoice::canonical), rail.map(Key::label))
        assertTrue(rail.all { it.scrollPanel == ScrollPanel.T9_LEFT_RAIL })
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
        t9SideSymbols: List<String> = T9SideSymbolPolicy.DEFAULT_SYMBOLS,
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
        t9SideSymbols = t9SideSymbols,
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
