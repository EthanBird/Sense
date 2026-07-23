package io.github.ethanbird.senseime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishInputSessionTest {
    private val lexicon = EnglishLexicon.fromWords(
        listOf("hosted", "host", "hosts", "hostile", "hello"),
    )

    @Test
    fun typingKeepsTheWordComposingAndProvidesDictionaryCandidates() {
        val session = EnglishInputSession(lexicon)
        "host".forEach { assertTrue(session.type(it)) }

        assertEquals("host", session.composing)
        assertEquals(4L, session.revision)
        assertEquals(
            listOf("host", "hosts", "hostile", "hosted"),
            session.candidates.take(4).map { it.text },
        )
    }

    @Test
    fun shiftedTypingPreservesCandidateCase() {
        val titleCase = EnglishInputSession(lexicon)
        "Host".forEach(titleCase::type)
        assertEquals(listOf("Host", "Hosts", "Hostile"), titleCase.candidates.take(3).map { it.text })

        val upperCase = EnglishInputSession(lexicon)
        "HOST".forEach(upperCase::type)
        assertEquals(listOf("HOST", "HOSTS", "HOSTILE"), upperCase.candidates.take(3).map { it.text })
    }

    @Test
    fun staleCandidateSelectionCannotCommitAReplacement() {
        val session = EnglishInputSession(lexicon)
        "hos".forEach(session::type)
        val staleRevision = session.revision
        session.type('t')

        assertNull(session.select(staleRevision, 0))
        assertEquals("host", session.select(session.revision, 0)?.text)
    }

    @Test
    fun incompleteWordIsNotSilentlyCompletedByASeparator() {
        val session = EnglishInputSession(lexicon)
        "hos".forEach(session::type)

        assertTrue(session.candidates.first().text.startsWith("hos"))
        assertNull(session.defaultCommitCandidate)

        session.type('t')
        assertEquals("host", session.defaultCommitCandidate?.text)
    }

    @Test
    fun deletingTheFinalLetterProducesAnEmptyEditorReplacement() {
        val session = EnglishInputSession(lexicon)
        session.type('h')

        assertTrue(session.backspace())
        assertEquals("", session.composing)
        assertTrue(session.candidates.isEmpty())
        assertEquals(
            EditorComposingTextUpdate(text = "", newCursorPosition = 1),
            editorComposingTextUpdate(session.composing),
        )
        assertFalse(session.backspace())
    }

    @Test
    fun pinyinFinalBackspaceAlsoRequestsAnEmptyReplacement() {
        val composition = PinyinComposition().type('w').backspace()

        assertEquals("", composition.visibleText)
        assertEquals("", editorComposingTextUpdate(composition.visibleText).text)
    }
}
