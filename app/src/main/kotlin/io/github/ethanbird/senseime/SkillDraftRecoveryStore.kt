package io.github.ethanbird.senseime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Durable app-private recovery for unsaved Skill editor buffers.
 *
 * Android saved-instance state has a strict Binder size limit, while a Skill document may contain
 * tens of thousands of characters. The Activity therefore stores only a small fallback in the
 * Bundle and keeps the complete versioned payload here. A successful write is published with an
 * atomic rename so process death cannot replace a previous good recovery snapshot with a prefix.
 */
internal class SkillDraftRecoveryStore internal constructor(
    private val directory: File,
    private val commitObserver: (SkillDraftRecoveryCommitPoint) -> Unit = {},
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY),
    )

    private val current = File(directory, CURRENT_FILE)
    private var currentKnownReadable = false

    data class SaveOutcome(
        val preservedUnreadableSnapshot: Boolean,
        val encodedSnapshot: ByteArray,
    )

    fun load(): Result<SkillDraftSessionState?> = runCatching {
        if (!current.isFile) return@runCatching null
        require(current.length() in 1..MAX_FILE_BYTES) {
            "Invalid Skill draft recovery file size"
        }
        SkillDraftSessionCodec.decode(current.readBytes()).also {
            currentKnownReadable = true
        }
    }

    fun save(state: SkillDraftSessionState): Result<SaveOutcome> = runCatching {
        val encoded = SkillDraftSessionCodec.encode(state)
        ensureDirectory()
        val preservedUnreadable = preserveUnreadableCurrentIfNecessary()
        val pending = File(directory, PENDING_FILE)
        FileOutputStream(pending).use { output ->
            output.write(encoded)
            output.flush()
            output.fd.sync()
        }
        commitObserver(SkillDraftRecoveryCommitPoint.PENDING_FILE_SYNCED)
        Files.move(
            pending.toPath(),
            current.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        commitObserver(SkillDraftRecoveryCommitPoint.CURRENT_REPLACED)
        syncDirectory(directory)
        commitObserver(SkillDraftRecoveryCommitPoint.CURRENT_DIRECTORY_SYNCED)
        currentKnownReadable = true
        SaveOutcome(
            preservedUnreadableSnapshot = preservedUnreadable,
            encodedSnapshot = encoded,
        )
    }

    private fun ensureDirectory() {
        if (!directory.exists()) {
            require(directory.mkdirs() || directory.isDirectory) {
                "Cannot create Skill draft recovery directory"
            }
            directory.parentFile
                ?.takeIf(File::isDirectory)
                ?.let(::syncDirectory)
            commitObserver(SkillDraftRecoveryCommitPoint.DIRECTORY_PUBLISHED)
        }
        require(directory.isDirectory) {
            "Skill draft recovery path is not a directory"
        }
    }

    private fun preserveUnreadableCurrentIfNecessary(): Boolean {
        if (!current.isFile || currentKnownReadable) return false
        val readable = runCatching {
            require(current.length() in 1..MAX_FILE_BYTES)
            SkillDraftSessionCodec.decode(current.readBytes())
        }.isSuccess
        if (readable) {
            currentKnownReadable = true
            return false
        }
        var suffix = System.currentTimeMillis()
        var preserved = File(directory, "$UNREADABLE_PREFIX$suffix")
        while (preserved.exists()) {
            suffix += 1L
            preserved = File(directory, "$UNREADABLE_PREFIX$suffix")
        }
        val pendingPreserved = File(directory, ".${preserved.name}.pending")
        current.inputStream().use { input ->
            FileOutputStream(pendingPreserved).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        commitObserver(SkillDraftRecoveryCommitPoint.UNREADABLE_COPY_SYNCED)
        Files.move(
            pendingPreserved.toPath(),
            preserved.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        syncDirectory(directory)
        commitObserver(SkillDraftRecoveryCommitPoint.UNREADABLE_COPY_PUBLISHED)
        return true
    }

    private fun syncDirectory(target: File) {
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (failure: AccessDeniedException) {
            // The Windows NIO provider has no directory-handle force support.
            val isWindows =
                System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
            if (!isWindows) throw failure
        }
    }

    companion object {
        private const val DIRECTORY = "settings-drafts-v1"
        private const val CURRENT_FILE = "skills.current"
        private const val PENDING_FILE = "skills.pending"
        private const val UNREADABLE_PREFIX = "skills.unreadable."
        private const val MAX_FILE_BYTES = 24L * 1024L * 1024L
    }
}

internal enum class SkillDraftRecoveryCommitPoint {
    DIRECTORY_PUBLISHED,
    UNREADABLE_COPY_SYNCED,
    UNREADABLE_COPY_PUBLISHED,
    PENDING_FILE_SYNCED,
    CURRENT_REPLACED,
    CURRENT_DIRECTORY_SYNCED,
}
