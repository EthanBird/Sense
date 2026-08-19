package io.github.ethanbird.senseime

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutNoticeControllerTest {
    @Test
    fun noticeAssetCatalogMatchesChineseDictionaryBundles() {
        assertEquals(
            listOf(
                "NOTICE" to "NOTICE.txt",
                "Sense GPL-3.0" to "LICENSE.txt",
                "Rime Frost NOTICE" to "RIME-FROST-NOTICE.txt",
                "Rime Frost GPL-3.0" to "RIME-FROST-GPL-3.0.txt",
                "Rime Wubi NOTICE" to "RIME-WUBI-NOTICE.txt",
                "Rime Wubi LGPL-3.0" to "RIME-WUBI-LGPL-3.0.txt",
                "OkHttp Apache-2.0" to "OKHTTP-APACHE-2.0.txt",
                "Lark OpenAPI Apache-2.0" to "LARK-OAPI-APACHE-2.0.txt",
                "Concentus BSD-3-Clause" to "CONCENTUS-BSD-3-CLAUSE.txt",
            ),
            ABOUT_NOTICE_ASSETS,
        )
    }

    @Test
    fun noticeReadUsesTaskLaneAndDeliversRepositoryText() {
        val tasks = QueuedTaskRunner()
        var repositoryCalls = 0
        val controller = AboutNoticeController(
            repository = AboutNoticeRepository {
                repositoryCalls += 1
                Result.success("NOTICE")
            },
            tasks = tasks,
        )
        var delivered: Result<String>? = null

        assertTrue(controller.load { delivered = it })
        assertEquals(0, repositoryCalls)
        assertEquals(null, delivered)

        tasks.runNext()

        assertEquals(1, repositoryCalls)
        assertEquals("NOTICE", delivered?.getOrThrow())
    }

    @Test
    fun duplicateLoadIsCoalescedAndCloseRevokesAcceptedCallback() {
        val tasks = QueuedTaskRunner()
        val controller = AboutNoticeController(
            repository = AboutNoticeRepository { Result.success("NOTICE") },
            tasks = tasks,
        )
        var deliveries = 0

        assertTrue(controller.load { deliveries += 1 })
        assertFalse(controller.load { deliveries += 1 })
        controller.close()
        tasks.runNext()

        assertEquals(0, deliveries)
        assertTrue(tasks.closed)
    }

    private class QueuedTaskRunner : SettingsTaskRunner {
        private val work = ArrayDeque<() -> Unit>()
        var closed = false
            private set

        override fun <T> refresh(
            channel: String,
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            if (closed) return false
            work += { deliver(runCatching(operation)) }
            return true
        }

        override fun <T> execute(
            operation: () -> T,
            deliver: (Result<T>) -> Unit,
        ): Boolean {
            if (closed) return false
            work += { deliver(runCatching(operation)) }
            return true
        }

        override fun close() {
            closed = true
        }

        fun runNext() {
            work.removeFirst().invoke()
        }
    }
}
