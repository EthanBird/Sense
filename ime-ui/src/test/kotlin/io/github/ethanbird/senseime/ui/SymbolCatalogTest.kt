package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolCatalogTest {
    @Test
    fun everyRequestedCategoryHasAStableIdAndContent() {
        assertEquals(
            SymbolCategoryId.entries.toSet(),
            SymbolCatalog.categories.map(SymbolCategory::id).toSet(),
        )
        assertEquals(SymbolCategoryId.entries.size, SymbolCatalog.categories.size)
        assertEquals(1_947, SymbolCatalog.totalCount)
        assertTrue(SymbolCatalog.categories.all { it.values.isNotEmpty() })

        val labels = SymbolCatalog.categories.map(SymbolCategory::label).toSet()
        assertTrue(
            labels.containsAll(
                setOf(
                    "数学",
                    "角标",
                    "序号",
                    "音标",
                    "平假",
                    "片假",
                    "箭头",
                    "特殊",
                    "拼音",
                    "注音",
                    "竖标",
                    "部首",
                    "俄文",
                    "希腊",
                    "拉丁",
                    "制表",
                    "土音",
                    "藏文",
                ),
            ),
        )
    }

    @Test
    fun mathAndArrowSetsContainTheRequiredDailySymbols() {
        val math = SymbolCatalog.category(SymbolCategoryId.MATH).values
        val arrows = SymbolCatalog.category(SymbolCategoryId.ARROWS).values

        assertTrue(math.containsAll(listOf("Ω", "π", "∞", "√", "∑", "∫", "℃")))
        assertTrue(arrows.containsAll(listOf("←", "→", "↑", "↓", "↔", "↕")))
    }

    @Test
    fun radicalCategoryContainsTheCompleteKangxiBlock() {
        val radicals = SymbolCatalog.category(SymbolCategoryId.RADICALS).values

        assertEquals(214, radicals.size)
        assertEquals("⼀", radicals.first())
        assertEquals("⿕", radicals.last())
    }
}
