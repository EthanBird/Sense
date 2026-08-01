package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillsTest {
    @Test
    fun `six built-ins have unique valid documents and conservative bindings`() {
        val catalog = AgentBuiltInSkills.initialCatalog()

        assertEquals(1L, catalog.generation)
        assertEquals(6, catalog.definitions.size)
        assertEquals(
            setOf("smart_edit", "answer", "rewrite", "continue", "translate", "format"),
            catalog.definitions.map { it.id }.toSet(),
        )
        assertEquals(6, catalog.bindings.size)
        assertTrue(catalog.definitions.all { it.builtIn && it.revision == 1L })
        assertTrue(catalog.definitions.all { it.description.length <= 240 })
        assertNull(catalog.active)
    }

    @Test
    fun `catalog defensively copies caller-owned lists`() {
        val definitions = AgentBuiltInSkills.definitions.toMutableList()
        val bindings = AgentBuiltInSkills.bindings.toMutableList()
        val catalog = AgentSkillCatalog(9L, definitions, bindings, null)

        definitions.clear()
        bindings.clear()

        assertEquals(6, catalog.definitions.size)
        assertEquals(6, catalog.bindings.size)
        assertEquals("answer", catalog.binding(slot('a'))?.skillId)
    }

    @Test
    fun `create allocates revision and may atomically bind its slot`() {
        val current = AgentBuiltInSkills.initialCatalog()
        val result = AgentSkillCatalogReducer.apply(
            current,
            AgentSkillMutation.Create(
                id = "meeting_notes",
                name = "会议纪要",
                description = "把散乱讨论整理成清晰的会议纪要。",
                content = "# 会议纪要\n保留决定、负责人和待办。",
                baseIntent = EditorIntent.FORMAT,
                binding = slot('m', AgentSkillDirection.LEFT),
                expectedGeneration = current.generation,
            ),
        )

        assertEquals(2L, result.catalog.generation)
        assertEquals(1L, result.newRevision?.revision)
        assertEquals("meeting_notes", result.catalog.binding(slot('m', AgentSkillDirection.LEFT))?.skillId)
        assertEquals(EditorIntent.FORMAT, result.catalog.definition("meeting_notes")?.baseIntent)
    }

    @Test
    fun `create replacing an active occupied slot clears the old activation`() {
        val current = AgentSkillCatalogReducer.apply(
            AgentBuiltInSkills.initialCatalog(),
            AgentSkillMutation.ToggleActive(slot('a')),
        ).catalog

        val result = AgentSkillCatalogReducer.apply(
            current,
            AgentSkillMutation.Create(
                id = "direct_answer",
                name = "直接回答",
                description = "用用户指定的方式直接回答。",
                content = "# 直接回答\n给出可直接发送的正文。",
                baseIntent = EditorIntent.ANSWER,
                binding = slot('a'),
                expectedGeneration = current.generation,
            ),
        ).catalog

        assertEquals("direct_answer", result.binding(slot('a'))?.skillId)
        assertNull(result.active)
        assertEquals("answer", current.binding(slot('a'))?.skillId)
        assertEquals("answer", current.active?.skillId)
    }

    @Test
    fun `update creates next immutable revision without changing binding or activation`() {
        val active = AgentSkillCatalogReducer.apply(
            AgentBuiltInSkills.initialCatalog(),
            AgentSkillMutation.ToggleActive(slot('r')),
        ).catalog

        val result = AgentSkillCatalogReducer.apply(
            active,
            AgentSkillMutation.Update(
                id = "rewrite",
                description = "新的简短描述",
                content = "# 改写\n采用更简洁的语气。",
            ),
        )

        assertEquals(2L, result.newRevision?.revision)
        assertEquals("新的简短描述", result.catalog.definition("rewrite")?.description)
        assertEquals(active.active, result.catalog.active)
        assertEquals(active.bindings, result.catalog.bindings)
        assertEquals(1L, active.definition("rewrite")?.revision)
    }

    @Test
    fun `binding replacement and unbind keep active state internally consistent`() {
        val activated = AgentSkillCatalogReducer.apply(
            AgentBuiltInSkills.initialCatalog(),
            AgentSkillMutation.ToggleActive(slot('a')),
        ).catalog

        val replaced = AgentSkillCatalogReducer.apply(
            activated,
            AgentSkillMutation.Bind("rewrite", slot('a')),
        ).catalog

        assertEquals("rewrite", replaced.binding(slot('a'))?.skillId)
        assertNull(replaced.active)

        val activeAgain = AgentSkillCatalogReducer.apply(
            replaced,
            AgentSkillMutation.ToggleActive(slot('a')),
        ).catalog
        val unbound = AgentSkillCatalogReducer.apply(
            activeAgain,
            AgentSkillMutation.Unbind(slot('a')),
        ).catalog

        assertNull(unbound.binding(slot('a')))
        assertNull(unbound.active)
    }

    @Test
    fun `selecting the active skill from another bound slot toggles it off`() {
        val secondBinding = AgentSkillCatalogReducer.apply(
            AgentBuiltInSkills.initialCatalog(),
            AgentSkillMutation.Bind("answer", slot('q', AgentSkillDirection.RIGHT)),
        ).catalog
        val activated = AgentSkillCatalogReducer.apply(
            secondBinding,
            AgentSkillMutation.ToggleActive(slot('a')),
        ).catalog
        val toggled = AgentSkillCatalogReducer.apply(
            activated,
            AgentSkillMutation.ToggleActive(slot('q', AgentSkillDirection.RIGHT)),
        ).catalog

        assertNull(toggled.active)
    }

    @Test
    fun `unbinding a skill removes all of its slots and its activation`() {
        val extra = AgentSkillCatalogReducer.apply(
            AgentBuiltInSkills.initialCatalog(),
            AgentSkillMutation.Bind("answer", slot('q', AgentSkillDirection.LEFT)),
        ).catalog
        val active = AgentSkillCatalogReducer.apply(
            extra,
            AgentSkillMutation.ToggleActive(slot('a')),
        ).catalog
        val result = AgentSkillCatalogReducer.apply(
            active,
            AgentSkillMutation.UnbindSkill("answer"),
        ).catalog

        assertFalse(result.bindings.any { it.skillId == "answer" })
        assertNull(result.active)
    }

    @Test
    fun `optimistic generation rejects stale settings writes`() {
        val current = AgentBuiltInSkills.initialCatalog()

        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(
                current,
                AgentSkillMutation.ClearActive(expectedGeneration = 99L),
            )
        }
    }

    @Test
    fun `invalid documents references and activation are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", description = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", baseIntent = EditorIntent.NO_CHANGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", description = "two\nlines")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", description = "tab\tinside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", content = "bad\u0000content")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition(id = "valid", content = "bad\uD800surrogate")
        }
        val allowed = definition(
            id = "valid",
            content = "line one\r\n\tline two 🌌",
        )
        assertTrue(allowed.content.contains("🌌"))
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalog(
                generation = 1L,
                definitions = AgentBuiltInSkills.definitions,
                bindings = AgentBuiltInSkills.bindings +
                    AgentSkillBinding(slot('a'), "rewrite"),
                active = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalog(
                generation = 1L,
                definitions = AgentBuiltInSkills.definitions,
                bindings = AgentBuiltInSkills.bindings,
                active = AgentSkillActivation(slot('s'), "answer"),
            )
        }
    }

    @Test
    fun `empty updates unknown skills and unbound activation fail closed`() {
        val catalog = AgentBuiltInSkills.initialCatalog()

        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(catalog, AgentSkillMutation.Update("rewrite"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(
                catalog,
                AgentSkillMutation.Update("does_not_exist", content = "new"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(
                catalog,
                AgentSkillMutation.ToggleActive(slot('z', AgentSkillDirection.DOWN)),
            )
        }
    }

    @Test
    fun `slots reject Space Delete unknown and non-rendered key codes`() {
        listOf(0, 32, -5, '中'.code, Int.MAX_VALUE).forEach { keyCode ->
            assertThrows(IllegalArgumentException::class.java) {
                AgentSkillSlot(keyCode, AgentSkillDirection.UP)
            }
        }

        listOf('a'.code, '9'.code, 10, -1, -20).forEach { keyCode ->
            AgentSkillSlot(keyCode, AgentSkillDirection.LEFT)
        }
    }

    @Test
    fun `new bindings cannot occupy undo and redo gestures while legacy slots remain removable`() {
        val undo = slot('z', AgentSkillDirection.DOWN)
        val redo = slot('y', AgentSkillDirection.DOWN)
        assertFalse(AgentSkillPolicy.isAssignableSlot(undo))
        assertFalse(AgentSkillPolicy.isAssignableSlot(redo))

        val legacy = AgentSkillCatalog(
            generation = 2L,
            definitions = AgentBuiltInSkills.definitions,
            bindings = AgentBuiltInSkills.bindings + AgentSkillBinding(undo, "answer"),
            active = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(
                legacy,
                AgentSkillMutation.Bind("rewrite", redo),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogReducer.apply(
                legacy,
                AgentSkillMutation.Create(
                    id = "reserved_binding",
                    name = "保留手势",
                    description = "不应覆盖内置编辑手势",
                    content = "# 保留手势\n测试。",
                    binding = redo,
                ),
            )
        }

        val cleaned = AgentSkillCatalogReducer.apply(
            legacy,
            AgentSkillMutation.Unbind(undo),
        ).catalog
        assertNull(cleaned.binding(undo))
    }

    @Test
    fun `catalog binding envelope covers every rendered semantic slot and no more`() {
        val keyCodes =
            ('a'..'z').map(Char::code) +
                ('0'..'9').map(Char::code) +
                listOf(10, -1, -2, -3, -6, -7, -8, -9, -10, -11, -12, -13, -14, -15, -19, -20)
        val everySlot = keyCodes.flatMap { keyCode ->
            AgentSkillDirection.entries.map { direction ->
                AgentSkillBinding(AgentSkillSlot(keyCode, direction), "answer")
            }
        }

        assertEquals(AgentSkillPolicy.MAX_BINDINGS, everySlot.size)
        AgentSkillCatalog(
            generation = 1L,
            definitions = AgentBuiltInSkills.definitions,
            bindings = everySlot,
            active = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalog(
                generation = 1L,
                definitions = AgentBuiltInSkills.definitions,
                bindings = everySlot + everySlot.first(),
                active = null,
            )
        }
    }

    private fun slot(
        character: Char,
        direction: AgentSkillDirection = AgentSkillDirection.UP,
    ) = AgentSkillSlot(character.code, direction)

    private fun definition(
        id: String,
        description: String = "Description",
        baseIntent: EditorIntent = EditorIntent.SMART_EDIT,
        content: String = "Content",
    ) = AgentSkillDefinition(
        id = id,
        revision = 1L,
        name = "Name",
        description = description,
        content = content,
        baseIntent = baseIntent,
    )
}
