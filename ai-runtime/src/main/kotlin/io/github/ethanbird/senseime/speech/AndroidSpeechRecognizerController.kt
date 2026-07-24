package io.github.ethanbird.senseime.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-session Android SpeechRecognizer controller for an input method.
 *
 * All SpeechRecognizer creation and calls are serialized on the main looper. Each start owns a new
 * recognizer instance so late callbacks from a cancelled session can be rejected by session id.
 * Cloud profiles are deliberately rejected until their protocol adapters are actually implemented.
 */
class AndroidSpeechRecognizerController(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    fun interface Listener {
        fun onStateChanged(state: SpeechRecognitionState)
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionIds = SpeechSessionIdSequence()
    private val requestedSessionId = AtomicLong(0L)

    @Volatile
    private var currentState = SpeechRecognitionState()
    private var active: ActiveSession? = null

    val state: SpeechRecognitionState
        get() = currentState

    /**
     * Starts recognition and returns its monotonic session id immediately.
     *
     * The state callback may arrive later when invoked off the main thread.
     */
    fun start(profile: SpeechProviderProfile): Long {
        val sessionId = reserveNextSessionId()
        enqueueStart(sessionId, profile)
        return sessionId
    }

    /**
     * Starts recognition with a caller-owned monotonic session id.
     *
     * Input-method integrations use this overload so system and cloud recognizers share one
     * sequence. The legacy [start] overload remains available for callers that only use the
     * Android recognizer.
     */
    fun start(
        sessionId: Long,
        profile: SpeechProviderProfile,
    ): Long {
        reserveSessionId(sessionId)
        enqueueStart(sessionId, profile)
        return sessionId
    }

    private fun enqueueStart(
        sessionId: Long,
        profile: SpeechProviderProfile,
    ) {
        requestedSessionId.set(sessionId)
        // Always cross one main-loop boundary. This lets the keyboard publish a surface carrying
        // [sessionId] before any synchronous platform callback can arrive.
        mainHandler.post {
            if (requestedSessionId.get() == sessionId) startOnMain(sessionId, profile)
        }
    }

    private fun reserveNextSessionId(): Long {
        return sessionIds.next()
    }

    private fun reserveSessionId(sessionId: Long) {
        sessionIds.reserve(sessionId)
    }

    /** Requests a final result for the current utterance. */
    fun stop() {
        runOnMain {
            val session = active ?: return@runOnMain
            dispatch(SpeechRecognitionEvent.ProcessingRequested(session.id))
            runCatching { session.recognizer.stopListening() }
                .onFailure {
                    failAndRelease(
                        session,
                        SpeechRecognitionFailure(
                            kind = SpeechRecognitionFailureKind.CLIENT,
                            message = "无法结束系统语音识别",
                        ),
                    )
                }
        }
    }

    /** Cancels recognition without committing a result. */
    fun cancel() {
        requestedSessionId.set(0L)
        runOnMain { cancelActive(dispatchCancellation = true) }
    }

    override fun close() {
        destroy()
    }

    fun destroy() {
        requestedSessionId.set(0L)
        runOnMain {
            if (currentState.phase == SpeechRecognitionPhase.DESTROYED) return@runOnMain
            cancelActive(dispatchCancellation = false)
            dispatch(SpeechRecognitionEvent.Destroyed)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun startOnMain(
        sessionId: Long,
        profile: SpeechProviderProfile,
    ) {
        if (currentState.phase == SpeechRecognitionPhase.DESTROYED) return
        cancelActive(dispatchCancellation = true)

        val profileError = profile.validate().errors.firstOrNull()
        if (profileError != null) {
            dispatch(SpeechRecognitionEvent.Started(sessionId, false))
            failWithoutRecognizer(
                sessionId,
                SpeechRecognitionFailure(
                    kind = SpeechRecognitionFailureKind.CLIENT,
                    message = "语音配置无效：${profileError.path} ${profileError.message}",
                ),
            )
            return
        }

        val preset = SpeechProviderPresetCatalog.require(profile.presetId)
        if (!preset.canTranscribe || profile.protocol != SpeechProviderProtocol.ANDROID_SYSTEM) {
            dispatch(SpeechRecognitionEvent.Started(sessionId, false))
            failWithoutRecognizer(
                sessionId,
                SpeechRecognitionFailure(
                    kind = SpeechRecognitionFailureKind.UNSUPPORTED_PROVIDER,
                    message = preset.capabilityNotice ?: "当前版本尚未启用该语音适配器",
                ),
            )
            return
        }

        if (
            applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            dispatch(SpeechRecognitionEvent.Started(sessionId, false))
            failWithoutRecognizer(
                sessionId,
                SpeechRecognitionFailure(
                    kind = SpeechRecognitionFailureKind.PERMISSION_DENIED,
                    message = "请先授予麦克风权限",
                ),
            )
            return
        }

        val recognizerResult = createRecognizer(profile.preferOnDevice)
        val recognizer = recognizerResult.getOrElse {
            dispatch(SpeechRecognitionEvent.Started(sessionId, false))
            failWithoutRecognizer(
                sessionId,
                SpeechRecognitionFailure(
                    kind = SpeechRecognitionFailureKind.RECOGNIZER_UNAVAILABLE,
                    message = "无法创建系统语音识别服务",
                ),
            )
            return
        }
        val session = ActiveSession(
            id = sessionId,
            recognizer = recognizer.recognizer,
            usingOnDeviceRecognizer = recognizer.usingOnDeviceRecognizer,
        )
        active = session
        dispatch(
            SpeechRecognitionEvent.Started(
                sessionId = sessionId,
                usingOnDeviceRecognizer = session.usingOnDeviceRecognizer,
            ),
        )
        session.recognizer.setRecognitionListener(SessionRecognitionListener(sessionId))
        runCatching {
            session.recognizer.startListening(
                recognitionIntent(
                    profile = profile,
                    usingOnDeviceRecognizer = session.usingOnDeviceRecognizer,
                ),
            )
        }.onFailure {
            failAndRelease(
                session,
                SpeechRecognitionFailure(
                    kind = SpeechRecognitionFailureKind.CLIENT,
                    message = "无法启动系统语音识别",
                ),
            )
        }
    }

    private fun createRecognizer(
        preferOnDevice: Boolean,
    ): Result<CreatedRecognizer> = runCatching creation@{
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onDeviceAvailable = runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)
            }.getOrDefault(false)
            if (onDeviceAvailable) {
                val onDevice = runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
                }.getOrNull()
                if (onDevice != null) return@creation CreatedRecognizer(onDevice, true)
            }
        }
        check(
            runCatching {
                SpeechRecognizer.isRecognitionAvailable(applicationContext)
            }.getOrDefault(false),
        ) {
            "No system recognition service"
        }
        CreatedRecognizer(
            recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext),
            usingOnDeviceRecognizer = false,
        )
    }

    private fun recognitionIntent(
        profile: SpeechProviderProfile,
        usingOnDeviceRecognizer: Boolean,
    ) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, profile.languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, profile.interimResults)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        if (usingOnDeviceRecognizer) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun cancelActive(dispatchCancellation: Boolean) {
        val session = active ?: return
        active = null
        runCatching { session.recognizer.cancel() }
        runCatching { session.recognizer.destroy() }
        if (dispatchCancellation) {
            dispatch(SpeechRecognitionEvent.Cancelled(session.id))
        }
    }

    private fun failWithoutRecognizer(
        sessionId: Long,
        failure: SpeechRecognitionFailure,
    ) {
        dispatch(SpeechRecognitionEvent.Failed(sessionId, failure))
        requestedSessionId.compareAndSet(sessionId, 0L)
    }

    private fun failAndRelease(
        session: ActiveSession,
        failure: SpeechRecognitionFailure,
    ) {
        if (active?.id != session.id) return
        dispatch(SpeechRecognitionEvent.Failed(session.id, failure))
        release(session)
    }

    private fun release(session: ActiveSession) {
        if (active?.id != session.id) return
        active = null
        requestedSessionId.compareAndSet(session.id, 0L)
        runCatching { session.recognizer.destroy() }
    }

    private fun dispatch(event: SpeechRecognitionEvent) {
        val next = SpeechRecognitionReducer.reduce(currentState, event)
        if (next === currentState || next == currentState) return
        currentState = next
        runCatching { listener.onStateChanged(next) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private inner class SessionRecognitionListener(
        private val sessionId: Long,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active?.id == sessionId) dispatch(SpeechRecognitionEvent.Ready(sessionId))
        }

        override fun onBeginningOfSpeech() {
            if (active?.id == sessionId) dispatch(SpeechRecognitionEvent.SpeechBegan(sessionId))
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (active?.id == sessionId) {
                dispatch(SpeechRecognitionEvent.RmsChanged(sessionId, rmsdB))
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (active?.id == sessionId) dispatch(SpeechRecognitionEvent.SpeechEnded(sessionId))
        }

        override fun onError(error: Int) {
            val session = active?.takeIf { it.id == sessionId } ?: return
            failAndRelease(session, mapRecognizerError(error))
        }

        override fun onResults(results: Bundle?) {
            val session = active?.takeIf { it.id == sessionId } ?: return
            val candidates = resultCandidates(results)
            val finalText = candidates.firstOrNull().orEmpty()
            if (finalText.isBlank()) {
                failAndRelease(
                    session,
                    SpeechRecognitionFailure(
                        kind = SpeechRecognitionFailureKind.NO_MATCH,
                        message = "没有识别到可输入的文字",
                    ),
                )
                return
            }
            dispatch(
                SpeechRecognitionEvent.FinalResult(
                    sessionId = sessionId,
                    text = finalText,
                    alternatives = candidates.drop(1),
                ),
            )
            release(session)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (active?.id != sessionId) return
            val text = resultCandidates(partialResults).firstOrNull() ?: return
            dispatch(SpeechRecognitionEvent.PartialResult(sessionId, text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private data class ActiveSession(
        val id: Long,
        val recognizer: SpeechRecognizer,
        val usingOnDeviceRecognizer: Boolean,
    )

    private data class CreatedRecognizer(
        val recognizer: SpeechRecognizer,
        val usingOnDeviceRecognizer: Boolean,
    )

    companion object {
        private const val MAX_RESULTS = 5
        private const val MAX_TRANSCRIPT_CHARS = 16_384

        private fun resultCandidates(results: Bundle?): List<String> =
            results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim().take(MAX_TRANSCRIPT_CHARS) }
                .filter(String::isNotBlank)
                .distinct()

        private fun mapRecognizerError(error: Int): SpeechRecognitionFailure {
            val kind = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    SpeechRecognitionFailureKind.PERMISSION_DENIED

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    SpeechRecognitionFailureKind.BUSY

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> SpeechRecognitionFailureKind.NETWORK

                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    SpeechRecognitionFailureKind.TIMEOUT

                SpeechRecognizer.ERROR_NO_MATCH ->
                    SpeechRecognitionFailureKind.NO_MATCH

                ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT,
                ERROR_LANGUAGE_UNAVAILABLE_COMPAT,
                -> SpeechRecognitionFailureKind.LANGUAGE_UNSUPPORTED

                SpeechRecognizer.ERROR_SERVER,
                ERROR_SERVER_DISCONNECTED_COMPAT,
                ERROR_TOO_MANY_REQUESTS_COMPAT,
                -> SpeechRecognitionFailureKind.SERVER

                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_AUDIO,
                -> SpeechRecognitionFailureKind.CLIENT

                else -> SpeechRecognitionFailureKind.UNKNOWN
            }
            val message = when (kind) {
                SpeechRecognitionFailureKind.PERMISSION_DENIED -> "麦克风权限不足"
                SpeechRecognitionFailureKind.RECOGNIZER_UNAVAILABLE -> "系统语音识别不可用"
                SpeechRecognitionFailureKind.UNSUPPORTED_PROVIDER -> "当前语音提供商尚未启用"
                SpeechRecognitionFailureKind.BUSY -> "系统语音识别正忙，请稍后重试"
                SpeechRecognitionFailureKind.NETWORK -> "系统语音识别网络异常"
                SpeechRecognitionFailureKind.TIMEOUT -> "没有检测到语音"
                SpeechRecognitionFailureKind.NO_MATCH -> "没有识别到可输入的文字"
                SpeechRecognitionFailureKind.LANGUAGE_UNSUPPORTED -> "系统不支持当前识别语言"
                SpeechRecognitionFailureKind.CLIENT -> "系统语音识别客户端异常"
                SpeechRecognitionFailureKind.SERVER -> "系统语音识别服务异常"
                SpeechRecognitionFailureKind.UNKNOWN -> "系统语音识别失败"
            }
            return SpeechRecognitionFailure(
                kind = kind,
                providerErrorCode = error,
                message = message,
            )
        }

        // SpeechRecognizer constants added in API 31. Local values avoid unsafe field access on 29/30.
        private const val ERROR_TOO_MANY_REQUESTS_COMPAT = 10
        private const val ERROR_SERVER_DISCONNECTED_COMPAT = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED_COMPAT = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE_COMPAT = 13
    }
}
