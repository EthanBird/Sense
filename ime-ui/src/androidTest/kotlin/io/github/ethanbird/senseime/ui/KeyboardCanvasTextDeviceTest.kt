package io.github.ethanbird.senseime.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardCanvasTextDeviceTest {
    @Test
    fun fittedLabelsAndLegendsStayInsideTheirOwnBandIncludingFallbackGlyphs() {
        val helper = KeyboardCanvasText()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        val metrics = Paint.FontMetrics()
        for (value in listOf("Q", "M", "中/英", "符", "123", "，", "。", "@", "1", "“")) {
            for ((width, height) in listOf(31f to 43f, 63f to 29f, 31f to 13f)) {
                for (scale in listOf(1.3f, 1.5f, 2f)) {
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 20f * scale * 2.625f
                    helper.fitToBox(paint, value, width * 2.625f, height * 2.625f)
                    paint.getTextBounds(value, 0, value.length, bounds)
                    paint.getFontMetrics(metrics)
                    val baseline = -(metrics.ascent + metrics.descent) / 2f
                    assertTrue("$value top at $scale", baseline + bounds.top >= -height * 2.625f / 2f - 1f)
                    assertTrue("$value bottom at $scale", baseline + bounds.bottom <= height * 2.625f / 2f + 1f)
                    assertTrue("$value width at $scale", paint.measureText(value) <= width * 2.625f + 1f)
                }
            }
        }
    }
}
