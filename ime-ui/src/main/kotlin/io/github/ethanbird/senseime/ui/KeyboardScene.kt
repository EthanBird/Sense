package io.github.ethanbird.senseime.ui

import android.graphics.RectF

/**
 * Read-only geometry published to rendering and interaction.
 *
 * The mutable implementation is rebuilt off the View's draw/touch paths and
 * then retained until a topology or viewport change requires another scene.
 */
internal interface KeyboardScene {
    val keys: List<Key>
    val toolbarKeyStart: Int
    val toolbarKeyEndExclusive: Int
    val panelKeyStart: Int
    val panelKeyEndExclusive: Int
    val systemBarKeyStart: Int
    val systemBarKeyEndExclusive: Int
    val emojiGridBounds: RectF?
    val emojiCategoryBounds: RectF?
    val symbolCategoryBounds: RectF?
    val symbolGridBounds: RectF?
    val t9LeftRailBounds: RectF?
    val editorMainBounds: RectF?
    val editorBottomTop: Float
    val editorBottomSeparators: FloatArray
    val editorBottomSeparatorCount: Int
    val voiceWaveformBounds: RectF
    val voiceStatusCenterY: Float
    val voiceTranscriptCenterY: Float
    val clipboardPageLabel: String
    val buildCount: Long

    fun viewportBounds(panel: ScrollPanel): RectF?

    fun scrollOffset(panel: ScrollPanel): Float
}

/**
 * Reusable scene storage. Array/list capacity and scroll state survive rebuilds
 * so switching panels does not create per-frame or per-touch object churn.
 */
internal class MutableKeyboardScene : KeyboardScene {
    internal val mutableKeys = ArrayList<Key>(64)
    override val keys: List<Key>
        get() = mutableKeys

    override var toolbarKeyStart = 0
        internal set
    override var toolbarKeyEndExclusive = 0
        internal set
    override var panelKeyStart = 0
        internal set
    override var panelKeyEndExclusive = 0
        internal set
    override var systemBarKeyStart = 0
        internal set
    override var systemBarKeyEndExclusive = 0
        internal set

    override var emojiGridBounds: RectF? = null
        internal set
    override var emojiCategoryBounds: RectF? = null
        internal set
    override var symbolCategoryBounds: RectF? = null
        internal set
    override var symbolGridBounds: RectF? = null
        internal set
    override var t9LeftRailBounds: RectF? = null
        internal set
    override var editorMainBounds: RectF? = null
        internal set
    override var editorBottomTop: Float = 0f
        internal set
    override val editorBottomSeparators = FloatArray(2)
    override var editorBottomSeparatorCount: Int = 0
        internal set
    override val voiceWaveformBounds = RectF()
    override var voiceStatusCenterY: Float = 0f
        internal set
    override var voiceTranscriptCenterY: Float = 0f
        internal set
    override var clipboardPageLabel: String = ""
        internal set
    override var buildCount: Long = 0L
        internal set

    val emojiScrollState = ContinuousVerticalScrollState()
    val emojiCategoryScrollState = ContinuousVerticalScrollState()
    val symbolCategoryScrollState = ContinuousVerticalScrollState()
    val symbolGridScrollState = ContinuousVerticalScrollState()
    val t9LeftRailScrollState = ContinuousVerticalScrollState()

    private val occurrenceCounts = HashMap<KeyboardSkillPhysicalOwner.Signature, Int>()

    internal fun beginRebuild() {
        buildCount += 1L
        mutableKeys.clear()
        toolbarKeyStart = 0
        toolbarKeyEndExclusive = 0
        panelKeyStart = 0
        panelKeyEndExclusive = 0
        systemBarKeyStart = 0
        systemBarKeyEndExclusive = 0
        emojiGridBounds = null
        emojiCategoryBounds = null
        symbolCategoryBounds = null
        symbolGridBounds = null
        t9LeftRailBounds = null
        editorMainBounds = null
        editorBottomTop = 0f
        editorBottomSeparators.fill(0f)
        editorBottomSeparatorCount = 0
        voiceWaveformBounds.setEmpty()
        voiceStatusCenterY = 0f
        voiceTranscriptCenterY = 0f
        clipboardPageLabel = ""
    }

    internal fun assignPhysicalKeyIds(panel: KeyboardPanel) {
        assignPhysicalKeyIds(
            surface = KeyboardSkillPhysicalOwner.Surface.TOOLBAR,
            panelToken = null,
            start = toolbarKeyStart,
            endExclusive = toolbarKeyEndExclusive,
        )
        assignPhysicalKeyIds(
            surface = KeyboardSkillPhysicalOwner.Surface.PANEL,
            panelToken = panel.name,
            start = panelKeyStart,
            endExclusive = panelKeyEndExclusive,
        )
        assignPhysicalKeyIds(
            surface = KeyboardSkillPhysicalOwner.Surface.SYSTEM_BAR,
            panelToken = null,
            start = systemBarKeyStart,
            endExclusive = systemBarKeyEndExclusive,
        )
    }

    fun physicalIdFor(key: Key): PhysicalKeyId? = key.physicalId

    fun keyFor(owner: KeyboardSkillPhysicalOwner): Key? {
        val start: Int
        val endExclusive: Int
        when (owner.surface) {
            KeyboardSkillPhysicalOwner.Surface.TOOLBAR -> {
                start = toolbarKeyStart
                endExclusive = toolbarKeyEndExclusive
            }
            KeyboardSkillPhysicalOwner.Surface.PANEL -> {
                start = panelKeyStart
                endExclusive = panelKeyEndExclusive
            }
            KeyboardSkillPhysicalOwner.Surface.SYSTEM_BAR -> {
                start = systemBarKeyStart
                endExclusive = systemBarKeyEndExclusive
            }
        }
        var index = start
        while (index < endExclusive) {
            val key = mutableKeys[index]
            if (key.physicalId?.matches(owner) == true) return key
            index += 1
        }
        return null
    }

    override fun viewportBounds(panel: ScrollPanel): RectF? = when (panel) {
        ScrollPanel.EMOJI -> emojiGridBounds
        ScrollPanel.EMOJI_CATEGORIES -> emojiCategoryBounds
        ScrollPanel.SYMBOL_CATEGORIES -> symbolCategoryBounds
        ScrollPanel.SYMBOL_VALUES -> symbolGridBounds
        ScrollPanel.T9_LEFT_RAIL -> t9LeftRailBounds
    }

    override fun scrollOffset(panel: ScrollPanel): Float = when (panel) {
        ScrollPanel.EMOJI -> emojiScrollState.offset
        ScrollPanel.EMOJI_CATEGORIES -> emojiCategoryScrollState.offset
        ScrollPanel.SYMBOL_CATEGORIES -> symbolCategoryScrollState.offset
        ScrollPanel.SYMBOL_VALUES -> symbolGridScrollState.offset
        ScrollPanel.T9_LEFT_RAIL -> t9LeftRailScrollState.offset
    }

    private fun assignPhysicalKeyIds(
        surface: KeyboardSkillPhysicalOwner.Surface,
        panelToken: String?,
        start: Int,
        endExclusive: Int,
    ) {
        occurrenceCounts.clear()
        var index = start
        while (index < endExclusive) {
            val key = mutableKeys[index]
            val signature = key.physicalSkillSignature()
            val occurrence = occurrenceCounts[signature] ?: 0
            occurrenceCounts[signature] = occurrence + 1
            key.physicalId = PhysicalKeyId(
                surface = surface,
                panelToken = panelToken,
                signature = signature,
                occurrence = occurrence,
            )
            index += 1
        }
    }

    private fun Key.physicalSkillSignature(): KeyboardSkillPhysicalOwner.Signature {
        val editorAction = (action as? KeyAction.Editor)?.action
        val clipboardAction = (action as? KeyAction.Clipboard)?.action
        return KeyboardSkillPhysicalOwner.Signature(
            keyCode = code,
            styleToken = style.name,
            iconToken = icon?.name,
            editorActionToken = editorAction?.name,
            clipboardActionToken = clipboardAction?.name,
        )
    }
}
