package io.github.ethanbird.senseime.speech

/**
 * One validation rule shared by Settings, persistence and both cloud speech
 * execution layers. API keys become HTTP authorization header values, so only
 * visible ASCII is accepted.
 */
object SpeechProviderCredentialPolicy {
    const val MAX_CHARS = 8 * 1_024

    fun isValid(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_CHARS &&
            value.all(::isVisibleAscii)

    fun isValid(value: CharArray): Boolean =
        value.isNotEmpty() &&
            value.size <= MAX_CHARS &&
            value.all(::isVisibleAscii)

    fun requireValid(value: CharArray) {
        require(isValid(value)) {
            "speech API key must be visible ASCII and at most $MAX_CHARS characters"
        }
    }

    private fun isVisibleAscii(value: Char): Boolean = value.code in 0x21..0x7e
}
