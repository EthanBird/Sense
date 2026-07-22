package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateSnapshotTest {
    private val snapshot = CandidateSnapshot(
        revision = 7L,
        candidates = listOf(Candidate("我"), Candidate("为")),
    )

    @Test
    fun selectsByGlobalIndexOnlyForTheActiveRevision() {
        assertEquals("为", snapshot.select(7L, 7L, 1)?.text)
        assertNull(snapshot.select(8L, 7L, 1))
        assertNull(snapshot.select(7L, 6L, 1))
    }

    @Test
    fun invalidUiIndexNeverFallsBackToRawInput() {
        assertNull(snapshot.select(7L, 7L, -1))
        assertNull(snapshot.select(7L, 7L, 2))
    }
}
