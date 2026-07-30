package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.EnglishInputSession

/**
 * Host boundary for transactional English composing edits.
 *
 * InputConnection implementations may synchronously switch editors or reset the
 * local session before setComposingText returns. The controller therefore
 * freezes both editor and session identity around the host call.
 */
internal interface EnglishCompositionEditHost {
    val englishSession: EnglishInputSession
    val editorSessionIdentity: Long
    val inputConnectionIdentity: Any?

    fun publishEnglishComposition(text: String): Boolean
}

internal class EnglishCompositionEditController(
    private val host: EnglishCompositionEditHost,
) {
    fun type(character: Char): Boolean {
        if (character.lowercaseChar() !in 'a'..'z') return false
        val session = host.englishSession
        val previousText = session.composing
        return publishThenApply(
            session = session,
            previousRevision = session.revision,
            previousText = previousText,
            nextText = previousText + character,
        ) {
            session.type(character)
        }
    }

    fun backspace(): Boolean {
        val session = host.englishSession
        val previousText = session.composing
        if (previousText.isEmpty()) return false
        return publishThenApply(
            session = session,
            previousRevision = session.revision,
            previousText = previousText,
            nextText = previousText.dropLast(1),
        ) {
            session.backspace()
        }
    }

    private inline fun publishThenApply(
        session: EnglishInputSession,
        previousRevision: Long,
        previousText: String,
        nextText: String,
        apply: () -> Boolean,
    ): Boolean {
        val editorIdentity = host.editorSessionIdentity
        val connectionIdentity = host.inputConnectionIdentity
        if (!host.publishEnglishComposition(nextText)) return false
        if (
            host.editorSessionIdentity != editorIdentity ||
            host.inputConnectionIdentity !== connectionIdentity ||
            host.englishSession !== session ||
            session.revision != previousRevision ||
            session.composing != previousText
        ) {
            return false
        }
        return apply()
    }
}
