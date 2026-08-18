package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAssociationTest {
    @Test
    fun userHistoryOutranksStaticCharacterFallbackAndMatchesEditorSuffix() {
        val store = MemoryUserAssociationLexicon(clock = { 1_000L })
        store.record("智能", "体")
        store.record("智能", "输入法")
        store.record("智能", "输入法")
        val engine = LocalAssociationEngine(
            store,
            BinaryCharacterBigramModel.fromBytes(
                model(Triple('能'.code, '力'.code, 3f)),
            ),
        )

        val suggestions = engine.suggest("这是智能", 8)

        assertEquals(listOf("输入法", "体", "力"), suggestions.map { it.text })
        assertEquals(AssociationSuggestionSource.USER_HISTORY, suggestions.first().source)
        assertEquals(AssociationSuggestionSource.STATIC_CHARACTER_BIGRAM, suggestions.last().source)
    }

    @Test
    fun invalidOrNonHanTransitionsAreSkipped() {
        val store = MemoryUserAssociationLexicon(clock = { 1_000L })
        val engine = LocalAssociationEngine(store, CharacterBigramModel.EMPTY)

        assertEquals(null, engine.observe("Sense", "输入法"))
        assertEquals(null, engine.observe("智能", " "))
        assertTrue(engine.suggest("Sense", 8).isEmpty())
    }

    @Test
    fun oneOffHistoryDoesNotPermanentlyDominateStrongStaticEvidence() {
        var now = 1_000L
        val store = MemoryUserAssociationLexicon(clock = { now })
        store.record("智", "能")
        val engine = LocalAssociationEngine(
            store,
            BinaryCharacterBigramModel.fromBytes(
                model(Triple('智'.code, '慧'.code, 3f)),
            ),
        )

        assertEquals("能", engine.suggest("智", 2).first().text)
        now += 365L * 24L * 60L * 60L * 1_000L
        assertEquals("慧", engine.suggest("智", 2).first().text)
    }

    private fun model(vararg entries: Triple<Int, Int, Float>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeBytes("SBGM")
                output.writeShort(1)
                output.writeInt(entries.size)
                entries.sortedWith(compareBy({ it.first }, { it.second })).forEach { (previous, next, score) ->
                    output.writeInt(previous)
                    output.writeInt(next)
                    output.writeFloat(score)
                }
            }
        }.toByteArray()
}
