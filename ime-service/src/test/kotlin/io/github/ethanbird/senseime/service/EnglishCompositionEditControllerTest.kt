package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.EnglishInputSession
import io.github.ethanbird.senseime.core.EnglishLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCompositionEditControllerTest {
    @Test
    fun rejectedTypeAndBackspaceLeaveTheSessionUntouched() {
        val typeHost = FakeHost()
        typeHost.acceptPublication = false
        val typeController = EnglishCompositionEditController(typeHost)

        assertFalse(typeController.type('a'))
        assertEquals("", typeHost.englishSession.composing)
        assertEquals(0L, typeHost.englishSession.revision)
        assertEquals(listOf("a"), typeHost.publications)

        val backspaceHost = FakeHost().apply {
            englishSession.type('a')
            englishSession.type('b')
            acceptPublication = false
        }
        val backspaceController = EnglishCompositionEditController(backspaceHost)

        assertFalse(backspaceController.backspace())
        assertEquals("ab", backspaceHost.englishSession.composing)
        assertEquals(2L, backspaceHost.englishSession.revision)
        assertEquals(listOf("a"), backspaceHost.publications)
    }

    @Test
    fun acceptedPublicationAdvancesTheFrozenSessionExactlyOnce() {
        val host = FakeHost()
        val controller = EnglishCompositionEditController(host)

        assertTrue(controller.type('A'))
        assertEquals("A", host.englishSession.composing)
        assertEquals(1L, host.englishSession.revision)
        assertTrue(controller.backspace())
        assertEquals("", host.englishSession.composing)
        assertEquals(2L, host.englishSession.revision)
        assertEquals(listOf("A", ""), host.publications)
    }

    @Test
    fun synchronousEditorSessionChangeDoesNotResurrectTheOldSession() {
        val host = FakeHost().apply {
            onPublish = {
                editorSessionIdentity += 1L
                inputConnectionIdentity = Any()
            }
        }
        val controller = EnglishCompositionEditController(host)

        assertFalse(controller.type('a'))
        assertEquals("", host.englishSession.composing)
        assertEquals(0L, host.englishSession.revision)
    }

    @Test
    fun synchronousSessionReplacementDoesNotMutateTheDetachedSession() {
        val original = EnglishInputSession(EnglishLexicon.EMPTY)
        val host = FakeHost(original).apply {
            onPublish = {
                englishSession = EnglishInputSession(EnglishLexicon.EMPTY)
            }
        }
        val controller = EnglishCompositionEditController(host)

        assertFalse(controller.type('a'))
        assertEquals("", original.composing)
        assertEquals("", host.englishSession.composing)
    }

    private class FakeHost(
        override var englishSession: EnglishInputSession =
            EnglishInputSession(EnglishLexicon.EMPTY),
    ) : EnglishCompositionEditHost {
        override var editorSessionIdentity: Long = 1L
        override var inputConnectionIdentity: Any? = Any()
        var acceptPublication = true
        var onPublish: () -> Unit = {}
        val publications = mutableListOf<String>()

        override fun publishEnglishComposition(text: String): Boolean {
            publications += text
            onPublish()
            return acceptPublication
        }
    }
}
