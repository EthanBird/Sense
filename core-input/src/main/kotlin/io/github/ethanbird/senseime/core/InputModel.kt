package io.github.ethanbird.senseime.core

data class Candidate(
    val text: String,
    val score: Float = 0f,
)

data class InputState(
    val composing: String = "",
    val revision: Long = 0,
    val committed: List<String> = emptyList(),
)

sealed interface InputAction {
    data class Type(val character: Char) : InputAction
    data object Backspace : InputAction
    data class Commit(val text: String) : InputAction
    data object Reset : InputAction
}

data class EditorTransaction(
    val id: Long,
    val sessionId: Long,
    val revision: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val selectedText: String?,
    val beforeCursor: String,
    val afterCursor: String,
    val composingText: String?,
)

interface InputDecoder {
    fun decode(composing: String, limit: Int = 5): List<Candidate>
}

