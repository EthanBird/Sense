package io.github.ethanbird.senseime.speech

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
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
}
