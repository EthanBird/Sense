package io.github.ethanbird.senseime.service

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSkillDirectoryWatcherDeviceTest {
    @Test
    fun currentEventsAndDirectoryInvalidationRemainObservableAcrossReregistration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = uniqueTestDirectory(context, "watcher")
        val directory = File(parent, AgentSkillStore.DIRECTORY_NAME)
        val movedDirectory = File(parent, "${AgentSkillStore.DIRECTORY_NAME}.moved")
        assertTrue(directory.mkdirs())
        val changed = LinkedBlockingQueue<Unit>()
        val invalidated = LinkedBlockingQueue<Unit>()
        val watcher = AgentSkillDirectoryWatcher(directory)

        try {
            assertTrue(watcher.register({ changed.offer(Unit) }, { invalidated.offer(Unit) }))
            assertTrue(watcher.isRegisteredForTest())

            val pending = File(directory, ".CURRENT.pending")
            pending.writeText("1\n")
            Files.move(
                pending.toPath(),
                File(directory, AgentSkillStore.CURRENT_FILE_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            assertNotNull(
                "atomic MOVED_TO CURRENT was not observed",
                changed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            changed.clear()
            FileOutputStream(File(directory, AgentSkillStore.CURRENT_FILE_NAME), false).use {
                it.write("2\n".toByteArray())
                it.fd.sync()
            }
            assertNotNull(
                "CLOSE_WRITE CURRENT was not observed",
                changed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            assertTrue(directory.renameTo(movedDirectory))
            assertNotNull(
                "MOVE_SELF was not observed",
                invalidated.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            watcher.unregister()
            assertFalse(watcher.isRegisteredForTest())

            assertTrue(directory.mkdirs())
            changed.clear()
            invalidated.clear()
            assertTrue(watcher.register({ changed.offer(Unit) }, { invalidated.offer(Unit) }))
            File(directory, AgentSkillStore.CURRENT_FILE_NAME).writeText("3\n")
            assertNotNull(
                "watch did not recover after MOVE_SELF",
                changed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            assertTrue(File(directory, AgentSkillStore.CURRENT_FILE_NAME).delete())
            assertTrue(directory.delete())
            assertNotNull(
                "DELETE_SELF was not observed",
                invalidated.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            watcher.unregister()
            assertFalse(watcher.isRegisteredForTest())

            assertTrue(directory.mkdirs())
            changed.clear()
            assertTrue(watcher.register({ changed.offer(Unit) }, { invalidated.offer(Unit) }))
            File(directory, AgentSkillStore.CURRENT_FILE_NAME).writeText("4\n")
            assertNotNull(
                "watch did not recover after DELETE_SELF",
                changed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            watcher.unregister()
            directory.deleteRecursively()
            movedDirectory.deleteRecursively()
            parent.deleteRecursively()
        }
    }

    @Test
    fun realStoreProjectionMutationAndWatcherLifecyclePerformNoMainThreadDiskIo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isolatedFiles = uniqueTestDirectory(context, "strictmode")
        val isolatedContext = IsolatedFilesContext(context, isolatedFiles)
        val store = AgentSkillStore(isolatedContext)
        val watcher = AgentSkillDirectoryWatcher(
            File(isolatedFiles, AgentSkillStore.DIRECTORY_NAME),
        )
        val background = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "skill-device-io")
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val published = LinkedBlockingQueue<AgentSkillCatalog>()
        val failures = LinkedBlockingQueue<AgentSkillProjectionFailure>()
        val backgroundLaneChecks = CopyOnWriteArrayList<Boolean>()
        val violations = CopyOnWriteArrayList<String>()
        val watcherRegistrations = AtomicInteger()
        val watcherUnregistrations = AtomicInteger()
        val coordinator = AgentSkillProjectionCoordinator(
            backgroundExecutor = background,
            deliveryExecutor = Executor { command -> mainHandler.post(command) },
            load = {
                backgroundLaneChecks += Looper.myLooper() != Looper.getMainLooper()
                store.loadCatalog()
            },
            registerWatcher = { onChanged, onInvalidated ->
                backgroundLaneChecks += Looper.myLooper() != Looper.getMainLooper()
                watcher.register(onChanged, onInvalidated).also { registered ->
                    if (registered) watcherRegistrations.incrementAndGet()
                }
            },
            unregisterWatcher = {
                backgroundLaneChecks += Looper.myLooper() != Looper.getMainLooper()
                watcher.unregister()
                watcherUnregistrations.incrementAndGet()
            },
            publish = { catalog -> published.offer(catalog) },
            reportFailure = { failure -> failures.offer(failure) },
        )

        var coordinatorClosed = false
        try {
            runOnMainUnderDiskStrictMode(instrumentation, violations) {
                coordinator.start()
            }
            val initial = requireNotNull(
                published.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            ) { "initial real AgentSkillStore projection timed out" }
            assertTrue(initial.generation > 0L)
            assertTrue(failures.isEmpty())

            // Drain the mandatory read-watch-read catch-up before measuring an explicit rebuild.
            // Otherwise its delayed delivery could satisfy the next publication assertion while
            // unregister/register never actually completed.
            awaitBackgroundDrain(background)
            instrumentation.waitForIdleSync()
            assertTrue(watcherRegistrations.get() >= 1)
            val registrationsBeforeRebuild = watcherRegistrations.get()
            val unregistrationsBeforeRebuild = watcherUnregistrations.get()
            published.clear()
            runOnMainUnderDiskStrictMode(instrumentation, violations) {
                coordinator.refreshAndRebuildWatcher()
            }
            assertNotNull(
                "watcher rebuild did not publish",
                published.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            awaitBackgroundDrain(background)
            instrumentation.waitForIdleSync()
            assertTrue(watcherRegistrations.get() > registrationsBeforeRebuild)
            assertTrue(watcherUnregistrations.get() > unregistrationsBeforeRebuild)

            published.clear()
            runOnMainUnderDiskStrictMode(instrumentation, violations) {
                coordinator.submit {
                    backgroundLaneChecks += Looper.myLooper() != Looper.getMainLooper()
                    store.clearActive()
                }
            }
            val mutated = awaitGenerationAfter(published, initial.generation)
            assertTrue(mutated.generation > initial.generation)
            assertTrue(failures.isEmpty())

            runOnMainUnderDiskStrictMode(instrumentation, violations) {
                coordinator.close()
                coordinatorClosed = true
            }
            awaitBackgroundDrain(background)
            instrumentation.waitForIdleSync()
            assertFalse(watcher.isRegisteredForTest())
            assertTrue(
                "store/watcher work escaped the coordinator background lane",
                backgroundLaneChecks.isNotEmpty() && backgroundLaneChecks.all { it },
            )
            assertTrue(
                "main-thread StrictMode disk violations: $violations",
                violations.isEmpty(),
            )
        } finally {
            if (!coordinatorClosed) {
                runOnMainUnderDiskStrictMode(instrumentation, violations) {
                    coordinator.close()
                    coordinatorClosed = true
                }
            }
            runCatching { awaitBackgroundDrain(background) }
            background.shutdown()
            assertTrue(background.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            watcher.unregister()
            isolatedFiles.deleteRecursively()
        }
    }

    private fun awaitGenerationAfter(
        published: LinkedBlockingQueue<AgentSkillCatalog>,
        generation: Long,
    ): AgentSkillCatalog {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val catalog = published.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            if (catalog.generation > generation) return catalog
        }
        error("timed out waiting for a catalog generation after $generation")
    }

    private fun awaitBackgroundDrain(background: ExecutorService) {
        background.submit {}.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun runOnMainUnderDiskStrictMode(
        instrumentation: android.app.Instrumentation,
        violations: MutableCollection<String>,
        action: () -> Unit,
    ) {
        instrumentation.runOnMainSync {
            val previousPolicy = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(previousPolicy)
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyListener(Executor { command -> command.run() }) { violation ->
                            violations += violation.javaClass.name
                        }
                        .build(),
                )
                action()
            } finally {
                StrictMode.setThreadPolicy(previousPolicy)
            }
        }
    }

    private fun uniqueTestDirectory(context: Context, prefix: String): File {
        val directory = File(
            context.cacheDir,
            "sense-$prefix-${UUID.randomUUID()}",
        )
        assertTrue(directory.mkdirs())
        return directory
    }

    private class IsolatedFilesContext(
        base: Context,
        private val isolatedFiles: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = isolatedFiles
    }

    private companion object {
        const val TIMEOUT_SECONDS = 8L
    }
}
