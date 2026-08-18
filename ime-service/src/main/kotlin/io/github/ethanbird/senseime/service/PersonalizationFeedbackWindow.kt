package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.LearnedPhrase

internal sealed interface PersonalizationLearningTarget {
    val text: String

    data class Pinyin(val phrase: LearnedPhrase) : PersonalizationLearningTarget {
        override val text: String
            get() = phrase.text
    }

    data class Wubi(
        val rawCode: String,
        val candidate: Candidate,
    ) : PersonalizationLearningTarget {
        override val text: String
            get() = candidate.text
    }
}

/**
 * Holds only the most recent learned commit.
 *
 * This state machine never touches storage. The service translates a consumed signal into an
 * in-memory demotion, and the active scheme-local lexicon journals it on its serial writer.
 */
internal class PersonalizationFeedbackWindow(
    private val clock: () -> Long,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private data class Pending(
        val target: PersonalizationLearningTarget,
        val start: Int,
        val endExclusive: Int,
        val recordedAtMillis: Long,
        var finalized: Boolean = false,
    )

    /**
     * Immutable first phase of a host edit.
     *
     * [token] deliberately retains the exact pending object instead of only an
     * integer generation. A synchronous editor callback may clear this window or
     * publish a newer learned commit while the InputConnection call is in flight.
     * Completing the old attempt still returns its matching feedback, while it
     * never clears the newer pending commit.
     */
    internal class Attempt internal constructor(
        internal val token: Any,
        internal val targetToDemote: PersonalizationLearningTarget?,
    ) {
        internal var completed = false
    }

    private var pending: Pending? = null

    init {
        require(windowMillis >= 0L)
    }

    fun remember(phrase: LearnedPhrase, start: Int = -1, endExclusive: Int = -1) {
        remember(PersonalizationLearningTarget.Pinyin(phrase), start, endExclusive)
    }

    fun rememberWubi(
        rawCode: String,
        candidate: Candidate,
        start: Int = -1,
        endExclusive: Int = -1,
    ) {
        remember(PersonalizationLearningTarget.Wubi(rawCode, candidate), start, endExclusive)
    }

    private fun remember(
        target: PersonalizationLearningTarget,
        start: Int,
        endExclusive: Int,
    ) {
        pending = Pending(
            target = target,
            start = start,
            endExclusive = endExclusive,
            recordedAtMillis = clock(),
        )
    }

    fun prepareQuickDelete(cursor: Int): Attempt? {
        val value = freshPending() ?: return null
        val cursorMatchesWholeCommit =
            value.start >= 0 &&
                value.endExclusive > value.start &&
                cursor >= 0 &&
                cursor == value.endExclusive &&
                value.endExclusive - value.start == value.target.text.length &&
                value.target.text.codePointCount(0, value.target.text.length) == 1
        return Attempt(
            token = value,
            // One Backspace deletes one code point. Treat it as whole-candidate rejection only
            // when the learned commit itself is exactly one code point; deleting the last
            // character of a multi-character word is a local correction, not a rejection of the
            // entire learned phrase.
            targetToDemote = value.target.takeIf { cursorMatchesWholeCommit },
        )
    }

    fun prepareReplacement(selectionStart: Int, selectionEnd: Int): Attempt? {
        val value = freshPending() ?: return null
        val exactlyMatchesCommittedRange = if (
            selectionStart < 0 ||
            selectionEnd < 0 ||
            selectionStart == selectionEnd ||
            value.start < 0 ||
            value.endExclusive < 0
        ) {
            false
        } else {
            val selectedStart = minOf(selectionStart, selectionEnd)
            val selectedEnd = maxOf(selectionStart, selectionEnd)
            selectedStart == value.start && selectedEnd == value.endExclusive
        }
        return Attempt(
            token = value,
            targetToDemote = value.target.takeIf { exactlyMatchesCommittedRange },
        )
    }

    /** Prepares an accepted action that invalidates feedback without demoting it. */
    fun prepareExpiration(): Attempt? =
        freshPending()?.let { value ->
            Attempt(token = value, targetToDemote = null)
        }

    /**
     * Completes a previously prepared edit after the host accepted it.
     *
     * Attempts are one-shot, and two nested attempts for the same pending phrase
     * can produce at most one demotion.
     */
    fun complete(attempt: Attempt?): PersonalizationLearningTarget? {
        attempt ?: return null
        if (attempt.completed) return null
        attempt.completed = true
        val value = attempt.token as Pending
        if (value.finalized) return null
        value.finalized = true
        if (pending === value) pending = null
        return attempt.targetToDemote
    }

    fun clear() {
        pending = null
    }

    private fun freshPending(): Pending? {
        val value = pending ?: return null
        val age = (clock() - value.recordedAtMillis).coerceAtLeast(0L)
        if (age <= windowMillis) return value
        pending = null
        return null
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 3_000L
    }
}
