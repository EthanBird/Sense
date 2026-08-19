package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePanelTest {
    @Test
    fun `pending publication keeps prior candidates stale until same revision becomes ready`() {
        val panel = panel()
        val oldValues = candidates(12, prefix = "old")
        panel.publishAt(revision = 40L, text = "ni", values = oldValues)

        val pendingChange = panel.publishAt(
            revision = 41L,
            text = "nin",
            values = null,
        )

        assertEquals(41L, panel.compositionRevision)
        assertEquals(40L, panel.candidateRevision)
        assertEquals(oldValues, panel.candidates)
        assertFalse(panel.candidatesReady)
        assertTrue(pendingChange.cancelSettle)

        val readyValues = listOf("您", "您好", "您们")
        val readyChange = panel.publishAt(
            revision = 41L,
            text = "nin",
            values = readyValues,
        )

        assertEquals(41L, panel.compositionRevision)
        assertEquals(41L, panel.candidateRevision)
        assertEquals(readyValues, panel.candidates)
        assertTrue(panel.candidatesReady)
        assertTrue(readyChange.cancelSettle)
        assertFalse(readyChange.requiresKeySceneRebuild)

        val first = panel.visibleCandidates.first()
        val hit = panel.hitTest(first.bounds.centerX, first.bounds.centerY, visible = true)
        assertTrue(hit is CandidateHit.Value)
        hit as CandidateHit.Value
        assertEquals(41L, hit.revision)
        assertEquals(0, hit.sourceIndex)
        assertFalse(hit.expanded)
    }

    @Test
    fun `pending candidates are visibly disabled and expose no stale hit targets`() {
        val panel = panel()
        panel.publishAt(
            revision = 7L,
            text = "zhong",
            values = candidates(20),
        )
        assertTrue(panel.hasCollapsedOverflow)

        panel.publishAt(
            revision = 8L,
            text = "zhongw",
            values = null,
        )

        val staleCandidate = panel.visibleCandidates.first()
        val staleHit = panel.hitTest(
            staleCandidate.bounds.centerX,
            staleCandidate.bounds.centerY,
            visible = true,
        )
        assertFalse(panel.candidatesReady)
        assertFalse(panel.canStartCollapsedDrag())
        assertNull(staleHit)
        assertFalse(
            panel.beginDrag(
                pointerId = 3,
                x = staleCandidate.bounds.centerX,
                y = staleCandidate.bounds.centerY,
                eventTimeMillis = 1_000L,
            ),
        )

        val expand = panel.controls.single { it.control == CandidateControl.EXPAND }
        assertFalse(expand.enabled)
        assertNull(panel.hitTest(expand.bounds.centerX, expand.bounds.centerY, visible = true))
        assertFalse(panel.activateAt(CandidateControl.EXPAND).requiresKeySceneRebuild)
        assertFalse(panel.expanded)
        assertNull(
            panel.hitTest(
                staleCandidate.bounds.centerX,
                staleCandidate.bounds.centerY,
                visible = false,
            ),
        )
    }

    @Test
    fun `same revision pending publication disables expanded continuous candidates`() {
        val panel = panel()
        panel.publishAt(
            revision = 10L,
            text = "xian",
            values = candidates(80),
        )
        panel.activateAt(CandidateControl.EXPAND)
        assertTrue(panel.expanded)
        assertEquals("80 项", panel.expandedStatusLabel)
        assertTrue(panel.maximumExpandedScrollOffset > 0f)
        assertTrue(panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset / 2f))
        val retainedOffset = panel.expandedScrollOffset

        val pending = panel.publishAt(
            revision = 10L,
            text = "xian",
            values = null,
        )

        assertFalse(panel.candidatesReady)
        assertTrue(pending.cancelSettle)
        assertEquals(retainedOffset, panel.expandedScrollOffset, 0f)
        val staleCandidate = panel.visibleCandidates.first()
        assertNull(
            panel.hitTest(
                staleCandidate.bounds.centerX,
                staleCandidate.bounds.centerY - panel.expandedScrollOffset,
                visible = true,
            ),
        )
        assertEquals(listOf(CandidateControl.COLLAPSE), panel.controls.map { it.control })
        assertEquals("80 项", panel.expandedStatusLabel)
    }

    @Test
    fun `overflow expands into one continuous grid and preserves global source indexes`() {
        val panel = panel()
        panel.publishAt(
            revision = 12L,
            text = "candidate",
            values = candidates(80),
        )

        assertTrue(panel.hasCollapsedOverflow)
        assertTrue(panel.canStartCollapsedDrag())
        assertEquals(
            CandidateControl.EXPAND,
            panel.controls.single { it.enabled }.control,
        )

        val expandChange = panel.activateAt(CandidateControl.EXPAND)
        assertTrue(expandChange.requiresKeySceneRebuild)
        assertTrue(panel.expanded)
        assertFalse(panel.canStartCollapsedDrag())
        assertEquals("80 项", panel.expandedStatusLabel)
        assertEquals((0..79).toList(), panel.visibleCandidates.map { it.sourceIndex })
        assertEquals(listOf(CandidateControl.COLLAPSE), panel.controls.map { it.control })
        assertTrue(panel.maximumExpandedScrollOffset > 0f)

        val firstHit = panel.visibleCandidates.first().let { candidate ->
            panel.hitTest(candidate.bounds.centerX, candidate.bounds.centerY, visible = true)
        }
        assertTrue(firstHit is CandidateHit.Value)
        firstHit as CandidateHit.Value
        assertEquals(0, firstHit.sourceIndex)
        assertTrue(firstHit.expanded)

        val viewport = checkNotNull(panel.expandedGridBounds)
        val middle = panel.visibleCandidates[40]
        val buildCountBeforeScroll = panel.sceneBuildCount
        assertTrue(
            panel.expandedScrollState.scrollTo(
                middle.bounds.top - viewport.top,
            ),
        )
        val middleHit = panel.hitTest(
            x = middle.bounds.centerX,
            y = middle.bounds.centerY - panel.expandedScrollOffset,
            visible = true,
        )
        assertTrue(middleHit is CandidateHit.Value)
        assertEquals(40, (middleHit as CandidateHit.Value).sourceIndex)
        assertEquals(buildCountBeforeScroll, panel.sceneBuildCount)

        assertTrue(panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset))
        val last = panel.visibleCandidates.last()
        val lastHit = panel.hitTest(
            x = last.bounds.centerX,
            y = last.bounds.centerY - panel.expandedScrollOffset,
            visible = true,
        )
        assertTrue(lastHit is CandidateHit.Value)
        assertEquals(79, (lastHit as CandidateHit.Value).sourceIndex)
    }

    @Test
    fun `expanded continuous grid keeps its placeholder when the ready batch is empty`() {
        val panel = panel()
        panel.publishAt(
            revision = 13L,
            text = "candidate",
            values = candidates(20),
        )
        panel.activateAt(CandidateControl.EXPAND)

        val emptyReady = panel.publishAt(
            revision = 13L,
            text = "candidate",
            values = emptyList(),
        )

        assertTrue(panel.expanded)
        assertEquals("\u2026", panel.expandedStatusLabel)
        assertTrue(panel.visibleCandidates.isEmpty())
        assertEquals(
            listOf(CandidateControl.COLLAPSE),
            panel.controls.map { it.control },
        )
        assertTrue(emptyReady.cancelSettle)
    }

    @Test
    fun `expanded offset converges when a same revision candidate update shrinks content`() {
        val panel = panel()
        panel.publishAt(
            revision = 17L,
            text = "candidate",
            values = candidates(120, prefix = "before"),
        )
        panel.activateAt(CandidateControl.EXPAND)
        val previousMaximum = panel.maximumExpandedScrollOffset
        assertTrue(previousMaximum > 0f)
        assertTrue(panel.expandedScrollState.scrollTo(previousMaximum))

        panel.publishAt(
            revision = 17L,
            text = "candidate",
            values = candidates(30, prefix = "after"),
        )

        assertTrue(panel.expanded)
        assertTrue(panel.maximumExpandedScrollOffset < previousMaximum)
        assertEquals(panel.maximumExpandedScrollOffset, panel.expandedScrollOffset, 0f)
        assertTrue(panel.expandedScrollOffset in 0f..panel.maximumExpandedScrollOffset)
        assertEquals((0..29).toList(), panel.visibleCandidates.map { it.sourceIndex })
    }

    @Test
    fun `revision change cancels expanded interaction even when text and candidates are equal`() {
        val panel = panel()
        val values = candidates(80)
        panel.publishAt(revision = 91L, text = "same", values = values)
        panel.activateAt(CandidateControl.EXPAND)
        assertTrue(panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset))

        val change = panel.publishAt(
            revision = 92L,
            text = "same",
            values = values.toList(),
        )

        assertTrue(change.cancelInteraction)
        assertTrue(change.cancelSettle)
        assertFalse(panel.expanded)
        assertEquals(0f, panel.expandedScrollOffset, 0f)
    }

    @Test
    fun `pending and ready publications clear drag and offset inherited from the prior batch`() {
        val panel = panel()
        val values = candidates(20)
        panel.publishAt(
            revision = 50L,
            text = "old",
            values = values,
        )
        assertTrue(panel.moveTo(panel.maximumScrollOffset))
        assertTrue(
            panel.beginDrag(
                pointerId = 4,
                x = 180f,
                y = 24f,
                eventTimeMillis = 1_000L,
            ),
        )

        val pending = panel.publishAt(
            revision = 51L,
            text = "new",
            values = null,
        )

        assertTrue(pending.cancelSettle)
        assertEquals(0f, panel.scrollOffset, 0f)
        assertFalse(panel.ownsDrag(pointerId = 4))

        val ready = panel.publishAt(
            revision = 51L,
            text = "new",
            values = values,
        )

        assertTrue(ready.cancelSettle)
        assertEquals(0f, panel.scrollOffset, 0f)
        assertFalse(panel.ownsDrag(pointerId = 4))
        assertEquals(51L, panel.candidateRevision)
    }

    @Test
    fun `collapsed hit testing projects screen coordinates through current scroll offset`() {
        val panel = panel()
        panel.publishAt(
            revision = 9L,
            text = "scroll",
            values = candidates(16),
        )
        val viewport = checkNotNull(panel.collapsedViewportBounds)
        val target = panel.visibleCandidates[5]
        val targetOffset = target.bounds.left - viewport.left
        val buildCountBeforeScroll = panel.sceneBuildCount

        assertTrue(panel.moveTo(targetOffset))
        assertEquals(targetOffset, panel.scrollOffset, 0f)
        assertEquals(buildCountBeforeScroll, panel.sceneBuildCount)

        val screenX = target.bounds.centerX - panel.scrollOffset
        val hit = panel.hitTest(screenX, target.bounds.centerY, visible = true)

        assertTrue(hit is CandidateHit.Value)
        hit as CandidateHit.Value
        assertEquals(5, hit.sourceIndex)
        assertEquals(9L, hit.revision)
        assertEquals(viewport.left, hit.bounds.left, 0f)
        assertEquals(target.bounds.right - panel.scrollOffset, hit.bounds.right, 0f)
        assertEquals(targetOffset + screenX, target.bounds.centerX, 0f)
    }

    @Test
    fun `510 candidate measurements are reused across relayout scrolling and equal publication`() {
        val measurer = RecordingMeasurer()
        val panel = panel(measurer)
        val values = candidates(510)

        panel.publishAt(
            revision = 21L,
            text = "large",
            values = values,
        )
        assertEquals(510, measurer.callCount)
        val initialBuildCount = panel.sceneBuildCount
        val initialFirstCandidate = panel.visibleCandidates.first()

        panel.relayoutAt()

        assertEquals(initialBuildCount, panel.sceneBuildCount)
        assertSame(initialFirstCandidate, panel.visibleCandidates.first())

        panel.relayoutAt(viewWidth = VIEW_WIDTH + 1)
        panel.relayoutAt(viewWidth = VIEW_WIDTH)
        panel.activateAt(CandidateControl.EXPAND)
        panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset)
        panel.collapseAt()
        val buildCountBeforeEqualPublication = panel.sceneBuildCount
        val firstCandidateBeforeEqualPublication = panel.visibleCandidates.first()
        val equalPublication = panel.publishAt(
            revision = 21L,
            text = "large",
            values = values.toMutableList(),
        )

        assertFalse(equalPublication.cancelInteraction)
        assertEquals(510, measurer.callCount)
        assertEquals(510, measurer.measuredTexts.distinct().size)
        assertEquals(buildCountBeforeEqualPublication, panel.sceneBuildCount)
        assertSame(firstCandidateBeforeEqualPublication, panel.visibleCandidates.first())
    }

    @Test
    fun `font scale and toolbar takeover changes invalidate every measured width exactly once`() {
        val measurer = RecordingMeasurer()
        val panel = panel(measurer)
        val values = candidates(3)

        panel.publishAt(
            revision = 31L,
            text = "compose",
            values = values,
            editorPanelVisible = false,
            fontScale = 1f,
        )
        assertEquals(listOf(19f, 19f, 19f), measurer.textSizes)

        panel.relayoutAt(editorPanelVisible = false, fontScale = 1f)
        assertEquals(3, measurer.callCount)

        val fontScaleChange = panel.relayoutAt(
            editorPanelVisible = false,
            fontScale = 1.25f,
        )
        assertFalse(fontScaleChange.requiresKeySceneRebuild)
        assertEquals(
            listOf(23.75f, 23.75f, 23.75f),
            measurer.textSizes.takeLast(3),
        )

        val takeoverChange = panel.relayoutAt(
            editorPanelVisible = true,
            fontScale = 1.25f,
        )
        assertTrue(takeoverChange.requiresKeySceneRebuild)
        assertEquals(
            listOf(21.25f, 21.25f, 21.25f),
            measurer.textSizes.takeLast(3),
        )

        panel.relayoutAt(editorPanelVisible = true, fontScale = 1.25f)
        assertEquals(9, measurer.callCount)
    }

    @Test
    fun `candidate change rebuild flag only tracks key scene topology changes`() {
        val panel = panel()

        val idle = panel.publishAt(
            revision = 0L,
            text = "",
            values = candidates(20),
        )
        assertFalse(idle.requiresKeySceneRebuild)
        assertFalse(idle.cancelSettle)
        assertFalse(idle.cancelInteraction)

        val compositionStarted = panel.publishAt(
            revision = 1L,
            text = "a",
            values = null,
        )
        assertTrue(compositionStarted.requiresKeySceneRebuild)
        assertTrue(compositionStarted.cancelSettle)
        assertTrue(compositionStarted.cancelInteraction)

        val decodeReady = panel.publishAt(
            revision = 1L,
            text = "a",
            values = candidates(20),
        )
        assertFalse(decodeReady.requiresKeySceneRebuild)
        assertTrue(decodeReady.cancelSettle)
        assertTrue(decodeReady.cancelInteraction)

        val expanded = panel.activateAt(CandidateControl.EXPAND)
        assertTrue(expanded.requiresKeySceneRebuild)
        assertTrue(expanded.cancelSettle)
        assertTrue(expanded.cancelInteraction)

        val sceneBuildsBeforeScroll = panel.sceneBuildCount
        assertTrue(panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset))
        assertEquals(sceneBuildsBeforeScroll, panel.sceneBuildCount)

        val collapsed = panel.collapseAt()
        assertTrue(collapsed.requiresKeySceneRebuild)
        assertTrue(collapsed.cancelSettle)
        assertTrue(collapsed.cancelInteraction)

        val geometryOnly = panel.relayoutAt(viewWidth = VIEW_WIDTH + 10)
        assertFalse(geometryOnly.requiresKeySceneRebuild)

        val editorTookToolbarBack = panel.relayoutAt(
            viewWidth = VIEW_WIDTH + 10,
            editorPanelVisible = true,
        )
        assertTrue(editorTookToolbarBack.requiresKeySceneRebuild)
    }

    @Test
    fun `association strip replaces toolbar and reserves a fixed dismiss control`() {
        val panel = panel()

        val association = panel.publishAt(
            revision = 81L,
            text = "",
            values = listOf("开发", "能力", "项目", "体验"),
            association = true,
        )

        assertTrue(association.requiresKeySceneRebuild)
        assertTrue(panel.takesToolbar(editorPanelVisible = false))
        assertFalse(panel.expanded)
        assertEquals(
            listOf(CandidateControl.DISMISS),
            panel.controls.map { it.control },
        )
        val dismiss = panel.controls.single()
        assertTrue(dismiss.bounds.left >= VIEW_WIDTH - 52f)
        assertTrue(checkNotNull(panel.collapsedViewportBounds).right <= dismiss.bounds.left)

        val idle = panel.publishAt(
            revision = 82L,
            text = "",
            values = emptyList(),
            association = false,
        )
        assertTrue(idle.requiresKeySceneRebuild)
        assertFalse(panel.takesToolbar(editorPanelVisible = false))
        assertTrue(panel.controls.isEmpty())
    }

    private fun panel(
        measurer: RecordingMeasurer = RecordingMeasurer(),
    ): CandidatePanel = CandidatePanel(
        metrics = KeyboardMetrics.fromDensity(1f),
        touchSlop = 8f,
        textMeasurer = measurer,
    )

    private fun CandidatePanel.publishAt(
        revision: Long,
        text: String,
        values: List<String>?,
        viewWidth: Int = VIEW_WIDTH,
        viewHeight: Int = VIEW_HEIGHT,
        editorPanelVisible: Boolean = false,
        fontScale: Float = 1f,
        association: Boolean = false,
    ): CandidateChange = publish(
        revision = revision,
        text = text,
        values = values,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        editorPanelVisible = editorPanelVisible,
        fontScale = fontScale,
        association = association,
    )

    private fun CandidatePanel.relayoutAt(
        viewWidth: Int = VIEW_WIDTH,
        viewHeight: Int = VIEW_HEIGHT,
        editorPanelVisible: Boolean = false,
        fontScale: Float = 1f,
    ): CandidateChange = relayout(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        editorPanelVisible = editorPanelVisible,
        fontScale = fontScale,
    )

    private fun CandidatePanel.activateAt(
        control: CandidateControl,
    ): CandidateChange = activate(
        control = control,
        viewWidth = VIEW_WIDTH,
        viewHeight = VIEW_HEIGHT,
        editorPanelVisible = false,
        fontScale = 1f,
    )

    private fun CandidatePanel.collapseAt(): CandidateChange = collapse(
        viewWidth = VIEW_WIDTH,
        viewHeight = VIEW_HEIGHT,
        editorPanelVisible = false,
        fontScale = 1f,
    )

    private class RecordingMeasurer(
        private val width: Float = 20f,
    ) : CandidateTextMeasurer {
        val measuredTexts = mutableListOf<String>()
        val textSizes = mutableListOf<Float>()

        val callCount: Int
            get() = measuredTexts.size

        override fun measure(text: String, textSizePx: Float): Float {
            measuredTexts += text
            textSizes += textSizePx
            return width
        }
    }

    private companion object {
        const val VIEW_WIDTH = 220
        const val VIEW_HEIGHT = 360

        fun candidates(
            count: Int,
            prefix: String = "candidate",
        ): List<String> = List(count) { index -> "$prefix-$index" }
    }
}
