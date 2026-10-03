package io.github.ethanbird.senseime.ui

internal sealed interface CandidateAccessibilityAction {
    data class Select(val revision: Long, val sourceIndex: Int) : CandidateAccessibilityAction
    data class Control(val control: CandidateControl) : CandidateAccessibilityAction
}

internal data class AccessibleCandidate(
    val id: Int,
    val text: String,
    val description: String,
    val bounds: KeyboardRect,
    val action: CandidateAccessibilityAction,
)

/** Android-free projection of the *visible* clipped scene. IDs never alias a newer batch. */
internal class CandidateAccessibilityProjection {
    private var nextId = 1
    private var revision = Long.MIN_VALUE
    private var ready = false
    private var values: List<String> = emptyList()
    private val ids = HashMap<CandidateAccessibilityAction, Int>()
    private var cacheKey: Key? = null
    private var cached: List<AccessibleCandidate> = emptyList()

    fun visible(scene: CandidateScene, shown: Boolean): List<AccessibleCandidate> {
        if (revision != scene.candidateRevision || ready != scene.candidatesReady || values != scene.candidates) {
            revision = scene.candidateRevision
            ready = scene.candidatesReady
            values = scene.candidates.toList()
            ids.clear()
            cacheKey = null
        }
        val key = Key(scene.sceneBuildCount, scene.scrollOffset, scene.expandedScrollOffset, shown, scene.association)
        if (key == cacheKey) return cached
        cacheKey = key
        if (!shown) return emptyList<AccessibleCandidate>().also { cached = it }
        val result = ArrayList<AccessibleCandidate>()
        val viewport = if (scene.expanded) scene.expandedGridBounds else scene.collapsedViewportBounds
        if (ready && viewport != null) {
            val xOffset = if (scene.expanded) 0f else scene.scrollOffset
            val yOffset = if (scene.expanded) scene.expandedScrollOffset else 0f
            val first = if (scene.expanded) scene.firstExpandedCandidateEndingAfter(viewport.top + yOffset)
                else scene.firstCandidateEndingAfter(viewport.left + xOffset)
            for (index in first until scene.visibleCandidates.size) {
                val slot = scene.visibleCandidates[index]
                if (scene.expanded && slot.bounds.top - yOffset >= viewport.bottom ||
                    !scene.expanded && slot.bounds.left - xOffset >= viewport.right) break
                val bounds = KeyboardRect(maxOf(slot.bounds.left - xOffset, viewport.left),
                    maxOf(slot.bounds.top - yOffset, viewport.top), minOf(slot.bounds.right - xOffset, viewport.right),
                    minOf(slot.bounds.bottom - yOffset, viewport.bottom))
                if (bounds.isEmpty) continue
                val text = values.getOrNull(slot.sourceIndex) ?: continue
                val action = CandidateAccessibilityAction.Select(revision, slot.sourceIndex)
                result += AccessibleCandidate(id(action), text,
                    "${if (scene.association) "联想词" else "候选词"}，${slot.sourceIndex + 1}，$text", bounds, action)
            }
        }
        scene.controls.filter { it.enabled && !it.bounds.isEmpty }.forEach { slot ->
            val action = CandidateAccessibilityAction.Control(slot.control)
            val label = when (slot.control) {
                CandidateControl.EXPAND -> "展开候选"
                CandidateControl.COLLAPSE -> "收起候选"
                CandidateControl.DISMISS -> "关闭联想"
            }
            result += AccessibleCandidate(id(action), label, label, slot.bounds, action)
        }
        return result.toList().also { cached = it }
    }

    private fun id(action: CandidateAccessibilityAction) = ids.getOrPut(action) { nextId++ }
    private data class Key(val sceneBuild: Long, val x: Float, val y: Float, val shown: Boolean, val association: Boolean)
}
