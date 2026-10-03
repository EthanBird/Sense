package io.github.ethanbird.senseime.service

/** Full-pinyin deadlines show recovery controls; they do not authorize a raw-text commit. */
internal object CandidateCommitTimeoutPolicy {
    fun maximumWaitMillis(
        intent: PendingDecodeCommit,
        fullPinyin: Boolean,
        runtimeLoading: Boolean = false,
    ): Long = when {
        !fullPinyin || intent !is PendingDecodeCommit.Candidate -> 120L
        // Includes the one-off off-main-thread asset load; publication resets the notice timer
        // without replacing the pending transaction or dropping queued input.
        runtimeLoading -> 5_000L
        else -> 1_000L
    }
}
