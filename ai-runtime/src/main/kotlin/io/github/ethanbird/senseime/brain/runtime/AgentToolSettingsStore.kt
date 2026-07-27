package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Cross-process-safe persistence for [AgentToolSettings].
 *
 * [AtomicFile] prevents partial writes and the sidecar file lock serializes the settings Activity
 * with the private Brain process. Missing storage intentionally means the documented default:
 * Agent tools are enabled, with every built-in tool individually allowed.
 */
class AgentToolSettingsStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, STORE_LOCK_FILE)

    fun load(): Result<AgentToolSettings> = runCatching {
        withStoreLock {
            val document = readDocumentOrNull() ?: return@withStoreLock AgentToolSettings()
            AgentToolSettingsCodec.decode(document)
        }
    }

    fun save(settings: AgentToolSettings): Result<Unit> = runCatching {
        withStoreLock {
            val stream = file.startWrite()
            try {
                stream.write(
                    AgentToolSettingsCodec.encode(settings).toByteArray(StandardCharsets.UTF_8),
                )
                stream.flush()
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Unable to create Agent tool settings directory")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun readDocumentOrNull(): String? {
        return try {
            file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private companion object {
        const val STORE_DIRECTORY = "agent-tools"
        const val STORE_FILE = "settings.v1"
        const val STORE_LOCK_FILE = "settings.lock"
        val STORE_MUTEX = Any()
    }
}
