package io.github.ethanbird.senseime.core

/** Shared allocation guard for every pinyin decoding path. */
object PinyinInputLimits {
    const val MAX_COMPOSING_CODE_LENGTH = 96
}
