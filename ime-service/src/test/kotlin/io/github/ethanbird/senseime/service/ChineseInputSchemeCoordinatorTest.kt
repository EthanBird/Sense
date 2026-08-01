package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseInputSchemeCoordinatorTest {
    @Test
    fun t9EditsPublishBeforeStateAndCaptureLeftContextOnce() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        val published = ArrayList<String>()
        var contextReads = 0

        val first = coordinator.type(
            character = '4',
            captureLeftContext = { contextReads += 1; "你" },
            publish = { published += it; true },
        )
        val second = coordinator.type(
            character = '8',
            captureLeftContext = { contextReads += 1; "错" },
            publish = { published += it; true },
        )

        assertEquals(AlternativeEditResult.CHANGED, first)
        assertEquals(AlternativeEditResult.CHANGED, second)
        assertEquals(listOf("4", "48"), published)
        assertEquals("48", coordinator.rawCode)
        assertEquals("你", coordinator.leftContext)
        assertEquals(1, contextReads)
        assertEquals(2L, coordinator.presentationRevision)
    }

    @Test
    fun failedEditorPublicationLeavesAlternativeStateUnchanged() {
        val coordinator = coordinator(ChineseInputScheme.WUBI_86)
        val before = coordinator.key()

        val result = coordinator.type('a', { "前" }) { false }

        assertEquals(AlternativeEditResult.REJECTED, result)
        assertEquals("", coordinator.rawCode)
        assertEquals("", coordinator.leftContext)
        assertEquals(before, coordinator.key())
    }

    @Test
    fun t9SeparatorIsConsumedAndBackspaceRemovesItBeforeDigits() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        val published = ArrayList<String>()
        val publish: (String) -> Boolean = { published += it; true }

        assertEquals(AlternativeEditResult.CONSUMED, coordinator.type('1', { "" }, publish))
        coordinator.type('4', { "" }, publish)
        coordinator.type('1', { "" }, publish)
        assertEquals(AlternativeEditResult.CONSUMED, coordinator.type('1', { "" }, publish))

        assertTrue(coordinator.backspace(publish))
        assertEquals("4", coordinator.rawCode)
        assertTrue(coordinator.backspace(publish))
        assertEquals("", coordinator.rawCode)
        assertEquals(listOf("4", "4", "4", ""), published)
    }

    @Test
    fun reentrantResetDuringEditorPublicationDoesNotResurrectStaleState() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        val beforeEpoch = coordinator.key().schemeEpoch

        val result = coordinator.type('4', { "左" }) {
            coordinator.reset()
            true
        }

        assertEquals(AlternativeEditResult.REJECTED, result)
        assertEquals("", coordinator.rawCode)
        assertEquals("", coordinator.leftContext)
        assertNotEquals(beforeEpoch, coordinator.key().schemeEpoch)
    }

    @Test
    fun schemeChangeResetsCompositionAndInvalidatesEpoch() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        coordinator.type('4', { "左" }) { true }
        val oldKey = coordinator.key()

        val changed = coordinator.applyPreferences(
            ImePreferencesV1(chineseInputScheme = ChineseInputScheme.WUBI_86),
        )

        assertTrue(changed)
        assertEquals(ChineseInputScheme.WUBI_86, coordinator.scheme)
        assertEquals("", coordinator.rawCode)
        assertEquals("", coordinator.leftContext)
        assertNotEquals(oldKey.schemeEpoch, coordinator.key().schemeEpoch)
        assertNotEquals(oldKey.presentationRevision, coordinator.presentationRevision)
    }

    @Test
    fun wubiPoliciesAndReverseLookupStayInsideSchemeState() {
        val coordinator = ChineseInputSchemeCoordinator(
            ImePreferencesV1(
                chineseInputScheme = ChineseInputScheme.WUBI_86,
                wubiAutoCommitMode = WubiAutoCommitMode.RIME_STYLE,
            ),
        )
        repeat(4) { coordinator.type('a', { "" }) { true } }

        assertEquals(WubiAutoCommitMode.RIME_STYLE, coordinator.preferences.wubiAutoCommitMode)
        assertFalse(coordinator.shouldUniqueAtFourCommit())
        coordinator.clearAfterCommit()
        coordinator.type('z', { "" }) { true }
        coordinator.type('n', { "" }) { true }

        assertEquals("zn", coordinator.rawCode)
        assertTrue(coordinator.isWubiReverseLookup)
        assertTrue(coordinator.learnsPinyinOnCommit)
    }

    @Test
    fun qwertySchemeDoesNotConsumeAlternativeCharacters() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_QWERTY)

        assertEquals(
            AlternativeEditResult.UNHANDLED,
            coordinator.type('2', { "" }) { true },
        )
        assertFalse(coordinator.backspace { true })
    }

    @Test
    fun newestLoadedPreferencesWaitForTheNextCompositionBoundary() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_QWERTY)
        val t9 = ImePreferencesV1(chineseInputScheme = ChineseInputScheme.PINYIN_T9)
        val wubi = ImePreferencesV1(chineseInputScheme = ChineseInputScheme.WUBI_86)

        assertEquals(null, coordinator.acceptLoadedPreferences(t9, compositionActive = true))
        assertEquals(null, coordinator.acceptLoadedPreferences(wubi, compositionActive = true))
        assertEquals(null, coordinator.takePendingPreferences(compositionActive = true))
        assertEquals(wubi, coordinator.takePendingPreferences(compositionActive = false))
        assertEquals(null, coordinator.takePendingPreferences(compositionActive = false))
    }

    private fun coordinator(scheme: ChineseInputScheme) = ChineseInputSchemeCoordinator(
        ImePreferencesV1(chineseInputScheme = scheme),
    )
}
