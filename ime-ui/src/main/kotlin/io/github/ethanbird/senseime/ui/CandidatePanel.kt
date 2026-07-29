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
    val candidateRevision: Long
    val candidates: List<String>
    val visibleCandidates: List<VisibleCandidate>
    val controls: List<CandidateControlSlot>
    val expanded: Boolean
    val pageLabel: String
    val expandedGridBounds: KeyboardRect?
    val collapsedViewportBounds: KeyboardRect?
    val hasCollapsedOverflow: Boolean
    val scrollOffset: Float
    val maximumScrollOffset: Float
    val sceneBuildCount: Long

    fun firstCandidateEndingAfter(contentX: Float): Int
}

internal data class CandidateChange(
    val requiresKeySceneRebuild: Boolean,
    val cancelSettle: Boolean = false,
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

    data class PageArea(
        override val bounds: KeyboardRect,
    ) : CandidateHit

    data class StripArea(
        override val bounds: KeyboardRect,
    ) : CandidateHit
}

/**
 * Deep module owning candidate publication, cached scene layout, hit testing,
 * paging, and horizontal drag state.
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
    private var candidatePages: List<KeyboardLayoutContract.CandidatePage> = emptyList()
    private var mutableCandidates: List<String> = emptyList()
    private var measuredWidths = FloatArray(0)
    private var widthsMeasured = false
    private var geometryGeneration = 0L
    private var pageCacheKey: CandidatePageCacheKey? = null
    private var sceneCacheKey: CandidateSceneCacheKey? = null
    private val stripLayoutCache = CandidateStripLayoutCache()
    private var collapsedLayout: CandidateStripGeometry.Layout? = null
    private var configuredStripLayout: CandidateStripGeometry.Layout? = null
    private val stripScrollState = CandidateStripScrollState(touchSlop = touchSlop)

    private var pageIndex = 0
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

    override val candidates: List<String>
        get() = mutableCandidates

    override val visibleCandidates: List<VisibleCandidate>
        get() = mutableVisibleCandidates

    override val controls: List<CandidateControlSlot>
        get() = mutableControls

    override var expanded: Boolean = false
        private set

    override var pageLabel: String = "1 / 1"
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
    ): CandidateChange {
        val previousTakeover = takesToolbar(this.editorPanelVisible)
        val previousExpanded = expanded
        val shouldResetNavigation = CandidatePresentationPolicy.shouldResetNavigation(
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
        val candidateSnapshotChanged =
            nextCandidates != null && nextCandidates != mutableCandidates
        val candidateBatchBecameCurrent =
            nextCandidates != null && candidateRevision != revision
        val hadCandidateGeometry = mutableCandidates.isNotEmpty()
        // A ready snapshot owns fresh strip coordinates, even when the
        // composition revision is unchanged. Preserve its expanded page state,
        // but detach any drag/settle inherited from retained stale candidates.
        val shouldResetStripInteraction =
            shouldResetNavigation ||
                (
                    hadCandidateGeometry &&
                        (candidateSnapshotChanged || candidateBatchBecameCurrent)
                )
        if (shouldResetStripInteraction) {
            stripScrollState.reset()
        }
        if (shouldResetNavigation) {
            expanded = false
            pageIndex = 0
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
        if (nextCandidates != null) {
            candidateRevision = revision
        }

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
        pageIndex = 0
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
        )
    }

    fun activate(
        control: CandidateControl,
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ): CandidateChange {
        val previousExpanded = expanded
        when (control) {
            CandidateControl.EXPAND -> {
                expanded = true
                pageIndex = 0
            }

            CandidateControl.COLLAPSE -> {
                expanded = false
                pageIndex = 0
            }

            CandidateControl.PREVIOUS_PAGE -> {
                pageIndex = KeyboardLayoutContract.adjacentCandidatePage(
                    currentPage = pageIndex,
                    pageCount = candidatePages.size,
                    delta = -1,
                )
            }

            CandidateControl.NEXT_PAGE -> {
                pageIndex = KeyboardLayoutContract.adjacentCandidatePage(
                    currentPage = pageIndex,
                    pageCount = candidatePages.size,
                    delta = 1,
                )
            }
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
        )
    }

    fun page(
        delta: Int,
        viewWidth: Int,
        viewHeight: Int,
        editorPanelVisible: Boolean,
        fontScale: Float,
    ): CandidateChange {
        if (!expanded || candidatePages.isEmpty()) {
            return CandidateChange(requiresKeySceneRebuild = false)
        }
        val nextPage = KeyboardLayoutContract.adjacentCandidatePage(
            currentPage = pageIndex,
            pageCount = candidatePages.size,
            delta = delta,
        )
        if (nextPage == pageIndex) {
            return CandidateChange(requiresKeySceneRebuild = false)
        }
        pageIndex = nextPage
        return relayout(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            editorPanelVisible = editorPanelVisible,
            fontScale = fontScale,
        )
    }

    fun takesToolbar(editorPanelVisible: Boolean): Boolean =
        CandidatePresentationPolicy.takesToolbar(
            composing = composing,
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
        if (candidateRevision == compositionRevision && canHitValue) {
            val candidate = if (expanded) {
                mutableVisibleCandidates.firstOrNull { it.bounds.contains(x, y) }
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
                    candidate.bounds
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
                if (hasCollapsedOverflow && bounds.contains(x, y)) {
                    return CandidateHit.StripArea(bounds)
                }
            }
        }
        expandedGridBounds?.let { bounds ->
            if (bounds.contains(x, y)) return CandidateHit.PageArea(bounds)
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

    fun canStartCollapsedDrag(): Boolean = !expanded && hasCollapsedOverflow

    fun ownsDrag(pointerId: Int): Boolean = stripScrollState.owns(pointerId)

    fun beginDrag(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): Boolean = stripScrollState.begin(pointerId, x, y, eventTimeMillis)

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
        candidatePages = emptyList()
        pageCacheKey = null
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
        val pagerTop = systemBarTop - metrics.expandedCandidatePagerHeight
        val gridBottom = pagerTop - metrics.dp(4f)
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
            if (expanded && composing.isNotBlank() && hasExpandedGridRoom) {
                expandedGridBounds = KeyboardRect(
                    left = 0f,
                    top = gridTop,
                    right = viewWidth.toFloat(),
                    bottom = gridBottom,
                )
                pageLabel = "…"
                addExpandedControls(
                    systemBarTop = systemBarTop,
                    pagerTop = pagerTop,
                    previousEnabled = false,
                    nextEnabled = false,
                )
            } else {
                expanded = false
                pageIndex = 0
                pageLabel = "1 / 1"
            }
            return
        }

        ensureMeasuredWidths()
        collapsedLayout = stripLayoutCache.getOrBuild(
            CandidateStripLayoutCache.Key(
                generation = geometryGeneration,
                viewWidth = viewWidth,
                takesToolbar = takesToolbar(editorPanelVisible),
            ),
        ) {
            CandidateStripGeometry.layout(
                viewWidth = viewWidth.toFloat(),
                measuredTextWidths = measuredWidths,
                padding = metrics.horizontalPadding,
                textInset = metrics.candidateTextInset,
                gap = metrics.candidateGap,
                minimumWidth = metrics.candidateMinimumWidth,
                overflowControlWidth = metrics.candidateControlWidth,
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
            pageIndex = 0
        }
        val canExpand = collapsed.hasOverflow && hasExpandedGridRoom

        if (expanded && canExpand) {
            val cacheKey = CandidatePageCacheKey(geometryGeneration, viewWidth, viewHeight)
            if (pageCacheKey != cacheKey) {
                candidatePages = KeyboardLayoutContract.pagedCandidateGrid(
                    viewWidth = viewWidth.toFloat(),
                    contentTop = gridTop,
                    contentBottom = gridBottom,
                    measuredTextWidths = measuredWidths,
                    horizontalPadding = metrics.horizontalPadding,
                    textInset = metrics.candidateTextInset,
                    horizontalGap = metrics.candidateGap,
                    verticalGap = metrics.dp(4f),
                    minimumWidth = metrics.candidateMinimumWidth,
                    rowHeight = metrics.expandedCandidateRowHeight,
                )
                pageCacheKey = cacheKey
            }
            if (candidatePages.isEmpty()) {
                expanded = false
                pageIndex = 0
            } else {
                expandedGridBounds = KeyboardRect(
                    left = 0f,
                    top = gridTop,
                    right = viewWidth.toFloat(),
                    bottom = gridBottom,
                )
                pageIndex = pageIndex.coerceIn(0, candidatePages.lastIndex)
                pageLabel = "${pageIndex + 1} / ${candidatePages.size}"
                candidatePages[pageIndex].slots.forEach { slot ->
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
                addExpandedControls(
                    systemBarTop = systemBarTop,
                    pagerTop = pagerTop,
                    previousEnabled = pageIndex > 0,
                    nextEnabled = pageIndex < candidatePages.lastIndex,
                )
                return
            }
        } else if (expanded) {
            expanded = false
            pageIndex = 0
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
        if (collapsed.hasOverflow && canExpand) {
            mutableControls += CandidateControlSlot(
                control = CandidateControl.EXPAND,
                bounds = KeyboardRect(
                    left = viewWidth - metrics.candidateControlWidth,
                    top = 0f,
                    right = viewWidth.toFloat(),
                    bottom = collapsedBottom,
                ),
                enabled = candidateRevision == compositionRevision,
            )
        }
    }

    private fun currentSceneCacheKey(): CandidateSceneCacheKey = CandidateSceneCacheKey(
        geometryGeneration = geometryGeneration,
        candidatesReady = candidateRevision == compositionRevision,
        composingBlank = composing.isBlank(),
        expanded = expanded,
        pageIndex = pageIndex,
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

    private fun addExpandedControls(
        systemBarTop: Float,
        pagerTop: Float,
        previousEnabled: Boolean,
        nextEnabled: Boolean,
    ) {
        mutableControls += CandidateControlSlot(
            control = CandidateControl.COLLAPSE,
            bounds = KeyboardRect(
                left = viewWidth - metrics.candidateControlWidth,
                top = 0f,
                right = viewWidth.toFloat(),
                bottom = metrics.candidateHeight,
            ),
        )
        val pagerButtonWidth = metrics.dp(68f)
        mutableControls += CandidateControlSlot(
            control = CandidateControl.PREVIOUS_PAGE,
            bounds = KeyboardRect(
                left = metrics.horizontalPadding,
                top = pagerTop,
                right = metrics.horizontalPadding + pagerButtonWidth,
                bottom = systemBarTop,
            ),
            enabled = previousEnabled,
        )
        mutableControls += CandidateControlSlot(
            control = CandidateControl.NEXT_PAGE,
            bounds = KeyboardRect(
                left = viewWidth - metrics.horizontalPadding - pagerButtonWidth,
                top = pagerTop,
                right = viewWidth - metrics.horizontalPadding,
                bottom = systemBarTop,
            ),
            enabled = nextEnabled,
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
        val pageIndex: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val takesToolbar: Boolean,
    )
}
