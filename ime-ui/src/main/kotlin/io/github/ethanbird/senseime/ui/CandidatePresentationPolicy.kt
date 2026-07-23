package io.github.ethanbird.senseime.ui

/** Pure presentation rules shared by pending and decoded candidate states. */
object CandidatePresentationPolicy {
    enum class HeaderRole {
        COMPOSING,
        BRANDING,
    }

    data class HeaderSpec(
        val role: HeaderRole,
        val xDp: Float,
        val yDp: Float,
        val textSizeSp: Float,
        val verticallyCentered: Boolean,
    )

    fun shouldResetNavigation(
        previousRevision: Long,
        previousComposing: String,
        nextRevision: Long,
        nextComposing: String,
    ): Boolean = previousRevision != nextRevision || previousComposing != nextComposing

    fun takesToolbar(composing: String, editorPanelVisible: Boolean): Boolean =
        composing.isNotBlank() && !editorPanelVisible

    /**
     * Composing text deliberately has one geometry whether candidates are
     * pending or ready, preventing the async empty -> values transition from
     * jumping between the centre and the top of the strip.
     */
    fun headerSpec(composing: String, hasCandidates: Boolean): HeaderSpec? = when {
        composing.isNotBlank() -> HeaderSpec(
            role = HeaderRole.COMPOSING,
            xDp = 10f,
            yDp = 14f,
            textSizeSp = 11f,
            verticallyCentered = false,
        )

        !hasCandidates -> HeaderSpec(
            role = HeaderRole.BRANDING,
            xDp = 14f,
            yDp = 22.5f,
            textSizeSp = 13f,
            verticallyCentered = true,
        )

        else -> null
    }
}
