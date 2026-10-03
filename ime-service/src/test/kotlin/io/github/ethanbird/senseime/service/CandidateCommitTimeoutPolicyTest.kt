package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateCommitTimeoutPolicyTest {
    @Test fun coldFullPinyinGetsABoundedAssetLoadingWindowButOtherSchemesDoNot() {
        assertEquals(5_000L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.Candidate(8), true, true))
        assertEquals(120L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.Candidate(8), false, true))
        assertEquals(120L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.WubiOverflow(8), true, true))
    }
    @Test fun fullPinyinConfirmationAllowsTheObservedQueuedDecodeToFinish() {
        assertEquals(1_000L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.Candidate(8), true))
    }
    @Test fun alternativeCandidateWaitIsUnchanged() {
        assertEquals(120L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.Candidate(8), false))
    }
    @Test fun wubiOverflowNeverInheritsThePinyinBudget() {
        assertEquals(120L, CandidateCommitTimeoutPolicy.maximumWaitMillis(PendingDecodeCommit.WubiOverflow(8), true))
    }
}
