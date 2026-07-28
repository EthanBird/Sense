package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import java.io.File

/**
 * Separate-JVM worker used by [AgentSkillStoreTest] to exercise the operating-system file lock.
 */
object AgentSkillProcessWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 3)
        val root = File(arguments[0])
        val prefix = arguments[1]
        val count = arguments[2].toInt()
        val repository = AgentSkillRepository(root)
        repeat(count) { index ->
            repository.apply(
                AgentSkillMutation.Create(
                    id = "${prefix}_$index",
                    name = "$prefix $index",
                    description = "Cross-process Skill $prefix $index",
                    content = "# $prefix $index",
                ),
            ).getOrThrow()
        }
    }
}
