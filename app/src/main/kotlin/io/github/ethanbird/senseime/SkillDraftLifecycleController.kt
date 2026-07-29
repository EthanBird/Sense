package io.github.ethanbird.senseime

import android.app.Activity
import android.view.View
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns the Skills draft recovery state machine and its process-scoped serial durability lane.
 *
 * UI code may replace [session], but filesystem ordering, restore/write races, Bundle fallback, and
 * the lifecycle fsync barrier remain local to this module.
 */
internal class SkillDraftLifecycleController(
    activity: Activity,
    bundledState: ByteArray?,
    private val onRecoveryStatusChanged: (String?, String?) -> Unit,
) : AutoCloseable {
    private val applicationContext = activity.applicationContext
    private val recoveryStore by lazy { SkillDraftRecoveryStore(applicationContext) }
    val ioSession = SkillSettingsIoSession(
        workerExecutor = SKILL_IO_EXECUTOR,
        uiExecutor = Executor { command -> activity.runOnUiThread(command) },
    )
    private val durabilityBarrier =
        SkillSettingsDurabilityBarrier<
            LifecycleSaveKey,
            SkillDraftRecoveryStore.SaveOutcome,
        >(
            workerExecutor = SKILL_IO_EXECUTOR,
            timeoutMillis = LIFECYCLE_FLUSH_TIMEOUT_MS,
        )

    var session: SkillDraftSessionState = SkillDraftSessionState()
        set(value) {
            if (!restoreCompleted && value != field) changedWhileRestoring = true
            field = value
        }

    var recoveryError: String? = null
        private set
    var recoveryNotice: String? = null
        private set

    private var restoreCompleted = false
    private var changedWhileRestoring = false
    @Volatile
    private var restoreWorkerCompleted = false
    @Volatile
    private var restoreWorkerState: SkillDraftSessionState? = null
    @Volatile
    private var restoreWorkerFailure: Throwable? = null
    private var saveRequestedWhileRestoring = false
    private var writeAuthorized = true
    private var durabilityGeneration = 0L
    private var completedDurabilityGeneration = 0L
    private var callbackRoot: View? = null
    private var captureBeforeDebouncedSave: (() -> Unit)? = null
    private val persistRunnable = Runnable {
        captureBeforeDebouncedSave?.invoke()
        persist()
    }

    init {
        restore(bundledState?.copyOf())
    }

    fun attach(
        callbackRoot: View,
        captureBeforeDebouncedSave: () -> Unit,
    ) {
        this.callbackRoot?.removeCallbacks(persistRunnable)
        this.callbackRoot = callbackRoot
        this.captureBeforeDebouncedSave = captureBeforeDebouncedSave
    }

    fun detach() {
        callbackRoot?.removeCallbacks(persistRunnable)
        callbackRoot = null
        captureBeforeDebouncedSave = null
    }

    fun authorizeWrites() {
        writeAuthorized = true
    }

    fun schedulePersistence() {
        val root = callbackRoot ?: return
        markDurabilityPending()
        root.removeCallbacks(persistRunnable)
        root.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    fun persist() {
        callbackRoot?.removeCallbacks(persistRunnable)
        if (!restoreCompleted) {
            markDurabilityPending()
            saveRequestedWhileRestoring = true
            return
        }
        if (!writeAuthorized) return
        val snapshot = session
        val saveGeneration = markDurabilityPending()
        ioSession.execute(
            operation = { recoveryStore.save(snapshot).getOrThrow() },
        ) { result ->
            result
                .onSuccess { outcome ->
                    if (durabilityGeneration == saveGeneration) {
                        completedDurabilityGeneration = saveGeneration
                    }
                    recoveryError = null
                    if (outcome.preservedUnreadableSnapshot) {
                        recoveryNotice = RECOVERY_NOTICE_TOKEN
                    }
                    notifyStatus()
                }
                .onFailure { error ->
                    recoveryError = error.message.orEmpty()
                    notifyStatus()
                }
        }
    }

    fun hasPendingDurabilityWork(): Boolean =
        writeAuthorized && durabilityGeneration != completedDurabilityGeneration

    /**
     * Returns the exact bounded Bundle fallback and ensures larger sessions join the durability
     * lane before Android may kill the process.
     */
    fun snapshotForSavedState(): ByteArray? {
        val bundled = if (restoreCompleted) {
            SkillDraftSessionCodec.encodeForSavedState(session, MAX_BUNDLE_DRAFT_BYTES)
        } else {
            null
        }
        if (bundled != null) {
            persist()
            return bundled.copyOf()
        }
        return flushForLifecycle()
            ?.encodedSnapshot
            ?.takeIf { it.size <= MAX_BUNDLE_DRAFT_BYTES }
            ?.copyOf()
    }

    fun flushForLifecycle(): SkillDraftRecoveryStore.SaveOutcome? {
        if (!writeAuthorized) return null
        callbackRoot?.removeCallbacks(persistRunnable)
        val inMemorySnapshot = session
        val wasRestoreCompleted = restoreCompleted
        val wasChangedWhileRestoring = changedWhileRestoring
        val key = LifecycleSaveKey(
            snapshot = inMemorySnapshot,
            restoreCompleted = wasRestoreCompleted,
            changedWhileRestoring = wasChangedWhileRestoring,
            durabilityGeneration = durabilityGeneration,
        )
        val flushGeneration = durabilityGeneration
        return durabilityBarrier.execute(key) {
            val finalSnapshot = if (wasRestoreCompleted) {
                inMemorySnapshot
            } else {
                check(restoreWorkerCompleted) {
                    "Skill draft restore did not precede the lifecycle save"
                }
                val failure = restoreWorkerFailure
                if (failure != null && !wasChangedWhileRestoring) throw failure
                val restored = restoreWorkerState ?: SkillDraftSessionState()
                if (wasChangedWhileRestoring) {
                    merge(restored, inMemorySnapshot)
                } else {
                    restored
                }
            }
            recoveryStore.save(finalSnapshot).getOrThrow()
        }
            .onSuccess { outcome ->
                completedDurabilityGeneration = flushGeneration
                recoveryError = null
                if (outcome.preservedUnreadableSnapshot) {
                    recoveryNotice = RECOVERY_NOTICE_TOKEN
                }
                notifyStatus()
            }
            .onFailure { error ->
                recoveryError = error.message.orEmpty()
                notifyStatus()
            }
            .getOrNull()
    }

    override fun close() {
        detach()
        ioSession.close()
    }

    private fun restore(bundled: ByteArray?) {
        ioSession.refresh(
            channel = DRAFT_RESTORE_CHANNEL,
            operation = {
                try {
                    val bundledSession = bundled
                        ?.let { runCatching { SkillDraftSessionCodec.decode(it) }.getOrNull() }
                    val restored = bundledSession
                        ?: recoveryStore.load().getOrThrow()
                        ?: SkillDraftSessionState()
                    restoreWorkerState = restored
                    restored
                } catch (error: Throwable) {
                    restoreWorkerFailure = error
                    throw error
                } finally {
                    restoreWorkerCompleted = true
                }
            },
        ) { result ->
            restoreCompleted = true
            val changed = changedWhileRestoring
            result
                .onSuccess { restored ->
                    session = if (changed) merge(restored, session) else restored
                    recoveryError = null
                }
                .onFailure { error ->
                    recoveryError = error.message.orEmpty()
                    // Preserve unreadable recovery bytes until an actual user edit authorizes write.
                    writeAuthorized = changed
                }
            changedWhileRestoring = false
            notifyStatus()
            if (saveRequestedWhileRestoring && writeAuthorized) {
                saveRequestedWhileRestoring = false
                persist()
            }
        }
    }

    private fun merge(
        restored: SkillDraftSessionState,
        inMemory: SkillDraftSessionState,
    ): SkillDraftSessionState {
        if (inMemory.records.isEmpty()) return restored
        return SkillDraftSessionState(
            selectedKey = inMemory.selectedKey ?: restored.selectedKey,
            records = restored.records + inMemory.records,
        )
    }

    private fun notifyStatus() {
        onRecoveryStatusChanged(
            recoveryError,
            recoveryNotice,
        )
    }

    private fun markDurabilityPending(): Long {
        durabilityGeneration =
            if (durabilityGeneration == Long.MAX_VALUE) 1L else durabilityGeneration + 1L
        return durabilityGeneration
    }

    private data class LifecycleSaveKey(
        val snapshot: SkillDraftSessionState,
        val restoreCompleted: Boolean,
        val changedWhileRestoring: Boolean,
        val durabilityGeneration: Long,
    )

    companion object {
        internal const val MAX_BUNDLE_DRAFT_BYTES = 192 * 1024
        internal const val RECOVERY_NOTICE_TOKEN = "unreadable-snapshot-preserved"
        private const val PERSIST_DEBOUNCE_MS = 400L
        private const val LIFECYCLE_FLUSH_TIMEOUT_MS = 3_500L
        private const val DRAFT_RESTORE_CHANNEL = "draft-restore"
        private val SKILL_IO_EXECUTOR = Executors.newSingleThreadExecutor { command ->
            Thread(command, "Sense-SkillSettings-IO").apply {
                isDaemon = true
            }
        }
    }
}
