package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardBuiltInGesturePolicyTest {
    @Test
    fun zDownIsAlwaysUndoAndPreservesOtherSkillDirections() {
        val configured = KeyboardSkillOptions(
            up = KeyboardSkillBinding(
                keyCode = 'z'.code,
                direction = KeyboardSkillDirection.UP,
                skillId = "polish",
                label = "润色",
            ),
            down = KeyboardSkillBinding(
                keyCode = 'z'.code,
                direction = KeyboardSkillDirection.DOWN,
                skillId = "stale-binding",
                label = "旧绑定",
            ),
        )

        val options = checkNotNull(
            KeyboardBuiltInGesturePolicy.optionsForKey('z'.code, configured),
        )

        assertEquals("polish", options.up?.skillId)
        assertEquals(
            KeyboardBuiltInCommand.UNDO,
            KeyboardBuiltInGesturePolicy.command(checkNotNull(options.down)),
        )
    }

    @Test
    fun yDownIsRedoEvenWithoutPersistedBindings() {
        val options = checkNotNull(
            KeyboardBuiltInGesturePolicy.optionsForKey('Y'.code, configured = null),
        )

        assertEquals(1, options.count)
        assertEquals(
            KeyboardBuiltInCommand.REDO,
            KeyboardBuiltInGesturePolicy.command(checkNotNull(options.down)),
        )
    }

    @Test
    fun unrelatedKeysDoNotGainBuiltInOptions() {
        assertNull(KeyboardBuiltInGesturePolicy.optionsForKey('x'.code, configured = null))
        assertNull(
            KeyboardBuiltInGesturePolicy.reservedCommand(
                'z'.code,
                KeyboardSkillDirection.UP,
            ),
        )
    }
}
