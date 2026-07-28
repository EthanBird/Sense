package io.github.ethanbird.senseime.service.ai

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSkillRunSnapshotTest {
    @Test
    fun `catalog may be frozen without an active Skill`() {
        val snapshot = AgentSkillRunSnapshot(catalogGeneration = 7L, activeSkill = null)

        assertEquals(7L, snapshot.catalogGeneration)
        assertNull(snapshot.activeSkill)
    }

    @Test
    fun `active Skill must come from the same immutable catalog generation`() {
        val selected = selectedSkill(catalogGeneration = 9L)

        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillRunSnapshot(catalogGeneration = 8L, activeSkill = selected)
        }
        assertEquals(
            selected,
            AgentSkillRunSnapshot(catalogGeneration = 9L, activeSkill = selected).activeSkill,
        )
    }

    @Test
    fun `no change cannot be used as an active Skill base intent`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectedAgentSkill(
                baseIntent = EditorIntent.NO_CHANGE,
                instruction = selectedSkill(1L).instruction,
            )
        }
    }

    private fun selectedSkill(catalogGeneration: Long) = SelectedAgentSkill(
        baseIntent = EditorIntent.REWRITE,
        instruction = ActiveSkillInstructionV1(
            id = "concise",
            revision = 3L,
            catalogGeneration = catalogGeneration,
            name = "精简",
            description = "压缩文字但保留事实。",
            content = "# 精简\n保留事实。",
        ),
    )
}
