package io.github.ethanbird.senseime.brain.api

import io.github.ethanbird.senseime.ai.protocol.EditorIntent

/**
 * Stable directions used by keyboard Skill bindings.
 *
 * These values deliberately live outside the Android UI module so the private Brain process,
 * settings Activity, and keyboard process can exchange the same durable model.
 */
enum class AgentSkillDirection(val wireValue: String) {
    UP("up"),
    RIGHT("right"),
    DOWN("down"),
    LEFT("left"),
    ;

    companion object {
        fun fromWireValue(value: String): AgentSkillDirection =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown Skill direction: $value")
    }
}

data class AgentSkillSlot(
    val keyCode: Int,
    val direction: AgentSkillDirection,
) {
    init {
        AgentSkillPolicy.requireBindableSlot(this)
    }
}

/**
 * One immutable revision of a user-owned Skill document.
 *
 * Updating a Skill creates a new [revision]; previous documents remain readable on disk.
 * [baseIntent] keeps the existing editor Patch validator authoritative while [content] supplies
 * the richer Agent instructions.
 */
data class AgentSkillDefinition(
    val id: String,
    val revision: Long,
    val name: String,
    val description: String,
    val content: String,
    val baseIntent: EditorIntent,
    val builtIn: Boolean = false,
) {
    init {
        AgentSkillPolicy.requireValidDefinition(this)
    }
}

data class AgentSkillBinding(
    val slot: AgentSkillSlot,
    val skillId: String,
) {
    init {
        AgentSkillPolicy.requireValidId(skillId)
    }
}

/**
 * The currently selected Skill. It records the source slot so the keyboard can render the
 * aurora on the exact key that activated it.
 */
data class AgentSkillActivation(
    val slot: AgentSkillSlot,
    val skillId: String,
) {
    init {
        AgentSkillPolicy.requireValidId(skillId)
    }
}

/**
 * A complete, generation-addressable catalog snapshot.
 *
 * Lists are copied and validated at construction so callers cannot mutate a snapshot behind the
 * repository's back.
 */
class AgentSkillCatalog(
    val generation: Long,
    definitions: List<AgentSkillDefinition>,
    bindings: List<AgentSkillBinding>,
    val active: AgentSkillActivation?,
) {
    val definitions: List<AgentSkillDefinition> = definitions.toList()
    val bindings: List<AgentSkillBinding> = bindings.toList()

    init {
        require(generation > 0L) { "Skill catalog generation must be positive" }
        AgentSkillPolicy.requireValidCatalog(definitions, bindings, active)
    }

    private val definitionsById: Map<String, AgentSkillDefinition> =
        definitions.associateBy(AgentSkillDefinition::id)
    private val bindingsBySlot: Map<AgentSkillSlot, AgentSkillBinding> =
        bindings.associateBy(AgentSkillBinding::slot)

    fun definition(skillId: String): AgentSkillDefinition? = definitionsById[skillId]

    fun binding(slot: AgentSkillSlot): AgentSkillBinding? = bindingsBySlot[slot]

    fun activeDefinition(): AgentSkillDefinition? = active?.let { definitionsById[it.skillId] }

    fun bindingsForKey(keyCode: Int): List<AgentSkillBinding> =
        bindings.filter { it.slot.keyCode == keyCode }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is AgentSkillCatalog &&
                    generation == other.generation &&
                    definitions == other.definitions &&
                    bindings == other.bindings &&
                    active == other.active
                )

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + definitions.hashCode()
        result = 31 * result + bindings.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AgentSkillCatalog(generation=$generation, definitions=$definitions, " +
            "bindings=$bindings, active=$active)"
}

sealed interface AgentSkillMutation {
    val expectedGeneration: Long?

    data class Create(
        val id: String,
        val name: String,
        val description: String,
        val content: String,
        val baseIntent: EditorIntent = EditorIntent.SMART_EDIT,
        val binding: AgentSkillSlot? = null,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    data class Update(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        val content: String? = null,
        val baseIntent: EditorIntent? = null,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    data class Bind(
        val skillId: String,
        val slot: AgentSkillSlot,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    data class Unbind(
        val slot: AgentSkillSlot,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    data class UnbindSkill(
        val skillId: String,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    /**
     * Selecting the already-active Skill clears the activation. Otherwise this activates the
     * Skill bound to [slot]. The caller cannot activate an unbound document accidentally.
     */
    data class ToggleActive(
        val slot: AgentSkillSlot,
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation

    data class ClearActive(
        override val expectedGeneration: Long? = null,
    ) : AgentSkillMutation
}

/**
 * Pure catalog reducer shared by Android storage and ordinary JVM tests.
 */
object AgentSkillCatalogReducer {
    data class Result(
        val catalog: AgentSkillCatalog,
        val newRevision: AgentSkillDefinition? = null,
    )

    fun apply(catalog: AgentSkillCatalog, mutation: AgentSkillMutation): Result {
        mutation.expectedGeneration?.let { expected ->
            require(expected == catalog.generation) {
                "Skill catalog changed: expected generation $expected, actual ${catalog.generation}"
            }
        }
        require(catalog.generation < Long.MAX_VALUE) { "Skill catalog generation exhausted" }
        val nextGeneration = catalog.generation + 1L

        return when (mutation) {
            is AgentSkillMutation.Create -> {
                AgentSkillPolicy.requireValidId(mutation.id)
                mutation.binding?.let(AgentSkillPolicy::requireAssignableSlot)
                require(catalog.definition(mutation.id) == null) {
                    "Skill already exists: ${mutation.id}"
                }
                require(catalog.definitions.size < AgentSkillPolicy.MAX_SKILLS) {
                    "Skill catalog limit reached"
                }
                val definition = AgentSkillDefinition(
                    id = mutation.id,
                    revision = 1L,
                    name = mutation.name,
                    description = mutation.description,
                    content = mutation.content,
                    baseIntent = mutation.baseIntent,
                    builtIn = false,
                )
                val bindings = if (mutation.binding == null) {
                    catalog.bindings
                } else {
                    replaceBinding(catalog.bindings, AgentSkillBinding(mutation.binding, definition.id))
                }
                Result(
                    catalog = AgentSkillCatalog(
                        generation = nextGeneration,
                        definitions = catalog.definitions + definition,
                        bindings = bindings,
                        active = catalog.active?.takeUnless { active ->
                            mutation.binding != null && active.slot == mutation.binding
                        },
                    ),
                    newRevision = definition,
                )
            }

            is AgentSkillMutation.Update -> {
                AgentSkillPolicy.requireValidId(mutation.id)
                val current = requireNotNull(catalog.definition(mutation.id)) {
                    "Unknown Skill: ${mutation.id}"
                }
                require(current.revision < Long.MAX_VALUE) { "Skill revision exhausted" }
                require(
                    mutation.name != null ||
                        mutation.description != null ||
                        mutation.content != null ||
                        mutation.baseIntent != null,
                ) { "Skill update does not contain a change" }
                val updated = AgentSkillDefinition(
                    id = current.id,
                    revision = current.revision + 1L,
                    name = mutation.name ?: current.name,
                    description = mutation.description ?: current.description,
                    content = mutation.content ?: current.content,
                    baseIntent = mutation.baseIntent ?: current.baseIntent,
                    builtIn = current.builtIn,
                )
                Result(
                    catalog = AgentSkillCatalog(
                        generation = nextGeneration,
                        definitions = catalog.definitions.map {
                            if (it.id == current.id) updated else it
                        },
                        bindings = catalog.bindings,
                        active = catalog.active,
                    ),
                    newRevision = updated,
                )
            }

            is AgentSkillMutation.Bind -> {
                AgentSkillPolicy.requireValidId(mutation.skillId)
                AgentSkillPolicy.requireAssignableSlot(mutation.slot)
                requireNotNull(catalog.definition(mutation.skillId)) {
                    "Unknown Skill: ${mutation.skillId}"
                }
                Result(
                    AgentSkillCatalog(
                        generation = nextGeneration,
                        definitions = catalog.definitions,
                        bindings = replaceBinding(
                            catalog.bindings,
                            AgentSkillBinding(mutation.slot, mutation.skillId),
                        ),
                        active = catalog.active?.takeUnless { it.slot == mutation.slot },
                    ),
                )
            }

            is AgentSkillMutation.Unbind -> Result(
                AgentSkillCatalog(
                    generation = nextGeneration,
                    definitions = catalog.definitions,
                    bindings = catalog.bindings.filterNot { it.slot == mutation.slot },
                    active = catalog.active?.takeUnless { it.slot == mutation.slot },
                ),
            )

            is AgentSkillMutation.UnbindSkill -> {
                AgentSkillPolicy.requireValidId(mutation.skillId)
                requireNotNull(catalog.definition(mutation.skillId)) {
                    "Unknown Skill: ${mutation.skillId}"
                }
                Result(
                    AgentSkillCatalog(
                        generation = nextGeneration,
                        definitions = catalog.definitions,
                        bindings = catalog.bindings.filterNot { it.skillId == mutation.skillId },
                        active = catalog.active?.takeUnless { it.skillId == mutation.skillId },
                    ),
                )
            }

            is AgentSkillMutation.ToggleActive -> {
                val binding = requireNotNull(catalog.binding(mutation.slot)) {
                    "No Skill is bound to ${mutation.slot}"
                }
                val nextActive = if (catalog.active?.skillId == binding.skillId) {
                    null
                } else {
                    AgentSkillActivation(mutation.slot, binding.skillId)
                }
                Result(
                    AgentSkillCatalog(
                        generation = nextGeneration,
                        definitions = catalog.definitions,
                        bindings = catalog.bindings,
                        active = nextActive,
                    ),
                )
            }

            is AgentSkillMutation.ClearActive -> Result(
                AgentSkillCatalog(
                    generation = nextGeneration,
                    definitions = catalog.definitions,
                    bindings = catalog.bindings,
                    active = null,
                ),
            )
        }
    }

    private fun replaceBinding(
        current: List<AgentSkillBinding>,
        replacement: AgentSkillBinding,
    ): List<AgentSkillBinding> =
        current.filterNot { it.slot == replacement.slot } + replacement
}

object AgentSkillPolicy {
    const val MAX_SKILLS = 64
    /** 26 letters + 10 digits + Enter + 15 semantic action keys, each with four directions. */
    const val MAX_BINDINGS = 208
    const val MAX_ID_CHARS = 64
    const val MAX_NAME_CHARS = 64
    const val MAX_DESCRIPTION_CHARS = 240
    const val MAX_CONTENT_CHARS = 65_536

    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val ACTION_KEY_CODES = setOf(
        -1,  // Shift
        -2,  // Symbols
        -3,  // Numbers
        -6,  // Language
        -7,  // Comma
        -8,  // Period
        -9,  // System input method
        -10, // Clipboard
        -11, // Letters
        -12, // Emoji
        -13, // Editor
        -14, // Voice
        -15, // Hide
        -19, // Toolbox
        -20, // Settings
    )

    fun requireValidId(id: String) {
        require(id.length <= MAX_ID_CHARS && ID_PATTERN.matches(id)) {
            "Skill id must match ${ID_PATTERN.pattern}"
        }
    }

    fun requireValidDefinition(definition: AgentSkillDefinition) {
        requireValidId(definition.id)
        require(definition.revision > 0L) { "Skill revision must be positive" }
        requireValidName(definition.name)
        requireValidDescription(definition.description)
        requireValidContent(definition.content)
        require(definition.baseIntent != EditorIntent.NO_CHANGE) {
            "NO_CHANGE cannot be used as a Skill base intent"
        }
    }

    fun requireValidName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) {
            "Skill name must contain 1..$MAX_NAME_CHARS characters"
        }
        requireValidText(name, "Skill name", singleLine = true)
    }

    fun requireValidDescription(description: String) {
        require(
            description.isNotBlank() &&
                description.length <= MAX_DESCRIPTION_CHARS,
        ) {
            "Skill description must contain 1..$MAX_DESCRIPTION_CHARS characters"
        }
        requireValidText(description, "Skill description", singleLine = true)
    }

    fun requireValidContent(content: String) {
        require(content.isNotBlank() && content.length <= MAX_CONTENT_CHARS) {
            "Skill content must contain 1..$MAX_CONTENT_CHARS characters"
        }
        requireValidText(content, "Skill content", singleLine = false)
    }

    /**
     * Only semantic key codes that the shipping keyboard can actually render are durable slots.
     * Space is reserved for hold-to-Agent and Delete for continuous repeat, so neither can be
     * captured by Skills.
     */
    fun requireBindableSlot(slot: AgentSkillSlot) {
        val keyCode = slot.keyCode
        require(
            keyCode in 'a'.code..'z'.code ||
                keyCode in '0'.code..'9'.code ||
                keyCode == 10 ||
                keyCode in ACTION_KEY_CODES,
        ) {
            "Skill slot key code is not bindable: $keyCode"
        }
    }

    /** Downward Z/Y holds are built-in editor history gestures, not assignable Skill slots. */
    fun isAssignableSlot(slot: AgentSkillSlot?): Boolean = when {
        slot == null || slot.direction != AgentSkillDirection.DOWN -> true
        slot.keyCode == 'z'.code || slot.keyCode == 'Z'.code -> false
        slot.keyCode == 'y'.code || slot.keyCode == 'Y'.code -> false
        else -> true
    }

    fun requireAssignableSlot(slot: AgentSkillSlot) {
        require(isAssignableSlot(slot)) {
            "Z/Y downward gestures are reserved for undo and redo"
        }
    }

    fun requireValidCatalog(
        definitions: List<AgentSkillDefinition>,
        bindings: List<AgentSkillBinding>,
        active: AgentSkillActivation?,
    ) {
        require(definitions.size <= MAX_SKILLS) { "Too many Skills" }
        require(bindings.size <= MAX_BINDINGS) { "Too many Skill bindings" }
        require(definitions.map { it.id }.toSet().size == definitions.size) {
            "Duplicate Skill id"
        }
        require(bindings.map { it.slot }.toSet().size == bindings.size) {
            "Duplicate Skill binding slot"
        }
        val ids = definitions.mapTo(HashSet(), AgentSkillDefinition::id)
        require(bindings.all { it.skillId in ids }) { "Skill binding references an unknown Skill" }
        if (active != null) {
            require(active.skillId in ids) { "Active selection references an unknown Skill" }
            require(bindings.any { it.slot == active.slot && it.skillId == active.skillId }) {
                "Active selection is not backed by its binding"
            }
        }
    }

    private fun requireValidText(value: String, label: String, singleLine: Boolean) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    require(
                        index + 1 < value.length &&
                            Character.isLowSurrogate(value[index + 1]),
                    ) { "$label contains an invalid Unicode surrogate" }
                    index += 2
                    continue
                }

                Character.isLowSurrogate(character) ->
                    throw IllegalArgumentException("$label contains an invalid Unicode surrogate")

                singleLine && (character == '\n' || character == '\r' ||
                    character == '\u2028' || character == '\u2029') ->
                    throw IllegalArgumentException("$label must be a single line")

                Character.isISOControl(character) &&
                    !(singleLine.not() && character in CONTENT_CONTROL_ALLOW_LIST) ->
                    throw IllegalArgumentException("$label contains an unsupported control character")
            }
            index += 1
        }
    }

    private val CONTENT_CONTROL_ALLOW_LIST = setOf('\n', '\r', '\t')
}

/**
 * First-run documents and conservative default bindings. Users and the Agent may revise every
 * document; revision 1 remains on disk as the original source.
 */
object AgentBuiltInSkills {
    val definitions: List<AgentSkillDefinition> = listOf(
        definition(
            id = "smart_edit",
            name = "智能编辑",
            description = "根据上下文判断最合适的编辑动作，保持语义、语气与格式一致。",
            content = """
                # 智能编辑
                分析当前输入框、选区和光标位置，选择最符合用户意图的编辑方式。
                保持事实、专有名词、语言风格和已有格式；没有必要修改时返回 no_change。
            """.trimIndent(),
            baseIntent = EditorIntent.SMART_EDIT,
        ),
        definition(
            id = "answer",
            name = "回答",
            description = "直接回答输入框中的问题，并给出适合当前输入场景的正文。",
            content = """
                # 回答
                将输入框内容视为用户问题，生成直接、准确、可粘贴发送的回答。
                优先解决问题，不复述题目；信息不足时明确保留不确定性。
            """.trimIndent(),
            baseIntent = EditorIntent.ANSWER,
        ),
        definition(
            id = "rewrite",
            name = "改写",
            description = "在不改变原意和事实的前提下改善表达、语气与可读性。",
            content = """
                # 改写
                保留原始含义、事实、数字、专有名词和语言种类，改善清晰度与自然度。
                除非上下文明确要求，不增加新主张，不删除关键限定条件。
            """.trimIndent(),
            baseIntent = EditorIntent.REWRITE,
        ),
        definition(
            id = "continue",
            name = "续写",
            description = "沿用已有上下文、语言和语气，从光标处自然继续。",
            content = """
                # 续写
                从光标位置继续现有文本，保持相同语言、人物视角、时态、语气与格式。
                不重复已经出现的句子，并让新增内容能自然接在原文之后。
            """.trimIndent(),
            baseIntent = EditorIntent.CONTINUE,
        ),
        definition(
            id = "translate",
            name = "翻译",
            description = "结合上下文翻译文本，保留含义、格式、数字和专有名词。",
            content = """
                # 翻译
                根据上下文推断目标语言；若目标语言不明确，翻译为当前输入环境最可能使用的语言。
                保留事实、数字、换行、列表和专有名词，不添加解释性前后缀。
            """.trimIndent(),
            baseIntent = EditorIntent.TRANSLATE,
        ),
        definition(
            id = "format",
            name = "整理格式",
            description = "整理段落、标点与列表结构，不擅自改变文本事实和含义。",
            content = """
                # 整理格式
                优化换行、空格、标点、段落和列表层级，使文本更易读。
                不改写事实和核心措辞，不添加输入中不存在的信息。
            """.trimIndent(),
            baseIntent = EditorIntent.FORMAT,
        ),
    )

    val bindings: List<AgentSkillBinding> = listOf(
        AgentSkillBinding(AgentSkillSlot('s'.code, AgentSkillDirection.UP), "smart_edit"),
        AgentSkillBinding(AgentSkillSlot('a'.code, AgentSkillDirection.UP), "answer"),
        AgentSkillBinding(AgentSkillSlot('r'.code, AgentSkillDirection.UP), "rewrite"),
        AgentSkillBinding(AgentSkillSlot('c'.code, AgentSkillDirection.UP), "continue"),
        AgentSkillBinding(AgentSkillSlot('t'.code, AgentSkillDirection.UP), "translate"),
        AgentSkillBinding(AgentSkillSlot('f'.code, AgentSkillDirection.UP), "format"),
    )

    fun initialCatalog(): AgentSkillCatalog = AgentSkillCatalog(
        generation = 1L,
        definitions = definitions,
        bindings = bindings,
        active = null,
    )

    private fun definition(
        id: String,
        name: String,
        description: String,
        content: String,
        baseIntent: EditorIntent,
    ) = AgentSkillDefinition(
        id = id,
        revision = 1L,
        name = name,
        description = description,
        content = content,
        baseIntent = baseIntent,
        builtIn = true,
    )
}
