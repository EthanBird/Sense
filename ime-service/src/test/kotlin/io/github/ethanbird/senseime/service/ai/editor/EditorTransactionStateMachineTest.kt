package io.github.ethanbird.senseime.service.ai.editor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTransactionStateMachineTest {
    @Test
    fun happyPathUsesValidationAndApplyCas() {
        val machine = machine()

        assertChanged(
            machine.beginValidation(REQUEST, GENERATION),
            EditorTransactionState.VALIDATING,
        )
        assertChanged(
            machine.tryBeginApply(REQUEST, GENERATION, APPLICATION_TOKEN),
            EditorTransactionState.APPLYING,
        )
        assertChanged(
            machine.markApplied(REQUEST, GENERATION, APPLICATION_TOKEN),
            EditorTransactionState.APPLIED,
        )
        assertEquals(APPLICATION_TOKEN, machine.snapshot.applicationToken)
    }

    @Test
    fun cancellationBeforeApplyIsTerminal() {
        val machine = machine()

        val cancelled = machine.cancel(
            REQUEST,
            GENERATION,
            EditorTransactionCancelReason.POINTER_RELEASED,
        )

        assertChanged(cancelled, EditorTransactionState.CANCELLED)
        assertEquals(
            EditorTransactionCancelReason.POINTER_RELEASED,
            machine.snapshot.cancelReason,
        )
        assertRejected(
            machine.beginValidation(REQUEST, GENERATION),
            EditorTransitionRejectReason.TERMINATED,
        )
    }

    @Test
    fun cancellationWinsWhileValidating() {
        val machine = validating()

        assertChanged(
            machine.cancel(
                REQUEST,
                GENERATION,
                EditorTransactionCancelReason.POINTER_CANCELLED,
            ),
            EditorTransactionState.CANCELLED,
        )
        assertRejected(
            machine.tryBeginApply(REQUEST, GENERATION, APPLICATION_TOKEN),
            EditorTransitionRejectReason.TERMINATED,
        )
    }

    @Test
    fun cancellationAfterApplyCasCannotInterruptHalfIssuedBatch() {
        val machine = applying()

        assertRejected(
            machine.cancel(
                REQUEST,
                GENERATION,
                EditorTransactionCancelReason.POINTER_RELEASED,
            ),
            EditorTransitionRejectReason.INVALID_STATE,
        )
        assertEquals(EditorTransactionState.APPLYING, machine.snapshot.state)
    }

    @Test
    fun externalEditorChangesStaleStreamingValidationAndApplying() {
        listOf(machine(), validating(), applying()).forEach { machine ->
            assertChanged(
                machine.markEditorChanged(EditorStaleReason.TEXT_CHANGED),
                EditorTransactionState.STALE,
            )
            assertEquals(EditorStaleReason.TEXT_CHANGED, machine.snapshot.staleReason)
        }
    }

    @Test
    fun ownApplySelectionCallbackDoesNotStaleTransaction() {
        val machine = applying()

        val transition = machine.markEditorChanged(
            EditorStaleReason.SELECTION_CHANGED,
            ownApplicationToken = APPLICATION_TOKEN,
        )

        assertTrue(transition is EditorTransactionTransition.OwnApplyObservationIgnored)
        assertEquals(EditorTransactionState.APPLYING, machine.snapshot.state)
        assertNull(machine.snapshot.staleReason)
    }

    @Test
    fun missingOrWrongApplyTokenMakesCallbackExternalAndStale() {
        listOf(null, APPLICATION_TOKEN + 1).forEach { token ->
            val machine = applying()
            assertChanged(
                machine.markEditorChanged(
                    EditorStaleReason.SELECTION_CHANGED,
                    ownApplicationToken = token,
                ),
                EditorTransactionState.STALE,
            )
        }
    }

    @Test
    fun owningTokenCannotHideConnectionOrFieldReplacement() {
        listOf(
            EditorStaleReason.FIELD_IDENTITY_CHANGED,
            EditorStaleReason.INPUT_CONNECTION_CHANGED,
            EditorStaleReason.START_INPUT,
            EditorStaleReason.FINISH_INPUT,
        ).forEach { reason ->
            val machine = applying()
            assertChanged(
                machine.markEditorChanged(
                    reason,
                    ownApplicationToken = APPLICATION_TOKEN,
                ),
                EditorTransactionState.STALE,
            )
        }
    }

    @Test
    fun wrongRequestAndGenerationNeverAdvanceState() {
        val machine = machine()

        assertRejected(
            machine.beginValidation("other", GENERATION),
            EditorTransitionRejectReason.REQUEST_MISMATCH,
        )
        assertRejected(
            machine.beginValidation(REQUEST, GENERATION + 1),
            EditorTransitionRejectReason.RUN_GENERATION_MISMATCH,
        )
        assertEquals(EditorTransactionState.STREAMING, machine.snapshot.state)
    }

    @Test
    fun applicationTokenIsRequiredAndMustMatchCompletion() {
        val machine = validating()
        assertRejected(
            machine.tryBeginApply(REQUEST, GENERATION, 0),
            EditorTransitionRejectReason.INVALID_STATE,
        )
        assertChanged(
            machine.tryBeginApply(REQUEST, GENERATION, APPLICATION_TOKEN),
            EditorTransactionState.APPLYING,
        )
        assertRejected(
            machine.markApplied(REQUEST, GENERATION, APPLICATION_TOKEN + 1),
            EditorTransitionRejectReason.APPLICATION_TOKEN_MISMATCH,
        )
        assertEquals(EditorTransactionState.APPLYING, machine.snapshot.state)
    }

    @Test
    fun failureDuringApplyAlsoRequiresOwningToken() {
        val machine = applying()

        assertRejected(
            machine.fail(
                EditorTransactionFailure.EDITOR_REJECTED,
                APPLICATION_TOKEN + 1,
            ),
            EditorTransitionRejectReason.APPLICATION_TOKEN_MISMATCH,
        )
        assertChanged(
            machine.fail(
                EditorTransactionFailure.EDITOR_REJECTED,
                APPLICATION_TOKEN,
            ),
            EditorTransactionState.FAILED,
        )
        assertEquals(EditorTransactionFailure.EDITOR_REJECTED, machine.snapshot.failure)
    }

    @Test
    fun pointerCancelAndApplyCasHaveExactlyOneWinnerUnderRace() {
        repeat(200) {
            val machine = validating()
            val ready = CountDownLatch(2)
            val fire = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val cancel = executor.submit<EditorTransactionTransition> {
                    ready.countDown()
                    fire.await()
                    machine.cancel(
                        REQUEST,
                        GENERATION,
                        EditorTransactionCancelReason.POINTER_RELEASED,
                    )
                }
                val apply = executor.submit<EditorTransactionTransition> {
                    ready.countDown()
                    fire.await()
                    machine.tryBeginApply(
                        REQUEST,
                        GENERATION,
                        APPLICATION_TOKEN,
                    )
                }
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                fire.countDown()
                val results = listOf(cancel.get(), apply.get())
                assertEquals(
                    1,
                    results.count { it is EditorTransactionTransition.Changed },
                )
                assertTrue(
                    machine.snapshot.state == EditorTransactionState.CANCELLED ||
                        machine.snapshot.state == EditorTransactionState.APPLYING,
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun machine(): EditorTransactionStateMachine =
        EditorTransactionStateMachine(
            requestId = REQUEST,
            runGeneration = GENERATION,
            editorGeneration = 3,
        )

    private fun validating(): EditorTransactionStateMachine = machine().also {
        it.beginValidation(REQUEST, GENERATION)
    }

    private fun applying(): EditorTransactionStateMachine = validating().also {
        it.tryBeginApply(REQUEST, GENERATION, APPLICATION_TOKEN)
    }

    private fun assertChanged(
        transition: EditorTransactionTransition,
        expected: EditorTransactionState,
    ) {
        assertEquals(
            expected,
            (transition as EditorTransactionTransition.Changed).current.state,
        )
    }

    private fun assertRejected(
        transition: EditorTransactionTransition,
        expected: EditorTransitionRejectReason,
    ) {
        assertEquals(
            expected,
            (transition as EditorTransactionTransition.Rejected).reason,
        )
    }

    private companion object {
        const val REQUEST = "request-1"
        const val GENERATION = 9L
        const val APPLICATION_TOKEN = 12L
    }
}
