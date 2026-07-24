package io.github.ethanbird.senseime.speech

/**
 * Android-free recovery policy for the platform SpeechRecognizer.
 *
 * Several OEM implementations report that their on-device recognizer is available, create it
 * successfully, and only then reject the first request asynchronously. Retrying the same
 * on-device route loops forever; switching once to the ordinary system RecognitionService is both
 * bounded and useful.
 */
enum class SystemSpeechRecognizerRoute {
    ON_DEVICE,
    SYSTEM_SERVICE,
}

enum class SystemSpeechRecognizerFailureSignal {
    CLIENT,
    AUDIO,
    BUSY,
    NETWORK,
    TIMEOUT,
    NO_MATCH,
    PERMISSION,
    SERVER,
    SERVER_DISCONNECTED,
    LANGUAGE_NOT_SUPPORTED,
    LANGUAGE_UNAVAILABLE,
    UNKNOWN,
}

data class SystemSpeechFallbackAttempt(
    val route: SystemSpeechRecognizerRoute,
    val fallbackConsumed: Boolean = false,
    val stopRequested: Boolean = false,
    val speechObserved: Boolean = false,
) {
    fun markStopRequested(): SystemSpeechFallbackAttempt =
        if (stopRequested) this else copy(stopRequested = true)

    fun markSpeechObserved(): SystemSpeechFallbackAttempt =
        if (speechObserved) this else copy(speechObserved = true)
}

enum class SystemSpeechRecoveryDecision {
    RETRY_WITH_SYSTEM_SERVICE,
    REPORT_FAILURE,
}

data class SystemSpeechAttemptToken(
    val sessionId: Long,
    val attemptOrdinal: Int,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(attemptOrdinal > 0) { "attemptOrdinal must be positive" }
    }

    fun nextAttempt(): SystemSpeechAttemptToken {
        check(attemptOrdinal < Int.MAX_VALUE) { "speech attempt ordinal exhausted" }
        return copy(attemptOrdinal = attemptOrdinal + 1)
    }
}

object SystemSpeechCallbackGate {
    /**
     * A fallback keeps the user-visible session id, so session id alone cannot reject callbacks
     * from the destroyed OEM recognizer. The attempt ordinal is the second half of the identity.
     */
    fun accepts(
        active: SystemSpeechAttemptToken?,
        callback: SystemSpeechAttemptToken,
        fallbackPending: Boolean,
    ): Boolean = !fallbackPending && active == callback
}

object SystemSpeechObservationPolicy {
    /** Explicit begin/end callbacks are evidence that the capture left initialization. */
    fun observeSpeechBoundary(
        attempt: SystemSpeechFallbackAttempt,
    ): SystemSpeechFallbackAttempt = attempt.markSpeechObserved()

    /** OEM services may emit empty partial bundles while warming up; those are not speech. */
    fun observePartialText(
        attempt: SystemSpeechFallbackAttempt,
        text: String?,
    ): SystemSpeechFallbackAttempt =
        if (text.isNullOrBlank()) attempt else attempt.markSpeechObserved()

    /**
     * A non-empty buffer means microphone audio has already been handed to the recognizer.
     * Contents are intentionally neither retained nor inspected.
     */
    fun observeAudioBuffer(
        attempt: SystemSpeechFallbackAttempt,
        byteCount: Int,
    ): SystemSpeechFallbackAttempt {
        require(byteCount >= 0) { "byteCount must not be negative" }
        return if (byteCount == 0) attempt else attempt.markSpeechObserved()
    }
}

object SystemSpeechFallbackPolicy {
    private val recoverableOnDeviceFailures = setOf(
        SystemSpeechRecognizerFailureSignal.CLIENT,
        SystemSpeechRecognizerFailureSignal.SERVER,
        SystemSpeechRecognizerFailureSignal.SERVER_DISCONNECTED,
        SystemSpeechRecognizerFailureSignal.LANGUAGE_NOT_SUPPORTED,
        SystemSpeechRecognizerFailureSignal.LANGUAGE_UNAVAILABLE,
    )

    fun decide(
        attempt: SystemSpeechFallbackAttempt,
        failure: SystemSpeechRecognizerFailureSignal,
    ): SystemSpeechRecoveryDecision {
        val shouldFallback =
            attempt.route == SystemSpeechRecognizerRoute.ON_DEVICE &&
                !attempt.fallbackConsumed &&
                !attempt.stopRequested &&
                !attempt.speechObserved &&
                failure in recoverableOnDeviceFailures
        return if (shouldFallback) {
            SystemSpeechRecoveryDecision.RETRY_WITH_SYSTEM_SERVICE
        } else {
            SystemSpeechRecoveryDecision.REPORT_FAILURE
        }
    }

    fun beginSystemFallback(
        attempt: SystemSpeechFallbackAttempt,
    ): SystemSpeechFallbackAttempt {
        check(attempt.route == SystemSpeechRecognizerRoute.ON_DEVICE) {
            "only an on-device attempt can fall back"
        }
        check(!attempt.fallbackConsumed) { "system fallback is one-shot" }
        check(!attempt.stopRequested) { "a stopped attempt cannot restart recognition" }
        check(!attempt.speechObserved) {
            "an attempt that observed speech cannot silently discard and restart capture"
        }
        return SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.SYSTEM_SERVICE,
            fallbackConsumed = true,
        )
    }
}
