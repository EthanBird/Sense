package io.github.ethanbird.senseime.core

/**
 * Revisioned English-word composition backed by the bundled dictionary.
 *
 * English input stays in Android's composing span until the user selects a
 * suggestion, types a separator, or confirms the raw word. This makes
 * backspace reversible and gives candidate taps the same stale-revision
 * protection as pinyin candidates.
 */
class EnglishInputSession(
    private val lexicon: EnglishLexicon,
    private val candidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
) {
    private var snapshot = CandidateSnapshot.EMPTY

    var composing: String = ""
        private set

    var revision: Long = 0
        private set

    val candidates: List<Candidate>
        get() = snapshot.candidates

    /**
     * Separators may confirm a known exact word, but must not silently replace
     * an incomplete or unknown word with the first longer completion.
     */
    val defaultCommitCandidate: Candidate?
        get() = snapshot.candidates.firstOrNull {
            it.matchKind == CandidateMatchKind.ENGLISH_EXACT
        }

    fun type(character: Char): Boolean {
        if (character.lowercaseChar() !in 'a'..'z') return false
        composing += character
        revision += 1
        rebuildCandidates()
        return true
    }

    fun backspace(): Boolean {
        if (composing.isEmpty()) return false
        composing = composing.dropLast(1)
        revision += 1
        rebuildCandidates()
        return true
    }

    fun select(requestedRevision: Long, sourceIndex: Int): Candidate? =
        snapshot.select(revision, requestedRevision, sourceIndex)

    fun reset() {
        if (composing.isNotEmpty() || snapshot.candidates.isNotEmpty()) revision += 1
        composing = ""
        snapshot = CandidateSnapshot(revision, emptyList())
    }

    private fun rebuildCandidates() {
        val values = if (composing.isEmpty()) {
            emptyList()
        } else {
            lexicon.suggest(composing, candidateLimit).map { candidate ->
                candidate.copy(text = applyTypedCase(candidate.text))
            }
        }
        snapshot = CandidateSnapshot(revision, values)
    }

    private fun applyTypedCase(word: String): String = when {
        composing.length > 1 && composing.all(Char::isUpperCase) -> word.uppercase()
        composing.firstOrNull()?.isUpperCase() == true ->
            word.replaceFirstChar(Char::uppercaseChar)

        else -> word
    }

    private companion object {
        const val DEFAULT_CANDIDATE_LIMIT = 255
    }
}

/**
 * The editor must always replace its composing span, including with an empty
 * string. Calling `finishComposingText()` for an empty state would commit the
 * last visible Latin letter instead of deleting it.
 */
data class EditorComposingTextUpdate(
    val text: String,
    val newCursorPosition: Int = 1,
)

fun editorComposingTextUpdate(visibleText: String): EditorComposingTextUpdate =
    EditorComposingTextUpdate(text = visibleText)
