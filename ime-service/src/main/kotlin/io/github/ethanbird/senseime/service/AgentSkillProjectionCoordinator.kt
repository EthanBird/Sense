package io.github.ethanbird.senseime.service

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal enum class AgentSkillProjectionOperation {
    REFRESH,
    MUTATION,
}

internal data class AgentSkillProjectionFailure(
    val operation: AgentSkillProjectionOperation,
    val cause: Throwable,
    val requestToken: Long? = null,
)

internal object AgentSkillProjectionFailureText {
    fun message(operation: AgentSkillProjectionOperation): String = when (operation) {
        AgentSkillProjectionOperation.REFRESH -> "Skill 配置读取失败，请重试"
        AgentSkillProjectionOperation.MUTATION -> "Skill 切换失败，配置未更改，请重新选择"
    }
}

/**
 * Keeps the IME's disk-free Skill projection synchronized with the immutable shared store.
 *
 * The first refresh installs the filesystem watcher even when that read fails, then schedules a
 * mandatory catch-up read. Consequently a commit is observed whether it lands before or after
 * watcher registration. All reads/mutations and watcher syscalls use the caller-provided serial
 * [backgroundExecutor]; only immutable projection delivery uses [deliveryExecutor]. Lifecycle
 * refreshes are coalesced and may request a complete watcher rebuild after FileObserver loss.
 */
internal class AgentSkillProjectionCoordinator<T>(
    private val backgroundExecutor: Executor,
    private val deliveryExecutor: Executor,
    private val load: () -> Result<T>,
    private val registerWatcher: (
        onChanged: () -> Unit,
        onInvalidated: () -> Unit,
    ) -> Boolean,
    private val unregisterWatcher: () -> Unit,
    private val publish: (T) -> Unit,
    private val reportFailure: (AgentSkillProjectionFailure) -> Unit = {},
    private val rejectedCleanupExecutor: Executor = FINAL_CLEANUP_EXECUTOR,
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var nextSequence = 0L
    private var deliveredSequence = 0L
    private var watcherRegistered = false
    private var refreshQueued = false
    private var refreshAgain = false
    private var rebuildRequested = false
    private var queuedRefreshSequence = 0L
    private var finalCleanupScheduled = false

    fun start() {
        refresh()
    }

    fun refresh() {
        requestRefresh(rebuildWatcher = false)
    }

    /**
     * Coalesces lifecycle reconciliation and performs FileObserver stop/start on the background
     * lane before reading. No filesystem call is made by the IME callback thread.
     */
    fun refreshAndRebuildWatcher() {
        requestRefresh(rebuildWatcher = true)
    }

    fun submit(
        requestToken: Long? = null,
        operation: () -> Result<T>,
    ) {
        require(requestToken == null || requestToken > 0L)
        val sequence = synchronized(stateLock) {
            if (closed) return
            ++nextSequence
        }
        try {
            backgroundExecutor.execute {
                val result = runCatching { operation().getOrThrow() }
                deliver(
                    sequence,
                    AgentSkillProjectionOperation.MUTATION,
                    requestToken,
                    result,
                )
            }
        } catch (_: RejectedExecutionException) {
            // Teardown owns the executor. The last complete immutable projection stays valid.
        }
    }

    private fun requestRefresh(rebuildWatcher: Boolean) {
        val shouldSchedule = synchronized(stateLock) {
            if (closed) return
            rebuildRequested = rebuildRequested || rebuildWatcher
            if (refreshQueued) {
                refreshAgain = true
                false
            } else {
                refreshQueued = true
                queuedRefreshSequence = ++nextSequence
                true
            }
        }
        if (!shouldSchedule) return
        try {
            backgroundExecutor.execute(::runRefresh)
        } catch (_: RejectedExecutionException) {
            synchronized(stateLock) {
                refreshQueued = false
            }
            // Teardown owns the executor. The last complete immutable projection stays valid.
        }
    }

    private fun runRefresh() {
        val (sequence, rebuild) = synchronized(stateLock) {
            if (closed) {
                refreshQueued = false
                return
            }
            queuedRefreshSequence to rebuildRequested.also {
                rebuildRequested = false
            }
        }

        if (rebuild) {
            runCatching(unregisterWatcher)
            synchronized(stateLock) {
                watcherRegistered = false
            }
        }
        val result = runCatching { load().getOrThrow() }
        val watcherInstalled = ensureWatcherOnBackground()
        deliver(sequence, AgentSkillProjectionOperation.REFRESH, null, result)

        val catchUpRequired = synchronized(stateLock) {
            refreshQueued = false
            val required = !closed && (refreshAgain || watcherInstalled)
            refreshAgain = false
            required
        }
        if (catchUpRequired) requestRefresh(rebuildWatcher = false)
    }

    private fun ensureWatcherOnBackground(): Boolean {
        val required = synchronized(stateLock) {
            !closed && !watcherRegistered
        }
        if (!required) return false

        val installed = runCatching {
            registerWatcher(
                ::refresh,
                ::onWatcherInvalidated,
            )
        }.getOrDefault(false)
        val accepted = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                watcherRegistered = installed
                installed
            }
        }
        if (installed && !accepted) runCatching(unregisterWatcher)
        return accepted
    }

    private fun onWatcherInvalidated() {
        requestRefresh(rebuildWatcher = true)
    }

    private fun deliver(
        sequence: Long,
        operation: AgentSkillProjectionOperation,
        requestToken: Long?,
        result: Result<T>,
    ) {
        try {
            deliveryExecutor.execute {
                val accepted = synchronized(stateLock) {
                    if (closed || sequence < deliveredSequence) {
                        false
                    } else {
                        deliveredSequence = sequence
                        true
                    }
                }
                if (!accepted) return@execute

                result.fold(
                    onSuccess = publish,
                    onFailure = { cause ->
                        reportFailure(
                            AgentSkillProjectionFailure(
                                operation = operation,
                                cause = cause,
                                requestToken = requestToken,
                            ),
                        )
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            // Delivery executor teardown also preserves the last complete projection.
        }
    }

    override fun close() {
        val scheduleCleanup = synchronized(stateLock) {
            if (closed) return
            closed = true
            if (finalCleanupScheduled) {
                false
            } else {
                finalCleanupScheduled = true
                true
            }
        }
        if (!scheduleCleanup) return

        val cleanup = Runnable {
            runCatching(unregisterWatcher)
            synchronized(stateLock) {
                watcherRegistered = false
                refreshQueued = false
                refreshAgain = false
                rebuildRequested = false
            }
        }
        try {
            /*
             * FIFO placement is intentional: mutations accepted before close retain their durable
             * side effects, then watcher teardown runs last. The owner may gracefully shut down
             * the executor immediately after this method returns, but must not use shutdownNow().
             */
            backgroundExecutor.execute(cleanup)
        } catch (_: RejectedExecutionException) {
            /*
             * A misordered owner shutdown must not move stopWatching() onto the IME callback
             * thread or abandon it. The process-wide daemon is an emergency path only; normal
             * Service teardown always uses the primary serial lane.
             */
            rejectedCleanupExecutor.execute(cleanup)
        }
    }

    private companion object {
        val FINAL_CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sense-agent-skills-cleanup").apply { isDaemon = true }
        }
    }
}
