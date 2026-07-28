package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.ai.protocol.EditorSnapshotV1
import io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1
import io.github.ethanbird.senseime.ai.protocol.SnapshotCapability
import io.github.ethanbird.senseime.brain.api.AgentBuiltInSkills
import io.github.ethanbird.senseime.brain.api.AgentSkillActivation
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSkillRunAdmissionTest {
    @Test
    fun `exact frozen catalog and active revision are admitted`() {
        AgentSkillRunAdmission.requireConsistent(request(), catalog())
    }

    @Test
    fun `stale discovery generation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillRunAdmission.requireConsistent(
                request().copy(skillCatalogGeneration = 8L),
                catalog(),
            )
        }
    }

    @Test
    fun `same identity with altered content is rejected`() {
        val request = request()
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillRunAdmission.requireConsistent(
                request.copy(
                    activeSkill = requireNotNull(request.activeSkill).copy(
                        content = "# 回答\n被 Binder 间隙替换的内容",
                    ),
                ),
                catalog(),
            )
        }
    }

    @Test
    fun `base intent must match the frozen Skill definition`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillRunAdmission.requireConsistent(
                request().copy(skill = EditorIntent.REWRITE),
                catalog(),
            )
        }
    }

    private fun catalog(): AgentSkillCatalog {
        val initial = AgentBuiltInSkills.initialCatalog()
        return AgentSkillCatalog(
            generation = 9L,
            definitions = initial.definitions,
            bindings = initial.bindings,
            active = AgentSkillActivation(
                slot = requireNotNull(initial.bindings.firstOrNull { it.skillId == "answer" }).slot,
                skillId = "answer",
            ),
        )
    }

    private fun request(): HarnessRequestV1 {
        val answer = requireNotNull(catalog().definition("answer"))
        return HarnessRequestV1(
            requestId = "request-1",
            runGeneration = 1L,
            skill = answer.baseIntent,
            skillCatalogGeneration = 9L,
            activeSkill = ActiveSkillInstructionV1(
                id = answer.id,
                revision = answer.revision,
                catalogGeneration = 9L,
                name = answer.name,
                description = answer.description,
                content = answer.content,
            ),
            snapshot = EditorSnapshotV1(
                requestId = "request-1",
                snapshotId = "snapshot-1",
                editorGeneration = 1L,
                fieldIdentity = "field",
                capability = SnapshotCapability.UNAVAILABLE,
                text = "",
                selection = null,
                target = null,
                baseSha256 = "e3b0c44298fc1c149afbf4c8996fb924" +
                    "27ae41e4649b934ca495991b7852b855",
                capturedAtMonotonicMs = 1L,
                truncated = false,
            ),
        )
    }
}
