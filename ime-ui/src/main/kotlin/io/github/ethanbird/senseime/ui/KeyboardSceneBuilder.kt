package io.github.ethanbird.senseime.ui

import android.graphics.RectF
import kotlin.math.max

internal data class KeyboardSceneRequest(
    val viewWidth: Int,
    val viewHeight: Int,
    val panel: KeyboardPanel,
    val primaryMode: PrimaryKeyboardMode,
    val candidatesTakeToolbar: Boolean,
    val candidateExpanded: Boolean,
    val shifted: Boolean,
    val chineseMode: Boolean,
    val emojiGroupIndex: Int,
    val symbolCategoryIndex: Int,
    val clipboardItems: List<String>,
    val clipboardPageIndex: Int,
    val voiceSurfaceState: VoiceSurfaceState?,
    val fontScale: Float,
)

/**
 * Deep scene-construction module for every keyboard panel.
 *
 * Android View state is passed explicitly. The builder owns geometry and writes
 * into reusable [MutableKeyboardScene] storage; drawing and MotionEvent handling
 * only consume the published scene.
 */
internal class KeyboardSceneBuilder(
    private val metrics: KeyboardMetrics,
    private val primaryLayout: KeyboardPrimaryLayout = KeyboardPrimaryLayout(metrics),
    private val textMeasurer: CandidateTextMeasurer = PaintCandidateTextMeasurer(),
) {
    fun rebuildInto(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        target.beginRebuild()
        if (request.viewWidth <= 0 || request.viewHeight <= 0) return

        val keys = target.mutableKeys
        val chromeBottom = chromeBottom(request)
        if (!request.candidateExpanded) {
            if (
                !request.candidatesTakeToolbar &&
                request.panel != KeyboardPanel.EDITOR &&
                request.panel != KeyboardPanel.VOICE
            ) {
                target.toolbarKeyStart = keys.size
                primaryLayout.appendToolbar(request.viewWidth, keys)
                target.toolbarKeyEndExclusive = keys.size
            }
            target.panelKeyStart = keys.size
            when (request.panel) {
                KeyboardPanel.LETTERS -> primaryLayout.appendLetters(
                    mode = request.primaryMode,
                    request = KeyboardLetterLayoutRequest(
                        viewWidth = request.viewWidth,
                        viewHeight = request.viewHeight,
                        chromeBottom = chromeBottom,
                        shifted = request.shifted,
                        chineseMode = request.chineseMode,
                        swipeMode = swipeCharacterMode(request.chineseMode),
                    ),
                    output = keys,
                )

                KeyboardPanel.NUMBERS -> primaryLayout.appendNumbers(
                    viewWidth = request.viewWidth,
                    viewHeight = request.viewHeight,
                    chromeBottom = chromeBottom,
                    chineseMode = request.chineseMode,
                    output = keys,
                )

                KeyboardPanel.TOOLBOX -> primaryLayout.appendToolbox(
                    viewWidth = request.viewWidth,
                    viewHeight = request.viewHeight,
                    chromeBottom = chromeBottom,
                    output = keys,
                )

                KeyboardPanel.SYMBOLS -> layoutSymbols(request, target)
                KeyboardPanel.EMOJI -> layoutEmoji(request, target)
                KeyboardPanel.CLIPBOARD -> layoutClipboard(request, target)
                KeyboardPanel.EDITOR -> layoutEditor(request, target)
                KeyboardPanel.VOICE -> layoutVoice(request, target)
            }
            target.panelKeyEndExclusive = keys.size
        }
        target.systemBarKeyStart = keys.size
        primaryLayout.appendSystemBar(request.viewWidth, request.viewHeight, keys)
        target.systemBarKeyEndExclusive = keys.size
        target.assignPhysicalKeyIds(request.panel)
    }

    private fun layoutSymbols(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        val keys = target.mutableKeys
        val top = chromeBottom(request) + metrics.dp(4f)
        val bottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(6f)
        val railWidth = minOf(metrics.dp(82f), request.viewWidth * 0.23f)
        val actionHeight = metrics.dp(42f)
        val railRight = metrics.horizontalPadding + railWidth
        if (bottom <= top) return
        if (bottom - top <= actionHeight + metrics.keyGap) {
            keys += Key(
                label = "返回",
                action = KeyAction.EmitKey(KeyCodes.LETTERS),
                bounds = RectF(
                    metrics.horizontalPadding,
                    top,
                    request.viewWidth - metrics.horizontalPadding,
                    bottom,
                ),
                style = KeyStyle.RAIL,
            )
            return
        }
        val categoryBottom = bottom - actionHeight - metrics.keyGap
        target.symbolCategoryBounds =
            RectF(metrics.horizontalPadding, top, railRight, categoryBottom)
        target.symbolGridBounds = RectF(
            railRight + metrics.keyGap,
            top,
            request.viewWidth - metrics.horizontalPadding,
            bottom,
        )

        val categoryViewport = categoryBottom - top
        val categoryHeight = metrics.dp(43f)
        target.symbolCategoryScrollState.configure(
            contentExtent = SymbolCatalog.categories.size * categoryHeight,
            viewportExtent = categoryViewport,
        )
        SymbolCatalog.categories.forEachIndexed { index, category ->
            val itemTop = top + index * categoryHeight
            val itemBottom = itemTop + categoryHeight
            keys += Key(
                label = category.label,
                action = KeyAction.SelectSymbolCategory(index),
                bounds = RectF(metrics.horizontalPadding, itemTop, railRight, itemBottom),
                style = KeyStyle.SYMBOL_CATEGORY,
                scrollPanel = ScrollPanel.SYMBOL_CATEGORIES,
            )
        }
        keys += Key(
            label = "返回",
            action = KeyAction.EmitKey(KeyCodes.LETTERS),
            bounds = RectF(
                metrics.horizontalPadding,
                categoryBottom + metrics.keyGap,
                railRight,
                bottom,
            ),
            style = KeyStyle.RAIL,
        )

        val categoryIndex =
            request.symbolCategoryIndex.coerceIn(0, SymbolCatalog.categories.lastIndex)
        val values = SymbolCatalog.categories[categoryIndex].values
        val grid = checkNotNull(target.symbolGridBounds)
        val columns = 4
        val itemWidth = grid.width() / columns
        val itemHeight = max(metrics.dp(49f), grid.height() / 5f)
        val contentRows = (values.size + columns - 1) / columns
        target.symbolGridScrollState.configure(
            contentExtent = contentRows * itemHeight,
            viewportExtent = grid.height(),
        )
        values.forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val itemTop = grid.top + row * itemHeight
            val itemBottom = itemTop + itemHeight
            keys += Key(
                label = text,
                action = KeyAction.CommitText(text),
                bounds = RectF(
                    grid.left + column * itemWidth,
                    itemTop,
                    grid.left + (column + 1) * itemWidth,
                    itemBottom,
                ),
                style = KeyStyle.SYMBOL,
                scrollPanel = ScrollPanel.SYMBOL_VALUES,
            )
        }
    }

    private fun layoutEmoji(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        val keys = target.mutableKeys
        val top = chromeBottom(request) + metrics.dp(4f)
        val bottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(6f)
        val categoryHeight = metrics.dp(29f)
        val actionHeight = metrics.dp(40f)
        val gridGap = metrics.dp(3f)
        if (bottom <= top) return
        if (bottom - top <= categoryHeight + actionHeight + gridGap * 2f) {
            appendWeightedRow(
                items = EMOJI_ACTION_ROW,
                request = request,
                y = top,
                rowHeight = bottom - top,
                output = keys,
            )
            return
        }
        val geometry = KeyboardLayoutContract.scrollableEmojiLayoutGeometry(
            contentTop = top,
            contentBottom = bottom,
            categoryHeight = categoryHeight,
            actionHeight = actionHeight,
            gridGap = gridGap,
        )
        val categorySlot =
            (request.viewWidth - metrics.horizontalPadding * 2) / EmojiCatalog.categories.size
        EmojiCatalog.categories.forEachIndexed { index, group ->
            keys += Key(
                label = group.icon,
                action = KeyAction.SelectEmojiCategory(index),
                bounds = RectF(
                    metrics.horizontalPadding + index * categorySlot + metrics.dp(2f),
                    geometry.categoryTop,
                    metrics.horizontalPadding + (index + 1) * categorySlot - metrics.dp(2f),
                    geometry.categoryBottom,
                ),
                style = KeyStyle.CATEGORY,
            )
        }
        val columns = 7
        val itemWidth = (request.viewWidth - metrics.horizontalPadding * 2) / columns
        val viewportHeight = geometry.gridBottom - geometry.gridTop
        val itemHeight = max(metrics.dp(46f), viewportHeight / 3f)
        val groupIndex = request.emojiGroupIndex.coerceIn(0, EmojiCatalog.categories.lastIndex)
        val values = EmojiCatalog.categories[groupIndex].values
        val contentRows = (values.size + columns - 1) / columns
        target.emojiScrollState.configure(contentRows * itemHeight, viewportHeight)
        target.emojiGridBounds = RectF(
            metrics.horizontalPadding,
            geometry.gridTop,
            request.viewWidth - metrics.horizontalPadding,
            geometry.gridBottom,
        )
        values.forEachIndexed { index, text ->
            val row = index / columns
            val column = index % columns
            val itemTop = geometry.gridTop + row * itemHeight
            val itemBottom = itemTop + itemHeight
            keys += Key(
                label = text,
                action = KeyAction.CommitText(text),
                bounds = RectF(
                    metrics.horizontalPadding + column * itemWidth,
                    itemTop,
                    metrics.horizontalPadding + (column + 1) * itemWidth,
                    itemBottom,
                ),
                style = KeyStyle.EMOJI,
                scrollPanel = ScrollPanel.EMOJI,
            )
        }
        appendWeightedRow(
            items = EMOJI_ACTION_ROW,
            request = request,
            y = geometry.actionTop,
            rowHeight = geometry.actionBottom - geometry.actionTop,
            output = keys,
        )
    }

    private fun layoutClipboard(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        val keys = target.mutableKeys
        val headerTop = chromeBottom(request)
        val headerHeight = metrics.dp(36f)
        val headerIconWidth = metrics.dp(39f)
        keys += Key(
            label = "",
            action = KeyAction.Clipboard(KeyboardClipboardAction.REFRESH),
            bounds = RectF(
                request.viewWidth - headerIconWidth * 3,
                headerTop,
                request.viewWidth - headerIconWidth * 2,
                headerTop + headerHeight,
            ),
            style = KeyStyle.TOOL,
            icon = Icon.REFRESH,
        )
        keys += Key(
            label = "",
            action = KeyAction.Clipboard(KeyboardClipboardAction.CLEAR),
            bounds = RectF(
                request.viewWidth - headerIconWidth * 2,
                headerTop,
                request.viewWidth - headerIconWidth,
                headerTop + headerHeight,
            ),
            style = KeyStyle.TOOL,
            icon = Icon.CLEAR,
        )
        keys += Key(
            label = "",
            action = KeyAction.EmitKey(KeyCodes.LETTERS),
            bounds = RectF(
                request.viewWidth - headerIconWidth,
                headerTop,
                request.viewWidth.toFloat(),
                headerTop + headerHeight,
            ),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
        )
        val top = headerTop + headerHeight
        val bottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(8f)
        if (
            bottom - top <= metrics.keyGap * (CLIPBOARD_ITEMS_PER_PAGE - 1) ||
            request.viewWidth.toFloat() <= metrics.horizontalPadding * 2f
        ) {
            return
        }
        if (request.clipboardItems.isEmpty()) {
            keys += Key(
                label = "暂无剪贴板文本  ·  复制文字后点刷新",
                action = KeyAction.None,
                bounds = RectF(
                    metrics.horizontalPadding,
                    top,
                    request.viewWidth - metrics.horizontalPadding,
                    bottom,
                ),
                style = KeyStyle.CARD,
            )
            return
        }
        val pageCount =
            (
                (request.clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) /
                    CLIPBOARD_ITEMS_PER_PAGE
                ).coerceAtLeast(1)
        val pageIndex = request.clipboardPageIndex.coerceIn(0, pageCount - 1)
        target.clipboardPageLabel = if (pageCount > 1) "${pageIndex + 1}/$pageCount" else ""
        val pageStart = pageIndex * CLIPBOARD_ITEMS_PER_PAGE
        KeyboardLayoutContract.clipboardCardSlots(
            viewWidth = request.viewWidth.toFloat(),
            contentTop = top,
            contentBottom = bottom,
            itemCount = request.clipboardItems.size,
            pageStart = pageStart,
            horizontalPadding = metrics.horizontalPadding,
            gap = metrics.keyGap,
            itemsPerPage = CLIPBOARD_ITEMS_PER_PAGE,
        ).forEach { slot ->
            val text = request.clipboardItems[slot.sourceIndex]
            val previewLines = clipboardPreviewLines(
                text = text,
                maximumWidth = slot.right - slot.left - metrics.dp(62f),
                fontScale = request.fontScale,
            )
            keys += Key(
                label = previewLines.first,
                action = KeyAction.CommitText(text),
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = KeyStyle.CARD,
                secondaryLabel = previewLines.second,
            )
            keys += Key(
                label = "",
                action = KeyAction.Clipboard(
                    action = KeyboardClipboardAction.DELETE,
                    index = slot.sourceIndex,
                ),
                bounds = RectF(
                    slot.right - metrics.dp(31f),
                    slot.top + metrics.dp(2f),
                    slot.right - metrics.dp(2f),
                    slot.top + metrics.dp(31f),
                ),
                style = KeyStyle.TOOL,
                icon = Icon.CLEAR,
            )
        }
    }

    private fun clipboardPreviewLines(
        text: String,
        maximumWidth: Float,
        fontScale: Float,
    ): Pair<String, String?> {
        val textSizePx = metrics.dp(13f) * fontScale
        return KeyboardLayoutContract.clipboardPreviewLines(
            text = text,
            maximumWidth = maximumWidth.coerceAtLeast(1f),
            measureText = { value -> textMeasurer.measure(value, textSizePx) },
        )
    }

    private fun layoutEditor(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        val keys = target.mutableKeys
        keys += Key(
            label = "",
            action = KeyAction.Editor(KeyboardEditorAction.BACK),
            bounds = RectF(
                request.viewWidth - metrics.dp(62f),
                0f,
                request.viewWidth.toFloat(),
                metrics.candidateHeight,
            ),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
        )
        val contentTop = metrics.candidateHeight + metrics.dp(7f)
        val contentBottom = request.viewHeight - metrics.systemBarHeight - metrics.dp(8f)
        if (
            contentBottom - contentTop <= metrics.keyGap * 5f ||
            request.viewWidth.toFloat() <=
            metrics.horizontalPadding * 2f + metrics.keyGap * 4f
        ) {
            return
        }
        val slots = KeyboardLayoutContract.editorLayout(
            viewWidth = request.viewWidth.toFloat(),
            contentTop = contentTop,
            contentBottom = contentBottom,
            horizontalPadding = metrics.horizontalPadding,
            gap = metrics.keyGap,
        )
        var mainLeft = Float.POSITIVE_INFINITY
        var mainTop = Float.POSITIVE_INFINITY
        var mainRight = Float.NEGATIVE_INFINITY
        var mainBottom = Float.NEGATIVE_INFINITY
        val bottomSeparators = target.editorBottomSeparators
        bottomSeparators.fill(0f)
        var bottomIndex = 0
        var previousBottomRight = 0f
        var bottomTop = 0f

        for (slot in slots) {
            val rail = slot.role == KeyboardLayoutContract.EditorKeyRole.DELETE ||
                slot.role == KeyboardLayoutContract.EditorKeyRole.COPY ||
                slot.role == KeyboardLayoutContract.EditorKeyRole.CUT ||
                slot.role == KeyboardLayoutContract.EditorKeyRole.PASTE
            if (!rail) {
                mainLeft = minOf(mainLeft, slot.left)
                mainTop = minOf(mainTop, slot.top)
                mainRight = maxOf(mainRight, slot.right)
                mainBottom = maxOf(mainBottom, slot.bottom)
            }
            val isBottom = slot.role == KeyboardLayoutContract.EditorKeyRole.HOME ||
                slot.role == KeyboardLayoutContract.EditorKeyRole.SELECT_ALL ||
                slot.role == KeyboardLayoutContract.EditorKeyRole.END
            if (isBottom) {
                if (bottomIndex > 0) {
                    bottomSeparators[bottomIndex - 1] = (previousBottomRight + slot.left) / 2f
                }
                previousBottomRight = slot.right
                bottomTop = slot.top
                bottomIndex += 1
            }
        }
        if (mainLeft.isFinite()) {
            target.editorMainBounds = RectF(mainLeft, mainTop, mainRight, mainBottom)
        }
        target.editorBottomTop = bottomTop
        target.editorBottomSeparatorCount = (bottomIndex - 1).coerceIn(0, bottomSeparators.size)

        slots.forEach { slot ->
            val action = slot.role.toEditorAction()
            val icon = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.UP -> Icon.UP
                KeyboardLayoutContract.EditorKeyRole.LEFT -> Icon.BACK
                KeyboardLayoutContract.EditorKeyRole.RIGHT -> Icon.RIGHT
                KeyboardLayoutContract.EditorKeyRole.DOWN -> Icon.DOWN
                KeyboardLayoutContract.EditorKeyRole.DELETE -> Icon.DELETE
                KeyboardLayoutContract.EditorKeyRole.HOME -> Icon.HOME
                KeyboardLayoutContract.EditorKeyRole.END -> Icon.END
                else -> null
            }
            val label = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION -> "开始选择"
                KeyboardLayoutContract.EditorKeyRole.COPY -> "复制"
                KeyboardLayoutContract.EditorKeyRole.CUT -> "剪切"
                KeyboardLayoutContract.EditorKeyRole.PASTE -> "粘贴"
                KeyboardLayoutContract.EditorKeyRole.SELECT_ALL -> "全选"
                else -> ""
            }
            val style = when (slot.role) {
                KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION -> KeyStyle.EDITOR_PRIMARY
                KeyboardLayoutContract.EditorKeyRole.DELETE,
                KeyboardLayoutContract.EditorKeyRole.COPY,
                KeyboardLayoutContract.EditorKeyRole.CUT,
                KeyboardLayoutContract.EditorKeyRole.PASTE,
                -> KeyStyle.EDITOR_ACTION

                else -> KeyStyle.EDITOR_DIRECTION
            }
            keys += Key(
                label = label,
                action = KeyAction.Editor(action),
                bounds = RectF(slot.left, slot.top, slot.right, slot.bottom),
                style = style,
                icon = icon,
            )
        }
    }

    private fun layoutVoice(
        request: KeyboardSceneRequest,
        target: MutableKeyboardScene,
    ) {
        val state = request.voiceSurfaceState ?: return
        val keys = target.mutableKeys
        keys += Key(
            label = "",
            action = KeyAction.EmitKey(KeyCodes.VOICE_CANCEL),
            bounds = RectF(
                request.viewWidth - metrics.dp(58f),
                metrics.dp(3f),
                request.viewWidth - metrics.dp(5f),
                metrics.candidateHeight - metrics.dp(3f),
            ),
            style = KeyStyle.TOOL,
            icon = Icon.BACK,
        )
        val contentBottom = request.viewHeight - metrics.systemBarHeight
        if (contentBottom <= metrics.candidateHeight) return
        val geometry = KeyboardLayoutContract.voiceLayout(
            candidateHeight = metrics.candidateHeight,
            contentBottom = contentBottom,
            unit = metrics.density,
        )
        target.voiceStatusCenterY = geometry.statusCenterY
        target.voiceTranscriptCenterY = geometry.transcriptCenterY
        val buttonWidth = minOf(metrics.dp(296f), request.viewWidth - metrics.dp(54f))
        keys += Key(
            label = VoiceSurfaceControlPolicy.primaryLabel(state.phase),
            action = KeyAction.EmitKey(
                VoiceSurfaceControlPolicy.primaryKeyCode(state.phase),
            ),
            bounds = RectF(
                request.viewWidth / 2f - buttonWidth / 2f,
                geometry.primaryButtonTop,
                request.viewWidth / 2f + buttonWidth / 2f,
                geometry.primaryButtonBottom,
            ),
            style = KeyStyle.VOICE_PRIMARY,
        )
        target.voiceWaveformBounds.set(
            metrics.dp(28f),
            geometry.waveformTop,
            request.viewWidth - metrics.dp(28f),
            geometry.waveformBottom,
        )
    }

    private fun appendWeightedRow(
        items: List<KeyboardLayoutContract.WeightedKey>,
        request: KeyboardSceneRequest,
        y: Float,
        rowHeight: Float,
        output: MutableList<Key>,
    ) {
        primaryLayout.appendWeightedRow(
            items = items,
            viewWidth = request.viewWidth,
            y = y,
            rowHeight = rowHeight,
            swipeMode = swipeCharacterMode(request.chineseMode),
            output = output,
            backToLettersIcon = Icon.BACK,
        )
    }

    private fun KeyboardLayoutContract.EditorKeyRole.toEditorAction(): KeyboardEditorAction =
        when (this) {
            KeyboardLayoutContract.EditorKeyRole.UP -> KeyboardEditorAction.UP
            KeyboardLayoutContract.EditorKeyRole.LEFT -> KeyboardEditorAction.LEFT
            KeyboardLayoutContract.EditorKeyRole.TOGGLE_SELECTION ->
                KeyboardEditorAction.TOGGLE_SELECTION

            KeyboardLayoutContract.EditorKeyRole.RIGHT -> KeyboardEditorAction.RIGHT
            KeyboardLayoutContract.EditorKeyRole.DOWN -> KeyboardEditorAction.DOWN
            KeyboardLayoutContract.EditorKeyRole.DELETE -> KeyboardEditorAction.DELETE
            KeyboardLayoutContract.EditorKeyRole.COPY -> KeyboardEditorAction.COPY
            KeyboardLayoutContract.EditorKeyRole.CUT -> KeyboardEditorAction.CUT
            KeyboardLayoutContract.EditorKeyRole.PASTE -> KeyboardEditorAction.PASTE
            KeyboardLayoutContract.EditorKeyRole.HOME -> KeyboardEditorAction.HOME
            KeyboardLayoutContract.EditorKeyRole.SELECT_ALL -> KeyboardEditorAction.SELECT_ALL
            KeyboardLayoutContract.EditorKeyRole.END -> KeyboardEditorAction.END
        }

    private fun chromeBottom(request: KeyboardSceneRequest): Float =
        KeyboardLayoutContract.topChromeBottom(
            candidateHeight = metrics.candidateHeight,
            toolbarHeight = metrics.toolbarHeight,
            candidatesTakeToolbar = request.candidatesTakeToolbar,
            editorPanelVisible = request.panel == KeyboardPanel.EDITOR,
        )

    private fun swipeCharacterMode(chineseMode: Boolean): SwipeCharacterMode =
        if (chineseMode) SwipeCharacterMode.CHINESE else SwipeCharacterMode.ENGLISH

    companion object {
        const val CLIPBOARD_ITEMS_PER_PAGE = 3

        val EMOJI_ACTION_ROW = listOf(
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.LETTERS,
                weight = 1.05f,
                action = true,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.SPACE,
                weight = 3.3f,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.DELETE,
                weight = 1.05f,
                action = true,
            ),
            KeyboardLayoutContract.WeightedKey(
                label = "",
                code = KeyCodes.ENTER,
                weight = 1.05f,
                action = true,
            ),
        )
    }
}
