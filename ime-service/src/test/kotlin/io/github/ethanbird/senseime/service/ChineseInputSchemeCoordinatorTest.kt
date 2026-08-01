package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.T9PinyinChoice
import io.github.ethanbird.senseime.core.T9SyllableIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun t9EditorPresentationCanPublishPinyinWithoutReplacingRawDecoderDigits() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        val published = ArrayList<String>()
        val display = mapOf("4" to "g", "48" to "hu", "486" to "hun")

        "486".forEach { digit ->
            assertEquals(
                AlternativeEditResult.CHANGED,
                coordinator.type(
                    character = digit,
                    captureLeftContext = { "" },
                    publish = { published += it; true },
                    presentT9 = { value -> display.getValue(value.rawDigits) },
                ),
            )
        }

        assertEquals(listOf("g", "hu", "hun"), published)
        assertEquals("486", coordinator.rawCode)
        assertEquals("hun", coordinator.editorComposingText)

        assertTrue(
            coordinator.backspace(
                publish = { published += it; true },
                presentT9 = { value -> display.getValue(value.rawDigits) },
            ),
        )
        assertEquals("48", coordinator.rawCode)
        assertEquals("hu", coordinator.editorComposingText)
        assertEquals("hu", published.last())
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
    fun t9SideRailChoiceIsRevisionBoundAndBackspaceReversible() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        "486".forEach { coordinator.type(it, { "" }) { true } }
        val originalPresentationRevision = coordinator.presentationRevision
        val choice = T9SyllableIndex(listOf("hun")).choices(coordinator.t9, 1).single()
        publishT9Choices(coordinator, choice)

        assertFalse(coordinator.selectT9PinyinChoice(originalPresentationRevision - 1, 0))
        assertFalse(coordinator.selectT9PinyinChoice(originalPresentationRevision, 1))
        assertTrue(coordinator.selectT9PinyinChoice(originalPresentationRevision, 0))
        assertEquals("486", coordinator.rawCode)
        assertEquals("hun", coordinator.t9.lockedEdges.single().spelling)
        assertEquals(originalPresentationRevision + 1, coordinator.presentationRevision)
        assertEquals(null, coordinator.currentDecoding())

        assertTrue(coordinator.backspace { true })
        assertTrue(coordinator.t9.lockedEdges.isEmpty())
        assertEquals("486", coordinator.rawCode)
    }

    @Test
    fun t9ReinputClearsOnlyAfterEditorAcceptsTheEmptyComposition() {
        val coordinator = coordinator(ChineseInputScheme.PINYIN_T9)
        "486".forEach { coordinator.type(it, { "" }) { true } }
        val before = coordinator.key()

        assertFalse(coordinator.clearT9Composition { false })
        assertEquals(before, coordinator.key())

        val published = ArrayList<String>()
        assertTrue(coordinator.clearT9Composition { published += it; true })
        assertEquals(listOf(""), published)
        assertEquals("", coordinator.rawCode)
        assertNotEquals(before.schemeEpoch, coordinator.key().schemeEpoch)
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

    private fun publishT9Choices(
        coordinator: ChineseInputSchemeCoordinator,
        vararg choices: T9PinyinChoice,
    ) {
        val key = coordinator.key()
        val request = AlternativeDecodeRequest(
            key = key,
            t9Composition = coordinator.t9,
            wubiComposition = null,
            t9Index = T9SyllableIndex(listOf("hun")),
            pinyinDecoder = FakeDecoder(),
            pinyinDecoderGeneration = 1,
            wubiDecoder = null,
            wubiCandidateDecoder = null,
            wubiDecoderGeneration = 1,
            leftContext = "",
            limit = 8,
        )
        coordinator.begin(request, forceDecode = false)
        assertNotNull(
            coordinator.complete(
                request = request,
                decoding = AlternativeDecoding(
                    key = key,
                    composingLabel = "hun",
                    candidates = emptyList(),
                    candidateLabels = emptyList(),
                    t9PinyinChoices = choices.toList(),
                ),
                activePinyinGeneration = 1,
                activeWubiGeneration = 1,
            ),
        )
    }
}
