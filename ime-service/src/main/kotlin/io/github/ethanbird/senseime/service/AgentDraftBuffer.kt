package io.github.ethanbird.senseime.service

/**
 * Editor-independent text target used while the Sense keyboard is composing an Agent message.
 *
 * The active Pinyin/Wubi/English composition remains owned by the existing IME state machines;
 * this buffer stores only committed draft text and exposes a projected composing suffix for UI.
 */
internal class AgentDraftBuffer(
    private val maxChars: Int = 12_000,
    private val historyLimit: Int = 64,
) {
    private data class Snapshot(val text: String, val cursor: Int)

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    var text: String = ""
        private set
    var cursor: Int = 0
        private set

    fun displayText(composingText: String): String = buildString(
        text.length + composingText.length,
    ) {
        append(text, 0, cursor)
        append(composingText)
        append(text, cursor, text.length)
    }

    fun contextBeforeCursor(maxLength: Int): String =
        text.substring(0, cursor).takeLast(maxLength.coerceAtLeast(0))

    fun insert(value: CharSequence): Boolean {
        if (value.isEmpty()) return true
        val available = maxChars - text.length
        if (available <= 0) return false
        if (value.length > available) return false
        val accepted = value.toString()
        recordMutation()
        text = text.substring(0, cursor) + accepted + text.substring(cursor)
        cursor += accepted.length
        return true
    }

    fun deleteBackward(): Boolean {
        if (cursor <= 0) return false
        val start = Character.offsetByCodePoints(text, cursor, -1)
        recordMutation()
        text = text.removeRange(start, cursor)
        cursor = start
        return true
    }

    fun moveCursor(deltaCodePoints: Int): Boolean {
        if (deltaCodePoints == 0) return true
        val available = if (deltaCodePoints > 0) {
            text.codePointCount(cursor, text.length)
        } else {
            text.codePointCount(0, cursor)
        }
        val applied = deltaCodePoints.coerceIn(-available, available)
        if (applied == 0) return false
        cursor = Character.offsetByCodePoints(text, cursor, applied)
        return true
    }

    fun moveHome(): Boolean {
        if (cursor == 0) return false
        cursor = 0
        return true
    }

    fun moveEnd(): Boolean {
        if (cursor == text.length) return false
        cursor = text.length
        return true
    }

    fun clear(): Boolean {
        if (text.isEmpty()) return false
        recordMutation()
        text = ""
        cursor = 0
        return true
    }

    fun replaceAll(value: String): Boolean {
        val accepted = value.take(maxChars)
        if (accepted == text && cursor == accepted.length) return false
        recordMutation()
        text = accepted
        cursor = accepted.length
        return true
    }

    fun undo(): Boolean {
        val previous = undo.removeLastOrNull() ?: return false
        pushBounded(redo, Snapshot(text, cursor))
        restore(previous)
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        pushBounded(undo, Snapshot(text, cursor))
        restore(next)
        return true
    }

    private fun recordMutation() {
        pushBounded(undo, Snapshot(text, cursor))
        redo.clear()
    }

    private fun restore(snapshot: Snapshot) {
        text = snapshot.text
        cursor = snapshot.cursor.coerceIn(0, text.length)
    }

    private fun pushBounded(target: ArrayDeque<Snapshot>, snapshot: Snapshot) {
        if (historyLimit <= 0) return
        while (target.size >= historyLimit) target.removeFirst()
        target.addLast(snapshot)
    }
}
