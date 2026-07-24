package io.github.ethanbird.senseime.speech

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable recognition state for a render-on-revision keyboard surface.
 *
 * A partial result always replaces the previous partial result for the active session. It must not
 * be appended, because Android recognition services are allowed to revise earlier words.
 */
data class SpeechRecognitionState(
    val revision: Long = 0L,
    val sessionId: Long = 0L,
    val phase: SpeechRecognitionPhase = SpeechRecognitionPhase.IDLE,
    val partialText: String = "",
    val finalText: String? = null,
    val alternatives: List<String> = emptyList(),
    val rmsDb: Float? = null,
    val waveformLevel: Float = 0f,
    val usingOnDeviceRecognizer: Boolean = false,
    val failure: SpeechRecognitionFailure? = null,
) {
    val isTerminal: Boolean
        get() = phase == SpeechRecognitionPhase.COMPLETED ||
            phase == SpeechRecognitionPhase.CANCELLED ||
            phase == SpeechRecognitionPhase.ERROR ||
            phase == SpeechRecognitionPhase.DESTROYED

    val visibleText: String
        get() = finalText ?: partialText
}

enum class SpeechRecognitionPhase {
    IDLE,
    STARTING,
    LISTENING,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    ERROR,
    DESTROYED,
}

enum class SpeechRecognitionFailureKind {
    PERMISSION_DENIED,
    RECOGNIZER_UNAVAILABLE,
    UNSUPPORTED_PROVIDER,
    BUSY,
    NETWORK,
    TIMEOUT,
    NO_MATCH,
    LANGUAGE_UNSUPPORTED,
    CLIENT,
    SERVER,
    UNKNOWN,
}

data class SpeechRecognitionFailure(
    val kind: SpeechRecognitionFailureKind,
    val providerErrorCode: Int? = null,
    val message: String,
)

sealed interface SpeechRecognitionEvent {
    val sessionId: Long

    data class Started(
        override val sessionId: Long,
        val usingOnDeviceRecognizer: Boolean,
    ) : SpeechRecognitionEvent

    /**
     * The OEM on-device recognizer rejected the request, so the same user session is being
     * restarted once on the ordinary system RecognitionService.
     */
    data class SystemFallbackStarted(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data class Ready(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data class SpeechBegan(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data class RmsChanged(
        override val sessionId: Long,
        val rmsDb: Float,
    ) : SpeechRecognitionEvent

    data class PartialResult(
        override val sessionId: Long,
        val text: String,
    ) : SpeechRecognitionEvent

    data class ProcessingRequested(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data class SpeechEnded(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data class FinalResult(
        override val sessionId: Long,
        val text: String,
        val alternatives: List<String> = emptyList(),
    ) : SpeechRecognitionEvent

    data class Failed(
        override val sessionId: Long,
        val failure: SpeechRecognitionFailure,
    ) : SpeechRecognitionEvent

    data class Cancelled(
        override val sessionId: Long,
    ) : SpeechRecognitionEvent

    data object Destroyed : SpeechRecognitionEvent {
        override val sessionId: Long = 0L
    }
}

object SpeechRecognitionReducer {
    fun reduce(
        state: SpeechRecognitionState,
        event: SpeechRecognitionEvent,
    ): SpeechRecognitionState {
        if (event is SpeechRecognitionEvent.Destroyed) {
            if (state.phase == SpeechRecognitionPhase.DESTROYED) return state
            return state.copy(
                revision = state.revision + 1L,
                phase = SpeechRecognitionPhase.DESTROYED,
                partialText = "",
                finalText = null,
                alternatives = emptyList(),
                rmsDb = null,
                waveformLevel = 0f,
                failure = null,
            )
        }

        if (event is SpeechRecognitionEvent.Started) {
            if (
                state.phase == SpeechRecognitionPhase.DESTROYED ||
                event.sessionId <= state.sessionId
            ) {
                return state
            }
            return SpeechRecognitionState(
                revision = state.revision + 1L,
                sessionId = event.sessionId,
                phase = SpeechRecognitionPhase.STARTING,
                usingOnDeviceRecognizer = event.usingOnDeviceRecognizer,
            )
        }

        if (
            event.sessionId <= 0L ||
            event.sessionId != state.sessionId ||
            state.phase == SpeechRecognitionPhase.IDLE ||
            state.isTerminal
        ) {
            return state
        }
        return when (event) {
            is SpeechRecognitionEvent.SystemFallbackStarted -> state.copy(
                revision = state.revision + 1L,
                phase = SpeechRecognitionPhase.STARTING,
                partialText = "",
                finalText = null,
                alternatives = emptyList(),
                rmsDb = null,
                waveformLevel = 0f,
                usingOnDeviceRecognizer = false,
                failure = null,
            )

            is SpeechRecognitionEvent.Ready,
            is SpeechRecognitionEvent.SpeechBegan,
            -> if (
                state.phase == SpeechRecognitionPhase.STARTING ||
                state.phase == SpeechRecognitionPhase.LISTENING
            ) {
                state.next(phase = SpeechRecognitionPhase.LISTENING)
            } else {
                state
            }

            is SpeechRecognitionEvent.RmsChanged -> {
                if (
                    state.phase != SpeechRecognitionPhase.STARTING &&
                    state.phase != SpeechRecognitionPhase.LISTENING
                ) {
                    return state
                }
                val finiteRms = event.rmsDb.takeIf(Float::isFinite) ?: return state
                state.copy(
                    revision = state.revision + 1L,
                    rmsDb = finiteRms,
                    waveformLevel = SpeechRmsNormalizer.normalize(finiteRms),
                )
            }

            is SpeechRecognitionEvent.PartialResult -> state.copy(
                revision = state.revision + 1L,
                phase = if (state.phase == SpeechRecognitionPhase.PROCESSING) {
                    SpeechRecognitionPhase.PROCESSING
                } else {
                    SpeechRecognitionPhase.LISTENING
                },
                partialText = event.text,
                finalText = null,
                alternatives = emptyList(),
                failure = null,
            )

            is SpeechRecognitionEvent.ProcessingRequested,
            is SpeechRecognitionEvent.SpeechEnded,
            -> state.next(
                phase = SpeechRecognitionPhase.PROCESSING,
                waveformLevel = 0f,
            )

            is SpeechRecognitionEvent.FinalResult -> state.copy(
                revision = state.revision + 1L,
                phase = SpeechRecognitionPhase.COMPLETED,
                partialText = "",
                finalText = event.text,
                alternatives = event.alternatives,
                rmsDb = null,
                waveformLevel = 0f,
                failure = null,
            )

            is SpeechRecognitionEvent.Failed -> state.copy(
                revision = state.revision + 1L,
                phase = SpeechRecognitionPhase.ERROR,
                partialText = "",
                finalText = null,
                alternatives = emptyList(),
                rmsDb = null,
                waveformLevel = 0f,
                failure = event.failure,
            )

            is SpeechRecognitionEvent.Cancelled -> state.copy(
                revision = state.revision + 1L,
                phase = SpeechRecognitionPhase.CANCELLED,
                partialText = "",
                finalText = null,
                alternatives = emptyList(),
                rmsDb = null,
                waveformLevel = 0f,
                failure = null,
            )

            is SpeechRecognitionEvent.Started,
            SpeechRecognitionEvent.Destroyed,
            -> state
        }
    }

    private fun SpeechRecognitionState.next(
        phase: SpeechRecognitionPhase,
        waveformLevel: Float = this.waveformLevel,
    ) = copy(
        revision = revision + 1L,
        phase = phase,
        waveformLevel = waveformLevel,
    )
}

object SpeechRmsNormalizer {
    /**
     * Android services use implementation-defined RMS ranges. This bounded curve gives useful
     * motion for the common -2..10 dB interval without ever trusting an unbounded provider value.
     */
    fun normalize(rmsDb: Float): Float {
        if (!rmsDb.isFinite()) return 0f
        val linear = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        return kotlin.math.sqrt(linear)
    }
}

/**
 * Process-local strictly increasing speech session ids.
 *
 * A single instance can be shared by multiple recognition backends. Explicit reservations let an
 * individual backend reject reuse while a service-level instance remains the source of truth.
 */
class SpeechSessionIdSequence(initialValue: Long = 0L) {
    private val highest = AtomicLong(initialValue)

    init {
        require(initialValue >= 0L) { "initialValue must not be negative" }
    }

    fun next(): Long {
        while (true) {
            val current = highest.get()
            check(current < Long.MAX_VALUE) { "speech session id space exhausted" }
            val next = current + 1L
            if (highest.compareAndSet(current, next)) return next
        }
    }

    fun reserve(sessionId: Long) {
        require(sessionId > 0L) { "sessionId must be positive" }
        while (true) {
            val current = highest.get()
            require(sessionId > current) {
                "sessionId must be strictly greater than the previous session id"
            }
            if (highest.compareAndSet(current, sessionId)) return
        }
    }
}
