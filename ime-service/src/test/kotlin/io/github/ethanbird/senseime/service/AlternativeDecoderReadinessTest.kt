package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativeDecoderReadinessTest {
    @Test
    fun wubiStaysPendingOnlyWhileItsAssetLoadIsInFlight() {
        assertFalse(
            isAlternativeDecoderReady(
                ChineseInputScheme.WUBI_86,
                wubiCandidateDecoderAvailable = false,
                wubiLoadInFlight = true,
            ),
        )

        // A successful load launches the real decoder.
        assertTrue(
            isAlternativeDecoderReady(
                ChineseInputScheme.WUBI_86,
                wubiCandidateDecoderAvailable = true,
                wubiLoadInFlight = false,
            ),
        )

        // A terminal load failure launches the bounded empty fallback, allowing raw commits.
        assertTrue(
            isAlternativeDecoderReady(
                ChineseInputScheme.WUBI_86,
                wubiCandidateDecoderAvailable = false,
                wubiLoadInFlight = false,
            ),
        )
    }

    @Test
    fun t9IsReadyAndQwertyUsesItsSeparateProgressivePipeline() {
        assertTrue(
            isAlternativeDecoderReady(
                ChineseInputScheme.PINYIN_T9,
                wubiCandidateDecoderAvailable = false,
                wubiLoadInFlight = true,
            ),
        )
        assertFalse(
            isAlternativeDecoderReady(
                ChineseInputScheme.PINYIN_QWERTY,
                wubiCandidateDecoderAvailable = true,
                wubiLoadInFlight = false,
            ),
        )
    }
}
