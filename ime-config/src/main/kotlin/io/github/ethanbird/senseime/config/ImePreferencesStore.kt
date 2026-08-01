package io.github.ethanbird.senseime.config

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Atomic cross-process persistence for keyboard scheme selection.
 *
 * Callers perform these small reads and writes on their own I/O lane. A sidecar OS file lock makes
 * AtomicFile's replace operation coherent across the app and :ime processes.
 */
class ImePreferencesStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, STORE_DIRECTORY)
    private val file = AtomicFile(File(root, STORE_FILE))
    private val lockFile = File(root, STORE_LOCK_FILE)

    fun load(): Result<ImePreferencesV1> = runCatching {
        withStoreLock(::readUnlocked)
    }

    fun save(value: ImePreferencesV1): Result<Unit> = runCatching {
        withStoreLock { writeUnlocked(value) }
    }

    /** Atomically reads, transforms and replaces the cross-process snapshot under one OS lock. */
    fun update(transform: (ImePreferencesV1) -> ImePreferencesV1): Result<ImePreferencesV1> =
        runCatching {
            withStoreLock {
                val updated = transform(readUnlocked())
                writeUnlocked(updated)
                updated
            }
        }

    private fun readUnlocked(): ImePreferencesV1 = try {
        file.openRead().use { ImePreferencesCodec.decode(it.readBytes()) }
    } catch (_: FileNotFoundException) {
        ImePreferencesV1.DEFAULT
    }

    private fun writeUnlocked(value: ImePreferencesV1) {
        val output = file.startWrite()
        try {
            output.write(ImePreferencesCodec.encode(value))
            output.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Failed to create IME preference directory")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private companion object {
        const val STORE_DIRECTORY = "sense-ime"
        const val STORE_FILE = "preferences-v1.conf"
        const val STORE_LOCK_FILE = "preferences.lock"
        val STORE_MUTEX = Any()
    }
}
