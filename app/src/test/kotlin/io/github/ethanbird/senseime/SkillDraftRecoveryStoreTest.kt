package io.github.ethanbird.senseime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDraftRecoveryStoreTest {
    @Test
    fun malformedUtf8SnapshotIsPreservedByteForByteBeforePublishingReplacement() {
        val directory = Files.createTempDirectory("sense-skill-draft-test").toFile()
        try {
            val malformed = malformedUtf8Snapshot()
            val current = directory.resolve("skills.current")
            current.writeBytes(malformed)
            val commitPoints = mutableListOf<SkillDraftRecoveryCommitPoint>()
            val store = SkillDraftRecoveryStore(directory, commitPoints::add)

            assertTrue(store.load().isFailure)

            val state = SkillDraftSessionState().beginCreate(
                SkillSettingsDraft(
                    id = "new_skill",
                    name = "新 Skill",
                    description = "完整恢复测试",
                    content = "# Skill\n保留全部用户字节。",
                    baseIntent = EditorIntent.SMART_EDIT,
                    bindingSlot = null,
                ),
            )
            val outcome = store.save(state).getOrThrow()

            assertTrue(outcome.preservedUnreadableSnapshot)
            val preserved = directory.listFiles()
                .orEmpty()
                .single { it.name.startsWith("skills.unreadable.") }
            assertArrayEquals(malformed, preserved.readBytes())
            assertEquals(state, store.load().getOrThrow())
            assertEquals(
                listOf(
                    SkillDraftRecoveryCommitPoint.UNREADABLE_COPY_SYNCED,
                    SkillDraftRecoveryCommitPoint.UNREADABLE_COPY_PUBLISHED,
                    SkillDraftRecoveryCommitPoint.PENDING_FILE_SYNCED,
                    SkillDraftRecoveryCommitPoint.CURRENT_REPLACED,
                    SkillDraftRecoveryCommitPoint.CURRENT_DIRECTORY_SYNCED,
                ),
                commitPoints,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failureAfterPendingFsyncLeavesPreviousCurrentByteExactAndReadable() {
        val parent = Files.createTempDirectory("sense-skill-draft-crash-test").toFile()
        val directory = parent.resolve("recovery")
        try {
            val original = stateWithContent("原始完整草稿")
            val creationPoints = mutableListOf<SkillDraftRecoveryCommitPoint>()
            SkillDraftRecoveryStore(directory, creationPoints::add)
                .save(original)
                .getOrThrow()
            assertEquals(
                SkillDraftRecoveryCommitPoint.DIRECTORY_PUBLISHED,
                creationPoints.first(),
            )
            val current = directory.resolve("skills.current")
            val originalBytes = current.readBytes()

            val crashingStore = SkillDraftRecoveryStore(directory) { point ->
                if (point == SkillDraftRecoveryCommitPoint.PENDING_FILE_SYNCED) {
                    throw SimulatedPowerLoss()
                }
            }
            val replacement = stateWithContent("绝不能部分覆盖的新草稿")

            assertFalse(crashingStore.save(replacement).isSuccess)
            assertArrayEquals(originalBytes, current.readBytes())
            assertEquals(original, SkillDraftRecoveryStore(directory).load().getOrThrow())
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun stateWithContent(content: String): SkillDraftSessionState =
        SkillDraftSessionState().beginCreate(
            SkillSettingsDraft(
                id = "new_skill",
                name = "新 Skill",
                description = "完整恢复测试",
                content = content,
                baseIntent = EditorIntent.SMART_EDIT,
                bindingSlot = null,
            ),
        )

    private fun malformedUtf8Snapshot(): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            DataOutputStream(gzip).use { data ->
                data.writeInt(0x53445331)
                data.writeInt(1)
                data.writeBoolean(false)
                data.writeInt(1)
                data.writeInt(1)
                data.writeByte(0x80)
            }
        }
        return output.toByteArray()
    }

    private class SimulatedPowerLoss : RuntimeException()
}
