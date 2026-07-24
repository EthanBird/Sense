package io.github.ethanbird.senseime.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

enum class PcmRecordingStopReason {
    USER_REQUESTED,
    MAX_DURATION_REACHED,
}

interface ShortPcm16RecorderListener {
    fun onRecordingStarted(sessionId: Long)

    fun onRmsChanged(
        sessionId: Long,
        speechUiDb: Float,
    )

    fun onRecordingStopped(
        sessionId: Long,
        audio: Pcm16WavAudio,
        reason: PcmRecordingStopReason,
    )

    fun onRecordingCancelled(sessionId: Long)

    fun onRecordingFailed(
        sessionId: Long,
        failure: CloudSpeechFailure,
    )
}

/**
 * Bounded AudioRecord controller for short cloud-transcription utterances.
 *
 * It records PCM16 mono at 16 kHz into one fixed byte array, emits throttled allocation-free RMS
 * updates, and creates one in-memory WAV only after stop. There is no file-system path.
 */
class ShortPcm16AudioRecorder(
    callbackExecutor: Executor,
    private val maxDurationMillis: Int = Pcm16AudioFormat.DEFAULT_MAX_DURATION_MILLIS,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(
        NamedDaemonThreadFactory("SensePcmRecorder"),
    ),
) : AutoCloseable {
    private val callbackExecutor = SerialExecutor(callbackExecutor)
    private val lock = Any()
    private var active: CaptureSession? = null
    private var destroyed = false

    init {
        Pcm16AudioFormat.maxPcmBytes(maxDurationMillis)
    }

    fun start(
        sessionId: Long,
        listener: ShortPcm16RecorderListener,
    ): Result<Unit> = runCatching {
        require(sessionId > 0L) { "sessionId must be positive" }
        val session = synchronized(lock) {
            check(!destroyed) { "recorder is destroyed" }
            check(active == null) { "recorder is busy" }
            CaptureSession(
                sessionId = sessionId,
                listener = listener,
                pcm = ByteArray(Pcm16AudioFormat.maxPcmBytes(maxDurationMillis)),
            ).also { active = it }
        }
        worker.execute { capture(session) }
    }

    fun stop(sessionId: Long): Boolean {
        val session = synchronized(lock) {
            active?.takeIf { it.sessionId == sessionId }?.also {
                it.stopRequested = true
            }
        } ?: return false
        safelyStop(session.audioRecord)
        return true
    }

    fun cancel(sessionId: Long): Boolean {
        val session = synchronized(lock) {
            active?.takeIf { it.sessionId == sessionId }?.also {
                active = null
                it.cancelled = true
            }
        } ?: return false
        safelyStop(session.audioRecord)
        dispatchRaw { session.listener.onRecordingCancelled(session.sessionId) }
        return true
    }

    override fun close() {
        val session = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            active.also {
                active = null
                it?.cancelled = true
            }
        }
        safelyStop(session?.audioRecord)
        session?.pcm?.fill(0)
        worker.shutdownNow()
    }

    private fun capture(session: CaptureSession) {
        try {
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }
            val minBufferBytes = AudioRecord.getMinBufferSize(
                Pcm16AudioFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "设备无法创建 16 kHz 单声道录音缓冲区",
                )
                return
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Pcm16AudioFormat.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufferBytes * 2, READ_CHUNK_BYTES * 4),
                )
            } catch (_: SecurityException) {
                fail(
                    session,
                    CloudSpeechFailureKind.PERMISSION_DENIED,
                    "没有麦克风权限",
                )
                return
            } catch (_: RuntimeException) {
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "设备无法初始化麦克风",
                )
                return
            }
            session.audioRecord = recorder
            if (!isCurrent(session)) {
                release(session, recorder)
                session.pcm.fill(0)
                return
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                release(session, recorder)
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "麦克风未能初始化",
                )
                return
            }

            try {
                recorder.startRecording()
            } catch (_: SecurityException) {
                release(session, recorder)
                fail(
                    session,
                    CloudSpeechFailureKind.PERMISSION_DENIED,
                    "没有麦克风权限",
                )
                return
            } catch (_: IllegalStateException) {
                release(session, recorder)
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "麦克风无法开始录音",
                )
                return
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                release(session, recorder)
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "麦克风未进入录音状态",
                )
                return
            }
            dispatchIfCurrent(session) {
                session.listener.onRecordingStarted(session.sessionId)
            }

            var written = 0
            var nextRmsNanos = 0L
            var readFailure = false
            while (isCurrent(session) && !session.stopRequested && written < session.pcm.size) {
                val requested = minOf(READ_CHUNK_BYTES, session.pcm.size - written)
                val read = try {
                    recorder.read(
                        session.pcm,
                        written,
                        requested,
                        AudioRecord.READ_BLOCKING,
                    )
                } catch (_: RuntimeException) {
                    if (session.stopRequested || session.cancelled) break
                    readFailure = true
                    break
                }
                when {
                    read > 0 -> {
                        if (read % Pcm16AudioFormat.BYTES_PER_SAMPLE != 0) {
                            readFailure = true
                            break
                        }
                        val now = System.nanoTime()
                        if (now >= nextRmsNanos) {
                            val uiDb = Pcm16Rms.speechUiDb(session.pcm, written, read)
                            dispatchIfCurrent(session) {
                                session.listener.onRmsChanged(session.sessionId, uiDb)
                            }
                            nextRmsNanos = now + RMS_INTERVAL_NANOS
                        }
                        written += read
                    }
                    read == 0 -> Thread.yield()
                    session.stopRequested || session.cancelled -> break
                    else -> {
                        readFailure = true
                        break
                    }
                }
            }
            safelyStop(recorder)
            release(session, recorder)

            if (!isCurrent(session)) {
                session.pcm.fill(0)
                return
            }
            if (readFailure) {
                fail(
                    session,
                    CloudSpeechFailureKind.RECORDER_UNAVAILABLE,
                    "录音流意外中断",
                )
                return
            }
            if (written == 0) {
                fail(
                    session,
                    CloudSpeechFailureKind.NO_AUDIO,
                    "没有录到可转写的语音",
                )
                return
            }

            val wav = try {
                Pcm16WavEncoder.encode(session.pcm, written)
            } catch (_: RuntimeException) {
                fail(
                    session,
                    CloudSpeechFailureKind.INTERNAL,
                    "无法封装录音数据",
                )
                return
            } finally {
                session.pcm.fill(0)
            }
            val reason = if (written >= session.pcm.size) {
                PcmRecordingStopReason.MAX_DURATION_REACHED
            } else {
                PcmRecordingStopReason.USER_REQUESTED
            }
            if (finish(session)) {
                dispatchRaw {
                    session.listener.onRecordingStopped(session.sessionId, wav, reason)
                }
            } else {
                wav.erase()
            }
        } catch (_: Exception) {
            fail(
                session,
                CloudSpeechFailureKind.INTERNAL,
                "录音控制器发生内部错误",
            )
        } finally {
            session.audioRecord?.let {
                safelyStop(it)
                it.release()
            }
            session.audioRecord = null
            if (!isCurrent(session)) session.pcm.fill(0)
        }
    }

    private fun fail(
        session: CaptureSession,
        kind: CloudSpeechFailureKind,
        message: String,
    ) {
        session.pcm.fill(0)
        if (!finish(session)) return
        dispatchRaw {
            session.listener.onRecordingFailed(
                session.sessionId,
                CloudSpeechFailure(kind = kind, message = message),
            )
        }
    }

    private fun finish(session: CaptureSession): Boolean = synchronized(lock) {
        if (active !== session || session.cancelled) return@synchronized false
        active = null
        true
    }

    private fun isCurrent(session: CaptureSession): Boolean = synchronized(lock) {
        !destroyed && active === session && !session.cancelled
    }

    private fun dispatchIfCurrent(
        session: CaptureSession,
        callback: () -> Unit,
    ) {
        if (!isCurrent(session)) return
        callbackExecutor.execute {
            if (isCurrent(session)) callback()
        }
    }

    private fun dispatchRaw(callback: () -> Unit) {
        callbackExecutor.execute(callback)
    }

    private fun safelyStop(recorder: AudioRecord?) {
        if (recorder == null) return
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
    }

    private fun release(
        session: CaptureSession,
        recorder: AudioRecord,
    ) {
        if (session.audioRecord === recorder) session.audioRecord = null
        runCatching { recorder.release() }
    }

    private class CaptureSession(
        val sessionId: Long,
        val listener: ShortPcm16RecorderListener,
        val pcm: ByteArray,
    ) {
        @Volatile
        var stopRequested = false

        @Volatile
        var cancelled = false

        @Volatile
        var audioRecord: AudioRecord? = null
    }

    private companion object {
        const val READ_CHUNK_BYTES = 1_024
        const val RMS_INTERVAL_NANOS = 40_000_000L
    }
}

internal class NamedDaemonThreadFactory(
    private val threadName: String,
) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, threadName).apply {
            isDaemon = true
        }
}
