package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.EditorIntent

/**
 * Stable IDs shared by settings, the Brain request factory and the execution router.
 *
 * Wire values are intentionally lowercase and must remain stable once released because saved
 * settings use them.
 */
enum class AgentToolId(val wireValue: String) {
    WEB_SEARCH("web_search"),
    WEB_FETCH("web_fetch"),
    CALCULATOR("calculator"),
    MEMORY_SEARCH("memory_search"),
    SKILL_READ("skill_read"),
    SKILL_MANAGE("skill_manage"),
    ;

    companion object {
        fun fromWireValue(value: String): AgentToolId? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Typed, locally validated arguments. Raw model JSON never reaches an executor. */
sealed interface AgentToolArguments {
    data class WebSearch(
        val query: String,
        val maxResults: Int,
    ) : AgentToolArguments

    data class WebFetch(
        val url: String,
        val maxChars: Int,
    ) : AgentToolArguments

    data class Calculator(
        val expression: String,
    ) : AgentToolArguments

    data class MemorySearch(
        val query: String,
        val maxResults: Int,
    ) : AgentToolArguments

    data class SkillRead(
        val skillId: String,
        /** Exact immutable revision advertised by the run-frozen discovery catalog. */
        val revision: Long,
        /** UTF-16 document offset. The executor rejects offsets inside a surrogate pair. */
        val offset: Int = 0,
        /** Bounded page size; repeated reads can recover the complete 65,536-char document. */
        val maxChars: Int = DEFAULT_MAX_CHARS,
    ) : AgentToolArguments {
        init {
            require(revision > 0L)
            require(offset >= 0)
            require(maxChars in MIN_MAX_CHARS..MAX_MAX_CHARS)
        }

        companion object {
            const val MIN_MAX_CHARS = 256
            const val MAX_MAX_CHARS = 6_000
            const val DEFAULT_MAX_CHARS = MAX_MAX_CHARS
        }
    }

    sealed interface SkillManage : AgentToolArguments {
        /**
         * Optimistic concurrency token frozen with the discovery catalog shown to the Agent.
         *
         * It is mandatory for every mutation. A stale Agent turn must never overwrite a newer
         * user or Agent change merely because its operation is otherwise valid.
         */
        val expectedCatalogGeneration: Long

        data class Create(
            val skillId: String,
            val name: String,
            val description: String,
            val content: String,
            val baseIntent: EditorIntent,
            val binding: AgentSkillSlot?,
            override val expectedCatalogGeneration: Long,
        ) : SkillManage {
            init {
                require(expectedCatalogGeneration > 0L)
            }
        }

        data class Update(
            val skillId: String,
            val name: String?,
            val description: String?,
            val content: String?,
            val baseIntent: EditorIntent?,
            override val expectedCatalogGeneration: Long,
        ) : SkillManage {
            init {
                require(expectedCatalogGeneration > 0L)
            }
        }

        data class Bind(
            val skillId: String,
            val slot: AgentSkillSlot,
            override val expectedCatalogGeneration: Long,
        ) : SkillManage {
            init {
                require(expectedCatalogGeneration > 0L)
            }
        }

        data class Unbind(
            val slot: AgentSkillSlot,
            override val expectedCatalogGeneration: Long,
        ) : SkillManage {
            init {
                require(expectedCatalogGeneration > 0L)
            }
        }

        data class UnbindSkill(
            val skillId: String,
            override val expectedCatalogGeneration: Long,
        ) : SkillManage {
            init {
                require(expectedCatalogGeneration > 0L)
            }
        }
    }
}

data class AgentToolCall(
    val callId: String,
    val tool: AgentToolId,
    val arguments: AgentToolArguments,
    /** Run identity used to prevent memory_search from recalling its own in-flight trace. */
    val requestId: String? = null,
    val runGeneration: Long? = null,
) {
    init {
        require(callId.isNotBlank())
        require(callId.length <= MAX_CALL_ID_CHARS)
        require(callId.none { Character.isISOControl(it) })
        require((requestId == null) == (runGeneration == null))
        requestId?.let { require(it.isNotBlank()) }
        runGeneration?.let { require(it >= 0L) }
    }

    private companion object {
        const val MAX_CALL_ID_CHARS = 256
    }
}

/**
 * One bounded tool result replayed to the provider as a tool message.
 *
 * [content] should be compact JSON whenever practical. Brain applies the authoritative character
 * cap even when an executor is buggy, so implementations do not control Provider prompt growth.
 */
data class AgentToolExecutionResult(
    val content: String,
    val isError: Boolean = false,
    /**
     * Out-of-band compact projection after one successful `skill_manage` mutation.
     *
     * Brain never trusts JSON text or a scalar alone to advance side-effect authority. The exact
     * generation and id/revision summaries advance atomically. Other tools and failed mutations
     * leave this null.
     */
    val skillCatalogSnapshot: AgentSkillCatalogSnapshot? = null,
) {
    init {
        require(!isError || skillCatalogSnapshot == null)
    }
}

/**
 * Blocking execution boundary called outside the Brain state lock.
 *
 * Android implementations may perform bounded network or local-memory work here. Cancellation of
 * the Agent run invalidates the result synchronously; executors must still configure their own
 * finite I/O timeouts so abandoned work cannot live indefinitely.
 */
fun interface AgentToolExecutor {
    fun execute(call: AgentToolCall): AgentToolExecutionResult

    companion object {
        val UNAVAILABLE = AgentToolExecutor {
            AgentToolExecutionResult(
                content = "{\"ok\":false,\"error\":\"tool runtime unavailable\"}",
                isError = true,
            )
        }
    }
}
