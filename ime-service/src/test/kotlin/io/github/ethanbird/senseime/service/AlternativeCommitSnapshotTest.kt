package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativeCommitSnapshotTest {
    @Test
    fun reverseWubiCommitKeepsPinyinRouteAcrossReentrantReset() {
        val coordinator = coordinator(ChineseInputScheme.WUBI_86)
        coordinator.type('z', { "" }) { true }
        coordinator.type('n', { "" }) { true }
        assertTrue(coordinator.isWubiReverseLookup)

        val snapshot = snapshot(coordinator, hasCandidate = true)
        coordinator.reset()

        assertEquals("zn", snapshot.rawCode)
        assertEquals(AlternativeLearningDomain.PINYIN, snapshot.learningDomain)
        assertFalse(coordinator.isWubiReverseLookup)
        assertFalse(snapshot.stillOwnsComposition(coordinator.key()))
    }

    @Test
    fun t9CommitKeepsPinyinRouteAcrossSchemeChange() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        coordinator.type('4', { "" }) { true }
        val snapshot = snapshot(coordinator, hasCandidate = true)

        coordinator.applyPreferences(
            ImePreferencesV1(chineseInputScheme = ChineseInputScheme.WUBI_86),
        )

        assertEquals("4", snapshot.rawCode)
        assertEquals(AlternativeLearningDomain.PINYIN, snapshot.learningDomain)
        assertFalse(snapshot.stillOwnsComposition(coordinator.key()))
    }

    @Test
    fun directWubiCommitUsesWubiRoute() {
        val coordinator = coordinator(ChineseInputScheme.WUBI_86)
        coordinator.type('a', { "" }) { true }

        val snapshot = snapshot(coordinator, hasCandidate = true)

        assertEquals(AlternativeLearningDomain.WUBI, snapshot.learningDomain)
        assertTrue(snapshot.stillOwnsComposition(coordinator.key()))
    }

    @Test
    fun rawCommitHasNoLearningRoute() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        coordinator.type('4', { "" }) { true }

        val snapshot = snapshot(coordinator, hasCandidate = false)

        assertEquals(AlternativeLearningDomain.NONE, snapshot.learningDomain)
    }

    @Test
    fun editorGateRequiresSameSessionAndConnectionIdentity() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        coordinator.type('4', { "" }) { true }
        val connection = Any()
        val equalButDifferentConnection = String(charArrayOf('i', 'c'))
        val snapshot = AlternativeCommitSnapshot.capture(
            coordinator = coordinator,
            hasCandidate = true,
            editorSessionId = 7L,
            inputConnectionIdentity = connection,
        )

        assertTrue(snapshot.isSameEditor(7L, connection))
        assertFalse(snapshot.isSameEditor(8L, connection))
        assertFalse(snapshot.isSameEditor(7L, Any()))

        val equalConnection = String(charArrayOf('i', 'c'))
        val equalIdentitySnapshot = AlternativeCommitSnapshot.capture(
            coordinator = coordinator,
            hasCandidate = true,
            editorSessionId = 7L,
            inputConnectionIdentity = equalButDifferentConnection,
        )
        assertEquals(equalButDifferentConnection, equalConnection)
        assertFalse(equalIdentitySnapshot.isSameEditor(7L, equalConnection))
    }

    private fun snapshot(
        coordinator: ChineseInputSchemeCoordinator,
        hasCandidate: Boolean,
    ) = AlternativeCommitSnapshot.capture(
        coordinator = coordinator,
        hasCandidate = hasCandidate,
        editorSessionId = 1L,
        inputConnectionIdentity = Any(),
    )

    private fun coordinator(scheme: ChineseInputScheme) = ChineseInputSchemeCoordinator(
        ImePreferencesV1(chineseInputScheme = scheme),
    )
}
