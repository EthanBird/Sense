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
    fun transportActivityRefreshesIdleWithoutConsumingTypedEventBudget() {
        val request = validRequest()
        val session = BoundedHarnessSession(
            request,
            limits = limits(
                firstEventTimeoutMs = 100,
                streamIdleTimeoutMs = 200,
                totalTimeoutMs = 1_000,
                maxProviderEvents = 1,
            ),
        )
        session.start(0)

        assertTrue(
            session.noteProviderActivity(
                request.requestId,
                request.runGeneration,
                90,
            ) is HarnessDispatch.NoEvent,
        )
        assertTrue(
            session.noteProviderActivity(
                request.requestId,
                request.runGeneration,
                280,
            ) is HarnessDispatch.NoEvent,
        )

        assertTrue(session.advanceTo(479) is HarnessDispatch.NoEvent)
        assertEquals(BoundedHarnessState.STREAMING, session.state)
        assertEquals(0, session.acceptedProviderEvents)
        val timeout = session.advanceTo(480) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.STREAM_IDLE_TIMEOUT,
            (timeout.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun timeoutLookaheadCanArmOneRecoveryWithoutExtendingTotalDeadline() {
        val request = validRequest()
        val session = BoundedHarnessSession(
            request,
            limits = limits(
                firstEventTimeoutMs = 100,
                streamIdleTimeoutMs = 200,
                totalTimeoutMs = 500,
            ),
        )
        session.start(0)

        assertEquals(HarnessErrorCode.FIRST_EVENT_TIMEOUT, session.pendingTimeoutCode(100))
        val recovery = session.recoverProviderStream(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 100,
        ) as HarnessDispatch.Emitted

        assertEquals("provider_recovering", (recovery.event as AiEvent.Status).label)
        assertTrue(session.advanceTo(199) is HarnessDispatch.NoEvent)
        val firstEvent = session.advanceTo(200) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.FIRST_EVENT_TIMEOUT,
            (firstEvent.event as AiEvent.Failed).code,
        )

        val oneShot = BoundedHarnessSession(request, limits = limits())
        oneShot.start(0)
        oneShot.recoverProviderStream(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            attempt = 2,
            statusLabel = "provider_repairing",
            nowMonotonicMs = 1,
        )
        val repeated = oneShot.recoverProviderStream(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 2,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.REPAIR_LIMIT_EXCEEDED,
            (repeated.event as AiEvent.Failed).code,
        )

        val totalSession = BoundedHarnessSession(
            request,
            limits = limits(streamIdleTimeoutMs = 100, totalTimeoutMs = 100),
        )
        totalSession.start(0)
        val total = totalSession.recoverProviderStream(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 100,
        ) as HarnessDispatch.Emitted
        assertEquals(HarnessErrorCode.TOTAL_TIMEOUT, (total.event as AiEvent.Failed).code)
    }

    @Test
    fun recoveredProviderUsesFirstEventTimeoutThenIdleTimeoutAfterRealActivity() {
        val request = validRequest()
        fun newSession() = BoundedHarnessSession(
            request,
            limits = limits(
                firstEventTimeoutMs = 50,
                streamIdleTimeoutMs = 200,
                totalTimeoutMs = 1_000,
            ),
        ).also {
            it.start(0)
            it.recoverProviderStream(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                attempt = 2,
                statusLabel = "provider_recovering",
                nowMonotonicMs = 10,
            )
        }

        val waitingForFirst = newSession()
        assertTrue(waitingForFirst.advanceTo(59) is HarnessDispatch.NoEvent)
        val firstTimeout = waitingForFirst.advanceTo(60) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.FIRST_EVENT_TIMEOUT,
            (firstTimeout.event as AiEvent.Failed).code,
        )

        val streaming = newSession()
        assertTrue(
            streaming.noteProviderActivity(
                request.requestId,
                request.runGeneration,
                nowMonotonicMs = 40,
            ) is HarnessDispatch.NoEvent,
        )
        assertTrue(streaming.advanceTo(239) is HarnessDispatch.NoEvent)
        val idleTimeout = streaming.advanceTo(240) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.STREAM_IDLE_TIMEOUT,
            (idleTimeout.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun staleTransportActivityCannotExtendTheCurrentRun() {
        val request = validRequest()
        val session = BoundedHarnessSession(request, limits = limits())
        session.start(0)

        val stale = session.noteProviderActivity(
            request.requestId,
            request.runGeneration - 1,
            90,
        )

        assertTrue(stale is HarnessDispatch.Dropped)
        val timeout = session.advanceTo(100) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.FIRST_EVENT_TIMEOUT,
            (timeout.event as AiEvent.Failed).code,
        )
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
    fun publicDescriptionIsIdentityGatedAndBounded() {
        val request = validRequest()
        val session = BoundedHarnessSession(
            request,
            limits = limits(maxDescriptionChars = 4),
        )
        session.start(0)

        val stale = session.accept(
            AiEvent.DescriptionDelta(
                requestId = request.requestId,
                runGeneration = request.runGeneration - 1,
                text = "旧",
            ),
            nowMonotonicMs = 1,
        )
        assertTrue(stale is HarnessDispatch.Dropped)
        assertEquals(
            HarnessDropReason.GENERATION_MISMATCH,
            (stale as HarnessDispatch.Dropped).reason,
        )

        val accepted = session.accept(
            AiEvent.DescriptionDelta(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                text = "正在分析",
            ),
            nowMonotonicMs = 2,
        )
        assertTrue(accepted is HarnessDispatch.Emitted)

        val overflow = session.accept(
            AiEvent.DescriptionDelta(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                text = "。",
            ),
            nowMonotonicMs = 3,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.DESCRIPTION_LIMIT_EXCEEDED,
            (overflow.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun previewResetClearsBothPreviewAndDescriptionBudgets() {
        val request = validRequest()
        val session = BoundedHarnessSession(
            request,
            limits = limits(
                maxPreviewChars = 4,
                maxDescriptionChars = 4,
            ),
        )
        session.start(0)
        session.accept(
            AiEvent.PreviewDelta(request.requestId, request.runGeneration, "1234"),
            nowMonotonicMs = 1,
        )
        session.accept(
            AiEvent.DescriptionDelta(request.requestId, request.runGeneration, "分析中"),
            nowMonotonicMs = 2,
        )

        val reset = session.accept(
            AiEvent.PreviewReset(request.requestId, request.runGeneration, attempt = 2),
            nowMonotonicMs = 3,
        )
        assertTrue(reset is HarnessDispatch.Emitted)
        assertTrue(
            session.accept(
                AiEvent.PreviewDelta(request.requestId, request.runGeneration, "5678"),
                nowMonotonicMs = 4,
            ) is HarnessDispatch.Emitted,
        )
        assertTrue(
            session.accept(
                AiEvent.DescriptionDelta(request.requestId, request.runGeneration, "生成中"),
                nowMonotonicMs = 5,
            ) is HarnessDispatch.Emitted,
        )
        assertEquals(BoundedHarnessState.STREAMING, session.state)
    }

    @Test
    fun previewReplaceIsAttemptAndIdentityGatedAndReplacesBothBudgets() {
        val request = validRequest(maxOutputChars = 4)
        val session = BoundedHarnessSession(
            request,
            limits = limits(
                maxPreviewChars = 4,
                maxDescriptionChars = 4,
                maxPreviewResets = 1,
            ),
        )
        session.start(0)
        session.accept(
            AiEvent.PreviewDelta(request.requestId, request.runGeneration, "old"),
            nowMonotonicMs = 1,
        )
        session.accept(
            AiEvent.DescriptionDelta(request.requestId, request.runGeneration, "旧"),
            nowMonotonicMs = 2,
        )
        session.recoverProviderStream(
            requestId = request.requestId,
            runGeneration = request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 3,
        )

        val replacement = session.accept(
            AiEvent.PreviewReplace(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                attempt = 2,
                text = "新结果",
                description = "新",
            ),
            nowMonotonicMs = 4,
        )
        assertTrue(replacement is HarnessDispatch.Emitted)
        assertTrue(
            session.accept(
                AiEvent.PreviewDelta(request.requestId, request.runGeneration, "续"),
                nowMonotonicMs = 5,
            ) is HarnessDispatch.Emitted,
        )
        val secondReplacement = session.accept(
            AiEvent.PreviewReplace(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                attempt = 3,
                text = "again",
            ),
            nowMonotonicMs = 6,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.REPAIR_LIMIT_EXCEEDED,
            (secondReplacement.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun previewReplaceRejects_empty_and_oversized_payloads() {
        val request = validRequest(maxOutputChars = 2)
        val empty = BoundedHarnessSession(request, limits = limits(maxPreviewChars = 2))
        empty.start(0)
        empty.recoverProviderStream(
            request.requestId,
            request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 1,
        )
        val emptyFailure = empty.accept(
            AiEvent.PreviewReplace(
                request.requestId,
                request.runGeneration,
                attempt = 2,
                text = "",
            ),
            nowMonotonicMs = 2,
        ) as HarnessDispatch.Emitted
        assertEquals(HarnessErrorCode.INVALID_EVENT, (emptyFailure.event as AiEvent.Failed).code)

        val oversized = BoundedHarnessSession(request, limits = limits(maxPreviewChars = 2))
        oversized.start(0)
        oversized.recoverProviderStream(
            request.requestId,
            request.runGeneration,
            attempt = 2,
            statusLabel = "provider_recovering",
            nowMonotonicMs = 1,
        )
        val oversizedFailure = oversized.accept(
            AiEvent.PreviewReplace(
                request.requestId,
                request.runGeneration,
                attempt = 2,
                text = "123",
            ),
            nowMonotonicMs = 2,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.PREVIEW_LIMIT_EXCEEDED,
            (oversizedFailure.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun previewReplaceCannotBeInjectedWithoutAnArmedRecovery() {
        val request = validRequest()
        val session = BoundedHarnessSession(request)
        session.start(0)

        val failure = session.accept(
            AiEvent.PreviewReplace(
                requestId = request.requestId,
                runGeneration = request.runGeneration,
                attempt = 2,
                text = "不得注入",
            ),
            nowMonotonicMs = 1,
        ) as HarnessDispatch.Emitted

        assertEquals(
            HarnessErrorCode.INVALID_EVENT,
            (failure.event as AiEvent.Failed).code,
        )
    }

    @Test
    fun publicDescriptionRejectsControlCharactersAndBrokenUnicode() {
        val request = validRequest()
        val newline = BoundedHarnessSession(request)
        newline.start(0)
        val newlineFailure = newline.accept(
            AiEvent.DescriptionDelta(request.requestId, request.runGeneration, "分析\n结果"),
            nowMonotonicMs = 1,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.INVALID_EVENT,
            (newlineFailure.event as AiEvent.Failed).code,
        )

        val brokenUnicode = BoundedHarnessSession(request)
        brokenUnicode.start(0)
        val unicodeFailure = brokenUnicode.accept(
            AiEvent.DescriptionDelta(
                request.requestId,
                request.runGeneration,
                "\uD83D",
            ),
            nowMonotonicMs = 1,
        ) as HarnessDispatch.Emitted
        assertEquals(
            HarnessErrorCode.INVALID_EVENT,
            (unicodeFailure.event as AiEvent.Failed).code,
        )

        listOf("伪装\u2028换行", "安全\u202e反转").forEach { unsafe ->
            val session = BoundedHarnessSession(request)
            session.start(0)
            val failure = session.accept(
                AiEvent.DescriptionDelta(
                    request.requestId,
                    request.runGeneration,
                    unsafe,
                ),
                nowMonotonicMs = 1,
            ) as HarnessDispatch.Emitted
            assertEquals(
                HarnessErrorCode.INVALID_EVENT,
                (failure.event as AiEvent.Failed).code,
            )
        }
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
        maxDescriptionChars: Int = BoundedHarnessLimits.DEFAULT_MAX_DESCRIPTION_CHARS,
    ) = BoundedHarnessLimits(
        firstEventTimeoutMs = firstEventTimeoutMs,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxProviderEvents = maxProviderEvents,
        maxPreviewChars = maxPreviewChars,
        maxPreviewResets = maxPreviewResets,
        maxDescriptionChars = maxDescriptionChars,
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
