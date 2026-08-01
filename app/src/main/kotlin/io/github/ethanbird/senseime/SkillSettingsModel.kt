package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class SkillKeyOption(
    val label: String,
    val keyCode: Int?,
)

/**
 * Every stable selectable key code rendered by the keyboard, except Space and Delete.
 * Space is reserved for the Agent hold gesture; Delete owns continuous-repeat semantics.
 */
internal object SkillKeyOptions {
    private val letterKeys = ('a'..'z').map { SkillKeyOption(it.uppercase(), it.code) }
    private val numberKeys = ('0'..'9').map { SkillKeyOption(it.toString(), it.code) }
    private val actionKeys = listOf(
        SkillKeyOption("逗号 ，/,", -7),
        SkillKeyOption("句号 。/.", -8),
        SkillKeyOption("Shift", -1),
        SkillKeyOption("符号页", -2),
        SkillKeyOption("数字页", -3),
        SkillKeyOption("字母页", -11),
        SkillKeyOption("中/英", -6),
        SkillKeyOption("回车", 10),
        SkillKeyOption("系统输入法", -9),
        SkillKeyOption("工具箱", -19),
        SkillKeyOption("剪贴板", -10),
        SkillKeyOption("Emoji", -12),
        SkillKeyOption("文字编辑", -13),
        SkillKeyOption("语音输入", -14),
        SkillKeyOption("隐藏键盘", -15),
        SkillKeyOption("先思设置", -20),
    )

    val all: List<SkillKeyOption> =
        listOf(SkillKeyOption("不绑定键位", null)) + letterKeys + numberKeys + actionKeys

    fun indexOf(keyCode: Int?): Int =
        all.indexOfFirst { it.keyCode == keyCode }.takeIf { it >= 0 } ?: 0

    fun labelOf(keyCode: Int): String =
        all.firstOrNull { it.keyCode == keyCode }?.label ?: "Key $keyCode"
}

internal data class SkillCreationTemplate(
    val key: String,
    val label: String,
    val suggestedId: String,
    val name: String,
    val description: String,
    val content: String,
    val baseIntent: EditorIntent,
) {
    fun instantiate(
        occupiedIds: Set<String>,
        bindingSlot: AgentSkillSlot? = null,
    ): SkillSettingsDraft = SkillSettingsDraft(
        id = SkillCreationTemplates.uniqueId(suggestedId, occupiedIds),
        name = name,
        description = description,
        content = content,
        baseIntent = baseIntent,
        bindingSlot = bindingSlot,
    )
}

/** One-tap starting points that turn the editor from a blank form into a guided workflow. */
internal object SkillCreationTemplates {
    val BLANK = SkillCreationTemplate(
        key = "blank",
        label = "自由创建",
        suggestedId = "my_skill",
        name = "",
        description = "",
        content = """# 目标
说明这个 Skill 要帮助用户完成什么。

# 工作方式
1. 先理解输入与上下文
2. 按要求处理
3. 输出可直接使用的结果

# 约束
- 保留用户原意
- 信息不足时明确指出需要补充的内容

# 输出格式
说明最终结果的语言、结构和长度。""",
        baseIntent = EditorIntent.SMART_EDIT,
    )

    val all: List<SkillCreationTemplate> = listOf(
        BLANK,
        SkillCreationTemplate(
            key = "polish",
            label = "自然润色",
            suggestedId = "polish",
            name = "自然润色",
            description = "让表达更自然、清晰，同时保持原意和语气",
            content = """# 自然润色
重写用户选中的文字，使其更自然、清晰、连贯。

## 规则
- 保持事实、立场、专有名词与原始意图
- 删除重复和生硬表达，不凭空增加信息
- 默认直接输出润色后的正文，不附解释
- 若原文有明确格式，则保持格式""",
            baseIntent = EditorIntent.REWRITE,
        ),
        SkillCreationTemplate(
            key = "translate",
            label = "中英翻译",
            suggestedId = "translate_zh_en",
            name = "中英翻译",
            description = "自动判断中英文并翻译成另一种语言",
            content = """# 中英翻译
判断输入语言：中文翻译为自然英文，英文翻译为自然中文。

## 规则
- 保留人名、术语、数字、链接和段落结构
- 优先传达语义与语气，不做逐字硬译
- 默认只输出译文
- 有歧义时采用最符合上下文的解释""",
            baseIntent = EditorIntent.TRANSLATE,
        ),
        SkillCreationTemplate(
            key = "summary",
            label = "要点总结",
            suggestedId = "key_points",
            name = "要点总结",
            description = "把长文本整理成简洁、可执行的重点清单",
            content = """# 要点总结
提取输入中的核心结论、证据、决定和待办事项。

## 输出
- 一句话结论
- 3–7 条关键要点
- 若存在行动项，列出负责人、事项与时间

不重复原文，不补充输入中不存在的事实。""",
            baseIntent = EditorIntent.FORMAT,
        ),
        SkillCreationTemplate(
            key = "reply",
            label = "专业回复",
            suggestedId = "professional_reply",
            name = "专业回复",
            description = "根据上下文生成礼貌、明确、可直接发送的回复",
            content = """# 专业回复
根据当前上下文起草一段可以直接发送的回复。

## 规则
- 先回应对方最重要的问题
- 语气礼貌、坚定、简洁
- 需要行动时给出清晰下一步
- 不编造承诺、时间或事实
- 默认只输出回复正文""",
            baseIntent = EditorIntent.ANSWER,
        ),
    )

    fun uniqueId(base: String, occupiedIds: Set<String>): String {
        val normalized = base.lowercase().replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .ifEmpty { "skill" }
            .take(AgentSkillPolicy.MAX_ID_CHARS)
        if (normalized !in occupiedIds) return normalized
        var suffix = 2
        while (true) {
            val suffixText = "_$suffix"
            val candidate = normalized
                .take(AgentSkillPolicy.MAX_ID_CHARS - suffixText.length) + suffixText
            if (candidate !in occupiedIds) return candidate
            suffix++
        }
    }
}

/** Z/Y downward holds are product editing gestures and are not assignable Skill slots. */
internal object SkillBindingSlotPolicy {
    fun reservedCommand(slot: AgentSkillSlot?): String? = when {
        slot == null || slot.direction != AgentSkillDirection.DOWN -> null
        slot.keyCode == 'z'.code || slot.keyCode == 'Z'.code -> "撤销"
        slot.keyCode == 'y'.code || slot.keyCode == 'Y'.code -> "重做"
        else -> null
    }

    fun isSelectable(slot: AgentSkillSlot?): Boolean = AgentSkillPolicy.isAssignableSlot(slot)
}

internal data class SkillSettingsDraft(
    val id: String,
    val name: String,
    val description: String,
    val content: String,
    val baseIntent: EditorIntent,
    val bindingSlot: AgentSkillSlot?,
) {
    fun validationError(): String? {
        val normalizedId = id.trim()
        return runCatching {
            AgentSkillPolicy.requireValidId(normalizedId)
            require(name.isNotBlank() &&
                name.length <= AgentSkillPolicy.MAX_NAME_CHARS
            ) {
                "名称需要 1–${AgentSkillPolicy.MAX_NAME_CHARS} 个字符"
            }
            require(description.isNotBlank() &&
                description.length <= AgentSkillPolicy.MAX_DESCRIPTION_CHARS
            ) {
                "简短描述需要 1–${AgentSkillPolicy.MAX_DESCRIPTION_CHARS} 个字符"
            }
            require(content.isNotBlank() &&
                content.length <= AgentSkillPolicy.MAX_CONTENT_CHARS
            ) {
                "Skill 文档需要 1–${AgentSkillPolicy.MAX_CONTENT_CHARS} 个字符"
            }
            require(baseIntent != EditorIntent.NO_CHANGE) {
                "NO_CHANGE 不能作为 Skill 的基础意图"
            }
            require(SkillBindingSlotPolicy.isSelectable(bindingSlot)) {
                "Z/Y 下滑已保留给撤销与重做"
            }
        }.exceptionOrNull()?.message
    }

    fun createMutation(generation: Long): AgentSkillMutation.Create {
        val error = validationError()
        check(error == null) { error ?: "Invalid Skill draft" }
        return AgentSkillMutation.Create(
            id = id.trim(),
            name = name,
            description = description,
            content = content,
            baseIntent = baseIntent,
            binding = bindingSlot,
            expectedGeneration = generation,
        )
    }

    fun updateMutation(
        current: AgentSkillDefinition,
        generation: Long,
    ): AgentSkillMutation.Update {
        check(id.trim() == current.id) { "现有 Skill 的 id 不可更改" }
        val error = validationError()
        check(error == null) { error ?: "Invalid Skill draft" }
        return AgentSkillMutation.Update(
            id = current.id,
            name = name.takeIf { it != current.name },
            description = description.takeIf { it != current.description },
            content = content.takeIf { it != current.content },
            baseIntent = baseIntent.takeIf { it != current.baseIntent },
            expectedGeneration = generation,
        )
    }
}

/**
 * Prevents a freshly inflated, still-empty editor from overwriting a restored draft before the
 * catalog/session has hydrated its views.
 */
internal object SkillDraftCapturePolicy {
    fun shouldCapture(
        editorAttached: Boolean,
        editorHydrated: Boolean,
        applyingStateToUi: Boolean,
        hasCurrentDraft: Boolean,
    ): Boolean =
        editorAttached &&
            editorHydrated &&
            !applyingStateToUi &&
            hasCurrentDraft
}

/**
 * New input stays inside the durable Skill contract, while an already-retained oversized legacy
 * draft remains fully visible and editable instead of being truncated by Android setText().
 */
internal object SkillDraftInputCapacity {
    fun maximumAcceptedLength(
        policyLimit: Int,
        retainedLength: Int,
    ): Int {
        require(policyLimit >= 0)
        require(retainedLength >= 0)
        return maxOf(policyLimit, retainedLength)
    }

    fun acceptsWholeEdit(
        maximumAcceptedLength: Int,
        currentLength: Int,
        replacedLength: Int,
        incomingLength: Int,
    ): Boolean {
        require(maximumAcceptedLength >= 0)
        require(currentLength >= 0)
        require(replacedLength in 0..currentLength)
        require(incomingLength >= 0)
        return currentLength.toLong() -
            replacedLength.toLong() +
            incomingLength.toLong() <= maximumAcceptedLength.toLong()
    }
}

/**
 * The binding selector is operation context, not part of an existing Skill document revision.
 *
 * [explicitlySelected] distinguishes a user-chosen slot (which must survive catalog reorder,
 * bind/unbind, save and Activity recreation) from the catalog-provided initial slot (which may
 * follow the latest binding when the user has not expressed a preference).
 */
internal data class SkillBindingSelection(
    val slot: AgentSkillSlot?,
    val explicitlySelected: Boolean,
)

/**
 * One explicitly retained editor buffer.
 *
 * [base] is the exact document revision from which editing started. It lets the settings UI
 * distinguish an ordinary catalog-generation race (for example an activation changed) from a
 * concurrent document revision that must be acknowledged before saving.
 */
internal data class SkillEditorDraftRecord(
    val key: String,
    val sourceSkillId: String?,
    val sourceRevision: Long?,
    val base: SkillSettingsDraft,
    val draft: SkillSettingsDraft,
    val bindingSelection: SkillBindingSelection = SkillBindingSelection(
        slot = draft.bindingSlot,
        explicitlySelected = draft.bindingSlot != base.bindingSlot,
    ),
) {
    init {
        require(key.isNotEmpty()) { "Skill draft key cannot be empty" }
        if (sourceSkillId == null) {
            require(sourceRevision == null) { "A new Skill draft cannot have a source revision" }
            require(key == SkillDraftSessionState.NEW_DRAFT_KEY) {
                "A new Skill draft must use the reserved key"
            }
            require(bindingSelection.slot == draft.bindingSlot) {
                "A new Skill selector must match its atomic create binding"
            }
        } else {
            require(key == sourceSkillId) { "Existing Skill draft key must equal its source id" }
            require((sourceRevision ?: 0L) > 0L) {
                "Existing Skill draft source revision must be positive"
            }
            require(base.bindingSlot == null && draft.bindingSlot == null) {
                "Existing Skill document drafts cannot own binding selector state"
            }
        }
    }

    val creating: Boolean
        get() = sourceSkillId == null

    val dirty: Boolean
        get() = if (creating) draft != base else documentDirty

    val documentDirty: Boolean
        get() =
            draft.id != base.id ||
                draft.name != base.name ||
                draft.description != base.description ||
                draft.content != base.content ||
                draft.baseIntent != base.baseIntent

    fun conflictsWith(latest: AgentSkillDefinition?): Boolean =
        !creating &&
            documentDirty &&
            (latest == null || latest.revision != sourceRevision)
}

/**
 * Activity-independent draft state. Existing Skill drafts are keyed by stable id; the single
 * reserved new-draft key survives selector changes and navigation just like existing drafts.
 */
internal data class SkillDraftSessionState(
    val selectedKey: String? = null,
    val records: Map<String, SkillEditorDraftRecord> = emptyMap(),
) {
    init {
        require(records.size <= AgentSkillPolicy.MAX_SKILLS + 1) {
            "Too many retained Skill drafts"
        }
        require(records.all { (key, record) -> key == record.key }) {
            "Skill draft map key mismatch"
        }
        require(selectedKey == null || records.containsKey(selectedKey)) {
            "Selected Skill draft is missing"
        }
    }

    fun current(): SkillEditorDraftRecord? = selectedKey?.let(records::get)

    fun capture(
        draft: SkillSettingsDraft,
        bindingSelectionExplicit: Boolean = false,
    ): SkillDraftSessionState {
        val current = current() ?: return this
        val selectedSlot = draft.bindingSlot
        val nextSelection = SkillBindingSelection(
            slot = selectedSlot,
            explicitlySelected = current.bindingSelection.explicitlySelected ||
                bindingSelectionExplicit ||
                selectedSlot != current.bindingSelection.slot,
        )
        val documentDraft = if (current.creating) {
            draft
        } else {
            draft.copy(bindingSlot = null)
        }
        return copy(
            records = records + (
                current.key to current.copy(
                    draft = documentDraft,
                    bindingSelection = nextSelection,
                )
                ),
        )
    }

    fun beginCreate(emptyDraft: SkillSettingsDraft): SkillDraftSessionState {
        val existing = records[NEW_DRAFT_KEY]
        val record = existing ?: SkillEditorDraftRecord(
            key = NEW_DRAFT_KEY,
            sourceSkillId = null,
            sourceRevision = null,
            base = emptyDraft,
            draft = emptyDraft,
        )
        return copy(
            selectedKey = NEW_DRAFT_KEY,
            records = records + (NEW_DRAFT_KEY to record),
        )
    }

    fun selectExisting(
        definition: AgentSkillDefinition,
        binding: AgentSkillSlot?,
    ): SkillDraftSessionState {
        val fresh = definition.toDraftRecord(binding)
        val retained = records[definition.id]
        val retainedSelection = retained?.bindingSelection?.resolve(binding)
        val record = when {
            retained == null -> fresh
            retained.documentDirty -> retained.copy(
                bindingSelection = requireNotNull(retainedSelection),
            )
            else -> definition.toDraftRecord(requireNotNull(retainedSelection).slot).copy(
                bindingSelection = retainedSelection,
            )
        }
        return copy(
            selectedKey = definition.id,
            records = records + (definition.id to record),
        )
    }

    /**
     * Refreshes clean buffers from the latest catalog while leaving every unsaved byte intact.
     * A dirty buffer whose source revision changed remains selected and reports a conflict through
     * [SkillEditorDraftRecord.conflictsWith].
     */
    fun reconcile(catalog: AgentSkillCatalog): SkillDraftSessionState {
        var next = records
        records.forEach { (key, record) ->
            val skillId = record.sourceSkillId ?: return@forEach
            val latest = catalog.definition(skillId) ?: return@forEach
            val selection = record.bindingSelection.resolve(
                catalog.preferredBindingSlot(skillId),
            )
            if (!record.documentDirty) {
                next = next + (
                    key to latest.toDraftRecord(selection.slot).copy(
                        bindingSelection = selection,
                    )
                    )
            } else {
                next = next + (key to record.copy(bindingSelection = selection))
            }
        }
        return copy(records = next)
    }

    fun discardCurrent(catalog: AgentSkillCatalog): SkillDraftSessionState {
        val current = current() ?: return this
        if (current.creating) {
            val withoutNew = records - NEW_DRAFT_KEY
            val fallback = catalog.definitions.firstOrNull()
            return if (fallback == null) {
                SkillDraftSessionState()
            } else {
                SkillDraftSessionState(records = withoutNew).selectExisting(
                    fallback,
                    catalog.preferredBindingSlot(fallback.id),
                )
            }
        }
        val latest = catalog.definition(requireNotNull(current.sourceSkillId))
            ?: return copy(records = records - current.key, selectedKey = null)
        val selection = current.bindingSelection.resolve(
            catalog.preferredBindingSlot(latest.id),
        )
        return copy(
            records = records + (
                current.key to latest.toDraftRecord(selection.slot).copy(
                    bindingSelection = selection,
                )
                ),
        )
    }

    fun acceptSaved(
        catalog: AgentSkillCatalog,
        skillId: String,
    ): SkillDraftSessionState {
        val saved = requireNotNull(catalog.definition(skillId)) {
            "Saved Skill is missing from returned catalog"
        }
        val previous = records[skillId]
            ?: current()?.takeIf { record ->
                record.creating && record.draft.id.trim() == skillId
            }
        val selection = previous?.bindingSelection?.resolve(
            catalog.preferredBindingSlot(skillId),
        ) ?: SkillBindingSelection(
            slot = catalog.preferredBindingSlot(skillId),
            explicitlySelected = false,
        )
        val withoutObsolete = records - NEW_DRAFT_KEY - skillId
        val savedRecord = saved.toDraftRecord(selection.slot).copy(
            bindingSelection = selection,
        )
        return SkillDraftSessionState(
            selectedKey = skillId,
            records = withoutObsolete + (skillId to savedRecord),
        )
    }

    companion object {
        const val NEW_DRAFT_KEY = "\u0000sense-new-skill"
    }
}

internal enum class SkillSlotOccupancyKind {
    EMPTY,
    CURRENT_SKILL,
    OTHER_SKILL,
}

internal data class SkillSlotOccupancy(
    val kind: SkillSlotOccupancyKind,
    val slot: AgentSkillSlot?,
    val incumbentSkillId: String? = null,
    val incumbentSkillName: String? = null,
) {
    val requiresReplacement: Boolean
        get() = kind == SkillSlotOccupancyKind.OTHER_SKILL
}

internal fun AgentSkillCatalog.occupancy(
    slot: AgentSkillSlot?,
    targetSkillId: String?,
): SkillSlotOccupancy {
    if (slot == null) return SkillSlotOccupancy(SkillSlotOccupancyKind.EMPTY, null)
    val binding = binding(slot)
        ?: return SkillSlotOccupancy(SkillSlotOccupancyKind.EMPTY, slot)
    val incumbent = definition(binding.skillId)
    return SkillSlotOccupancy(
        kind = if (binding.skillId == targetSkillId) {
            SkillSlotOccupancyKind.CURRENT_SKILL
        } else {
            SkillSlotOccupancyKind.OTHER_SKILL
        },
        slot = slot,
        incumbentSkillId = binding.skillId,
        incumbentSkillName = incumbent?.name ?: binding.skillId,
    )
}

/**
 * Restoring history never overwrites an old document. It creates an ordinary Update whose
 * repository application appends a new immutable revision.
 */
internal fun AgentSkillDefinition.restoreAsNewRevision(
    current: AgentSkillDefinition,
    generation: Long,
): AgentSkillMutation.Update? {
    require(id == current.id) { "Cannot restore history into a different Skill" }
    val mutation = AgentSkillMutation.Update(
        id = current.id,
        name = name.takeIf { it != current.name },
        description = description.takeIf { it != current.description },
        content = content.takeIf { it != current.content },
        baseIntent = baseIntent.takeIf { it != current.baseIntent },
        expectedGeneration = generation,
    )
    return mutation.takeIf {
        it.name != null ||
            it.description != null ||
            it.content != null ||
            it.baseIntent != null
    }
}

private fun AgentSkillDefinition.toDraftRecord(binding: AgentSkillSlot?): SkillEditorDraftRecord {
    val draft = toSettingsDraft(binding = null)
    return SkillEditorDraftRecord(
        key = id,
        sourceSkillId = id,
        sourceRevision = revision,
        base = draft,
        draft = draft,
        bindingSelection = SkillBindingSelection(
            slot = binding,
            explicitlySelected = false,
        ),
    )
}

private fun AgentSkillDefinition.toSettingsDraft(binding: AgentSkillSlot?): SkillSettingsDraft =
    SkillSettingsDraft(
        id = id,
        name = name,
        description = description,
        content = content,
        baseIntent = baseIntent,
        bindingSlot = binding,
    )

private fun SkillBindingSelection.resolve(
    preferredSlot: AgentSkillSlot?,
): SkillBindingSelection =
    if (explicitlySelected) this else copy(slot = preferredSlot)

private fun AgentSkillCatalog.preferredBindingSlot(skillId: String): AgentSkillSlot? =
    bindings.firstOrNull { it.skillId == skillId }?.slot

/**
 * Versioned, compressed codec used by both saved-instance state and the app-private recovery file.
 * It deliberately stores only editor buffers, never the authoritative Skill catalog.
 */
internal object SkillDraftSessionCodec {
    private const val MAGIC = 0x53445331 // SDS1
    private const val LEGACY_UTF8_VERSION = 1
    private const val UTF16_DRAFT_VERSION = 2
    private const val VERSION = 3
    private const val MAX_RECORDS = AgentSkillPolicy.MAX_SKILLS + 1
    private const val MAX_ENCODED_BYTES = 24 * 1024 * 1024
    private const val MAX_TOTAL_STRING_BYTES = 32 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = MAX_TOTAL_STRING_BYTES + 1024 * 1024
    private const val MAX_SAVED_STATE_SOURCE_CODE_UNITS = 256 * 1024

    fun encode(state: SkillDraftSessionState): ByteArray {
        val output = ByteArrayOutputStream()
        val budget = StringBudget()
        GZIPOutputStream(output).use { gzip ->
            DataOutputStream(gzip).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeNullableUtf16String(state.selectedKey, budget)
                data.writeInt(state.records.size)
                state.records.values.forEach { record ->
                    data.writeUtf16String(record.key, budget)
                    data.writeNullableUtf16String(record.sourceSkillId, budget)
                    data.writeLong(record.sourceRevision ?: 0L)
                    data.writeUtf16Draft(record.base, budget)
                    data.writeUtf16Draft(record.draft, budget)
                    data.writeBindingSelection(record.bindingSelection, budget)
                }
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_ENCODED_BYTES) { "Skill draft recovery state is too large" }
        }
    }

    /**
     * Returns the exact current session for Android saved state only when both source work and the
     * compressed result are bounded. Large sessions use the lifecycle durability handoff instead
     * of risking a Binder-sized Bundle or doing multi-megabyte compression on the UI thread.
     */
    fun encodeForSavedState(
        state: SkillDraftSessionState,
        maximumEncodedBytes: Int,
    ): ByteArray? {
        require(maximumEncodedBytes > 0)
        if (state.totalStringCodeUnits() > MAX_SAVED_STATE_SOURCE_CODE_UNITS) return null
        return runCatching { encode(state) }
            .getOrNull()
            ?.takeIf { it.size <= maximumEncodedBytes }
    }

    fun decode(encoded: ByteArray): SkillDraftSessionState {
        require(encoded.isNotEmpty() && encoded.size <= MAX_ENCODED_BYTES) {
            "Invalid Skill draft recovery size"
        }
        val budget = StringBudget()
        val decompressed = BoundedInputStream(
            GZIPInputStream(ByteArrayInputStream(encoded)),
            MAX_DECOMPRESSED_BYTES.toLong(),
        )
        return DataInputStream(decompressed).use { data ->
            require(data.readInt() == MAGIC) { "Unknown Skill draft recovery format" }
            val version = data.readInt()
            val encoding = when (version) {
                LEGACY_UTF8_VERSION -> StringEncoding.LEGACY_UTF8
                UTF16_DRAFT_VERSION, VERSION -> StringEncoding.UTF16_CODE_UNITS
                else -> throw IllegalArgumentException(
                    "Unsupported Skill draft recovery version",
                )
            }
            val selectedKey = data.readNullableString(
                MAX_KEY_UTF8_BYTES,
                MAX_KEY_UTF16_CHARS,
                budget,
                encoding,
            )
            val count = data.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid Skill draft recovery count" }
            val records = LinkedHashMap<String, SkillEditorDraftRecord>(count)
            repeat(count) {
                val key = data.readString(
                    MAX_KEY_UTF8_BYTES,
                    MAX_KEY_UTF16_CHARS,
                    budget,
                    encoding,
                )
                val sourceId = data.readNullableString(
                    MAX_ID_UTF8_BYTES,
                    MAX_ID_UTF16_CHARS,
                    budget,
                    encoding,
                )
                val revision = data.readLong().takeIf { it != 0L }
                val base = data.readDraft(budget, encoding)
                val draft = data.readDraft(budget, encoding)
                val migratedSelection = SkillBindingSelection(
                    slot = draft.bindingSlot,
                    explicitlySelected = draft.bindingSlot != base.bindingSlot,
                )
                val bindingSelection = if (version >= VERSION) {
                    data.readBindingSelection(budget, encoding)
                } else {
                    migratedSelection
                }
                val creating = sourceId == null
                val record = SkillEditorDraftRecord(
                    key = key,
                    sourceSkillId = sourceId,
                    sourceRevision = revision,
                    base = if (creating) base else base.copy(bindingSlot = null),
                    draft = if (creating) draft else draft.copy(bindingSlot = null),
                    bindingSelection = bindingSelection,
                )
                require(records.put(key, record) == null) { "Duplicate Skill draft key" }
            }
            require(data.read() == -1) { "Trailing Skill draft recovery data" }
            SkillDraftSessionState(selectedKey, records)
        }
    }

    private fun SkillDraftSessionState.totalStringCodeUnits(): Long {
        var total = selectedKey?.length?.toLong() ?: 0L
        records.values.forEach { record ->
            total += record.key.length
            total += record.sourceSkillId?.length ?: 0
            total += record.base.totalStringCodeUnits()
            total += record.draft.totalStringCodeUnits()
            total += record.bindingSelection.slot?.direction?.name?.length ?: 0
        }
        return total
    }

    private fun SkillSettingsDraft.totalStringCodeUnits(): Long =
        id.length.toLong() +
            name.length +
            description.length +
            content.length +
            baseIntent.name.length +
            (bindingSlot?.direction?.name?.length ?: 0)

    private fun DataOutputStream.writeUtf16Draft(
        draft: SkillSettingsDraft,
        budget: StringBudget,
    ) {
        writeUtf16String(draft.id, budget)
        writeUtf16String(draft.name, budget)
        writeUtf16String(draft.description, budget)
        writeUtf16String(draft.content, budget)
        writeUtf16String(draft.baseIntent.name, budget)
        writeBoolean(draft.bindingSlot != null)
        draft.bindingSlot?.let { slot ->
            writeInt(slot.keyCode)
            writeUtf16String(slot.direction.name, budget)
        }
    }

    private fun DataOutputStream.writeBindingSelection(
        selection: SkillBindingSelection,
        budget: StringBudget,
    ) {
        writeBoolean(selection.explicitlySelected)
        writeBoolean(selection.slot != null)
        selection.slot?.let { slot ->
            writeInt(slot.keyCode)
            writeUtf16String(slot.direction.name, budget)
        }
    }

    private fun DataInputStream.readDraft(
        budget: StringBudget,
        encoding: StringEncoding,
    ): SkillSettingsDraft {
        val id = readString(MAX_ID_UTF8_BYTES, MAX_ID_UTF16_CHARS, budget, encoding)
        val name = readString(MAX_NAME_UTF8_BYTES, MAX_NAME_UTF16_CHARS, budget, encoding)
        val description = readString(
            MAX_DESCRIPTION_UTF8_BYTES,
            MAX_DESCRIPTION_UTF16_CHARS,
            budget,
            encoding,
        )
        val content = readString(
            MAX_CONTENT_UTF8_BYTES,
            MAX_CONTENT_UTF16_CHARS,
            budget,
            encoding,
        )
        val intentName = readString(
            MAX_ENUM_UTF8_BYTES,
            MAX_ENUM_UTF16_CHARS,
            budget,
            encoding,
        )
        val intent = EditorIntent.entries.firstOrNull { it.name == intentName }
            ?: throw IllegalArgumentException("Unknown Skill draft intent")
        val binding = if (readBoolean()) {
            val keyCode = readInt()
            val directionName = readString(
                MAX_ENUM_UTF8_BYTES,
                MAX_ENUM_UTF16_CHARS,
                budget,
                encoding,
            )
            AgentSkillSlot(
                keyCode = keyCode,
                direction = AgentSkillDirection.entries.firstOrNull {
                    it.name == directionName
                } ?: throw IllegalArgumentException("Unknown Skill draft direction"),
            )
        } else {
            null
        }
        return SkillSettingsDraft(id, name, description, content, intent, binding)
    }

    private fun DataInputStream.readBindingSelection(
        budget: StringBudget,
        encoding: StringEncoding,
    ): SkillBindingSelection {
        val explicitlySelected = readBoolean()
        val slot = if (readBoolean()) {
            val keyCode = readInt()
            val directionName = readString(
                MAX_ENUM_UTF8_BYTES,
                MAX_ENUM_UTF16_CHARS,
                budget,
                encoding,
            )
            AgentSkillSlot(
                keyCode = keyCode,
                direction = AgentSkillDirection.entries.firstOrNull {
                    it.name == directionName
                } ?: throw IllegalArgumentException("Unknown Skill selector direction"),
            )
        } else {
            null
        }
        return SkillBindingSelection(slot, explicitlySelected)
    }

    private fun DataOutputStream.writeNullableUtf16String(
        value: String?,
        budget: StringBudget,
    ) {
        writeBoolean(value != null)
        if (value != null) writeUtf16String(value, budget)
    }

    private fun DataInputStream.readNullableString(
        maxLegacyUtf8Bytes: Int,
        maxUtf16Chars: Int,
        budget: StringBudget,
        encoding: StringEncoding,
    ): String? = if (readBoolean()) {
        readString(maxLegacyUtf8Bytes, maxUtf16Chars, budget, encoding)
    } else {
        null
    }

    private fun DataOutputStream.writeUtf16String(value: String, budget: StringBudget) {
        val byteCount = value.length.toLong() * 2L
        budget.consume(byteCount)
        writeInt(value.length)
        val buffer = ByteArray(UTF16_CHUNK_BYTES)
        var sourceIndex = 0
        while (sourceIndex < value.length) {
            val characterCount = minOf(
                value.length - sourceIndex,
                buffer.size / 2,
            )
            var outputIndex = 0
            repeat(characterCount) {
                val codeUnit = value[sourceIndex++].code
                buffer[outputIndex++] = (codeUnit ushr 8).toByte()
                buffer[outputIndex++] = codeUnit.toByte()
            }
            write(buffer, 0, outputIndex)
        }
    }

    private fun DataInputStream.readString(
        maxLegacyUtf8Bytes: Int,
        maxUtf16Chars: Int,
        budget: StringBudget,
        encoding: StringEncoding,
    ): String = when (encoding) {
        StringEncoding.LEGACY_UTF8 -> readLegacyUtf8String(maxLegacyUtf8Bytes, budget)
        StringEncoding.UTF16_CODE_UNITS -> readUtf16String(maxUtf16Chars, budget)
    }

    private fun DataInputStream.readLegacyUtf8String(
        maxBytes: Int,
        budget: StringBudget,
    ): String {
        val size = readInt()
        require(size in 0..maxBytes) { "Invalid Skill draft string size" }
        budget.consume(size.toLong())
        return ByteArray(size).also(::readFully).decodeStrictUtf8()
    }

    private fun DataInputStream.readUtf16String(
        maxChars: Int,
        budget: StringBudget,
    ): String {
        val characterCount = readInt()
        require(characterCount in 0..maxChars) { "Invalid Skill draft string size" }
        budget.consume(characterCount.toLong() * 2L)
        val characters = CharArray(characterCount)
        val buffer = ByteArray(UTF16_CHUNK_BYTES)
        var destinationIndex = 0
        while (destinationIndex < characterCount) {
            val chunkCharacters = minOf(
                characterCount - destinationIndex,
                buffer.size / 2,
            )
            val chunkBytes = chunkCharacters * 2
            readFully(buffer, 0, chunkBytes)
            var inputIndex = 0
            repeat(chunkCharacters) {
                val high = buffer[inputIndex++].toInt() and 0xff
                val low = buffer[inputIndex++].toInt() and 0xff
                characters[destinationIndex++] = ((high shl 8) or low).toChar()
            }
        }
        return String(characters)
    }

    private fun ByteArray.decodeStrictUtf8(): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()

    private class StringBudget {
        private var used = 0L

        fun consume(byteCount: Long) {
            require(byteCount >= 0) { "Invalid Skill draft string size" }
            used += byteCount
            require(used <= MAX_TOTAL_STRING_BYTES.toLong()) {
                "Skill draft recovery content is too large"
            }
        }
    }

    private class BoundedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int {
            val result = super.read()
            if (result >= 0) countBytes(1)
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = super.read(buffer, offset, length)
            if (result > 0) countBytes(result)
            return result
        }

        private fun countBytes(count: Int) {
            bytesRead += count.toLong()
            require(bytesRead <= maxBytes) {
                "Skill draft recovery decompressed data is too large"
            }
        }
    }

    private enum class StringEncoding {
        LEGACY_UTF8,
        UTF16_CODE_UNITS,
    }

    private const val UTF16_CHUNK_BYTES = 8 * 1024
    private const val MAX_KEY_UTF8_BYTES = 256
    private const val MAX_KEY_UTF16_CHARS = 256
    // Drafts may temporarily exceed save policy while the user is editing. Recovery therefore
    // uses generous bounded limits instead of silently dropping an over-limit unsaved buffer.
    private const val MAX_ID_UTF8_BYTES = 16 * 1024
    private const val MAX_ID_UTF16_CHARS = 16 * 1024
    private const val MAX_NAME_UTF8_BYTES = 256 * 1024
    private const val MAX_NAME_UTF16_CHARS = 256 * 1024
    private const val MAX_DESCRIPTION_UTF8_BYTES = 512 * 1024
    private const val MAX_DESCRIPTION_UTF16_CHARS = 512 * 1024
    private const val MAX_CONTENT_UTF8_BYTES = 4 * 1024 * 1024
    private const val MAX_CONTENT_UTF16_CHARS = 4 * 1024 * 1024
    private const val MAX_ENUM_UTF8_BYTES = 64
    private const val MAX_ENUM_UTF16_CHARS = 64
}
