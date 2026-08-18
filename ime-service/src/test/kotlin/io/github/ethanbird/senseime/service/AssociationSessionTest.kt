package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.AssociationSuggestion
import io.github.ethanbird.senseime.core.AssociationSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssociationSessionTest {
    @Test
    fun selectionRequiresExactRevisionEditorAndConnectionIdentity() {
        val connection = Any()
        val session = AssociationSession()
        val presentation = session.publish(
            editorSessionId = 7L,
            connectionIdentity = connection,
            context = "智能",
            suggestions = listOf(suggestion("体")),
        )

        assertEquals(
            "体",
            session.select(presentation.revision, 0, 7L, connection, "智能")?.text,
        )
        assertNull(session.select(presentation.revision + 1L, 0, 7L, connection, "智能"))
        assertNull(session.select(presentation.revision, 0, 8L, connection, "智能"))
        assertNull(session.select(presentation.revision, 0, 7L, Any(), "智能"))
        assertNull(session.select(presentation.revision, 0, 7L, connection, "别处"))
    }

    @Test
    fun publishingNewContextAndClearingInvalidateOldSelection() {
        val connection = Any()
        val session = AssociationSession()
        val old = session.publish(1L, connection, "智能", listOf(suggestion("体")))
        val current = session.publish(1L, connection, "体", listOf(suggestion("系")))

        assertNull(session.select(old.revision, 0, 1L, connection, "智能"))
        assertEquals("系", session.select(current.revision, 0, 1L, connection, "体")?.text)

        session.clear()
        assertNull(session.select(current.revision, 0, 1L, connection, "体"))
    }

    private fun suggestion(text: String) = AssociationSuggestion(
        text = text,
        score = 1f,
        source = AssociationSuggestionSource.USER_HISTORY,
    )
}
