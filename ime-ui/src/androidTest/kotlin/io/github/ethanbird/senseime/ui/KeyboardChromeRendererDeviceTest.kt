package io.github.ethanbird.senseime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardChromeRendererDeviceTest {
    @Test
    fun largeFontCandidatePreservesTheEntireMeasuredGlyph() = assertMeasuredGlyph(expanded = false)

    @Test
    fun expandedCandidateUsesTheMeasuredFontRatherThanASmallerFont() = assertMeasuredGlyph(expanded = true)

    private fun assertMeasuredGlyph(expanded: Boolean) {
        val density = 2.625f
        val fontScale = 2f
        val width = 1080
        val height = 1200
        val metrics = KeyboardMetrics.fromDensity(density)
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        var measuredSize = 0f
        val panel = CandidatePanel(metrics, 8f, CandidateTextMeasurer { value, size ->
            measuredSize = size
            measurePaint.textSize = size
            measurePaint.measureText(value)
        })
        panel.publish(1L, "ni'hao", List(if (expanded) 30 else 1) { "你好" }, width, height, false, fontScale)
        if (expanded) panel.activate(CandidateControl.EXPAND, width, height, false, fontScale)
        assertEquals(expanded, panel.expanded)
        val candidate = panel.visibleCandidates.first()
        val state = BackgroundOnlyRendererState(width, height, panel)
        val renderer = KeyboardChromeRenderer(density, fontScale, metrics, KeyboardPalette(false))
        renderer.updateSurface(width, height, fontScale)
        val actual = renderFreshFrame(width, height) { renderer.drawCandidates(it, state) }
        val expected = renderFreshFrame(width, height) { canvas ->
            measurePaint.color = 0xFF172033.toInt()
            measurePaint.textAlign = Paint.Align.LEFT
            measurePaint.textSize = measuredSize
            KeyboardCanvasText().drawCentered(canvas, "你好", measurePaint, candidate.textAnchor,
                CandidateTextVerticalPolicy.centerY(candidate.bounds.top, candidate.bounds.bottom, density))
        }
        try {
            // Compare actual raster ink, not an independent approximation of font metrics.
            // The reference has no per-slot clip. Raw-pinyin/header colors are different.
            fun ink(bitmap: Bitmap): Int {
                var count = 0
                val bottom = if (expanded) candidate.bounds.bottom.toInt() else metrics.candidateHeight.toInt() + 40
                for (y in 0 until bottom) for (x in candidate.bounds.left.toInt() until candidate.bounds.right.toInt()) {
                    if (bitmap.getPixel(x, y) == 0xFF172033.toInt()) count++
                }
                return count
            }
            assertEquals("Candidate raster must retain the measured glyph, without clipping or a font switch", ink(expected), ink(actual))
        } finally {
            actual.recycle()
            expected.recycle()
        }
    }

    @Test
    fun consecutiveBackgroundFramesStayOpaqueAndColorStable() {
        val width = 128
        val height = 256
        val metrics = KeyboardMetrics.fromDensity(1f)
        val state = BackgroundOnlyRendererState(width, height)

        listOf(false, true).forEach { nightMode ->
            val renderer = createRenderer(width, height, metrics, nightMode)
            val firstFrame = renderFreshFrame(width, height) { canvas ->
                renderer.drawBackground(canvas, state)
            }
            val secondFrame = renderFreshFrame(width, height) { canvas ->
                renderer.drawBackground(canvas, state)
            }

            try {
                val sampleX = width / 2
                val sampleY = 24
                val firstPixel = firstFrame.getPixel(sampleX, sampleY)
                val secondPixel = secondFrame.getPixel(sampleX, sampleY)
                val mode = if (nightMode) "dark" else "light"
                assertEquals(
                    "$mode initial keyboard background must be opaque",
                    255,
                    Color.alpha(firstPixel),
                )
                assertEquals(
                    "$mode background Paint alpha leaked from the previous frame",
                    255,
                    Color.alpha(secondPixel),
                )
                assertEquals(
                    "$mode identical consecutive keyboard backgrounds changed color",
                    firstPixel,
                    secondPixel,
                )
            } finally {
                firstFrame.recycle()
                secondFrame.recycle()
            }
        }
    }

    @Test
    fun dirtyBackgroundFrameMatchesTheFullFrameAcrossPalettes() {
        val width = 128
        val height = 256
        val metrics = KeyboardMetrics.fromDensity(1f)
        val state = BackgroundOnlyRendererState(width, height)

        listOf(false, true).forEach { nightMode ->
            val renderer = createRenderer(width, height, metrics, nightMode)
            val fullFrame = renderFreshFrame(width, height) { canvas ->
                renderer.drawBackground(canvas, state)
            }
            val underlay = if (nightMode) Color.WHITE else Color.BLACK
            val dirtyFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            dirtyFrame.eraseColor(underlay)
            val canvas = Canvas(dirtyFrame)
            val saveCount = canvas.save()
            canvas.clipRect(24, 12, 104, 96)
            renderer.drawBackground(canvas, state)
            canvas.restoreToCount(saveCount)

            try {
                val mode = if (nightMode) "dark" else "light"
                assertEquals(
                    "$mode dirty redraw exposed the host window",
                    fullFrame.getPixel(64, 24),
                    dirtyFrame.getPixel(64, 24),
                )
                assertEquals(
                    "$mode dirty redraw escaped its clip",
                    underlay,
                    dirtyFrame.getPixel(8, 112),
                )
            } finally {
                fullFrame.recycle()
                dirtyFrame.recycle()
            }
        }
    }

    private fun createRenderer(
        width: Int,
        height: Int,
        metrics: KeyboardMetrics,
        nightMode: Boolean,
    ): KeyboardChromeRenderer = KeyboardChromeRenderer(
        density = 1f,
        fontScale = 1f,
        metrics = metrics,
        palette = KeyboardPalette(nightMode),
    ).also { renderer ->
        renderer.updateSurface(width, height, fontScale = 1f)
    }

    private fun renderFreshFrame(
        width: Int,
        height: Int,
        draw: (Canvas) -> Unit,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        draw(Canvas(bitmap))
    }

    private class BackgroundOnlyRendererState(
        override val viewWidth: Int,
        override val viewHeight: Int,
        private val candidateScene: CandidateScene? = null,
    ) : KeyboardRendererState {
        override val panel: KeyboardPanel
            get() = unused()
        override val scene: KeyboardScene
            get() = unused()
        override val candidates: CandidateScene
            get() = candidateScene ?: unused()
        override val candidatesTakeToolbar: Boolean
            get() = candidateScene != null
        override val chromeBottom: Float
            get() = unused()
        override val collapsedCandidateBottom: Float
            get() = unused()
        override val aiSurface: AiSurfaceState?
            get() = unused()
        override val aiLockProgress: Float
            get() = unused()
        override val aiLocked: Boolean
            get() = unused()
        override val aiGeometry: AiSurfaceRenderGeometry
            get() = unused()
        override val aiStopPressed: Boolean
            get() = unused()
        override val voiceSurface: VoiceSurfaceState?
            get() = unused()
        override val voiceWaveformBuffer: VoiceWaveformBuffer
            get() = unused()
        override val skillFeedbackMessage: String?
            get() = unused()
        override val activeSkillSourceKey: Key?
            get() = unused()
        override val activeKeyboardSkill: ActiveKeyboardSkill?
            get() = unused()
        override val hasAuroraSibling: Boolean
            get() = unused()
        override val skillPickerVisible: Boolean
            get() = unused()
        override val skillPickerSourceBounds: RectF
            get() = unused()
        override val skillPickerOptions: KeyboardSkillOptions?
            get() = unused()
        override val skillPickerOptionBounds: Array<RectF>
            get() = unused()
        override val highlightedSkillDirection: KeyboardSkillDirection?
            get() = unused()
        override val emojiGroupIndex: Int
            get() = unused()
        override val symbolCategoryIndex: Int
            get() = unused()
        override val editorSelectionMode: Boolean
            get() = unused()

        override fun isCandidatePressed(sourceIndex: Int): Boolean = false

        override fun isCandidateControlPressed(control: CandidateControl): Boolean = false

        override fun isKeyPressed(key: Key): Boolean = unused()

        override fun isKeyEnabled(key: Key): Boolean = unused()

        private fun <T> unused(): T = error("Background renderer accessed unrelated state")
    }
}
