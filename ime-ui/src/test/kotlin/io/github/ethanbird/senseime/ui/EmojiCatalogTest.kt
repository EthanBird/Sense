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
        assertTrue(EmojiCatalog.categories.size >= 11)
    }

    @Test
    fun catalogIsLargeFlatAndContainsRequiredSemanticGlyphs() {
        assertTrue(EmojiCatalog.totalCount >= 1_600)
        assertTrue(EmojiCatalog.categories.all { it.values.size >= 70 })
        assertTrue(EmojiCatalog.categories.flatMap(EmojiCategory::values).all(String::isNotBlank))

        val all = EmojiCatalog.categories.flatMap(EmojiCategory::values).toSet()
        assertTrue("🐔" in all)
        assertTrue("💊" in all)
        assertTrue("🈶" in all)
        assertTrue("🔒" in all)
    }

    @Test
    fun gesturesHeartsAndFlagsHaveDedicatedDiscoverableCategories() {
        val gestures = EmojiCatalog.category(EmojiCategoryId.GESTURES_BODY).values
        val hearts = EmojiCatalog.category(EmojiCategoryId.HEARTS).values
        val flags = EmojiCatalog.category(EmojiCategoryId.FLAGS).values

        listOf("🏻", "🏼", "🏽", "🏾", "🏿").forEach { tone ->
            assertTrue("👍$tone" in gestures)
            assertTrue("🫶$tone" in gestures)
        }
        assertTrue("❤️‍🔥" in hearts)
        assertTrue("💞" in hearts)
        assertTrue(hearts.size >= 70)
        assertTrue("🇨🇳" in flags)
        assertTrue("🇺🇳" in flags)
        assertTrue(flags.size >= 250)
    }

    @Test
    fun lookupPreservesTheDeclaredFlatOrder() {
        val smileys = EmojiCatalog.category(EmojiCategoryId.SMILEYS)
        assertEquals("😀", smileys.values.first())
        assertEquals(smileys, EmojiCatalog.categories.first())
    }
}
