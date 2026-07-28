package io.github.ethanbird.senseime.ui

/**
 * Allocation-free policy shared by the active-Skill Aurora renderer and its
 * host-side tests.
 *
 * Reduced motion is a render mode, not an inactive state: the active key keeps
 * a stable Aurora frame while recurring callbacks remain disabled.
 */
internal object ActiveSkillAuroraLoopPolicy {
    const val FRAME_INTERVAL_MILLIS = 33L
    const val BASE_PERIOD_MILLIS = 4_800L
    const val STATIC_PHASE = 0.625f

    enum class RenderMode {
        INACTIVE,
        STATIC,
        ANIMATED,
    }

    fun renderMode(
        active: Boolean,
        attached: Boolean,
        visible: Boolean,
        hostRenderingEnabled: Boolean,
        hasDrawableBounds: Boolean,
        animatorsEnabled: Boolean,
    ): RenderMode = when {
        !active || !attached || !visible || !hostRenderingEnabled || !hasDrawableBounds ->
            RenderMode.INACTIVE
        !animatorsEnabled -> RenderMode.STATIC
        else -> RenderMode.ANIMATED
    }

    fun requiresFrameCallback(mode: RenderMode): Boolean =
        mode == RenderMode.ANIMATED

    fun phase(
        uptimeMillis: Long,
        periodMillis: Long,
        mode: RenderMode,
    ): Float {
        require(uptimeMillis >= 0L) { "Aurora uptime must be non-negative" }
        require(periodMillis > 0L) { "Aurora period must be positive" }
        return if (mode == RenderMode.ANIMATED) {
            (uptimeMillis % periodMillis).toFloat() / periodMillis.toFloat()
        } else {
            STATIC_PHASE
        }
    }
}
