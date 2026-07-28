package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillBinding
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogReducer
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSettingsModelTest {
    @Test
    fun createCanAtomicallyIncludeOrOmitBinding() {
        val bound = validDraft(
            binding = AgentSkillSlot('k'.code, AgentSkillDirection.LEFT),
        ).copy(content = "# 周报\n按结果整理。\n").createMutation(generation = 7)
        val unbound = validDraft(binding = null).createMutation(generation = 8)

        assertEquals(7L, bound.expectedGeneration)
        assertEquals('k'.code, bound.binding?.keyCode)
        assertEquals("# 周报\n按结果整理。\n", bound.content)
        assertNull(unbound.binding)
    }

    @Test
    fun updateOnlyEmitsChangedFieldsAndKeepsStableId() {
        val current = AgentSkillDefinition(
            id = "weekly_report",
            revision = 3,
            name = "周报",
            description = "整理一周工作",
            content = "# 周报\n按结果整理。",
            baseIntent = EditorIntent.FORMAT,
        )
        val mutation = validDraft().copy(
            name = current.name,
            description = current.description,
            content = "# 周报\n按结果和影响整理。",
            baseIntent = current.baseIntent,
        ).updateMutation(current, generation = 12)

        assertNull(mutation.name)
        assertNull(mutation.description)
        assertNotNull(mutation.content)
        assertNull(mutation.baseIntent)
        assertEquals(12L, mutation.expectedGeneration)
    }

    @Test
    fun invalidDraftsFailBeforeTouchingPersistentCatalog() {
        assertNotNull(validDraft().copy(id = "含 空格").validationError())
        assertNotNull(validDraft().copy(description = "").validationError())
        assertNotNull(validDraft().copy(content = "").validationError())
        assertNotNull(validDraft().copy(baseIntent = EditorIntent.NO_CHANGE).validationError())
    }

    @Test
    fun keyCatalogIsUniqueAndNeverOffersSpace() {
        val concrete = SkillKeyOptions.all.mapNotNull(SkillKeyOption::keyCode)

        assertEquals(concrete.size, concrete.toSet().size)
        assertFalse(32 in concrete)
        assertFalse(-5 in concrete)
        assertTrue(('a'..'z').all { it.code in concrete })
        assertTrue(('0'..'9').all { it.code in concrete })
    }

    @Test
    fun selectorChangesRetainIndependentUnsavedDrafts() {
        val first = definition("first", 1, "第一项")
        val second = definition("second", 2, "第二项")
        var state = SkillDraftSessionState()
            .selectExisting(first, null)
            .capture(validDraft().copy(id = first.id, name = "第一项 · 草稿"))
            .selectExisting(second, null)
            .capture(validDraft().copy(id = second.id, name = "第二项 · 草稿"))

        state = state.selectExisting(first, null)
        assertEquals("第一项 · 草稿", state.current()?.draft?.name)

        state = state.selectExisting(second, null)
        assertEquals("第二项 · 草稿", state.current()?.draft?.name)
        assertEquals(2, state.records.size)
    }

    @Test
    fun capturePolicyRejectsFreshUnhydratedOrProgrammaticallyUpdatingEditors() {
        assertFalse(
            SkillDraftCapturePolicy.shouldCapture(
                editorAttached = true,
                editorHydrated = false,
                applyingStateToUi = false,
                hasCurrentDraft = true,
            ),
        )
        assertFalse(
            SkillDraftCapturePolicy.shouldCapture(
                editorAttached = true,
                editorHydrated = true,
                applyingStateToUi = true,
                hasCurrentDraft = true,
            ),
        )
        assertFalse(
            SkillDraftCapturePolicy.shouldCapture(
                editorAttached = false,
                editorHydrated = true,
                applyingStateToUi = false,
                hasCurrentDraft = true,
            ),
        )
        assertFalse(
            SkillDraftCapturePolicy.shouldCapture(
                editorAttached = true,
                editorHydrated = true,
                applyingStateToUi = false,
                hasCurrentDraft = false,
            ),
        )
        assertTrue(
            SkillDraftCapturePolicy.shouldCapture(
                editorAttached = true,
                editorHydrated = true,
                applyingStateToUi = false,
                hasCurrentDraft = true,
            ),
        )
    }

    @Test
    fun inputCapacityCapsNewTextButNeverTruncatesRetainedLegacyDrafts() {
        assertEquals(
            AgentSkillPolicy.MAX_CONTENT_CHARS,
            SkillDraftInputCapacity.maximumAcceptedLength(
                AgentSkillPolicy.MAX_CONTENT_CHARS,
                retainedLength = 0,
            ),
        )
        assertEquals(
            70_000,
            SkillDraftInputCapacity.maximumAcceptedLength(
                AgentSkillPolicy.MAX_CONTENT_CHARS,
                retainedLength = 70_000,
            ),
        )
        assertFalse(
            SkillDraftInputCapacity.acceptsWholeEdit(
                maximumAcceptedLength = AgentSkillPolicy.MAX_CONTENT_CHARS,
                currentLength = 65_530,
                replacedLength = 0,
                incomingLength = 10,
            ),
        )
        assertTrue(
            SkillDraftInputCapacity.acceptsWholeEdit(
                maximumAcceptedLength = 70_000,
                currentLength = 70_000,
                replacedLength = 20,
                incomingLength = 20,
            ),
        )
    }

    @Test
    fun reconcileRefreshesCleanDraftButPreservesDirtyDraftAndReportsRevisionConflict() {
        val original = definition("weekly_report", 1, "周报")
        val remote = original.copy(revision = 2, name = "周报 · 远端")
        val clean = SkillDraftSessionState().selectExisting(original, null)
            .reconcile(catalog(remote))
        assertEquals("周报 · 远端", clean.current()?.draft?.name)
        assertFalse(requireNotNull(clean.current()).conflictsWith(remote))

        val dirty = SkillDraftSessionState()
            .selectExisting(original, null)
            .capture(validDraft().copy(name = "周报 · 本地草稿"))
            .reconcile(catalog(remote))
        assertEquals("周报 · 本地草稿", dirty.current()?.draft?.name)
        assertTrue(requireNotNull(dirty.current()).conflictsWith(remote))
        assertEquals(1L, dirty.current()?.sourceRevision)
    }

    @Test
    fun bindingOnlyDraftRebasesOntoConcurrentDocumentRevisionWithoutRevertingIt() {
        val original = definition("weekly_report", 1, "周报")
        val remote = original.copy(revision = 2, name = "周报 · Agent 新版")
        val chosenSlot = AgentSkillSlot('b'.code, AgentSkillDirection.DOWN)
        val initial = SkillDraftSessionState().selectExisting(original, null)
        val state = initial
            .capture(
                requireNotNull(initial.current()).draft.copy(bindingSlot = chosenSlot),
                bindingSelectionExplicit = true,
            )
            .reconcile(catalog(remote))
        val record = requireNotNull(state.current())

        assertEquals(2L, record.sourceRevision)
        assertEquals("周报 · Agent 新版", record.draft.name)
        assertNull(record.draft.bindingSlot)
        assertEquals(chosenSlot, record.bindingSelection.slot)
        assertTrue(record.bindingSelection.explicitlySelected)
        assertFalse(record.dirty)
        assertFalse(record.documentDirty)
        assertFalse(record.conflictsWith(remote))
    }

    @Test
    fun explicitSecondSlotSurvivesBindReplacementAndUnbindCatalogTransitions() {
        val selected = definition("selected", 1, "目标")
        val incumbent = definition("incumbent", 1, "原占用者")
        val firstSlot = AgentSkillSlot('a'.code, AgentSkillDirection.UP)
        val secondSlot = AgentSkillSlot('b'.code, AgentSkillDirection.RIGHT)
        val initialCatalog = catalogWithBindings(
            generation = 10,
            definitions = listOf(selected, incumbent),
            bindings = listOf(
                AgentSkillBinding(firstSlot, selected.id),
                AgentSkillBinding(secondSlot, incumbent.id),
            ),
        )
        var state = SkillDraftSessionState()
            .selectExisting(selected, firstSlot)
        state = state.capture(
            requireNotNull(state.current()).draft.copy(bindingSlot = secondSlot),
            bindingSelectionExplicit = true,
        )

        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertFalse(requireNotNull(state.current()).dirty)

        val replacedCatalog = AgentSkillCatalogReducer.apply(
            initialCatalog,
            AgentSkillMutation.Bind(
                skillId = selected.id,
                slot = secondSlot,
                expectedGeneration = initialCatalog.generation,
            ),
        ).catalog
        state = state.reconcile(replacedCatalog)

        assertEquals(
            setOf(firstSlot, secondSlot),
            replacedCatalog.bindings
                .filter { it.skillId == selected.id }
                .mapTo(linkedSetOf(), AgentSkillBinding::slot),
        )
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertFalse(requireNotNull(state.current()).dirty)

        val slotUnboundCatalog = AgentSkillCatalogReducer.apply(
            replacedCatalog,
            AgentSkillMutation.Unbind(
                secondSlot,
                expectedGeneration = replacedCatalog.generation,
            ),
        ).catalog
        state = state.reconcile(slotUnboundCatalog)

        assertEquals(listOf(firstSlot), slotUnboundCatalog.bindings.map(AgentSkillBinding::slot))
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertFalse(requireNotNull(state.current()).dirty)

        val allUnboundCatalog = AgentSkillCatalogReducer.apply(
            slotUnboundCatalog,
            AgentSkillMutation.UnbindSkill(
                selected.id,
                expectedGeneration = slotUnboundCatalog.generation,
            ),
        ).catalog
        state = state.reconcile(allUnboundCatalog)

        assertTrue(allUnboundCatalog.bindings.none { it.skillId == selected.id })
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertFalse(requireNotNull(state.current()).dirty)
    }

    @Test
    fun editingDocumentPreservesAllBindingsAndSecondSlotThroughSaveAndDiscard() {
        val original = definition("weekly_report", 1, "周报")
        val firstSlot = AgentSkillSlot('w'.code, AgentSkillDirection.UP)
        val secondSlot = AgentSkillSlot('r'.code, AgentSkillDirection.LEFT)
        val initialCatalog = catalogWithBindings(
            generation = 20,
            definitions = listOf(original),
            bindings = listOf(
                AgentSkillBinding(firstSlot, original.id),
                AgentSkillBinding(secondSlot, original.id),
            ),
        )
        var state = SkillDraftSessionState()
            .selectExisting(original, firstSlot)
        state = state.capture(
            requireNotNull(state.current()).draft.copy(bindingSlot = secondSlot),
            bindingSelectionExplicit = true,
        )
        state = state.capture(
            requireNotNull(state.current()).draft.copy(
                name = "周报 · 本地编辑",
                bindingSlot = secondSlot,
            ),
        )
        val edited = requireNotNull(state.current())

        assertTrue(edited.documentDirty)
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertNull(edited.draft.bindingSlot)

        val mutation = edited.draft.updateMutation(
            current = original,
            generation = initialCatalog.generation,
        )
        val savedCatalog = AgentSkillCatalogReducer.apply(initialCatalog, mutation).catalog

        assertEquals(initialCatalog.bindings, savedCatalog.bindings)
        state = state.acceptSaved(savedCatalog, original.id)
        assertEquals("周报 · 本地编辑", state.current()?.draft?.name)
        assertFalse(requireNotNull(state.current()).dirty)
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)

        state = state.capture(
            requireNotNull(state.current()).draft.copy(
                content = "# 尚未保存\n绝不丢失",
                bindingSlot = secondSlot,
            ),
        )
        state = state.discardCurrent(savedCatalog)

        assertEquals(savedCatalog.definition(original.id)?.content, state.current()?.draft?.content)
        assertFalse(requireNotNull(state.current()).dirty)
        assertSelectedSlot(state, secondSlot, explicitlySelected = true)
        assertEquals(initialCatalog.bindings, savedCatalog.bindings)
    }

    @Test
    fun secondSlotAndDirtyDocumentSurviveConcurrentReconcileSkillSwitchAndHydration() {
        val first = definition("first", 1, "第一项")
        val second = definition("second", 1, "第二项")
        val firstDefault = AgentSkillSlot('a'.code, AgentSkillDirection.UP)
        val firstSelected = AgentSkillSlot('b'.code, AgentSkillDirection.DOWN)
        val secondDefault = AgentSkillSlot('c'.code, AgentSkillDirection.LEFT)
        val remoteFirst = first.copy(revision = 2, name = "第一项 · 远端")
        val remoteCatalog = catalogWithBindings(
            generation = 30,
            definitions = listOf(remoteFirst, second),
            bindings = listOf(
                AgentSkillBinding(firstDefault, first.id),
                AgentSkillBinding(firstSelected, first.id),
                AgentSkillBinding(secondDefault, second.id),
            ),
        )
        var state = SkillDraftSessionState()
            .selectExisting(first, firstDefault)
        state = state.capture(
            requireNotNull(state.current()).draft.copy(bindingSlot = firstSelected),
            bindingSelectionExplicit = true,
        )
        state = state.capture(
            requireNotNull(state.current()).draft.copy(
                content = "# 第一项\n本地未保存正文",
                bindingSlot = firstSelected,
            ),
        )
        state = state.selectExisting(second, secondDefault)
        state = state.selectExisting(first, firstDefault).reconcile(remoteCatalog)

        assertEquals("# 第一项\n本地未保存正文", state.current()?.draft?.content)
        assertTrue(requireNotNull(state.current()).conflictsWith(remoteFirst))
        assertSelectedSlot(state, firstSelected, explicitlySelected = true)

        val hydrated = SkillDraftSessionCodec.decode(SkillDraftSessionCodec.encode(state))
        assertEquals(state, hydrated)
        assertEquals("# 第一项\n本地未保存正文", hydrated.current()?.draft?.content)
        assertNull(hydrated.current()?.draft?.bindingSlot)
        assertSelectedSlot(hydrated, firstSelected, explicitlySelected = true)
    }

    @Test
    fun automaticSelectorFollowsCatalogButLegacyV2ExplicitSecondSlotMigrates() {
        val skill = definition("weekly_report", 1, "周报")
        val firstSlot = AgentSkillSlot('a'.code, AgentSkillDirection.UP)
        val secondSlot = AgentSkillSlot('b'.code, AgentSkillDirection.RIGHT)
        var state = SkillDraftSessionState().selectExisting(skill, firstSlot)

        state = state.reconcile(
            catalogWithBindings(
                generation = 40,
                definitions = listOf(skill),
                bindings = listOf(AgentSkillBinding(secondSlot, skill.id)),
            ),
        )

        assertSelectedSlot(state, secondSlot, explicitlySelected = false)
        assertFalse(requireNotNull(state.current()).dirty)

        val legacyBase = validDraft(firstSlot)
        val legacyDraft = legacyBase.copy(bindingSlot = secondSlot)
        val legacyV2 = gzipPayload { data ->
            data.writeInt(0x53445331)
            data.writeInt(2)
            data.writeBoolean(true)
            data.writeTestUtf16String(skill.id)
            data.writeInt(1)
            data.writeTestUtf16String(skill.id)
            data.writeBoolean(true)
            data.writeTestUtf16String(skill.id)
            data.writeLong(skill.revision)
            data.writeTestUtf16Draft(legacyBase)
            data.writeTestUtf16Draft(legacyDraft)
        }
        val migrated = SkillDraftSessionCodec.decode(legacyV2)

        assertNull(migrated.current()?.base?.bindingSlot)
        assertNull(migrated.current()?.draft?.bindingSlot)
        assertFalse(requireNotNull(migrated.current()).dirty)
        assertSelectedSlot(migrated, secondSlot, explicitlySelected = true)
    }

    @Test
    fun draftCodecRoundTripsNewAndExistingBuffersIncludingOverPolicyUnsavedContent() {
        val longUnsavedContent = "草".repeat(70_000)
        val existing = definition("weekly_report", 4, "周报")
        val existingSlot = AgentSkillSlot('w'.code, AgentSkillDirection.UP)
        var state = SkillDraftSessionState()
            .selectExisting(existing, existingSlot)
            .capture(validDraft(existingSlot).copy(content = longUnsavedContent))
            .beginCreate(validDraft().copy(id = "", name = "", description = "", content = ""))
            .capture(
                validDraft(
                    AgentSkillSlot('n'.code, AgentSkillDirection.RIGHT),
                ).copy(id = "new_skill", name = "新草稿"),
            )

        state = SkillDraftSessionCodec.decode(SkillDraftSessionCodec.encode(state))

        assertEquals(SkillDraftSessionState.NEW_DRAFT_KEY, state.selectedKey)
        assertEquals("新草稿", state.current()?.draft?.name)
        assertEquals(longUnsavedContent, state.records["weekly_report"]?.draft?.content)
        assertEquals(existingSlot, state.records["weekly_report"]?.bindingSelection?.slot)
        assertEquals(
            AgentSkillDirection.RIGHT,
            state.current()?.draft?.bindingSlot?.direction,
        )
    }

    @Test
    fun draftCodecRejectsTruncationInsteadOfReturningPartialUserData() {
        val state = SkillDraftSessionState()
            .beginCreate(validDraft().copy(id = "new_skill"))
        val encoded = SkillDraftSessionCodec.encode(state)

        assertTrue(
            runCatching {
                SkillDraftSessionCodec.decode(encoded.copyOf(encoded.size / 2))
            }.isFailure,
        )
    }

    @Test
    fun draftCodecPreservesOneRecordAtCompleteRecoveryContentLimit() {
        val content = "x".repeat(4 * 1024 * 1024)
        val draft = validDraft().copy(content = content)
        val record = SkillEditorDraftRecord(
            key = SkillDraftSessionState.NEW_DRAFT_KEY,
            sourceSkillId = null,
            sourceRevision = null,
            base = draft,
            draft = draft,
        )
        val state = SkillDraftSessionState(
            selectedKey = record.key,
            records = mapOf(record.key to record),
        )

        val restored = SkillDraftSessionCodec.decode(SkillDraftSessionCodec.encode(state))

        assertEquals(content, restored.current()?.base?.content)
        assertEquals(content, restored.current()?.draft?.content)
    }

    @Test
    fun draftCodecRejectsMalformedUtf8InsteadOfReplacingUserBytes() {
        val encoded = gzipPayload { data ->
            data.writeInt(0x53445331)
            data.writeInt(1)
            data.writeBoolean(false)
            data.writeInt(1)
            data.writeInt(1)
            data.writeByte(0x80)
        }

        assertTrue(runCatching { SkillDraftSessionCodec.decode(encoded) }.isFailure)
    }

    @Test
    fun draftCodecPreservesUnpairedUtf16CodeUnitsWithoutReplacement() {
        val state = SkillDraftSessionState().beginCreate(
            validDraft().copy(content = "before\uD800after"),
        )

        val restored = SkillDraftSessionCodec.decode(SkillDraftSessionCodec.encode(state))

        assertEquals("before\uD800after", restored.current()?.draft?.content)
    }

    @Test
    fun draftCodecReadsLegacyUtf8RecoverySnapshots() {
        val draft = validDraft(
            AgentSkillSlot('w'.code, AgentSkillDirection.LEFT),
        )
        val state = SkillDraftSessionState().beginCreate(draft)
        val encoded = gzipPayload { data ->
            data.writeInt(0x53445331)
            data.writeInt(1)
            data.writeBoolean(true)
            data.writeTestString(SkillDraftSessionState.NEW_DRAFT_KEY)
            data.writeInt(1)
            data.writeTestString(SkillDraftSessionState.NEW_DRAFT_KEY)
            data.writeBoolean(false)
            data.writeLong(0L)
            data.writeTestDraft(draft)
            data.writeTestDraft(draft)
        }

        assertEquals(state, SkillDraftSessionCodec.decode(encoded))
    }

    @Test
    fun savedStateEncodingUsesTheExactCurrentSmallSessionAndDefersLargeSessions() {
        val small = SkillDraftSessionState().beginCreate(
            validDraft().copy(content = "刚刚输入、尚未 fsync 的草稿"),
        )
        val bundled = requireNotNull(
            SkillDraftSessionCodec.encodeForSavedState(small, 192 * 1024),
        )

        assertEquals(small, SkillDraftSessionCodec.decode(bundled))

        val large = small.capture(
            requireNotNull(small.current()).draft.copy(
                content = "x".repeat(300 * 1024),
            ),
        )
        assertNull(SkillDraftSessionCodec.encodeForSavedState(large, 192 * 1024))
    }

    @Test
    fun draftCodecRejectsHighlyCompressibleAggregateBeyondTotalBudget() {
        val encoded = gzipPayload { data ->
            data.writeInt(0x53445331)
            data.writeInt(1)
            data.writeBoolean(false)
            data.writeInt(5)
            repeat(5) { index ->
                val id = "skill_$index"
                data.writeTestString(id)
                data.writeBoolean(true)
                data.writeTestString(id)
                data.writeLong(1L)
                repeat(2) {
                    data.writeTestString(id)
                    data.writeTestString("")
                    data.writeTestString("")
                    data.writeInt(4 * 1024 * 1024)
                    data.writeRepeatedByte(0, 4 * 1024 * 1024)
                    data.writeTestString(EditorIntent.SMART_EDIT.name)
                    data.writeBoolean(false)
                }
            }
        }

        assertTrue(encoded.size < 128 * 1024)
        assertTrue(runCatching { SkillDraftSessionCodec.decode(encoded) }.isFailure)
    }

    @Test
    fun slotOccupancyMakesReplacementExplicit() {
        val slot = AgentSkillSlot('k'.code, AgentSkillDirection.LEFT)
        val catalog = AgentSkillCatalog(
            generation = 8,
            definitions = listOf(
                definition("first", 1, "第一项"),
                definition("second", 1, "第二项"),
            ),
            bindings = listOf(AgentSkillBinding(slot, "first")),
            active = null,
        )

        assertEquals(
            SkillSlotOccupancyKind.CURRENT_SKILL,
            catalog.occupancy(slot, "first").kind,
        )
        val replacement = catalog.occupancy(slot, "second")
        assertTrue(replacement.requiresReplacement)
        assertEquals("第一项", replacement.incumbentSkillName)
        assertEquals(
            SkillSlotOccupancyKind.EMPTY,
            catalog.occupancy(
                AgentSkillSlot('x'.code, AgentSkillDirection.DOWN),
                "second",
            ).kind,
        )
    }

    @Test
    fun historyRestoreBuildsAppendOnlyUpdateAgainstCurrentGeneration() {
        val historical = definition("weekly_report", 2, "旧周报").copy(
            content = "# 旧版",
            baseIntent = EditorIntent.REWRITE,
        )
        val current = definition("weekly_report", 5, "新周报").copy(
            content = "# 新版",
            baseIntent = EditorIntent.FORMAT,
        )

        val restore = requireNotNull(historical.restoreAsNewRevision(current, generation = 19))

        assertEquals("weekly_report", restore.id)
        assertEquals("旧周报", restore.name)
        assertEquals("# 旧版", restore.content)
        assertEquals(EditorIntent.REWRITE, restore.baseIntent)
        assertEquals(19L, restore.expectedGeneration)
        assertNull(current.restoreAsNewRevision(current, generation = 20))
    }

    @Test
    fun discardingNewDraftExplicitlyReturnsToCatalogWithoutDroppingExistingDrafts() {
        val existing = definition("weekly_report", 1, "周报")
        val catalog = catalog(existing)
        val state = SkillDraftSessionState()
            .selectExisting(existing, null)
            .beginCreate(validDraft().copy(id = "", name = "未保存"))
            .capture(validDraft().copy(id = "new_skill", name = "未保存"))
            .discardCurrent(catalog)

        assertEquals("weekly_report", state.selectedKey)
        assertNull(state.records[SkillDraftSessionState.NEW_DRAFT_KEY])
        assertEquals("周报", state.current()?.draft?.name)
    }

    private fun validDraft(binding: AgentSkillSlot? = null) = SkillSettingsDraft(
        id = "weekly_report",
        name = "周报",
        description = "整理一周工作",
        content = "# 周报\n按结果整理。",
        baseIntent = EditorIntent.FORMAT,
        bindingSlot = binding,
    )

    private fun gzipPayload(write: (DataOutputStream) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            DataOutputStream(gzip).use(write)
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeTestString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeTestDraft(draft: SkillSettingsDraft) {
        writeTestString(draft.id)
        writeTestString(draft.name)
        writeTestString(draft.description)
        writeTestString(draft.content)
        writeTestString(draft.baseIntent.name)
        writeBoolean(draft.bindingSlot != null)
        draft.bindingSlot?.let { slot ->
            writeInt(slot.keyCode)
            writeTestString(slot.direction.name)
        }
    }

    private fun DataOutputStream.writeTestUtf16String(value: String) {
        writeInt(value.length)
        value.forEach { codeUnit ->
            writeByte(codeUnit.code ushr 8)
            writeByte(codeUnit.code)
        }
    }

    private fun DataOutputStream.writeTestUtf16Draft(draft: SkillSettingsDraft) {
        writeTestUtf16String(draft.id)
        writeTestUtf16String(draft.name)
        writeTestUtf16String(draft.description)
        writeTestUtf16String(draft.content)
        writeTestUtf16String(draft.baseIntent.name)
        writeBoolean(draft.bindingSlot != null)
        draft.bindingSlot?.let { slot ->
            writeInt(slot.keyCode)
            writeTestUtf16String(slot.direction.name)
        }
    }

    private fun DataOutputStream.writeRepeatedByte(value: Int, count: Int) {
        val buffer = ByteArray(8192) { value.toByte() }
        var remaining = count
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size)
            write(buffer, 0, chunk)
            remaining -= chunk
        }
    }

    private fun definition(
        id: String,
        revision: Long,
        name: String,
    ) = AgentSkillDefinition(
        id = id,
        revision = revision,
        name = name,
        description = "用于测试的简短描述",
        content = "# $name\n完整内容",
        baseIntent = EditorIntent.FORMAT,
    )

    private fun assertSelectedSlot(
        state: SkillDraftSessionState,
        expected: AgentSkillSlot?,
        explicitlySelected: Boolean,
    ) {
        val selection = requireNotNull(state.current()).bindingSelection
        assertEquals(expected, selection.slot)
        assertEquals(explicitlySelected, selection.explicitlySelected)
    }

    private fun catalog(vararg definitions: AgentSkillDefinition) = AgentSkillCatalog(
        generation = definitions.maxOfOrNull(AgentSkillDefinition::revision)?.plus(10) ?: 1,
        definitions = definitions.toList(),
        bindings = emptyList(),
        active = null,
    )

    private fun catalogWithBindings(
        generation: Long,
        definitions: List<AgentSkillDefinition>,
        bindings: List<AgentSkillBinding>,
    ) = AgentSkillCatalog(
        generation = generation,
        definitions = definitions,
        bindings = bindings,
        active = null,
    )
}
