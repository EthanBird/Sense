package io.github.ethanbird.senseime.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ImePreferencesCodecTest {
    @Test
    fun everySchemeRoundTripsThroughTheVersionedWireFormat() {
        ChineseInputScheme.entries.forEach { scheme ->
            WubiAutoCommitMode.entries.forEach { autoCommit ->
                val expected = ImePreferencesV1(scheme, autoCommit)
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
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSchema() {
        ImePreferencesCodec.decode("schema_version=2\n".encodeToByteArray())
    }
}
