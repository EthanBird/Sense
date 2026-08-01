package io.github.ethanbird.senseime.speech

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in end-to-end probe: set SENSE_SOGOU_ASR_LIVE_PCM to raw 16 kHz mono PCM16. */
class SogouAsrLiveProbeTest {
    @Test
    fun `live SRSS endpoint transcribes PCM fixture`() {
        val fixturePath = System.getenv("SENSE_SOGOU_ASR_LIVE_PCM")
        assumeTrue(!fixturePath.isNullOrBlank())
        val pcm = File(requireNotNull(fixturePath)).readBytes()
        val wav = Pcm16WavEncoder.encode(pcm)
        val terminal = AtomicReference<CloudSpeechHttpResult>()
        val partials = mutableListOf<String>()
        val latch = CountDownLatch(1)
        val client = SogouAsrWebSocketClient(
            callbackExecutor = Executor(Runnable::run),
        )
        try {
            client.transcribe(
                profile = SpeechProviderPresetCatalog
                    .require(SpeechProviderPresetCatalog.SOGOU)
                    .defaultProfile("zh-CN"),
                audio = wav,
                callback = object : SogouAsrCallback {
                    override fun onPartialResult(transcript: CloudSpeechTranscript) {
                        partials += transcript.text
                    }

                    override fun onResult(result: CloudSpeechHttpResult) {
                        terminal.set(result)
                        latch.countDown()
                    }
                },
            ).getOrThrow()
            wav.erase()

            assertTrue("SRSS live probe timed out", latch.await(75, TimeUnit.SECONDS))
            val result = terminal.get()
            assertNotNull(result)
            val success = result as? CloudSpeechHttpResult.Success
                ?: throw AssertionError("SRSS live probe failed: $result")
            assertTrue(success.transcript.text.isNotBlank())
            assertTrue(partials.isNotEmpty())
            System.getenv("SENSE_SOGOU_ASR_EXPECTED")
                ?.takeIf(String::isNotBlank)
                ?.let { assertEquals(it, success.transcript.text) }
        } finally {
            pcm.fill(0)
            wav.erase()
            client.close()
        }
    }

    @Test
    fun `streaming optimization returns text before microphone input ends`() {
        val fixturePath = System.getenv("SENSE_SOGOU_ASR_LIVE_PCM")
        assumeTrue(!fixturePath.isNullOrBlank())
        val pcm = File(requireNotNull(fixturePath)).readBytes()
        val terminal = AtomicReference<CloudSpeechHttpResult>()
        val inputFinished = AtomicBoolean(false)
        val partialBeforeFinish = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val client = SogouAsrWebSocketClient(
            callbackExecutor = Executor(Runnable::run),
        )
        try {
            val call = client.startStreaming(
                profile = SpeechProviderPresetCatalog
                    .require(SpeechProviderPresetCatalog.SOGOU)
                    .defaultProfile("zh-CN")
                    .copy(streamingOptimization = true, interimResults = true),
                callback = object : SogouAsrCallback {
                    override fun onPartialResult(transcript: CloudSpeechTranscript) {
                        if (transcript.text.isNotBlank() && !inputFinished.get()) {
                            partialBeforeFinish.set(true)
                        }
                    }

                    override fun onResult(result: CloudSpeechHttpResult) {
                        terminal.set(result)
                        latch.countDown()
                    }
                },
            ).getOrThrow()

            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + SogouOpusPacketEncoder.FRAME_PCM_BYTES, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                try {
                    assertTrue("SRSS live PCM chunk was rejected", call.sendPcm(chunk))
                } finally {
                    chunk.fill(0)
                }
                offset = end
                Thread.sleep(FRAME_DURATION_MILLIS)
            }
            inputFinished.set(true)
            assertTrue("SRSS live input could not be finished", call.finishInput())

            assertTrue("SRSS streaming probe timed out", latch.await(75, TimeUnit.SECONDS))
            val result = terminal.get()
            val success = result as? CloudSpeechHttpResult.Success
                ?: throw AssertionError("SRSS streaming probe failed: $result")
            assertTrue(success.transcript.text.isNotBlank())
            assertTrue(
                "SRSS did not return partial text until after microphone input ended",
                partialBeforeFinish.get(),
            )
            System.getenv("SENSE_SOGOU_ASR_EXPECTED")
                ?.takeIf(String::isNotBlank)
                ?.let { assertEquals(it, success.transcript.text) }
        } finally {
            pcm.fill(0)
            client.close()
        }
    }

    private companion object {
        const val FRAME_DURATION_MILLIS = 20L
    }
}
