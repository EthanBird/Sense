package io.github.ethanbird.senseime.ui

/** Fixed-width, retained-offset policy for the horizontally scrolling Emoji rail. */
internal object EmojiCategoryRailPolicy {
    private const val SLOT_WIDTH_DP = 46f

    fun slotWidth(density: Float): Float {
        require(density > 0f)
        return SLOT_WIDTH_DP * density
    }

    fun configureAndReveal(
        state: ContinuousVerticalScrollState,
        itemCount: Int,
        selectedIndex: Int,
        viewportExtent: Float,
        slotWidth: Float,
    ) {
        require(itemCount > 0)
        require(selectedIndex in 0 until itemCount)
        state.configure(itemCount * slotWidth, viewportExtent)
        state.ensureVisible(
            itemStart = selectedIndex * slotWidth,
            itemEnd = (selectedIndex + 1) * slotWidth,
        )
    }
}
