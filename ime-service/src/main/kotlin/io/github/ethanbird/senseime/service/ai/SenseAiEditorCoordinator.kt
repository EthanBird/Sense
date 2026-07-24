package io.github.ethanbird.senseime.service.ai

import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.PatchTarget
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.runtime.SenseAiBrainClient
import io.github.ethanbird.senseime.service.ai.editor.ActiveEditorPatchLease
import io.github.ethanbird.senseime.service.ai.editor.AndroidEditorSecurityClassifier
import io.github.ethanbird.senseime.service.ai.editor.EditorApplyCommand
import io.github.ethanbird.senseime.service.ai.editor.EditorCaptureDecision
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchGuard
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchGuardDecision
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchGuardInput
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchPlan
import io.github.ethanbird.senseime.service.ai.editor.EditorPatchPlanner
import io.github.ethanbird.senseime.service.ai.editor.EditorPostApplyVerifier
import io.github.ethanbird.senseime.service.ai.editor.EditorPointerOwner
import io.github.ethanbird.senseime.service.ai.editor.EditorSecurityContext
import io.github.ethanbird.senseime.service.ai.editor.EditorSnapshotCaptureInput
import io.github.ethanbird.senseime.service.ai.editor.EditorSnapshotCapturePolicy
import io.github.ethanbird.senseime.service.ai.editor.EditorStaleReason
import io.github.ethanbird.senseime.service.ai.editor.EditorTransactionCancelReason
import io.github.ethanbird.senseime.service.ai.editor.EditorTransactionFailure
import io.github.ethanbird.senseime.service.ai.editor.EditorTransactionStateMachine
import io.github.ethanbird.senseime.service.ai.editor.ExtractedEditorText
import io.github.ethanbird.senseime.service.ai.editor.LiveEditorRead
import io.github.ethanbird.senseime.service.ai.editor.SelectionOnlyPostApplyObservation
import io.github.ethanbird.senseime.service.ai.editor.SelectedEditorText
import io.github.ethanbird.senseime.service.ai.editor.SurroundingEditorText
import io.github.ethanbird.senseime.ui.AiSurfacePhase
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The Android authority boundary for one hold-to-AI editor transaction.
 *
 * Brain can propose and stream, but only this class sees InputConnection. A final patch is
 * re-read, generation/pointer/hash guarded, planned, applied in one batch, and verified before the
 * UI may say that text was written.
 */
class SenseAiEditorCoordinator(
    context: android.content.Context,
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val editorSelection: () -> TextSelectionV1?,
    private val editorGeneration: () -> Long,
    private val fieldIdentity: () -> String,
    private val pointerStillDown: (Long) -> Boolean,
    private val onSurfaceUpdate: (
        generation: Long,
        phase: AiSurfacePhase,
        preview: String,
        status: String,
    ) -> Unit,
    private val onOwnApplyWindow: (applicationToken: Long?, active: Boolean) -> Unit,
) : AutoCloseable {
    private val brainClient = SenseAiBrainClient(context, ::onBrainEvent)
    private val applicationTokens = AtomicLong(1)
    private var active: ActiveRun? = null

    fun start(uiGeneration: Long) {
        if (uiGeneration <= 0L) return
        active?.let {
            cancel(it.uiGeneration, HarnessCancelReason.CALLER_REQUESTED)
        }
        val info = editorInfo()
        val security = securityContext(info)
        val blocked = EditorSnapshotCapturePolicy.preflight(security)
        if (blocked != null) {
            showError(uiGeneration, "此输入框不允许远端 AI")
            return
        }
        val currentConnection = connection()
        if (currentConnection == null) {
            showError(uiGeneration, "无法读取当前输入框")
            return
        }

        val requestId = UUID.randomUUID().toString()
        val snapshotId = UUID.randomUUID().toString()
        val generation = editorGeneration()
        val frozenSelection = editorSelection()
        val decision = capture(
            inputConnection = currentConnection,
            info = info,
            requestId = requestId,
            snapshotId = snapshotId,
            generation = generation,
            fieldId = fieldIdentity(),
            selection = frozenSelection,
            preferredTarget = null,
            preferExtractedSelection = true,
        )
        val snapshot = (decision as? EditorCaptureDecision.Captured)?.snapshot
        if (snapshot == null || snapshot.target == null) {
            val status = if (decision is EditorCaptureDecision.Blocked) {
                "此输入框不允许远端 AI"
            } else {
                "当前应用无法提供可安全覆盖的全文或选区"
            }
            showError(uiGeneration, status)
            return
        }

        val request = HarnessRequestV1(
            requestId = requestId,
            runGeneration = uiGeneration,
            skill = EditorIntent.SMART_EDIT,
            snapshot = snapshot,
        )
        val run = ActiveRun(
            uiGeneration = uiGeneration,
            request = request,
            lease = ActiveEditorPatchLease(
                snapshot = snapshot,
                runGeneration = uiGeneration,
                pointerId = LOGICAL_SPACE_POINTER,
                pointerOwnershipToken = uiGeneration,
            ),
            transaction = EditorTransactionStateMachine(
                requestId = requestId,
                runGeneration = uiGeneration,
                editorGeneration = generation,
            ),
        )
        active = run
        onSurfaceUpdate(uiGeneration, AiSurfacePhase.STARTING, "", "正在连接模型…")
        brainClient.start(request)
    }

    fun cancel(
        uiGeneration: Long,
        reason: HarnessCancelReason = HarnessCancelReason.POINTER_RELEASED,
    ) {
        val run = active ?: return
        if (run.uiGeneration != uiGeneration) return
        run.transaction.cancel(
            run.request.requestId,
            run.request.runGeneration,
            reason.toTransactionReason(),
        )
        // Local authority is revoked before Binder/network cancellation is requested.
        active = null
        brainClient.cancel(run.request.requestId, run.request.runGeneration, reason)
    }

    fun markEditorChanged(
        reason: EditorStaleReason,
        ownApplicationToken: Long? = null,
    ) {
        val run = active ?: return
        val transition = run.transaction.markEditorChanged(reason, ownApplicationToken)
        if (transition is io.github.ethanbird.senseime.service.ai.editor.EditorTransactionTransition.OwnApplyObservationIgnored) {
            return
        }
        active = null
        brainClient.cancel(
            run.request.requestId,
            run.request.runGeneration,
            HarnessCancelReason.EDITOR_CHANGED,
        )
        onSurfaceUpdate(
            run.uiGeneration,
            AiSurfacePhase.ERROR,
            run.preview.toString(),
            "输入框已变化，松开空格后重试",
        )
    }

    /**
     * Returns true when the service should advance its editor generation.
     *
     * Some hosts acknowledge a just-finished composing span after AI capture. A callback that
     * merely reports the already-frozen selection is not an editor mutation and must not
     * invalidate the lease. Own-apply callbacks are likewise ignored by the transaction CAS.
     */
    fun markSelectionChanged(
        newSelection: TextSelectionV1?,
        ownApplicationToken: Long? = null,
    ): Boolean {
        val run = active ?: return ownApplicationToken == null
        if (
            ownApplicationToken == null &&
            newSelection == run.lease.snapshot.selection
        ) {
            return false
        }
        val transition = run.transaction.markEditorChanged(
            EditorStaleReason.SELECTION_CHANGED,
            ownApplicationToken,
        )
        if (transition is io.github.ethanbird.senseime.service.ai.editor.EditorTransactionTransition.OwnApplyObservationIgnored) {
            return false
        }
        active = null
        brainClient.cancel(
            run.request.requestId,
            run.request.runGeneration,
            HarnessCancelReason.EDITOR_CHANGED,
        )
        onSurfaceUpdate(
            run.uiGeneration,
            AiSurfacePhase.ERROR,
            run.preview.toString(),
            "输入框已变化，松开空格后重试",
        )
        return true
    }

    override fun close() {
        active?.let {
            cancel(it.uiGeneration, HarnessCancelReason.CALLER_REQUESTED)
        }
        brainClient.close()
    }

    private fun onBrainEvent(event: AiEvent) {
        val run = active ?: return
        if (
            event.requestId != run.request.requestId ||
            event.runGeneration != run.request.runGeneration ||
            !pointerStillDown(run.uiGeneration)
        ) {
            return
        }
        when (event) {
            is AiEvent.Started -> onSurfaceUpdate(
                run.uiGeneration,
                AiSurfacePhase.STARTING,
                "",
                "正在理解输入框…",
            )

            is AiEvent.Status -> onSurfaceUpdate(
                run.uiGeneration,
                AiSurfacePhase.STREAMING,
                run.preview.toString(),
                statusLabel(event),
            )

            is AiEvent.PreviewReset -> {
                run.preview.setLength(0)
                onSurfaceUpdate(
                    run.uiGeneration,
                    AiSurfacePhase.STREAMING,
                    "",
                    "正在修正输出格式…",
                )
            }

            is AiEvent.PreviewDelta -> {
                val remaining = MAX_PREVIEW_CHARS - run.preview.length
                if (remaining > 0) run.preview.append(event.text.take(remaining))
                onSurfaceUpdate(
                    run.uiGeneration,
                    AiSurfacePhase.STREAMING,
                    run.preview.toString(),
                    "正在生成…",
                )
            }

            is AiEvent.FinalPatch -> applyFinalPatch(run, event)
            is AiEvent.Cancelled -> {
                active = null
            }

            is AiEvent.Failed -> {
                active = null
                onSurfaceUpdate(
                    run.uiGeneration,
                    AiSurfacePhase.ERROR,
                    run.preview.toString(),
                    failureLabel(event),
                )
            }

            is AiEvent.Usage -> Unit
        }
    }

    private fun applyFinalPatch(run: ActiveRun, event: AiEvent.FinalPatch) {
        val validation = run.transaction.beginValidation(event.requestId, event.runGeneration)
        if (validation !is io.github.ethanbird.senseime.service.ai.editor.EditorTransactionTransition.Changed) {
            return
        }
        val live = readLive(run.lease.snapshot)
        val guard = EditorPatchGuard.evaluate(
            EditorPatchGuardInput(
                lease = run.lease,
                eventRequestId = event.requestId,
                eventRunGeneration = event.runGeneration,
                pointerOwner = EditorPointerOwner(
                    pointerId = LOGICAL_SPACE_POINTER,
                    pointerOwnershipToken = run.uiGeneration,
                    isDown = pointerStillDown(run.uiGeneration),
                ),
                liveEditor = live,
                patch = event.patch,
            ),
        )
        val guarded = (guard as? EditorPatchGuardDecision.Accepted)?.guardedPatch
        if (guarded == null) {
            run.transaction.fail(EditorTransactionFailure.PATCH_REJECTED)
            active = null
            onSurfaceUpdate(
                run.uiGeneration,
                AiSurfacePhase.ERROR,
                run.preview.toString(),
                "输入框已变化，未覆盖",
            )
            return
        }

        val token = applicationTokens.getAndIncrement().coerceAtLeast(1L)
        val transition = run.transaction.tryBeginApply(
            event.requestId,
            event.runGeneration,
            token,
        )
        if (transition !is io.github.ethanbird.senseime.service.ai.editor.EditorTransactionTransition.Changed) {
            return
        }
        val plan = runCatching {
            EditorPatchPlanner.plan(guarded, Build.VERSION.SDK_INT)
        }.getOrElse {
            run.transaction.fail(EditorTransactionFailure.INTERNAL_FAILURE, token)
            failApply(run, "无法生成安全的文本替换计划")
            return
        }
        if (plan is EditorPatchPlan.NoChange) {
            run.transaction.markApplied(event.requestId, event.runGeneration, token)
            active = null
            onSurfaceUpdate(
                run.uiGeneration,
                AiSurfacePhase.COMPLETE,
                run.preview.toString(),
                "无需修改",
            )
            return
        }
        plan as EditorPatchPlan.Replace
        val currentConnection = connection()
        if (currentConnection == null) {
            run.transaction.fail(EditorTransactionFailure.EDITOR_REJECTED, token)
            failApply(run, "输入连接已断开")
            return
        }

        onOwnApplyWindow(token, true)
        val execution = try {
            executePlan(currentConnection, plan)
        } catch (_: RuntimeException) {
            null
        } finally {
            onOwnApplyWindow(token, false)
        }
        if (execution == null) {
            run.transaction.fail(EditorTransactionFailure.INTERNAL_FAILURE, token)
            failApply(run, "宿主输入框发生异常，已停止写入")
            return
        }
        if (!execution.mutationAccepted) {
            run.transaction.fail(EditorTransactionFailure.EDITOR_REJECTED, token)
            failApply(run, "宿主应用未接受文本替换")
            return
        }

        val verified = runCatching {
            verifyExpectedState(run.lease.snapshot, plan)
        }.getOrDefault(false)
        if (!verified) {
            run.transaction.fail(EditorTransactionFailure.POST_APPLY_VERIFICATION_FAILED, token)
            failApply(run, "文本已提交，但宿主未返回可验证结果")
            return
        }
        run.transaction.markApplied(event.requestId, event.runGeneration, token)
        active = null
        onSurfaceUpdate(
            run.uiGeneration,
            AiSurfacePhase.COMPLETE,
            plan.expectedState.textWindow,
            "已写入输入框",
        )
    }

    private fun executePlan(
        inputConnection: InputConnection,
        plan: EditorPatchPlan.Replace,
    ): EditorExecutionResult {
        val batchStarted = safeExecute(inputConnection, plan.beginCommand)
        var mutationAccepted = false
        var postMutationSelectionRejected = false
        try {
            body@ for (command in plan.bodyCommands) {
                when (command) {
                    EditorApplyCommand.FinishComposingText -> {
                        // Many otherwise-correct custom editors return false here. The
                        // following selection + mutation commands are the real write gate.
                        safeExecute(inputConnection, command)
                    }

                    is EditorApplyCommand.SetSelection -> {
                        val accepted = safeExecute(inputConnection, command)
                        if (!accepted && !mutationAccepted) break@body
                        if (!accepted) postMutationSelectionRejected = true
                    }

                    is EditorApplyCommand.CommitText,
                    is EditorApplyCommand.ReplaceText,
                    -> {
                        if (!safeExecute(inputConnection, command)) break@body
                        mutationAccepted = true
                    }

                    EditorApplyCommand.BeginBatchEdit,
                    EditorApplyCommand.EndBatchEdit,
                    -> Unit
                }
            }
            if (mutationAccepted && postMutationSelectionRejected) {
                // A host may acknowledge the text mutation before accepting the explicit
                // cursor/selection update. Retry once, then let the fresh read decide success.
                safeExecute(
                    inputConnection,
                    EditorApplyCommand.SetSelection(
                        plan.expectedState.selection.start,
                        plan.expectedState.selection.end,
                    ),
                )
            }
        } finally {
            if (batchStarted) {
                // Batch edit is a flicker/notification optimization. Custom
                // editors that return false can still support the body commands.
                plan.finallyCommands.forEach { command ->
                    safeExecute(inputConnection, command)
                }
            }
        }
        return EditorExecutionResult(mutationAccepted)
    }

    private fun safeExecute(
        inputConnection: InputConnection,
        command: EditorApplyCommand,
    ): Boolean = runCatching { execute(inputConnection, command) }.getOrDefault(false)

    private fun execute(
        inputConnection: InputConnection,
        command: EditorApplyCommand,
    ): Boolean = when (command) {
        EditorApplyCommand.BeginBatchEdit -> inputConnection.beginBatchEdit()
        EditorApplyCommand.EndBatchEdit -> inputConnection.endBatchEdit()
        EditorApplyCommand.FinishComposingText -> inputConnection.finishComposingText()
        is EditorApplyCommand.SetSelection ->
            inputConnection.setSelection(command.start, command.end)
        is EditorApplyCommand.CommitText ->
            inputConnection.commitText(command.text, command.newCursorPosition)
        is EditorApplyCommand.ReplaceText -> {
            if (Build.VERSION.SDK_INT < EditorPatchPlanner.REPLACE_TEXT_SDK) {
                false
            } else {
                inputConnection.replaceText(
                    command.start,
                    command.end,
                    command.text,
                    command.newCursorPosition,
                    null,
                )
            }
        }
    }

    private fun verifyExpectedState(
        original: EditorSnapshotV1,
        plan: EditorPatchPlan.Replace,
    ): Boolean {
        if (original.capability == SnapshotCapability.SELECTION_ONLY) {
            return verifySelectionOnlyExpectedState(plan)
        }
        val current = readLive(
            original.copy(
                baseSha256 = plan.expectedState.textWindowSha256,
                text = plan.expectedState.textWindow,
                selection = plan.expectedState.selection,
            ),
            useTrackedSelection = false,
        ) ?: return false
        return current.textStartOffset == plan.expectedState.textStartOffset &&
            current.text == plan.expectedState.textWindow &&
            current.selection == plan.expectedState.selection
    }

    private fun verifySelectionOnlyExpectedState(plan: EditorPatchPlan.Replace): Boolean {
        val inputConnection = connection() ?: return false
        val expectedSelection = plan.expectedState.selection
        val replacement = plan.expectedState.textWindow
        val selection = readFreshSelection(inputConnection) ?: editorSelection()
        val observation = when {
            !expectedSelection.isCollapsed -> SelectionOnlyPostApplyObservation(
                selection = selection,
                selectedText = runCatching {
                    inputConnection.getSelectedText(0)?.toString()
                }.getOrNull(),
            )

            expectedSelection.start == plan.checkpoint.targetRange.start ->
                SelectionOnlyPostApplyObservation(
                    selection = selection,
                    textAfterCursor = runCatching {
                        inputConnection.getTextAfterCursor(replacement.length, 0)?.toString()
                    }.getOrNull(),
                )

            else -> SelectionOnlyPostApplyObservation(
                selection = selection,
                textBeforeCursor = runCatching {
                    inputConnection.getTextBeforeCursor(replacement.length, 0)?.toString()
                }.getOrNull(),
            )
        }
        return EditorPostApplyVerifier.verifySelectionOnly(plan, observation)
    }

    private fun readFreshSelection(inputConnection: InputConnection): TextSelectionV1? {
        val extracted = runCatching {
            inputConnection.getExtractedText(
                ExtractedTextRequest().apply {
                    token = editorGeneration()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                    hintMaxChars = MAX_CONTEXT_CHARS
                    hintMaxLines = MAX_CONTEXT_LINES
                },
                0,
            )
        }.getOrNull() ?: return null
        val textLength = extracted.text?.length ?: return null
        val startInText = minOf(extracted.selectionStart, extracted.selectionEnd)
        val endInText = maxOf(extracted.selectionStart, extracted.selectionEnd)
        if (
            extracted.startOffset < 0 ||
            startInText < 0 ||
            endInText < startInText ||
            endInText > textLength
        ) {
            return null
        }
        val absoluteStart = extracted.startOffset.toLong() + startInText
        val absoluteEnd = extracted.startOffset.toLong() + endInText
        if (
            absoluteStart !in 0..Int.MAX_VALUE.toLong() ||
            absoluteEnd !in absoluteStart..Int.MAX_VALUE.toLong()
        ) {
            return null
        }
        return TextSelectionV1(absoluteStart.toInt(), absoluteEnd.toInt())
    }

    private fun readLive(
        snapshot: EditorSnapshotV1,
        useTrackedSelection: Boolean = true,
    ): LiveEditorRead? {
        val currentConnection = connection() ?: return null
        val selection = if (useTrackedSelection) editorSelection() else null
        val decision = capture(
            inputConnection = currentConnection,
            info = editorInfo(),
            requestId = snapshot.requestId,
            snapshotId = snapshot.snapshotId,
            generation = editorGeneration(),
            fieldId = fieldIdentity(),
            selection = selection,
            preferredTarget = snapshot.target ?: PatchTarget.WHOLE_FIELD,
        )
        val current = (decision as? EditorCaptureDecision.Captured)?.snapshot ?: return null
        return LiveEditorRead(
            editorGeneration = current.editorGeneration,
            fieldIdentity = current.fieldIdentity,
            capability = current.capability,
            text = current.text,
            textStartOffset = current.textStartOffset,
            selection = current.selection,
        )
    }

    private fun capture(
        inputConnection: InputConnection,
        info: EditorInfo?,
        requestId: String,
        snapshotId: String,
        generation: Long,
        fieldId: String,
        selection: TextSelectionV1?,
        preferredTarget: PatchTarget?,
        preferExtractedSelection: Boolean = false,
    ): EditorCaptureDecision {
        val security = securityContext(info)
        EditorSnapshotCapturePolicy.preflight(security)?.let {
            return EditorCaptureDecision.Blocked(it)
        }
        val request = ExtractedTextRequest().apply {
            token = generation.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            hintMaxChars = MAX_CONTEXT_CHARS
            hintMaxLines = MAX_CONTEXT_LINES
        }
        val extractedText = runCatching {
            inputConnection.getExtractedText(request, 0)
        }.getOrNull()
        val before = runCatching {
            inputConnection.getTextBeforeCursor(CONTEXT_PROBE_CHARS, 0)?.toString()
        }.getOrNull()
        val after = runCatching {
            inputConnection.getTextAfterCursor(CONTEXT_PROBE_CHARS, 0)?.toString()
        }.getOrNull()
        val extracted = extractedText?.text?.toString()?.let { text ->
            val partial = extractedText.partialStartOffset >= 0 ||
                extractedText.partialEndOffset >= 0
            val normalizedSelectionStart =
                minOf(extractedText.selectionStart, extractedText.selectionEnd)
            val normalizedSelectionEnd =
                maxOf(extractedText.selectionStart, extractedText.selectionEnd)
            val validSelection =
                normalizedSelectionStart >= 0 &&
                    normalizedSelectionEnd <= text.length
            val completeDocument =
                extractedText.startOffset == 0 &&
                    !partial &&
                    validSelection &&
                    before == text.substring(0, normalizedSelectionStart) &&
                    after == text.substring(normalizedSelectionEnd)
            ExtractedEditorText(
                text = text,
                startOffset = extractedText.startOffset,
                selectionStartInText = normalizedSelectionStart,
                selectionEndInText = normalizedSelectionEnd,
                completeDocument = completeDocument,
                partialUpdate = partial,
            )
        }
        val extractedSelection = extracted?.takeIf {
            it.startOffset >= 0 &&
                it.selectionStartInText >= 0 &&
                it.selectionEndInText >= it.selectionStartInText &&
                it.selectionEndInText <= it.text.length
        }?.absoluteSelection
        val effectiveSelection = if (preferExtractedSelection && extractedSelection != null) {
            extractedSelection
        } else {
            selection ?: extractedSelection
        }
        val effectiveTarget = preferredTarget ?: if (effectiveSelection?.isCollapsed == false) {
            PatchTarget.SELECTION
        } else {
            PatchTarget.WHOLE_FIELD
        }
        val selectedValue = runCatching {
            inputConnection.getSelectedText(0)?.toString()
        }.getOrNull()
        val selected = SelectedEditorText(
            readSucceeded = effectiveSelection?.isCollapsed != false || selectedValue != null,
            text = selectedValue,
        )
        val surrounding = if (extracted?.completeDocument == true) {
            null
        } else {
            if (before == null || after == null) null else SurroundingEditorText(before, after)
        }
        return EditorSnapshotCapturePolicy.capture(
            EditorSnapshotCaptureInput(
                requestId = requestId,
                snapshotId = snapshotId,
                editorGeneration = generation,
                fieldIdentity = fieldId,
                capturedAtMonotonicMs = SystemClock.elapsedRealtime(),
                security = security,
                preferredTarget = effectiveTarget,
                currentSelection = effectiveSelection,
                extracted = extracted,
                selected = selected,
                surrounding = surrounding,
            ),
        )
    }

    private fun securityContext(info: EditorInfo?): EditorSecurityContext {
        if (info == null) return EditorSecurityContext(classificationComplete = false)
        return AndroidEditorSecurityClassifier.classify(
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            oneTimeCodeSignal = AndroidEditorSecurityClassifier.hasOneTimeCodeSignal(
                info.privateImeOptions,
                info.hintText,
            ),
        )
    }

    private fun failApply(run: ActiveRun, status: String) {
        active = null
        onSurfaceUpdate(
            run.uiGeneration,
            AiSurfacePhase.ERROR,
            run.preview.toString(),
            status,
        )
    }

    private fun showError(generation: Long, status: String) {
        onSurfaceUpdate(generation, AiSurfacePhase.ERROR, "", status)
    }

    private fun failureLabel(event: AiEvent.Failed): String = when (event.code) {
        io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode.FIRST_EVENT_TIMEOUT ->
            "模型连接超时"
        io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode.STREAM_IDLE_TIMEOUT ->
            "模型响应中断"
        io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode.TOTAL_TIMEOUT ->
            "本次思考超时"
        io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode.PROTOCOL_INVALID ->
            "模型没有返回可验证的编辑结果"
        io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode.PROVIDER_FAILURE ->
            "Provider 未配置或请求失败"
        else -> "AI 暂时不可用"
    }

    private fun statusLabel(event: AiEvent.Status): String = when (event.phase) {
        io.github.ethanbird.senseime.ai.protocol.HarnessPhase.CONNECTING ->
            "正在连接模型…"
        io.github.ethanbird.senseime.ai.protocol.HarnessPhase.UNDERSTANDING ->
            "正在理解输入框…"
        io.github.ethanbird.senseime.ai.protocol.HarnessPhase.GENERATING ->
            "正在生成…"
        io.github.ethanbird.senseime.ai.protocol.HarnessPhase.VALIDATING ->
            "正在校验编辑结果…"
    }

    private data class ActiveRun(
        val uiGeneration: Long,
        val request: HarnessRequestV1,
        val lease: ActiveEditorPatchLease,
        val transaction: EditorTransactionStateMachine,
        val preview: StringBuilder = StringBuilder(),
    )

    private data class EditorExecutionResult(
        val mutationAccepted: Boolean,
    )

    companion object {
        private const val LOGICAL_SPACE_POINTER = 0
        private const val MAX_CONTEXT_CHARS = 65_536
        private const val CONTEXT_PROBE_CHARS = MAX_CONTEXT_CHARS + 1
        private const val MAX_CONTEXT_LINES = 8_192
        private const val MAX_PREVIEW_CHARS = 4_096
    }
}

private fun HarnessCancelReason.toTransactionReason(): EditorTransactionCancelReason = when (this) {
    HarnessCancelReason.POINTER_RELEASED -> EditorTransactionCancelReason.POINTER_RELEASED
    HarnessCancelReason.POINTER_CANCELLED -> EditorTransactionCancelReason.POINTER_CANCELLED
    HarnessCancelReason.INPUT_CONNECTION_LOST ->
        EditorTransactionCancelReason.INPUT_CONNECTION_LOST
    HarnessCancelReason.WINDOW_HIDDEN -> EditorTransactionCancelReason.WINDOW_HIDDEN
    HarnessCancelReason.CONFIGURATION_CHANGED ->
        EditorTransactionCancelReason.CONFIGURATION_CHANGED
    HarnessCancelReason.BRAIN_DIED -> EditorTransactionCancelReason.BRAIN_DIED
    HarnessCancelReason.CALLER_REQUESTED,
    HarnessCancelReason.EDITOR_CHANGED,
    -> EditorTransactionCancelReason.CALLER_REQUESTED
}
