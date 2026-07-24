package io.github.ethanbird.senseime.brain

internal data class NativeToolVisibleDelta(
    val description: String = "",
    val patchText: String = "",
)

internal data class NativePatchToolSubmission(
    val description: String,
    val patchDocument: String,
)

/**
 * Bounded accumulator for the JSON string carried by DeepSeek's streamed function arguments.
 *
 * Prefix extraction is display-only. [finish] reparses the complete document and enforces the
 * closed outer contract before the patch reaches the authoritative protocol codec.
 */
internal class NativePatchToolAccumulator {
    private val document = StringBuilder()
    private val visibleFields = IncrementalJsonStringFieldScanner(
        setOf(DESCRIPTION_FIELD, PATCH_TEXT_FIELD),
    )
    private var streamedDescriptionChars = 0

    internal val scannedCharCount: Long
        get() = visibleFields.scannedCharCount

    fun append(fragment: String): NativeToolVisibleDelta {
        if (document.length.toLong() + fragment.length > ProviderJson.MAX_DOCUMENT_CHARS) {
            throw ProviderPayloadException(
                "native tool arguments exceed ${ProviderJson.MAX_DOCUMENT_CHARS} characters",
            )
        }
        document.append(fragment)
        val deltas = visibleFields.append(fragment)
        val descriptionDelta = deltas[DESCRIPTION_FIELD].orEmpty()
        val patchDelta = deltas[PATCH_TEXT_FIELD].orEmpty()
        streamedDescriptionChars = Math.addExact(
            streamedDescriptionChars,
            descriptionDelta.length,
        )
        validateDescriptionDelta(descriptionDelta, streamedDescriptionChars)
        return NativeToolVisibleDelta(descriptionDelta, patchDelta)
    }

    fun fullDocument(): String = document.toString()

    fun finish(): NativePatchToolSubmission {
        val root = ProviderJson.parse(document.toString()) as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("native tool arguments must be an object")
        if (root.members.keys != setOf("description", "patch")) {
            throw ProviderPayloadException(
                "native tool arguments must contain exactly description and patch",
            )
        }
        val description = (root.members["description"] as? JsonValue.StringValue)?.value
            ?: throw ProviderPayloadException("native tool description must be a string")
        validateDescription(description)
        val patch = root.members["patch"] as? JsonValue.ObjectValue
            ?: throw ProviderPayloadException("native tool patch must be an object")
        return NativePatchToolSubmission(
            description = description,
            patchDocument = ProviderJson.stringify(patch),
        )
    }

    private fun validateDescriptionPrefix(value: String) {
        if (value.length > MAX_DESCRIPTION_CHARS || value.any(::isUnsafeDescriptionCharacter)) {
            throw ProviderPayloadException("native tool description is not a safe single line")
        }
    }

    private fun validateDescriptionDelta(value: String, totalChars: Int) {
        if (totalChars > MAX_DESCRIPTION_CHARS || value.any(::isUnsafeDescriptionCharacter)) {
            throw ProviderPayloadException("native tool description is not a safe single line")
        }
    }

    private fun validateDescription(value: String) {
        validateDescriptionPrefix(value)
        if (value.isBlank()) {
            throw ProviderPayloadException("native tool description must not be blank")
        }
    }

    private fun isUnsafeDescriptionCharacter(value: Char): Boolean =
        Character.isISOControl(value) ||
            value == '\u2028' ||
            value == '\u2029' ||
            value == '\u061c' ||
            value == '\u200e' ||
            value == '\u200f' ||
            value in '\u202a'..'\u202e' ||
            value in '\u2066'..'\u2069'

    private companion object {
        const val DESCRIPTION_FIELD = "description"
        const val PATCH_TEXT_FIELD = "text"
        const val MAX_DESCRIPTION_CHARS = 160
    }
}
