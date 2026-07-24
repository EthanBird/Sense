package io.github.ethanbird.senseime.brain

/** Strict decoder for the non-terminal `sense_report_progress` Agent tool. */
internal object NativeProgressToolSubmission {
    fun decode(document: String): String {
        val root = ProviderJson.parse(document) as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("progress tool arguments must be an object")
        if (root.members.keys != setOf(MESSAGE_FIELD)) {
            throw ProviderPayloadException(
                "progress tool arguments must contain exactly message",
            )
        }
        val message = (root.members[MESSAGE_FIELD] as? JsonValue.StringValue)?.value
            ?: throw ProviderPayloadException("progress message must be a string")
        if (
            message.isBlank() ||
            message.length > MAX_MESSAGE_CHARS ||
            message.any(::isUnsafePublicCharacter)
        ) {
            throw ProviderPayloadException("progress message must be one safe public line")
        }
        return message
    }

    private fun isUnsafePublicCharacter(value: Char): Boolean =
        Character.isISOControl(value) ||
            value == '\u2028' ||
            value == '\u2029' ||
            value == '\u061c' ||
            value == '\u200e' ||
            value == '\u200f' ||
            value in '\u202a'..'\u202e' ||
            value in '\u2066'..'\u2069'

    private const val MESSAGE_FIELD = "message"
    private const val MAX_MESSAGE_CHARS = 160
}
