package io.github.ethanbird.senseime.ui

/**
 * Read-only candidate scene consumed by rendering and Android touch adapters.
 *
 * Lists are stable mutable buffers owned by [CandidatePanel]. Callers must not
 * retain an item across a subsequent publish/relayout.
 */
internal interface CandidateScene {
    val composing: String
    val compositionRevision: Long
    /** Revision of [candidates], which may be retained while a newer batch is pending. */
    val candidateRevision: Long
    /** False means retained candidates are visual continuity only and are inert. */
    val candidatesReady: Boolean
    val candidates: List<String>
    /** True while a transient next-word strip owns the toolbar row. */
    val association: Boolean
    val visibleCandidates: List<VisibleCandidate>
    val controls: List<CandidateControlSlot>
    val expanded: Boolean
    val expandedStatusLabel: String
    val expandedGridBounds: KeyboardRect?
    val collapsedViewportBounds: KeyboardRect?
    val hasCollapsedOverflow: Boolean
    /** Horizontal offset of the compact strip. */
    val scrollOffset: Float
    val maximumScrollOffset: Float
    /** Vertical content offset of the expanded continuous grid. */
    val expandedScrollOffset: Float
    val maximumExpandedScrollOffset: Float
    val sceneBuildCount: Long

    fun firstCandidateEndingAfter(contentX: Float): Int
    fun firstExpandedCandidateEndingAfter(contentY: Float): Int
}

internal data class CandidateChange(
    val requiresKeySceneRebuild: Boolean,
    val cancelSettle: Boolean = false,
    /** Invalidates frozen candidate pointers and expanded kinetic scrolling. */
    val cancelInteraction: Boolean = false,
)

internal sealed interface CandidateHit {
    val bounds: KeyboardRect

    data class Value(
        val revision: Long,
        val sourceIndex: Int,
        val expanded: Boolean,
        override val bounds: KeyboardRect,
    ) : CandidateHit

    data class Control(
        val control: CandidateControl,
        override val bounds: KeyboardRect,
    ) : CandidateHit

    data class GridArea(
        override val bounds: KeyboardRect,
    ) : CandidateHit

    data class StripArea(
        override val bounds: KeyboardRect,
    ) : CandidateHit
}

/**
 * Deep module owning candidate publication, cached scene layout, hit testing,
 * continuous expanded scrolling, and compact-strip horizontal drag state.
 *
 * Android frame scheduling and Canvas rendering deliberately remain in the
 * View. This module has no View, Canvas, MotionEvent, or RectF dependency.
 */
internal class CandidatePanel(
    private val metrics: KeyboardMetrics,
    touchSlop: Float,
    private val textMeasurer: CandidateTextMeasurer,
) : CandidateScene {
    private val mutableVisibleCandidates = ArrayList<VisibleCandidate>()
    private val mutableControls = ArrayList<CandidateControlSlot>(3)
    private var mutableCandidates: List<String> = emptyList()
    private var measuredWidths = FloatArray(0)
    private var widthsMeasured = false
    private var geometryGeneration = 0L
    private var expandedGridCacheKey: CandidateGridCacheKey? = null
    private var expandedGrid: KeyboardLayoutContract.ContinuousCandidateGrid? = null
    private var sceneCacheKey: CandidateSceneCacheKey? = null
    private val stripLayoutCache = CandidateStripLayoutCache()
    private var collapsedLayout: CandidateStripGeometry.Layout? = null
    private var configuredStripLayout: CandidateStripGeometry.Layout? = null
    private val stripScrollState = CandidateStripScrollState(touchSlop = touchSlop)
    internal val expandedScrollState = ContinuousVerticalScrollState()

    private var editorPanelVisible = false
    private var fontScale = 1f
    private var viewWidth = 0
    private var viewHeight = 0
    private var measuredTextSizePx = Float.NaN

    override var composing: String = ""
        private set

    override var compositionRevision: Long = 0L
        private set

    override var candidateRevision: Long = 0L
        private set

    override var candidatesReady: Boolean = true
        private set

    override var association: Boolean = false
        private set

    override val candidates: List<String>
        get() = mutableCandidates

    override val visibleCandidates: List<VisibleCandidate>
        get() = mutableVisibleCandidates

    override val controls: List<CandidateControlSlot>
        get() = mutableControls

    override var expanded: Boolean = false
        private set

    override var expandedStatusLabel: String = ""
        private set

    override var expandedGridBounds: KeyboardRect? = null
        private set

    override var collapsedViewportBounds: KeyboardRect? = null
        private set

    override val hasCollapsedOverflow: Boolean
        get() = collapsedLayout?.hasOverflow == true

    override val scrollOffset: Float
        get() = stripScrollState.offset

    override val maximumScrollOffset: Float
        get() = stripScrollState.maximumOffset

    override val expandedScrollOffset: Float
        get() = expandedScrollState.offset

    override val maximumExpandedScrollOffset: Float
        get() = expandedScrollState.maximumOffset

    override var sceneBuildCount: Long = 0L
        private set

    fun publish(
        revision: Long,
        text: String,
        values: List<String>?,
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
        association: Boolean = false,
    ): CandidateChange {
        val previousTakeover = takesToolbar(this.editorPanelVisible)
        val previousExpanded = expanded
        val associationChanged = this.association != association
        val shouldResetNavigation = associationChanged ||
            CandidatePresentationPolicy.shouldResetNavigation(
                previousRevision = compositionRevision,
                previousComposing = composing,
                nextRevision = revision,
                nextComposing = text,
            )
        val nextCandidates = when {
            values != null -> values.toList()
            text.isEmpty() -> emptyList()
            else -> null
        }
        val nextCandidatesReady = nextCandidates != null
        val candidateSnapshotChanged =
            nextCandidates != null && nextCandidates != mutableCandidates
        val candidateBatchBecameCurrent =
            nextCandidates != null && candidateRevision != revision
        val candidateReadinessChanged =
            candidatesReady != nextCandidatesReady
        val hadCandidateGeometry = mutableCandidates.isNotEmpty()
        // A ready snapshot owns fresh strip coordinates, even when the
        // composition revision is unchanged. Preserve its expanded page state,
        // but detach any drag/settle inherited from retained stale candidates.
        val shouldResetStripInteraction =
            shouldResetNavigation ||
                (
                    hadCandidateGeometry &&
                        (
                            candidateSnapshotChanged ||
                                candidateBatchBecameCurrent ||
                                candidateReadinessChanged
                        )
                )
        if (shouldResetStripInteraction) {
            stripScrollState.reset()
        }
        if (shouldResetNavigation) {
            expanded = false
            expandedScrollState.reset()
        }

        if (candidateSnapshotChanged) {
            mutableCandidates = checkNotNull(nextCandidates)
            resetMeasurementsAndGeometry()
        } else if (nextCandidates != null) {
            // Keep the published snapshot detached from a mutable caller list.
            mutableCandidates = nextCandidates
        }

        compositionRevision = revision
        composing = text
        this.association = association
        if (associationChanged) resetMeasurementsAndGeometry()
        if (nextCandidates != null) {
            candidateRevision = revision
        }
        // Null is an explicit pending publication. Keep the previous batch only
        // as inert visual continuity, even if a defensive caller reuses a revision.
        candidatesReady = nextCandidatesReady

        updateViewportConfiguration(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            editorPanelVisible = editorPanelVisible,
            fontScale = fontScale,
        )
        relayoutInternal()

        return CandidateChange(
            requiresKeySceneRebuild =
                previousExpanded != expanded ||
                    previousTakeover != takesToolbar(editorPanelVisible),
            cancelSettle = shouldResetStripInteraction,
            cancelInteraction = shouldResetStripInteraction,
        )
    }

    fun relayout(
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ): CandidateChange {
        val previousTakeover = takesToolbar(this.editorPanelVisible)
        val previousExpanded = expanded
        updateViewportConfiguration(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            editorPanelVisible = editorPanelVisible,
            fontScale = fontScale,
        )
        relayoutInternal()
        return CandidateChange(
            requiresKeySceneRebuild =
                previousExpanded != expanded ||
                    previousTakeover != takesToolbar(editorPanelVisible),
        )
    }

    fun collapse(
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ): CandidateChange {
        val previousExpanded = expanded
        expanded = false
        expandedScrollState.reset()
        val relayout = relayout(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            editorPanelVisible = editorPanelVisible,
            fontScale = fontScale,
        )
        return relayout.copy(
            requiresKeySceneRebuild =
                relayout.requiresKeySceneRebuild || previousExpanded,
            cancelSettle = relayout.cancelSettle || previousExpanded,
            cancelInteraction = relayout.cancelInteraction || previousExpanded,
        )
    }

    fun activate(
        control: CandidateControl,
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ): CandidateChange {
        if (!candidatesReady && control != CandidateControl.COLLAPSE) {
            return CandidateChange(requiresKeySceneRebuild = false)
        }
        val previousExpanded = expanded
        when (control) {
            CandidateControl.EXPAND -> {
                expanded = true
                expandedScrollState.reset()
            }

            CandidateControl.COLLAPSE -> {
                expanded = false
                expandedScrollState.reset()
            }

            CandidateControl.DISMISS -> Unit
        }
        val relayout = relayout(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            editorPanelVisible = editorPanelVisible,
            fontScale = fontScale,
        )
        val expandedChanged = previousExpanded != expanded
        return relayout.copy(
            requiresKeySceneRebuild =
                relayout.requiresKeySceneRebuild || expandedChanged,
            cancelSettle = relayout.cancelSettle || expandedChanged,
            cancelInteraction = relayout.cancelInteraction || expandedChanged,
        )
    }

    fun takesToolbar(editorPanelVisible: Boolean): Boolean =
        CandidatePresentationPolicy.takesToolbar(
            composing = composing,
            association = association,
            editorPanelVisible = editorPanelVisible,
        )

    fun hitTest(x: Float, y: Float, visible: Boolean): CandidateHit? {
        if (!visible) return null

        mutableControls.forEach { slot ->
            if (slot.enabled && slot.bounds.contains(x, y)) {
                return CandidateHit.Control(slot.control, slot.bounds)
            }
        }

        val canHitValue =
            expanded ||
                collapsedViewportBounds?.contains(x, y) == true
        if (candidatesReady && canHitValue) {
            val candidate = if (expanded) {
                val viewport = expandedGridBounds
                if (viewport?.contains(x, y) == true) {
                    val contentY = y + expandedScrollOffset
                    var candidateIndex = firstExpandedCandidateEndingAfter(contentY)
                    var match: VisibleCandidate? = null
                    while (candidateIndex < mutableVisibleCandidates.size) {
                        val value = mutableVisibleCandidates[candidateIndex]
                        if (value.bounds.top > contentY) break
                        if (value.bounds.contains(x, contentY)) {
                            match = value
                            break
                        }
                        candidateIndex += 1
                    }
                    match
                } else {
                    null
                }
            } else {
                val candidateX = KeyboardScrollProjection.contentCoordinate(
                    screenCoordinate = x,
                    offset = scrollOffset,
                )
                mutableVisibleCandidates
                    .getOrNull(firstCandidateEndingAfter(candidateX))
                    ?.takeIf { it.bounds.contains(candidateX, y) }
            }
            if (candidate != null) {
                val frozenBounds = if (expanded) {
                    val viewport = checkNotNull(expandedGridBounds)
                    KeyboardRect(
                        left = maxOf(candidate.bounds.left, viewport.left),
                        top = maxOf(candidate.bounds.top - expandedScrollOffset, viewport.top),
                        right = minOf(candidate.bounds.right, viewport.right),
                        bottom = minOf(
                            candidate.bounds.bottom - expandedScrollOffset,
                            viewport.bottom,
                        ),
                    )
                } else {
                    val viewport = checkNotNull(collapsedViewportBounds)
                    KeyboardRect(
                        left = maxOf(
                            KeyboardScrollProjection.screenCoordinate(
                                contentCoordinate = candidate.bounds.left,
                                offset = scrollOffset,
                            ),
                            viewport.left,
                        ),
                        top = candidate.bounds.top,
                        right = minOf(
                            KeyboardScrollProjection.screenCoordinate(
                                contentCoordinate = candidate.bounds.right,
                                offset = scrollOffset,
                            ),
                            viewport.right,
                        ),
                        bottom = candidate.bounds.bottom,
                    )
                }
                return CandidateHit.Value(
                    revision = candidateRevision,
                    sourceIndex = candidate.sourceIndex,
                    expanded = expanded,
                    bounds = frozenBounds,
                )
            }
        }

        if (!expanded) {
            collapsedViewportBounds?.let { bounds ->
                if (candidatesReady && hasCollapsedOverflow && bounds.contains(x, y)) {
                    return CandidateHit.StripArea(bounds)
                }
            }
        }
        expandedGridBounds?.let { bounds ->
            if (candidatesReady && bounds.contains(x, y)) {
                return CandidateHit.GridArea(bounds)
            }
        }
        return null
    }

    override fun firstCandidateEndingAfter(contentX: Float): Int {
        var low = 0
        var high = mutableVisibleCandidates.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (mutableVisibleCandidates[middle].bounds.right <= contentX) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    override fun firstExpandedCandidateEndingAfter(contentY: Float): Int {
        var low = 0
        var high = mutableVisibleCandidates.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (mutableVisibleCandidates[middle].bounds.bottom <= contentY) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    fun canStartCollapsedDrag(): Boolean =
        candidatesReady && !expanded && hasCollapsedOverflow

    fun ownsDrag(pointerId: Int): Boolean = stripScrollState.owns(pointerId)

    fun beginDrag(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): Boolean = candidatesReady &&
        stripScrollState.begin(pointerId, x, y, eventTimeMillis)

    fun moveDrag(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): CandidateStripScrollState.DragUpdate =
        stripScrollState.move(pointerId, x, y, eventTimeMillis)

    fun moveDragFlags(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): Int = stripScrollState.moveFlags(pointerId, x, y, eventTimeMillis)

    fun finishDrag(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
        fastFlingVelocity: Float,
    ): CandidateStripScrollState.Settle? =
        stripScrollState.finish(
            pointerId = pointerId,
            x = x,
            y = y,
            eventTimeMillis = eventTimeMillis,
            fastFlingVelocity = fastFlingVelocity,
        )

    fun cancelAllDrags(): CandidateStripScrollState.Settle? = stripScrollState.cancelAll()

    fun moveTo(offset: Float): Boolean = stripScrollState.moveTo(offset)

    private fun updateViewportConfiguration(
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ) {
        require(fontScale.isFinite() && fontScale > 0f)
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.editorPanelVisible = editorPanelVisible
        this.fontScale = fontScale

        val nextTextSizePx = candidateTextSizePx()
        if (measuredTextSizePx.toBits() != nextTextSizePx.toBits()) {
            measuredTextSizePx = nextTextSizePx
            resetMeasurementsAndGeometry()
        }
    }

    private fun resetMeasurementsAndGeometry() {
        measuredWidths = FloatArray(mutableCandidates.size)
        widthsMeasured = false
        invalidateGeometry()
    }

    private fun invalidateGeometry() {
        geometryGeneration += 1L
        expandedGrid = null
        expandedGridCacheKey = null
        collapsedLayout = null
        configuredStripLayout = null
        stripLayoutCache.invalidate()
    }

    private fun relayoutInternal() {
        val requestedKey = currentSceneCacheKey()
        if (sceneCacheKey == requestedKey) return
        rebuildScene()
        sceneCacheKey = currentSceneCacheKey()
    }

    private fun rebuildScene() {
        sceneBuildCount += 1L
        mutableVisibleCandidates.clear()
        mutableControls.clear()
        expandedGridBounds = null
        collapsedViewportBounds = null
        if (viewWidth <= 0) return

        val systemBarTop = viewHeight - metrics.systemBarHeight
        val gridTop = metrics.candidateHeight + metrics.dp(5f)
        val statusTop = systemBarTop - metrics.expandedCandidateStatusHeight
        val gridBottom = statusTop - metrics.dp(4f)
        val hasExpandedGridRoom =
            viewHeight > 0 &&
                gridBottom - gridTop >= metrics.expandedCandidateRowHeight

        if (mutableCandidates.isEmpty()) {
            collapsedLayout = null
            configuredStripLayout = null
            stripLayoutCache.invalidate()
            stripScrollState.configure(
                maximumOffset = 0f,
                viewportExtent = 0f,
                snapOffsets = listOf(0f),
            )
            expandedScrollState.configure(contentExtent = 0f, viewportExtent = 0f)
            if (expanded && composing.isNotBlank() && hasExpandedGridRoom) {
                expandedGridBounds = KeyboardRect(
                    left = 0f,
                    top = gridTop,
                    right = viewWidth.toFloat(),
                    bottom = gridBottom,
                )
                expandedStatusLabel = "…"
                addExpandedControls()
            } else {
                expanded = false
                expandedScrollState.reset()
                expandedStatusLabel = ""
            }
            return
        }

        ensureMeasuredWidths()
        val associationControlWidth = if (association) metrics.candidateControlWidth else 0f
        collapsedLayout = stripLayoutCache.getOrBuild(
            CandidateStripLayoutCache.Key(
                generation = geometryGeneration,
                viewWidth = viewWidth,
                takesToolbar = takesToolbar(editorPanelVisible),
            ),
        ) {
            CandidateStripGeometry.layout(
                viewWidth = (viewWidth.toFloat() - associationControlWidth).coerceAtLeast(1f),
                measuredTextWidths = measuredWidths,
                padding = metrics.horizontalPadding,
                textInset = metrics.candidateTextInset,
                gap = metrics.candidateGap,
                minimumWidth = metrics.candidateMinimumWidth,
                overflowControlWidth = if (association) 0f else metrics.candidateControlWidth,
            )
        }
        val collapsed = checkNotNull(collapsedLayout)
        if (configuredStripLayout !== collapsed) {
            stripScrollState.configure(
                maximumOffset = collapsed.maximumOffset,
                viewportExtent = collapsed.viewportExtent,
                snapOffsets = collapsed.snapOffsets,
            )
            configuredStripLayout = collapsed
        }

        if (!collapsed.hasOverflow) {
            expanded = false
            expandedScrollState.reset()
        }
        val canExpand =
            collapsed.hasOverflow &&
                hasExpandedGridRoom &&
                viewWidth.toFloat() > metrics.horizontalPadding * 2f &&
                !association

        if (expanded && canExpand) {
            val cacheKey = CandidateGridCacheKey(geometryGeneration, viewWidth)
            if (expandedGridCacheKey != cacheKey) {
                expandedGrid = KeyboardLayoutContract.continuousCandidateGrid(
                    viewWidth = viewWidth.toFloat(),
                    contentTop = gridTop,
                    measuredTextWidths = measuredWidths,
                    horizontalPadding = metrics.horizontalPadding,
                    textInset = metrics.candidateTextInset,
                    horizontalGap = metrics.candidateGap,
                    verticalGap = metrics.dp(4f),
                    minimumWidth = metrics.candidateMinimumWidth,
                    rowHeight = metrics.expandedCandidateRowHeight,
                )
                expandedGridCacheKey = cacheKey
            }
            val grid = checkNotNull(expandedGrid)
            if (grid.slots.isEmpty()) {
                expanded = false
                expandedScrollState.reset()
            } else {
                expandedGridBounds = KeyboardRect(
                    left = 0f,
                    top = gridTop,
                    right = viewWidth.toFloat(),
                    bottom = gridBottom,
                )
                expandedScrollState.configure(
                    contentExtent = (grid.contentBottom - gridTop).coerceAtLeast(0f),
                    viewportExtent = (gridBottom - gridTop).coerceAtLeast(0f),
                )
                expandedStatusLabel = "${mutableCandidates.size} 项"
                grid.slots.forEach { slot ->
                    mutableVisibleCandidates += VisibleCandidate(
                        sourceIndex = slot.sourceIndex,
                        bounds = KeyboardRect(
                            left = slot.left,
                            top = slot.top,
                            right = slot.right,
                            bottom = slot.bottom,
                        ),
                        textAnchor = slot.textAnchor,
                    )
                }
                addExpandedControls()
                return
            }
        } else if (expanded) {
            expanded = false
            expandedScrollState.reset()
        }

        val collapsedBottom = KeyboardLayoutContract.collapsedCandidateBottom(
            candidateHeight = metrics.candidateHeight,
            toolbarHeight = metrics.toolbarHeight,
            takesToolbar = takesToolbar(editorPanelVisible),
        )
        val top = if (composing.isBlank()) metrics.dp(3f) else metrics.dp(18f)
        collapsedViewportBounds = KeyboardRect(
            left = collapsed.viewportLeft,
            top = 0f,
            right = collapsed.viewportRight,
            bottom = collapsedBottom,
        )
        collapsed.slots.forEachIndexed { sourceIndex, slot ->
            mutableVisibleCandidates += VisibleCandidate(
                sourceIndex = sourceIndex,
                bounds = KeyboardRect(
                    left = slot.left,
                    top = top,
                    right = slot.right,
                    bottom = collapsedBottom - metrics.dp(3f),
                ),
                textAnchor = slot.textAnchor,
            )
        }
        if (association) {
            mutableControls += CandidateControlSlot(
                control = CandidateControl.DISMISS,
                bounds = KeyboardRect(
                    left = viewWidth - metrics.candidateControlWidth,
                    top = 0f,
                    right = viewWidth.toFloat(),
                    bottom = collapsedBottom,
                ),
                enabled = true,
            )
        } else if (collapsed.hasOverflow && canExpand) {
            mutableControls += CandidateControlSlot(
                control = CandidateControl.EXPAND,
                bounds = KeyboardRect(
                    left = viewWidth - metrics.candidateControlWidth,
                    top = 0f,
                    right = viewWidth.toFloat(),
                    bottom = collapsedBottom,
                ),
                enabled = candidatesReady,
            )
        }
    }

    private fun currentSceneCacheKey(): CandidateSceneCacheKey = CandidateSceneCacheKey(
        geometryGeneration = geometryGeneration,
        candidatesReady = candidatesReady,
        composingBlank = composing.isBlank(),
        expanded = expanded,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        takesToolbar = takesToolbar(editorPanelVisible),
    )

    private fun ensureMeasuredWidths() {
        if (widthsMeasured) return
        mutableCandidates.indices.forEach { sourceIndex ->
            measuredWidths[sourceIndex] = textMeasurer.measure(
                text = mutableCandidates[sourceIndex],
                textSizePx = measuredTextSizePx,
            )
        }
        widthsMeasured = true
    }

    private fun addExpandedControls() {
        mutableControls += CandidateControlSlot(
            control = CandidateControl.COLLAPSE,
            bounds = KeyboardRect(
                left = viewWidth - metrics.candidateControlWidth,
                top = 0f,
                right = viewWidth.toFloat(),
                bottom = metrics.candidateHeight,
            ),
        )
    }

    private fun candidateTextSizePx(): Float =
        metrics.density *
            fontScale *
            if (takesToolbar(editorPanelVisible)) 19f else 17f

    private data class CandidateSceneCacheKey(
        val geometryGeneration: Long,
        val candidatesReady: Boolean,
        val composingBlank: Boolean,
        val expanded: Boolean,
        val viewWidth: Int,
        val viewHeight: Int,
        val takesToolbar: Boolean,
    )

    private data class CandidateGridCacheKey(
        val geometryGeneration: Long,
        val viewWidth: Int,
    )
}
