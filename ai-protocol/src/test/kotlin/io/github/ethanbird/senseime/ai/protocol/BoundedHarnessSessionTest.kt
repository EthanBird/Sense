package io.github.ethanbird.senseime.ai.protocol

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedHarnessSessionTest {
    @Test
    fun streamsInOrderAndAcceptsExactlyOneTerminalPatch() {
        val fake = DeterministicFakeHarness(validRequest())

        fake.start()
        fake.status(HarnessPhase.UNDERSTANDING, "正在理解输入")
        fake.previewDelta("你好")
        fake.previewDelta("，世界")
        fake.finalPatch(validPatch())
        val duplicate = fake.providerFailure()

        assertEquals(
            listOf(
                AiEvent.Started::class,
                AiEvent.Status::class,
                AiEvent.PreviewDelta::class,
                AiEvent.PreviewDelta::class,
                AiEvent.FinalPatch::class,
            ),
            fake.events.map { it::class },
        )
        assertEquals(BoundedHarnessState.FINAL_PATCH, fake.state)
        assertEquals(1, fake.terminalEvents.size)
        assertTrue(duplicate is HarnessDispatch.Dropped)
        assertEquals(HarnessDropReason.TERMINATED, (duplicate as HarnessDispatch.Dropped).reason)
    }

    @Test
    fun pointerReleaseSynchronouslyCancelsAndDropsLateFinalPatch() {
        val request = validRequest()
        val fake = DeterministicFakeHarness(request)
        fake.start()
        fake.previewDelta("尚未完成")

        val cancellation = fake.release()
        val latePatch = fake.finalPatch(validPatch())

        assertTrue(cancellation is HarnessDispatch.Emitted)
        assertEquals(BoundedHarnessState.CANCELLED, fake.state)
        assertTrue(fake.transportCancellationRequested)
        assertEquals(1, fake.terminalEvents.size)
        assertTrue(fake.terminalEvents.single() is AiEvent.Cancelled)
        assertTrue(latePatch is HarnessDispatch.Dropped)
        assertEquals(HarnessDropReason.TERMINATED, (latePatch as HarnessDispatch.Dropped).reason)
        assertFalse(fake.events.any { it is AiEvent.FinalPatch })
    }

    @Test
    fun simultaneousReleaseAndFinalPatchProduceExactlyOneTerminalEvent() {
        val request = validRequest()
        val session = BoundedHarnessSession(request)
        session.start(nowMonotonicMs = 0)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<HarnessDispatch>()

        val releaseThread = thread(name = "release") {
            ready.countDown()
            start.await()
            results += session.cancel(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                reason = HarnessCancelReason.POINTER_RELEASED,
                nowMonotonicMs = 10,
            )
        }
        val finalThread = thread(name = "final-patch") {
            ready.countDown()
            start.await()
            results += session.accept(
                AiEvent.FinalPatch(
                    requestId = request.requestId,
                    runGeneration = request.runGeneration,
                    patch = validPatch(),
                ),
                nowMonotonicMs = 10,
            )
        }

        ready.await()
        start.countDown()
        releaseThread.join()
        finalThread.join()

        assertEquals(2, results.size)
        assertEquals(1, results.count { it is HarnessDispatch.Emitted })
        assertEquals(1, results.count { it is HarnessDispatch.Dropped })
        assertTrue(session.terminal?.isTerminal == true)
        assertTrue(
            session.state == BoundedHarnessState.CANCELLED ||
                session.state == BoundedHarnessState.FINAL_PATCH,
        )
    }

    @Test
    fun wrongGenerationCannotRenderOrTerminateCurrentRun() {
        val request = validRequest()
        val fake = DeterministicFakeHarness(request)
        fake.start()

        val stale = fake.emit(
            AiEvent.FinalPatch(
                requestId = request.requestId,
                runGeneration = request.runGeneration - 1,
                patch = validPatch(),
            ),
        )

        assertTrue(stale is HarnessDispatch.Dropped)
        assertEquals(
            HarnessDropReason.GENERATION_MISMATCH,
            (stale as HarnessDispatch.Dropped).reason,
        )
        assertEquals(BoundedHarnessState.STREAMING, fake.state)
        assertTrue(fake.terminalEvents.isEmpty())
    }

    @Test
    fun invalidPatchBecomesOneProtocolFailure() {
        val fake = DeterministicFakeHarness(validRequest())
        fake.start()
        val mismatchedPatch = validPatch().copy(snapshotId = "different-snapshot")

        val result = fake.finalPatch(mismatchedPatch)
        fake.finalPatch(validPatch())

        assertTrue(result is HarnessDispatch.Emitted)
        val failure = (result as HarnessDispatch.Emitted).event as AiEvent.Failed
        assertEquals(HarnessErrorCode.PROTOCOL_INVALID, failure.code)
        assertEquals(BoundedHarnessState.FAILED, fake.state)
        assertEquals(listOf(failure), fake.terminalEvents)
        assertEquals(1, fake.dropped.size)
    }

    @Test
    fun firstEventIdleAndTotalTimeoutsAreDeterministic() {
        val firstEvent = DeterministicFakeHarness(
            validRequest(),
            limits = limits(),
        )
        firstEvent.start()
        firstEvent.advanceBy(99)
        assertEquals(BoundedHarnessState.STREAMING, firstEvent.state)
        firstEvent.advanceBy(1)
        assertFailure(firstEvent, HarnessErrorCode.FIRST_EVENT_TIMEOUT)

        val idle = DeterministicFakeHarness(validRequest(), limits = limits())
        idle.start()
        idle.status(HarnessPhase.GENERATING, "正在生成")
        idle.advanceBy(199)
        assertEquals(BoundedHarnessState.STREAMING, idle.state)
        idle.advanceBy(1)
        assertFailure(idle, HarnessErrorCode.STREAM_IDLE_TIMEOUT)

        val total = DeterministicFakeHarness(
            validRequest(),
            limits = limits(
                streamIdleTimeoutMs = 900,
                totalTimeoutMs = 1_000,
            ),
        )
        total.start()
        total.status(HarnessPhase.GENERATING, "正在生成")
        repeat(9) {
            total.advanceBy(100)
            total.status(HarnessPhase.GENERATING, "正在生成")
        }
        total.advanceBy(100)
        assertFailure(total, HarnessErrorCode.TOTAL_TIMEOUT)
    }

    @Test
    fun explicitProviderFailureIsTerminalAndCannotBeReplaced() {
        val fake = DeterministicFakeHarness(validRequest())
        fake.start()

        fake.providerFailure(HarnessErrorCode.PROVIDER_FAILURE, retryable = true)
        fake.release()

        assertEquals(BoundedHarnessState.FAILED, fake.state)
        val failure = fake.terminalEvents.single() as AiEvent.Failed
        assertEquals(HarnessErrorCode.PROVIDER_FAILURE, failure.code)
        assertTrue(failure.retryable)
        assertFalse(fake.transportCancellationRequested)
        assertEquals(1, fake.dropped.size)
    }

    @Test
    fun previewAndRepairBudgetsFailClosed() {
        val preview = DeterministicFakeHarness(
            validRequest(maxOutputChars = 4),
            limits = limits(maxPreviewChars = 4),
        )
        preview.start()
        preview.previewDelta("1234")
        preview.previewDelta("5")
        assertFailure(preview, HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED)

        val repair = DeterministicFakeHarness(
            validRequest(),
            limits = limits(maxPreviewResets = 1),
        )
        repair.start()
        repair.previewDelta("bad")
        repair.previewReset(attempt = 2)
        repair.previewReset(attempt = 3)
        assertFailure(repair, HarnessErrorCode.REPAIR_LIMIT_EXCEEDED)
    }

    @Test
    fun invalidStatusAndProviderEventBudgetFailClosed() {
        val invalidStatus = DeterministicFakeHarness(validRequest())
        invalidStatus.start()
        invalidStatus.status(HarnessPhase.UNDERSTANDING, "unsafe\u0000label")
        assertFailure(invalidStatus, HarnessErrorCode.INVALID_EVENT)

        val budgeted = DeterministicFakeHarness(
            validRequest(),
            limits = limits(maxProviderEvents = 1),
        )
        budgeted.start()
        budgeted.status(HarnessPhase.UNDERSTANDING, "理解中")
        budgeted.status(HarnessPhase.GENERATING, "生成中")
        assertFailure(budgeted, HarnessErrorCode.EVENT_LIMIT_EXCEEDED)
    }

    private fun assertFailure(fake: DeterministicFakeHarness, code: HarnessErrorCode) {
        assertEquals(BoundedHarnessState.FAILED, fake.state)
        val failure = fake.terminalEvents.single() as AiEvent.Failed
        assertEquals(code, failure.code)
    }

    private fun limits(
        firstEventTimeoutMs: Long = 100,
        streamIdleTimeoutMs: Long = 200,
        totalTimeoutMs: Long = 1_000,
        maxProviderEvents: Int = 2_048,
        maxPreviewChars: Int = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS,
        maxPreviewResets: Int = 1,
    ) = BoundedHarnessLimits(
        firstEventTimeoutMs = firstEventTimeoutMs,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxProviderEvents = maxProviderEvents,
        maxPreviewChars = maxPreviewChars,
        maxPreviewResets = maxPreviewResets,
    )

    private fun validRequest(
        maxOutputChars: Int = SenseAiProtocol.DEFAULT_MAX_OUTPUT_CHARS,
    ): HarnessRequestV1 {
        val snapshot = EditorSnapshotV1(
            requestId = REQUEST_ID,
            snapshotId = SNAPSHOT_ID,
            editorGeneration = 7,
            fieldIdentity = "field-1",
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = "原始内容",
            selection = TextSelectionV1(start = 4, end = 4),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = BASE_SHA256,
            capturedAtMonotonicMs = 10,
            truncated = false,
            maxOutputChars = maxOutputChars,
        )
        return HarnessRequestV1(
            requestId = REQUEST_ID,
            runGeneration = 11,
            snapshot = snapshot,
            maxOutputChars = maxOutputChars,
        )
    }

    private fun validPatch(): EditorPatchV1 = EditorPatchV1(
        requestId = REQUEST_ID,
        snapshotId = SNAPSHOT_ID,
        baseSha256 = BASE_SHA256,
        intent = EditorIntent.REWRITE,
        operation = PatchOperationV1(
            type = PatchOperationType.REPLACE,
            target = PatchTarget.WHOLE_FIELD,
            text = "你好，世界",
            selectionAfter = SelectionAfter.END,
        ),
    )

    private companion object {
        const val REQUEST_ID = "request-1"
        const val SNAPSHOT_ID = "snapshot-1"
        val BASE_SHA256 = EditorTextDigest.sha256Utf8("原始内容")
    }
}
