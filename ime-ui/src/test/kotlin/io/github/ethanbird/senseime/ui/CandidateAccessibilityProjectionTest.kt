package io.github.ethanbird.senseime.ui

import org.junit.Assert.*
import org.junit.Test

class CandidateAccessibilityProjectionTest {
    @Test fun collapsedNodesAreClippedToTheVisibleStripAndMatchTouchTargets() {
        val panel = panel()
        publish(panel, 1, List(255) { "候选$it" })
        val projection = CandidateAccessibilityProjection()
        val nodes = projection.visible(panel, true)
        val words = nodes.filter { it.action is CandidateAccessibilityAction.Select }
        assertTrue(words.isNotEmpty())
        assertTrue(words.size < 20)
        for (node in words) {
            val hit = panel.hitTest(node.bounds.centerX, node.bounds.centerY, true) as CandidateHit.Value
            assertEquals((node.action as CandidateAccessibilityAction.Select).sourceIndex, hit.sourceIndex)
            assertTrue(node.bounds.left >= panel.collapsedViewportBounds!!.left)
            assertTrue(node.bounds.right <= panel.collapsedViewportBounds!!.right)
        }
        assertSame(nodes, projection.visible(panel, true))
    }

    @Test fun staleIdsAreNotReusedEvenForASameRevisionReplacement() {
        val panel = panel()
        val projection = CandidateAccessibilityProjection()
        publish(panel, 1, listOf("你", "拟"))
        val before = projection.visible(panel, true).first().id
        publish(panel, 1, listOf("拟", "你"))
        assertTrue(projection.visible(panel, true).none { it.id == before })
        publish(panel, 2, null)
        assertTrue(projection.visible(panel, true).none { it.action is CandidateAccessibilityAction.Select })
        publish(panel, 2, listOf("你", "拟"))
        assertTrue(projection.visible(panel, true).none { it.id == before })
        assertTrue(projection.visible(panel, false).isEmpty())
    }

    @Test fun expandedScrollingProjectsOnlyVisibleRowsAndRetainsSameBatchIdentities() {
        val panel = panel()
        val projection = CandidateAccessibilityProjection()
        publish(panel, 9, List(255) { "候选$it" })
        val firstId = projection.visible(panel, true).first().id
        panel.activate(CandidateControl.EXPAND, 400, 450, false, 1f)
        assertEquals(firstId, projection.visible(panel, true).first().id)
        val builds = panel.sceneBuildCount
        panel.expandedScrollState.scrollTo(panel.maximumExpandedScrollOffset / 2)
        val visible = projection.visible(panel, true)
        assertTrue(visible.none { it.id == firstId })
        assertTrue(visible.size < 80)
        for (node in visible.filter { it.action is CandidateAccessibilityAction.Select }) {
            val hit = panel.hitTest(node.bounds.centerX, node.bounds.centerY, true) as CandidateHit.Value
            assertEquals((node.action as CandidateAccessibilityAction.Select).sourceIndex, hit.sourceIndex)
            assertTrue(node.bounds.top >= panel.expandedGridBounds!!.top)
            assertTrue(node.bounds.bottom <= panel.expandedGridBounds!!.bottom)
        }
        assertEquals(builds, panel.sceneBuildCount)
        panel.expandedScrollState.scrollTo(0f)
        assertEquals(firstId, projection.visible(panel, true).first().id)
    }

    @Test fun pendingExpandedPanelRetainsOnlyTheCollapseAction() {
        val panel = panel()
        val projection = CandidateAccessibilityProjection()
        publish(panel, 1, List(40) { "字$it" })
        panel.activate(CandidateControl.EXPAND, 400, 450, false, 1f)
        val ids = projection.visible(panel, true).map { it.id }.toSet()
        publish(panel, 1, null)
        val pending = projection.visible(panel, true)
        assertEquals(listOf(CandidateAccessibilityAction.Control(CandidateControl.COLLAPSE)), pending.map { it.action })
        assertTrue(pending.none { it.id in ids })
    }

    @Test fun duplicateDisplayTextHasDistinctActionsAndAssociationsHaveADismissNode() {
        val panel = panel()
        val projection = CandidateAccessibilityProjection()
        panel.publish(4, "", listOf("你好", "你好"), 400, 450, false, 1f, association = true)
        val nodes = projection.visible(panel, true)
        val words = nodes.filter { it.action is CandidateAccessibilityAction.Select }
        assertEquals(2, words.size)
        assertNotEquals(words[0].id, words[1].id)
        assertTrue(words.all { it.description.startsWith("联想词，") })
        assertTrue(nodes.any { it.description == "关闭联想" })
    }

    private fun panel() = CandidatePanel(KeyboardMetrics.fromDensity(1f), 8f, CandidateTextMeasurer { _, _ -> 36f })
    private fun publish(panel: CandidatePanel, revision: Long, values: List<String>?) =
        panel.publish(revision, "ni", values, 400, 450, false, 1f)
}
