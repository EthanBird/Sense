package io.github.ethanbird.senseime.memory.journal.core

import io.github.ethanbird.senseime.memory.protocol.FeatureStageV1

/**
 * The only event-journal availability state shipped by X-02.
 *
 * This diagnostic has no wire token and grants no append, read, recall, storage, or effect API.
 */
enum class X02EventJournalAvailabilityV1 {
    SCHEMA_ONLY_NO_STORAGE,
}

/**
 * Non-persistent module seam for the future M9A-01 protocol and codec work.
 *
 * X-02 intentionally exposes no body/byte input, record identity, acknowledgement, writer,
 * reader, directory, file, or Android adapter.
 */
object X02EventJournalScaffoldV1 {
    fun availability(): X02EventJournalAvailabilityV1 =
        X02EventJournalAvailabilityV1.SCHEMA_ONLY_NO_STORAGE

    fun normalStageCeiling(): FeatureStageV1 = FeatureStageV1.SCHEMA_ONLY
}
