package io.github.ethanbird.senseime.ui

/**
 * Stable ordering contract for the phone keyboard.
 *
 * Keeping these rows outside the Canvas view makes the physical layout testable:
 * M is always followed by backspace, and the system bar contains only its two
 * edge actions so its centre remains reserved.
 */
object KeyboardLayoutContract {
    data class CandidateSlot(
        val left: Float,
        val right: Float,
        val textAnchor: Float,
    )

    data class WeightedKey(
        val label: String,
        val code: Int,
        val weight: Float,
        val action: Boolean = false,
    )

    enum class Side {
        LEFT,
        RIGHT,
    }

    data class SystemKey(
        val code: Int,
        val side: Side,
    )

    fun thirdLetterRow(shifted: Boolean): List<WeightedKey> = buildList {
        add(WeightedKey("⇧", KeyCodes.SHIFT, 1.25f, action = true))
        "zxcvbnm".forEach { character ->
            add(WeightedKey(if (shifted) character.uppercase() else character.toString(), character.code, 1f))
        }
        add(WeightedKey("⌫", KeyCodes.DELETE, 1.25f, action = true))
    }

    fun functionRow(chineseMode: Boolean, returnToLetters: Boolean = false): List<WeightedKey> = listOf(
        WeightedKey(if (returnToLetters) "ABC" else "符", if (returnToLetters) KeyCodes.LETTERS else KeyCodes.SYMBOLS, 0.9f, action = true),
        WeightedKey("123", KeyCodes.NUMBERS, 1.05f, action = true),
        WeightedKey(if (chineseMode) "，" else ",", KeyCodes.COMMA, 0.8f),
        WeightedKey("空格", KeyCodes.SPACE, 2.7f),
        WeightedKey(if (chineseMode) "。" else ".", KeyCodes.PERIOD, 0.8f),
        WeightedKey("中/英", KeyCodes.LANGUAGE, 1f, action = true),
        WeightedKey("↵", KeyCodes.ENTER, 1.2f, action = true),
    )

    val systemBar: List<SystemKey> = listOf(
        SystemKey(KeyCodes.SWITCH_INPUT_METHOD, Side.LEFT),
        SystemKey(KeyCodes.CLIPBOARD, Side.RIGHT),
    )

    fun leftAlignedCandidateSlots(
        viewWidth: Float,
        measuredTextWidths: List<Float>,
        padding: Float,
        textInset: Float,
        gap: Float,
        minimumWidth: Float,
    ): List<CandidateSlot> {
        val result = ArrayList<CandidateSlot>(measuredTextWidths.size)
        var left = padding
        for (textWidth in measuredTextWidths) {
            if (viewWidth - padding - left < minimumWidth) break
            val width = maxOf(minimumWidth, textWidth + textInset * 2)
            val right = minOf(viewWidth - padding, left + width)
            result += CandidateSlot(left, right, left + textInset)
            left = right + gap
        }
        return result
    }
}
