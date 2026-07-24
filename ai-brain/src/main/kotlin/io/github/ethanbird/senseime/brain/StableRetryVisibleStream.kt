package io.github.ethanbird.senseime.brain

/**
 * Reconciles the public text of a one-shot retry with text already shown by the first attempt.
 *
 * Provider streaming APIs do not expose a portable resume cursor. A retry therefore regenerates
 * the whole structured answer. Matching prefix bytes are suppressed and only the unseen suffix is
 * emitted. A genuinely different replacement is buffered before one atomic [replace] update, so
 * the keyboard never renders an empty reset frame or visually types the same prefix twice.
 */
internal class StableRetryVisibleStream(
    private val firstDescription: String,
    private val firstPreview: String,
    private val divergenceBufferChars: Int = DEFAULT_DIVERGENCE_BUFFER_CHARS,
) {
    private val retryDescription = StringBuilder()
    private val retryPreview = StringBuilder()
    private var descriptionDiverged = false
    private var previewDiverged = false
    private var replaced = false
    private var comparedDescriptionChars = 0
    private var comparedPreviewChars = 0

    internal val comparedCharCount: Long
        get() = comparedDescriptionChars.toLong() + comparedPreviewChars

    init {
        require(divergenceBufferChars > 0)
    }

    fun appendDescription(delta: String): StableRetryVisibleUpdate {
        if (delta.isEmpty()) return StableRetryVisibleUpdate()
        val previousLength = retryDescription.length
        retryDescription.append(delta)
        if (replaced) return StableRetryVisibleUpdate(description = delta)
        if (descriptionDiverged) return StableRetryVisibleUpdate()

        val compareEnd = minOf(firstDescription.length, retryDescription.length)
        while (comparedDescriptionChars < compareEnd) {
            val index = comparedDescriptionChars
            comparedDescriptionChars += 1
            if (firstDescription[index] != retryDescription[index]) {
                descriptionDiverged = true
                break
            }
        }
        if (descriptionDiverged) {
            // A public description is only auxiliary status. It must never rewind an otherwise
            // stable replacement preview; keep the old description until PreviewReplace or final.
            return StableRetryVisibleUpdate()
        }
        if (retryDescription.length <= firstDescription.length) {
            return StableRetryVisibleUpdate()
        }
        return StableRetryVisibleUpdate(
            description = retryDescription.substring(maxOf(firstDescription.length, previousLength)),
        )
    }

    fun appendPreview(delta: String): StableRetryVisibleUpdate {
        if (delta.isEmpty()) return StableRetryVisibleUpdate()
        val previousLength = retryPreview.length
        retryPreview.append(delta)
        if (replaced) return StableRetryVisibleUpdate(preview = delta)

        if (!previewDiverged) {
            val compareEnd = minOf(firstPreview.length, retryPreview.length)
            while (comparedPreviewChars < compareEnd) {
                val index = comparedPreviewChars
                comparedPreviewChars += 1
                if (firstPreview[index] != retryPreview[index]) {
                    previewDiverged = true
                    break
                }
            }
            if (!previewDiverged && retryPreview.length > firstPreview.length) {
                return StableRetryVisibleUpdate(
                    preview = retryPreview.substring(maxOf(firstPreview.length, previousLength)),
                )
            }
        }
        return replaceIfBuffered()
    }

    /**
     * Resolves a retry that completed with a shorter or divergent public result.
     *
     * Empty regenerated text is left for the validated FinalPatch to display atomically. This
     * prevents an interrupted deletion/no-change response from blanking the AI surface before it
     * has passed the strict patch gate.
     */
    fun finish(): StableRetryVisibleUpdate {
        if (replaced) return StableRetryVisibleUpdate()
        val previewShortened =
            retryPreview.length < firstPreview.length &&
                !previewDiverged
        if ((previewDiverged || previewShortened) && retryPreview.isNotEmpty()) {
            return replaceNow()
        }
        return StableRetryVisibleUpdate()
    }

    private fun replaceIfBuffered(): StableRetryVisibleUpdate {
        if (!previewDiverged || retryPreview.isEmpty()) return StableRetryVisibleUpdate()
        val readyAt = minOf(divergenceBufferChars, maxOf(1, firstPreview.length))
        return if (retryPreview.length >= readyAt) replaceNow() else StableRetryVisibleUpdate()
    }

    private fun replaceNow(): StableRetryVisibleUpdate {
        replaced = true
        return StableRetryVisibleUpdate(
            replace = true,
            description = retryDescription.toString()
                .takeIf(String::isNotEmpty)
                ?: firstDescription,
            preview = retryPreview.toString(),
        )
    }

    private companion object {
        const val DEFAULT_DIVERGENCE_BUFFER_CHARS = 24
    }
}

internal data class StableRetryVisibleUpdate(
    val replace: Boolean = false,
    val description: String = "",
    val preview: String = "",
)
