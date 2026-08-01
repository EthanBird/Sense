package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCreationExperienceTest {
    @Test
    fun starterTemplatesProduceValidReadyToSaveDrafts() {
        SkillCreationTemplates.all.drop(1).forEach { template ->
            val draft = template.instantiate(emptySet())
            assertNull("${template.key}: ${draft.validationError()}", draft.validationError())
        }
    }

    @Test
    fun suggestedIdsAdvanceWithoutColliding() {
        assertEquals(
            "polish_4",
            SkillCreationTemplates.uniqueId(
                "polish",
                setOf("polish", "polish_2", "polish_3"),
            ),
        )
    }

    @Test
    fun undoAndRedoSlotsAreReservedButOtherDirectionsRemainAvailable() {
        val undo = AgentSkillSlot('z'.code, AgentSkillDirection.DOWN)
        val redo = AgentSkillSlot('y'.code, AgentSkillDirection.DOWN)
        val zUp = AgentSkillSlot('z'.code, AgentSkillDirection.UP)

        assertEquals("撤销", SkillBindingSlotPolicy.reservedCommand(undo))
        assertEquals("重做", SkillBindingSlotPolicy.reservedCommand(redo))
        assertFalse(SkillBindingSlotPolicy.isSelectable(undo))
        assertFalse(SkillBindingSlotPolicy.isSelectable(redo))
        assertTrue(SkillBindingSlotPolicy.isSelectable(zUp))
        assertTrue(
            SkillCreationTemplates.all[1]
                .instantiate(emptySet(), undo)
                .validationError()
                ?.contains("撤销") == true,
        )
    }
}
