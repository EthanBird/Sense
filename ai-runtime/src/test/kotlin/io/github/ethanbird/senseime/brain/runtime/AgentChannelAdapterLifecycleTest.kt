package io.github.ethanbird.senseime.brain.runtime

import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChannelAdapterLifecycleTest {
    @Test
    fun `compat failed future completes exceptionally on api 29 path`() {
        val failure = IllegalStateException("transport unavailable")
        val future = failedAgentChannelFuture<String>(failure)
        val thrown = runCatching { future.get() }.exceptionOrNull()
        assertTrue(thrown is ExecutionException)
        assertTrue(thrown?.cause === failure)
    }

    @Test
    fun `compat timeout preserves completion and times out stalled source`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val completed = CompletableFuture.completedFuture("connected")
                .withAgentChannelTimeout(scheduler, 1, TimeUnit.SECONDS)
            assertEquals("connected", completed.get(1, TimeUnit.SECONDS))

            val stalled = CompletableFuture<String>()
            val timed = stalled.withAgentChannelTimeout(scheduler, 20, TimeUnit.MILLISECONDS)
            val thrown = runCatching { timed.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(thrown is ExecutionException)
            assertTrue(thrown?.cause is TimeoutException)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `feishu channel disables sdk chat batching`() {
        assertTrue(!feishuIndependentMessageSafety().isChatQueueEnabled)
    }

    @Test
    fun `feishu close is non blocking even before start`() {
        val adapter = FeishuAgentChannelAdapter(
            appId = "cli_test",
            appSecret = "secret",
            domain = FeishuDomain.FEISHU,
        )
        val elapsed = measureNanoTime { adapter.close() }
        adapter.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed) < 1_000L)
    }

    @Test
    fun `pending recovery gets failed future while platform transport is disconnected`() {
        val source = AgentChannelSource(
            channel = AgentChannelType.FEISHU,
            chatId = "chat",
            peerId = "peer",
            messageId = "message",
        )
        val feishu = FeishuAgentChannelAdapter(
            appId = "cli_test",
            appSecret = "secret",
            domain = FeishuDomain.FEISHU,
        )
        assertTrue(feishu.sendText(source, "恢复草稿").isCompletedExceptionally)
        assertTrue(feishu.editText(source, "remote", "恢复草稿").isCompletedExceptionally)
        feishu.close()

        val telegram = TelegramAgentChannelAdapter(
            token = "123456:abcdefghijklmnopqrstuvwxyzABCD",
            initialOffset = 0,
            advanceOffset = {},
        )
        assertTrue(
            telegram.sendText(source.copy(channel = AgentChannelType.TELEGRAM), "恢复草稿")
                .isCompletedExceptionally,
        )
        telegram.close()
    }

    @Test
    fun `feishu admission lane preserves fifo under concurrent callbacks`() {
        val lane = FeishuAdmissionLane(capacity = 2)
        val executor = Executors.newFixedThreadPool(3)
        val firstAttempt = CountDownLatch(1)
        val releaseFirst = AtomicBoolean(false)
        val admitted = Collections.synchronizedList(mutableListOf<String>())
        val listener = AgentChannelInboundListener { inbound ->
            if (inbound.source.messageId == "1" && !releaseFirst.get()) {
                firstAttempt.countDown()
                AgentChannelAdmission.RETRY_LATER
            } else {
                admitted += inbound.source.messageId
                AgentChannelAdmission.ADMITTED
            }
        }
        fun inbound(id: String) = AgentChannelInbound(
            source = AgentChannelSource(
                channel = AgentChannelType.FEISHU,
                chatId = "chat",
                peerId = "peer",
                messageId = id,
            ),
            text = id,
            receivedAtEpochMs = id.toLong(),
        )
        val first = executor.submit<Boolean> { lane.admit(inbound("1"), listener) { true } }
        assertTrue(firstAttempt.await(2, TimeUnit.SECONDS))
        val second = executor.submit<Boolean> { lane.admit(inbound("2"), listener) { true } }
        awaitQueued(lane, 1)
        val third = executor.submit<Boolean> { lane.admit(inbound("3"), listener) { true } }
        awaitQueued(lane, 2)
        releaseFirst.set(true)
        assertTrue(first.get(3, TimeUnit.SECONDS))
        assertTrue(second.get(3, TimeUnit.SECONDS))
        assertTrue(third.get(3, TimeUnit.SECONDS))
        assertEquals(listOf("1", "2", "3"), admitted)
        lane.close()
        executor.shutdownNow()
    }

    @Test
    fun `telegram close releases transport even before start`() {
        val adapter = TelegramAgentChannelAdapter(
            token = "123456:abcdefghijklmnopqrstuvwxyzABCD",
            initialOffset = 0,
            advanceOffset = {},
        )
        val elapsed = measureNanoTime { adapter.close() }
        adapter.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed) < 1_000L)
    }

    @Test
    fun `telegram offset advances in memory only after durable cursor write`() {
        var inMemory = 10L
        val failure = runCatching {
            inMemory = TelegramOffsetCommit.advance(inMemory, 11L) {
                error("disk write failed")
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(10L, inMemory)
        inMemory = TelegramOffsetCommit.advance(inMemory, 11L) { }
        assertEquals(11L, inMemory)
    }

    @Test
    fun `telegram truncation preserves emoji surrogate pair`() {
        val value = "a".repeat(3_979) + "😀" + "b".repeat(30)
        val delivered = telegramTextForDelivery(value)
        val body = delivered.removeSuffix("\n…")
        assertTrue(body.isNotEmpty())
        assertTrue(!Character.isHighSurrogate(body.last()))
        assertTrue(delivered.endsWith("\n…"))
    }

    @Test
    fun `telegram repeated final edit after crash is idempotent success`() {
        assertTrue(
            TelegramEditResponse.isIdempotentSuccess(
                httpCode = 400,
                description = "Bad Request: message is not modified: identical content",
            ),
        )
        assertTrue(
            !TelegramEditResponse.isIdempotentSuccess(
                httpCode = 400,
                description = "Bad Request: message to edit not found",
            ),
        )
        assertTrue(
            !TelegramEditResponse.isIdempotentSuccess(
                httpCode = 401,
                description = "Bad Request: message is not modified",
            ),
        )
    }


    private fun awaitQueued(lane: FeishuAdmissionLane, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lane.queuedCountForTest() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        assertEquals(expected, lane.queuedCountForTest())
    }
}
