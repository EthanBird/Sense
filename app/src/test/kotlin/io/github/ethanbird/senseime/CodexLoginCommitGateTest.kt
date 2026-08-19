package io.github.ethanbird.senseime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLoginCommitGateTest {
    @Test
    fun cancelAfterExchangeAndBeforeSavePreservesTheOldCredential() {
        val gate = CodexLoginCommitGate()
        val credential = AtomicReference("old-credential")
        val exchangeCompleted = CountDownLatch(1)
        val allowCommitAttempt = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        gate.begin(GENERATION)

        try {
            val outcome = worker.submit<CodexLoginCommitResult<Unit>> {
                // The network exchange has completed, but the worker has not yet claimed the vault
                // commit. This is the lifecycle race that previously overwrote the old credential.
                exchangeCompleted.countDown()
                check(allowCommitAttempt.await(2, TimeUnit.SECONDS))
                gate.commitIfActive(GENERATION) {
                    credential.set("new-credential")
                }
            }

            assertTrue(exchangeCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(gate.cancel(GENERATION))
            allowCommitAttempt.countDown()

            assertSame(CodexLoginCommitResult.Rejected, outcome.get(2, TimeUnit.SECONDS))
            assertEquals("old-credential", credential.get())
        } finally {
            allowCommitAttempt.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun closeAfterExchangeAndBeforeSavePreservesTheOldCredential() {
        val gate = CodexLoginCommitGate()
        val credential = AtomicReference("old-credential")
        gate.begin(GENERATION)

        assertTrue(gate.close())
        val outcome = gate.commitIfActive(GENERATION) {
            credential.set("new-credential")
        }

        assertSame(CodexLoginCommitResult.Rejected, outcome)
        assertEquals("old-credential", credential.get())
    }

    @Test
    fun normalSuccessCommitsAndACommitWinnerIsNotReportedAsCancelled() {
        val gate = CodexLoginCommitGate()
        val credential = AtomicReference("old-credential")
        val commitClaimed = CountDownLatch(1)
        val allowSave = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        gate.begin(GENERATION)

        try {
            val outcome = worker.submit<CodexLoginCommitResult<Unit>> {
                gate.commitIfActive(GENERATION) {
                    commitClaimed.countDown()
                    check(allowSave.await(2, TimeUnit.SECONDS))
                    credential.set("new-credential")
                }
            }

            assertTrue(commitClaimed.await(2, TimeUnit.SECONDS))
            assertFalse(gate.cancel(GENERATION))
            allowSave.countDown()

            val accepted = outcome.get(2, TimeUnit.SECONDS) as CodexLoginCommitResult.Accepted
            assertTrue(accepted.result.isSuccess)
            assertEquals("new-credential", credential.get())
            gate.finish(GENERATION)
        } finally {
            allowSave.countDown()
            worker.shutdownNow()
        }
    }

    private companion object {
        const val GENERATION = 7L
    }
}
