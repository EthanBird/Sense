package io.github.ethanbird.senseime.service

/**
 * Persistence is disabled whenever the editor marks the session as
 * non-personalized or exposes a password variation.
 */
internal object EditorPrivacyPolicy {
    fun allowsPersistence(
        noPersonalizedLearning: Boolean,
        passwordVariation: Boolean,
    ): Boolean = !noPersonalizedLearning && !passwordVariation
}
