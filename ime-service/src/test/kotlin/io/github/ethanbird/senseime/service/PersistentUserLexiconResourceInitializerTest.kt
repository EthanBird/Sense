package io.github.ethanbird.senseime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PersistentUserLexiconResourceInitializerTest {
    @Test
    fun loadFailureClosesStorageAndRethrowsTheOriginalError() {
        val storage = FakeStorage()
        val loadError = IllegalStateException("migration failed")

        val thrown = captureFailure {
            initialize(
                storage = storage,
                load = { throw loadError },
            )
        }

        assertSame(loadError, thrown)
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun writerAcquisitionFailureClosesStorage() {
        val storage = FakeStorage()
        val writerError = IllegalStateException("writer failed")

        val thrown = captureFailure {
            initialize(
                storage = storage,
                openWriter = { throw writerError },
            )
        }

        assertSame(writerError, thrown)
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun buildFailureClosesWriterWhichOwnsStorage() {
        val storage = FakeStorage()
        lateinit var writer: FakeWriter
        val buildError = IllegalArgumentException("memory restore failed")

        val thrown = captureFailure {
            initialize(
                storage = storage,
                openWriter = {
                    FakeWriter(it).also { created -> writer = created }
                },
                build = { _, _ -> throw buildError },
            )
        }

        assertSame(buildError, thrown)
        assertEquals(1, writer.closeCount)
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun closeFailureIsSuppressedWithoutReplacingTheInitializationError() {
        val storage = FakeStorage(closeError = IllegalStateException("close failed"))
        val loadError = IllegalStateException("load failed")

        val thrown = captureFailure {
            initialize(
                storage = storage,
                load = { throw loadError },
            )
        }

        assertSame(loadError, thrown)
        assertEquals(1, storage.closeCount)
        assertEquals(1, thrown.suppressed.size)
        assertEquals("close failed", thrown.suppressed.single().message)
    }

    @Test
    fun successfulInitializationTransfersOwnershipWithoutEarlyClose() {
        val storage = FakeStorage()

        val writer = initialize(storage = storage)

        assertEquals(0, writer.closeCount)
        assertEquals(0, storage.closeCount)
        writer.close()
        assertEquals(1, storage.closeCount)
    }

    private fun initialize(
        storage: FakeStorage,
        load: (FakeStorage) -> String = { "loaded" },
        openWriter: (FakeStorage) -> FakeWriter = ::FakeWriter,
        build: (String, FakeWriter) -> FakeWriter = { _, writer -> writer },
    ): FakeWriter =
        PersistentUserLexiconResourceInitializer.initialize(
            openStorage = { storage },
            load = load,
            openWriter = openWriter,
            build = build,
            closeStorage = FakeStorage::close,
            closeWriter = FakeWriter::close,
        )

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        fail("Expected initialization failure")
        error("unreachable")
    } catch (error: Throwable) {
        error
    }

    private class FakeStorage(
        private val closeError: Throwable? = null,
    ) {
        var closeCount = 0
            private set

        fun close() {
            closeCount += 1
            closeError?.let { throw it }
        }
    }

    private class FakeWriter(
        private val storage: FakeStorage,
    ) {
        var closeCount = 0
            private set

        fun close() {
            closeCount += 1
            storage.close()
        }
    }
}
