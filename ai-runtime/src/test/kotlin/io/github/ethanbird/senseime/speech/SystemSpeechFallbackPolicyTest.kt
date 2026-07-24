package io.github.ethanbird.senseime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemSpeechFallbackPolicyTest {
    @Test
    fun `async client error from on-device recognizer falls back once`() {
        val onDevice = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        )

        assertEquals(
            SystemSpeechRecoveryDecision.RETRY_WITH_SYSTEM_SERVICE,
            SystemSpeechFallbackPolicy.decide(
                onDevice,
                SystemSpeechRecognizerFailureSignal.CLIENT,
            ),
        )

        val system = SystemSpeechFallbackPolicy.beginSystemFallback(onDevice)
        assertEquals(SystemSpeechRecognizerRoute.SYSTEM_SERVICE, system.route)
        assertEquals(true, system.fallbackConsumed)
        assertEquals(
            SystemSpeechRecoveryDecision.REPORT_FAILURE,
            SystemSpeechFallbackPolicy.decide(
                system,
                SystemSpeechRecognizerFailureSignal.CLIENT,
            ),
        )
    }

    @Test
    fun `missing on-device language may use ordinary system service`() {
        val attempt = SystemSpeechFallbackAttempt(SystemSpeechRecognizerRoute.ON_DEVICE)

        assertEquals(
            SystemSpeechRecoveryDecision.RETRY_WITH_SYSTEM_SERVICE,
            SystemSpeechFallbackPolicy.decide(
                attempt,
                SystemSpeechRecognizerFailureSignal.LANGUAGE_UNAVAILABLE,
            ),
        )
        assertEquals(
            SystemSpeechRecoveryDecision.RETRY_WITH_SYSTEM_SERVICE,
            SystemSpeechFallbackPolicy.decide(
                attempt,
                SystemSpeechRecognizerFailureSignal.LANGUAGE_NOT_SUPPORTED,
            ),
        )
    }

    @Test
    fun `audio permission timeout no-match and busy failures never restart capture`() {
        val attempt = SystemSpeechFallbackAttempt(SystemSpeechRecognizerRoute.ON_DEVICE)
        val nonRecoverable = listOf(
            SystemSpeechRecognizerFailureSignal.AUDIO,
            SystemSpeechRecognizerFailureSignal.PERMISSION,
            SystemSpeechRecognizerFailureSignal.TIMEOUT,
            SystemSpeechRecognizerFailureSignal.NO_MATCH,
            SystemSpeechRecognizerFailureSignal.BUSY,
            SystemSpeechRecognizerFailureSignal.NETWORK,
        )

        nonRecoverable.forEach { failure ->
            assertEquals(
                failure.name,
                SystemSpeechRecoveryDecision.REPORT_FAILURE,
                SystemSpeechFallbackPolicy.decide(attempt, failure),
            )
        }
    }

    @Test
    fun `explicit stop suppresses fallback after a late client error`() {
        val stopped = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        ).markStopRequested()

        assertEquals(
            SystemSpeechRecoveryDecision.REPORT_FAILURE,
            SystemSpeechFallbackPolicy.decide(
                stopped,
                SystemSpeechRecognizerFailureSignal.CLIENT,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            SystemSpeechFallbackPolicy.beginSystemFallback(stopped)
        }
    }

    @Test
    fun `once speech begins every error is reported without restarting capture`() {
        val speaking = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        ).markSpeechObserved()

        SystemSpeechRecognizerFailureSignal.entries.forEach { failure ->
            assertEquals(
                failure.name,
                SystemSpeechRecoveryDecision.REPORT_FAILURE,
                SystemSpeechFallbackPolicy.decide(speaking, failure),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            SystemSpeechFallbackPolicy.beginSystemFallback(speaking)
        }
    }

    @Test
    fun `speech observation is sticky and idempotent`() {
        val initial = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        )
        val observed = initial.markSpeechObserved()

        assertEquals(true, observed.speechObserved)
        assertEquals(observed, observed.markSpeechObserved())
    }

    @Test
    fun `empty partial does not suppress initialization fallback`() {
        val initial = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        )
        val afterNull = SystemSpeechObservationPolicy.observePartialText(initial, null)
        val afterBlank = SystemSpeechObservationPolicy.observePartialText(initial, "   ")

        assertEquals(initial, afterNull)
        assertEquals(initial, afterBlank)
        assertEquals(
            SystemSpeechRecoveryDecision.RETRY_WITH_SYSTEM_SERVICE,
            SystemSpeechFallbackPolicy.decide(
                afterBlank,
                SystemSpeechRecognizerFailureSignal.CLIENT,
            ),
        )
    }

    @Test
    fun `non-empty partial suppresses restart`() {
        val initial = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        )
        val observed = SystemSpeechObservationPolicy.observePartialText(initial, "先思")

        assertEquals(true, observed.speechObserved)
        assertEquals(
            SystemSpeechRecoveryDecision.REPORT_FAILURE,
            SystemSpeechFallbackPolicy.decide(
                observed,
                SystemSpeechRecognizerFailureSignal.CLIENT,
            ),
        )
    }

    @Test
    fun `non-empty audio buffer suppresses restart without retaining bytes`() {
        val initial = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.ON_DEVICE,
        )
        val empty = SystemSpeechObservationPolicy.observeAudioBuffer(initial, byteCount = 0)
        val captured = SystemSpeechObservationPolicy.observeAudioBuffer(initial, byteCount = 320)

        assertEquals(initial, empty)
        assertEquals(true, captured.speechObserved)
        assertEquals(
            SystemSpeechRecoveryDecision.REPORT_FAILURE,
            SystemSpeechFallbackPolicy.decide(
                captured,
                SystemSpeechRecognizerFailureSignal.SERVER_DISCONNECTED,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SystemSpeechObservationPolicy.observeAudioBuffer(initial, byteCount = -1)
        }
    }

    @Test
    fun `system route and consumed fallback cannot recurse`() {
        val system = SystemSpeechFallbackAttempt(
            route = SystemSpeechRecognizerRoute.SYSTEM_SERVICE,
            fallbackConsumed = true,
        )

        SystemSpeechRecognizerFailureSignal.entries.forEach { failure ->
            assertEquals(
                failure.name,
                SystemSpeechRecoveryDecision.REPORT_FAILURE,
                SystemSpeechFallbackPolicy.decide(system, failure),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            SystemSpeechFallbackPolicy.beginSystemFallback(system)
        }
    }

    @Test
    fun `late callback from retired attempt is rejected inside the same session`() {
        val onDevice = SystemSpeechAttemptToken(sessionId = 42L, attemptOrdinal = 1)
        val systemFallback = onDevice.nextAttempt()

        assertEquals(
            false,
            SystemSpeechCallbackGate.accepts(
                active = onDevice,
                callback = onDevice,
                fallbackPending = true,
            ),
        )
        assertEquals(
            false,
            SystemSpeechCallbackGate.accepts(
                active = systemFallback,
                callback = onDevice,
                fallbackPending = false,
            ),
        )
        assertEquals(
            true,
            SystemSpeechCallbackGate.accepts(
                active = systemFallback,
                callback = systemFallback,
                fallbackPending = false,
            ),
        )
    }

    @Test
    fun `callback token also isolates different user sessions`() {
        val oldSession = SystemSpeechAttemptToken(sessionId = 6L, attemptOrdinal = 1)
        val currentSession = SystemSpeechAttemptToken(sessionId = 7L, attemptOrdinal = 1)

        assertEquals(
            false,
            SystemSpeechCallbackGate.accepts(
                active = currentSession,
                callback = oldSession,
                fallbackPending = false,
            ),
        )
    }
}
