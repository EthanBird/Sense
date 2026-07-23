package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun everyStableCategoryIsPresentExactlyOnce() {
        assertEquals(
            EmojiCategoryId.entries.toSet(),
            EmojiCatalog.categories.map(EmojiCategory::id).toSet(),
        )
        assertEquals(EmojiCategoryId.entries.size, EmojiCatalog.categories.size)
    }

    @Test
    fun catalogIsLargeFlatAndContainsRequiredSemanticGlyphs() {
        assertEquals(1_310, EmojiCatalog.totalCount)
        assertTrue(EmojiCatalog.categories.all { it.values.size >= 70 })
        assertTrue(EmojiCatalog.categories.flatMap(EmojiCategory::values).all(String::isNotBlank))

        val all = EmojiCatalog.categories.flatMap(EmojiCategory::values).toSet()
        assertTrue("🐔" in all)
        assertTrue("💊" in all)
        assertTrue("🈶" in all)
        assertTrue("🔒" in all)
    }

    @Test
    fun lookupPreservesTheDeclaredFlatOrder() {
        val smileys = EmojiCatalog.category(EmojiCategoryId.SMILEYS)
        assertEquals("😀", smileys.values.first())
        assertEquals(smileys, EmojiCatalog.categories.first())
    }
}
