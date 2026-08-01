package io.github.ethanbird.senseime.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ImePreferencesCodecTest {
    @Test
    fun everySchemeRoundTripsThroughTheVersionedWireFormat() {
        ChineseInputScheme.entries.forEach { scheme ->
            WubiAutoCommitMode.entries.forEach { autoCommit ->
                val expected = ImePreferencesV1(
                    chineseInputScheme = scheme,
                    wubiAutoCommitMode = autoCommit,
                    portraitKeyboardHeightDp = 412,
                    landscapeKeyboardHeightDp = 286,
                    t9SideSymbols = listOf("，", "。", "……", "@"),
                )
                assertEquals(expected, ImePreferencesCodec.decode(ImePreferencesCodec.encode(expected)))
            }
        }
    }

    @Test
    fun missingOptionalFieldsUseStableDefaults() {
        val decoded = ImePreferencesCodec.decode(
            "schema_version=1\nchinese_input_scheme=PINYIN_T9\n".encodeToByteArray(),
        )

        assertEquals(ChineseInputScheme.PINYIN_T9, decoded.chineseInputScheme)
        assertEquals(WubiAutoCommitMode.RIME_STYLE, decoded.wubiAutoCommitMode)
        assertEquals(KeyboardHeightPolicy.DEFAULT_PORTRAIT_HEIGHT_DP, decoded.portraitKeyboardHeightDp)
        assertEquals(KeyboardHeightPolicy.DEFAULT_LANDSCAPE_HEIGHT_DP, decoded.landscapeKeyboardHeightDp)
        assertEquals(T9SideSymbolPolicy.DEFAULT_SYMBOLS, decoded.t9SideSymbols)
    }

    @Test
    fun symbolEditorProjectionIsBoundedStableAndFallsBackFromEmptyText() {
        val repeated = buildString {
            repeat(40) { index -> appendCodePoint(0x2200 + index) }
            append("，，")
        }

        val parsed = T9SideSymbolPolicy.fromEditorText(repeated)

        assertEquals(T9SideSymbolPolicy.MAX_SYMBOL_COUNT, parsed.size)
        assertEquals(parsed.distinct(), parsed)
        assertEquals(
            T9SideSymbolPolicy.DEFAULT_SYMBOLS,
            T9SideSymbolPolicy.fromEditorText("  \n  "),
        )
        assertEquals(20, T9SideSymbolPolicy.DEFAULT_SYMBOLS.size)
        assertEquals(emptyList<String>(), T9SideSymbolPolicy.normalize(listOf("\uD800")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeKeyboardHeight() {
        ImePreferencesV1(
            portraitKeyboardHeightDp = KeyboardHeightPolicy.MIN_HEIGHT_DP - 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSchema() {
        ImePreferencesCodec.decode("schema_version=2\n".encodeToByteArray())
    }
}
