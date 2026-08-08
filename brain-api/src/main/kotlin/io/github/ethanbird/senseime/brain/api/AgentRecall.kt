package io.github.ethanbird.senseime.brain.api

/**
 * Immutable, request-frozen cross-session recall assembled before model admission.
 *
 * Evidence can come from complete raw Session records or typed semantic sidecars. Semantic hits
 * always retain links to their source records, so a derived event never becomes the sole authority.
 */
data class AgentRecallFrame(
    val query: String,
    val evidence: List<AgentRecallEvidence>,
    val coverage: AgentRecallCoverage,
) {
    init {
        require(query.length <= MAX_QUERY_CHARS)
        require(evidence.size <= MAX_EVIDENCE)
        require(evidence.map(AgentRecallEvidence::recordId).toSet().size == evidence.size)
    }

    companion object {
        const val MAX_QUERY_CHARS = 256
        const val MAX_EVIDENCE = 6

        val EMPTY = AgentRecallFrame(
            query = "",
            evidence = emptyList(),
            coverage = AgentRecallCoverage(),
        )
    }
}

data class AgentRecallEvidence(
    val recordId: String,
    val text: String,
    val source: String,
    val channel: String,
    val evidenceRecordIds: List<String>,
) {
    init {
        require(recordId.isNotBlank() && recordId.length <= 256)
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS)
        require(source.length <= 256)
        require(channel.isNotBlank() && channel.length <= 64)
        require(evidenceRecordIds.size <= 8)
        require(evidenceRecordIds.all { it.isNotBlank() && it.length <= 256 })
    }

    companion object {
        const val MAX_TEXT_CHARS = 1_000
    }
}

data class AgentRecallCoverage(
    val scannedRecords: Int = 0,
    val scannedBytes: Long = 0L,
    val truncated: Boolean = false,
    val channels: Set<String> = emptySet(),
) {
    init {
        require(scannedRecords >= 0)
        require(scannedBytes >= 0L)
        require(channels.all { it.isNotBlank() && it.length <= 64 })
    }
}
