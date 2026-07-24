package io.github.ethanbird.senseime.speech

import java.util.concurrent.Executor

fun interface CloudSpeechRecognitionListener {
    fun onSpeechRecognitionEvent(event: SpeechRecognitionEvent)
}

/**
 * Record-then-transcribe facade for executable cloud speech providers.
 *
 * Callers use the same monotonically increasing session ids as
 * [AndroidSpeechRecognizerController]. All microphone and HTTP callbacks pass through an
 * independent generation gate before becoming [SpeechRecognitionEvent] values.
 */
class CloudSpeechRecognitionController(
    callbackExecutor: Executor,
    maxDurationMillis: Int = Pcm16AudioFormat.DEFAULT_MAX_DURATION_MILLIS,
) : AutoCloseable {
    private val eventExecutor = SerialExecutor(callbackExecutor)
    private val recorder = ShortPcm16AudioRecorder(
        callbackExecutor = eventExecutor,
        maxDurationMillis = maxDurationMillis,
    )
    private val httpsClient = CloudSpeechHttpsClient(callbackExecutor = eventExecutor)
    private val gate = CloudSpeechSessionGate()
    private val lock = Any()
    private var active: Session? = null
    private var destroyed = false

    fun start(
        sessionId: Long,
        profile: SpeechProviderProfile,
        apiKey: CharArray,
        listener: CloudSpeechRecognitionListener,
    ): Result<Unit> = runCatching {
        profile.requireValid()
        val preset = SpeechProviderPresetCatalog.require(profile.presetId)
        require(preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
            "speech provider adapter is not available"
        }
        require(
            profile.protocol == SpeechProviderProtocol.OPENAI_TRANSCRIPTIONS ||
                profile.protocol == SpeechProviderProtocol.DEEPGRAM_LISTEN,
        ) { "profile is not handled by the cloud speech controller" }
        SpeechProviderCredentialPolicy.requireValid(apiKey)

        val session = synchronized(lock) {
            check(!destroyed) { "cloud speech controller is destroyed" }
            check(active == null) { "cloud speech controller is busy" }
            val token = checkNotNull(gate.tryBegin(sessionId)) {
                "session id must be positive, monotonic, and not already active"
            }
            Session(
                token = token,
                profile = profile,
                apiKey = apiKey.copyOf(),
                listener = listener,
            ).also { active = it }
        }
        emitRaw(session.listener, SpeechRecognitionEvent.Started(sessionId, false))
        recorder.start(sessionId, RecorderBridge(session))
            .getOrElse {
                finishFailed(
                    session,
                    CloudSpeechFailure(
                        kind = CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                        message = "无法启动麦克风录音",
                    ),
                )
                throw it
            }
    }

    fun stop(sessionId: Long): Boolean {
        val session = synchronized(lock) {
            active?.takeIf {
                it.token.sessionId == sessionId && it.phase == SessionPhase.RECORDING
            }?.also {
                it.phase = SessionPhase.STOPPING
            }
        } ?: return false
        emitIfCurrent(
            session,
            SpeechRecognitionEvent.ProcessingRequested(session.token.sessionId),
        )
        // The recorder may already have completed its last blocking read and queued the WAV
        // callback. In that narrow window stop() returns false, but the authoritative callback is
        // still pending and must win instead of being replaced by a synthetic failure.
        recorder.stop(sessionId)
        return true
    }

    fun cancel(sessionId: Long): Boolean {
        val session = synchronized(lock) {
            val current = active?.takeIf { it.token.sessionId == sessionId } ?: return false
            if (gate.cancel(sessionId) == null) return false
            active = null
            current
        }
        recorder.cancel(sessionId)
        session.httpCall?.cancel()
        session.eraseKey()
        emitRaw(session.listener, SpeechRecognitionEvent.Cancelled(sessionId))
        return true
    }

    override fun close() {
        val session = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            gate.invalidateAll()
            active.also { active = null }
        }
        session?.let {
            recorder.cancel(it.token.sessionId)
            it.httpCall?.cancel()
            it.eraseKey()
            emitRaw(it.listener, SpeechRecognitionEvent.Destroyed)
        }
        recorder.close()
        httpsClient.close()
    }

    private fun beginUpload(
        session: Session,
        audio: Pcm16WavAudio,
    ) {
        val priorPhase = synchronized(lock) {
            if (active !== session || !gate.isCurrent(session.token)) {
                null
            } else {
                val previous = session.phase
                session.phase = SessionPhase.UPLOADING
                previous
            }
        }
        if (priorPhase == null) {
            audio.erase()
            return
        }
        emitIfCurrent(
            session,
            SpeechRecognitionEvent.SpeechEnded(session.token.sessionId),
        )
        if (priorPhase != SessionPhase.STOPPING) {
            emitIfCurrent(
                session,
                SpeechRecognitionEvent.ProcessingRequested(session.token.sessionId),
            )
        }

        val request = try {
            CloudSpeechRequestFactory.create(session.profile, audio).getOrThrow()
        } catch (_: RuntimeException) {
            finishFailed(
                session,
                CloudSpeechFailure(
                    kind = CloudSpeechFailureKind.INVALID_CONFIGURATION,
                    message = "无法创建语音 Provider 请求",
                ),
            )
            return
        } finally {
            audio.erase()
        }
        val call = httpsClient.transcribe(
            request = request,
            apiKey = session.apiKey,
            callback = CloudSpeechHttpCallback { result ->
                when (result) {
                    is CloudSpeechHttpResult.Success -> finishSucceeded(
                        session,
                        result.transcript,
                    )
                    is CloudSpeechHttpResult.Failure -> {
                        if (result.failure.kind == CloudSpeechFailureKind.CANCELLED) {
                            finishCancelled(session)
                        } else {
                            finishFailed(session, result.failure)
                        }
                    }
                }
            },
        ).getOrElse {
            request.eraseBody()
            finishFailed(
                session,
                CloudSpeechFailure(
                    kind = CloudSpeechFailureKind.INTERNAL,
                    message = "无法启动语音 HTTPS 请求",
                ),
            )
            return
        }
        session.eraseKey()
        synchronized(lock) {
            if (active === session && gate.isCurrent(session.token)) {
                session.httpCall = call
            } else {
                call.cancel()
            }
        }
    }

    private fun finishSucceeded(
        session: Session,
        transcript: CloudSpeechTranscript,
    ) {
        val terminal = SpeechRecognitionEvent.FinalResult(
            sessionId = session.token.sessionId,
            text = transcript.text,
            alternatives = transcript.alternatives,
        )
        if (complete(session)) emitRaw(session.listener, terminal)
    }

    private fun finishFailed(
        session: Session,
        failure: CloudSpeechFailure,
    ) {
        val terminal = SpeechRecognitionEvent.Failed(
            sessionId = session.token.sessionId,
            failure = failure.toRecognitionFailure(),
        )
        if (complete(session)) emitRaw(session.listener, terminal)
    }

    private fun finishCancelled(session: Session) {
        if (complete(session)) {
            emitRaw(
                session.listener,
                SpeechRecognitionEvent.Cancelled(session.token.sessionId),
            )
        }
    }

    private fun complete(session: Session): Boolean = synchronized(lock) {
        if (active !== session || !gate.complete(session.token)) return@synchronized false
        active = null
        session.eraseKey()
        true
    }

    private fun emitIfCurrent(
        session: Session,
        event: SpeechRecognitionEvent,
    ) {
        val authoritative = synchronized(lock) {
            active === session && gate.isCurrent(session.token)
        }
        if (authoritative) emitRaw(session.listener, event)
    }

    private fun emitRaw(
        listener: CloudSpeechRecognitionListener,
        event: SpeechRecognitionEvent,
    ) {
        eventExecutor.execute {
            runCatching { listener.onSpeechRecognitionEvent(event) }
        }
    }

    private inner class RecorderBridge(
        private val session: Session,
    ) : ShortPcm16RecorderListener {
        override fun onRecordingStarted(sessionId: Long) {
            emitIfCurrent(session, SpeechRecognitionEvent.Ready(sessionId))
            emitIfCurrent(session, SpeechRecognitionEvent.SpeechBegan(sessionId))
        }

        override fun onRmsChanged(
            sessionId: Long,
            speechUiDb: Float,
        ) {
            emitIfCurrent(
                session,
                SpeechRecognitionEvent.RmsChanged(sessionId, speechUiDb),
            )
        }

        override fun onRecordingStopped(
            sessionId: Long,
            audio: Pcm16WavAudio,
            reason: PcmRecordingStopReason,
        ) {
            beginUpload(session, audio)
        }

        override fun onRecordingCancelled(sessionId: Long) {
            finishCancelled(session)
        }

        override fun onRecordingFailed(
            sessionId: Long,
            failure: CloudSpeechFailure,
        ) {
            finishFailed(session, failure)
        }
    }

    private class Session(
        val token: CloudSpeechSessionToken,
        val profile: SpeechProviderProfile,
        val apiKey: CharArray,
        val listener: CloudSpeechRecognitionListener,
    ) {
        var phase: SessionPhase = SessionPhase.RECORDING
        var httpCall: CloudSpeechHttpCall? = null

        fun eraseKey() {
            apiKey.fill('\u0000')
        }
    }

    private enum class SessionPhase {
        RECORDING,
        STOPPING,
        UPLOADING,
    }

    private companion object {
    }
}

private fun CloudSpeechFailure.toRecognitionFailure(): SpeechRecognitionFailure {
    val recognitionKind = when (kind) {
        CloudSpeechFailureKind.PERMISSION_DENIED ->
            SpeechRecognitionFailureKind.PERMISSION_DENIED
        CloudSpeechFailureKind.RECORDER_UNAVAILABLE ->
            SpeechRecognitionFailureKind.RECOGNIZER_UNAVAILABLE
        CloudSpeechFailureKind.NO_AUDIO ->
            SpeechRecognitionFailureKind.NO_MATCH
        CloudSpeechFailureKind.UNSUPPORTED_PROVIDER ->
            SpeechRecognitionFailureKind.UNSUPPORTED_PROVIDER
        CloudSpeechFailureKind.NETWORK ->
            SpeechRecognitionFailureKind.NETWORK
        CloudSpeechFailureKind.TIMEOUT ->
            SpeechRecognitionFailureKind.TIMEOUT
        CloudSpeechFailureKind.SERVER,
        CloudSpeechFailureKind.QUOTA,
        CloudSpeechFailureKind.RATE_LIMIT,
        -> SpeechRecognitionFailureKind.SERVER
        CloudSpeechFailureKind.INVALID_CONFIGURATION,
        CloudSpeechFailureKind.AUTHENTICATION,
        CloudSpeechFailureKind.PROTOCOL,
        -> SpeechRecognitionFailureKind.CLIENT
        CloudSpeechFailureKind.CANCELLED,
        CloudSpeechFailureKind.RESPONSE_TOO_LARGE,
        CloudSpeechFailureKind.INTERNAL,
        -> SpeechRecognitionFailureKind.UNKNOWN
    }
    return SpeechRecognitionFailure(
        kind = recognitionKind,
        providerErrorCode = httpStatus,
        message = message,
    )
}
