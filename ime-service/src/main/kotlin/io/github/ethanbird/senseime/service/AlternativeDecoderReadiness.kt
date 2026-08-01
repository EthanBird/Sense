package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme

/** Worker launch gate that distinguishes an in-flight Wubi asset from a terminal empty fallback. */
internal fun isAlternativeDecoderReady(
    scheme: ChineseInputScheme,
    wubiCandidateDecoderAvailable: Boolean,
    wubiLoadInFlight: Boolean,
): Boolean = when (scheme) {
    ChineseInputScheme.PINYIN_T9 -> true
    ChineseInputScheme.WUBI_86 -> wubiCandidateDecoderAvailable || !wubiLoadInFlight
    ChineseInputScheme.PINYIN_QWERTY -> false
}
