package io.github.ethanbird.senseime.brain.memory

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventJournalTest {
    @Test
    fun `complete records survive restart with stable global sequence`() = withJournalDirectory {
        val privateReasoning = "private reasoning: 用户原文保持完整\u0000🧠"
        val providerBytes = byteArrayOf(0, 1, 2, 3, -1, 0x41)

        AgentEventJournal.open(it).use { journal ->
            val run = journal.beginRun(
                requestId = "request-1",
                runGeneration = 7,
                payload = """{"snapshot":"完整输入","selection":[1,2]}"""
                    .toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json",
                lexicalText = "完整输入 selection",
                attributes = mapOf("provider" to "deepseek", "model" to "deepseek-reasoner"),
            )
            assertEquals(
                2L,
                run.appendText(
                    AgentJournalKind.PRIVATE_AGENT_EVENT,
                    privateReasoning,
                    attributes = mapOf("channel" to "reasoning"),
                ).sequence,
            )
            assertEquals(
                3L,
                run.append(
                    kind = AgentJournalKind.PROVIDER_OUTPUT,
                    payload = providerBytes,
                    contentType = "application/octet-stream",
                    lexicalText = "raw provider binary output",
                ).sequence,
            )
            run.appendText(
                AgentJournalKind.TOOL_CALL,
                """{"name":"memory_search","query":"上次偏好"}""",
                contentType = "application/json",
            )
            run.appendText(
                AgentJournalKind.TOOL_RESULT,
                """{"matches":2,"complete":true}""",
                contentType = "application/json",
            )
            run.appendText(AgentJournalKind.PREVIEW, "预览正文")
            run.appendText(AgentJournalKind.FINAL, "最终正文")
            assertEquals(7L, journal.stats().lastSequence)
        }

        AgentEventJournal.open(it).use { reopened ->
            val run2 = reopened.beginRun(
                requestId = "request-2",
                runGeneration = 8,
                payload = "second snapshot".toByteArray(),
                contentType = "text/plain",
                lexicalText = "second snapshot",
            )
            run2.appendText(AgentJournalKind.CANCELLED, "CALLER_REQUESTED")
            assertEquals(9L, reopened.stats().lastSequence)

            val records = reopened.readLatest(limit = 20).records
            assertEquals((1L..9L).toList(), records.map { record -> record.sequence })
            assertEquals(privateReasoning, records[1].lexicalText)
            assertEquals("reasoning", records[1].attributes["channel"])
            assertArrayEquals(providerBytes, records[2].payload)
            assertEquals(
                listOf(
                    AgentJournalKind.REQUEST_INPUT_SNAPSHOT,
                    AgentJournalKind.PRIVATE_AGENT_EVENT,
                    AgentJournalKind.PROVIDER_OUTPUT,
                    AgentJournalKind.TOOL_CALL,
                    AgentJournalKind.TOOL_RESULT,
                    AgentJournalKind.PREVIEW,
                    AgentJournalKind.FINAL,
                    AgentJournalKind.REQUEST_INPUT_SNAPSHOT,
                    AgentJournalKind.CANCELLED,
                ),
                records.map { record -> record.kind },
            )
        }
    }

    @Test
    fun `memory search switch gates only reads and writing always continues`() =
        withJournalDirectory {
            AgentEventJournal.open(it).use { journal ->
                val run = journal.beginRun(
                    requestId = "request-1",
                    runGeneration = 1,
                    payload = "偏好：冷静克制的设计".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "偏好 冷静克制 设计",
                )

                val disabled = journal.search(
                    query = "冷静克制",
                    access = AgentMemorySearchAccess.DISABLED,
                )
                assertTrue(disabled.hits.isEmpty())
                assertEquals(0, disabled.scannedRecords)
                assertEquals(0L, disabled.scannedBytes)

                // Search being disabled has no representation in the writer and cannot stop it.
                run.appendText(
                    AgentJournalKind.PUBLIC_AGENT_EVENT,
                    "memory_search 已关闭，但此事件仍完整写入",
                )
                assertEquals(2L, journal.stats().lastSequence)
            }

            AgentEventJournal.open(it).use { reopened ->
                val enabled = reopened.search(
                    query = "冷静克制",
                    access = AgentMemorySearchAccess.ENABLED,
                )
                assertEquals(listOf(1L), enabled.hits.map { hit -> hit.sequence })
                assertEquals(2L, reopened.stats().records)
            }
        }

    @Test
    fun `bounded lexical recall ranks exact phrase and reports bounded truncation`() =
        withJournalDirectory {
            AgentEventJournal.open(it).use { journal ->
                repeat(6) { index ->
                    journal.beginRun(
                        requestId = "request-$index",
                        runGeneration = index.toLong(),
                        payload = "snapshot-$index".toByteArray(),
                        contentType = "text/plain",
                        lexicalText = when (index) {
                            2 -> "memory notes discuss a durable local frontier"
                            4 -> "memory note without the other query terms"
                            5 -> "memory durable frontier exact phrase"
                            else -> "unrelated Agent event $index"
                        },
                    )
                }

                val result = journal.search(
                    query = "memory durable frontier",
                    access = AgentMemorySearchAccess.ENABLED,
                    bounds = AgentMemorySearchBounds(
                        maxResults = 2,
                        maxScannedRecords = 6,
                        maxScannedBytes = 1_000_000,
                        maxExcerptChars = 64,
                    ),
                )
                assertEquals(listOf(6L, 3L), result.hits.map { hit -> hit.sequence })
                assertTrue(result.hits[0].score > result.hits[1].score)
                assertFalse(result.truncated)

                val bounded = journal.search(
                    query = "memory",
                    access = AgentMemorySearchAccess.ENABLED,
                    bounds = AgentMemorySearchBounds(
                        maxResults = 5,
                        maxScannedRecords = 2,
                        maxScannedBytes = 1_000_000,
                        maxExcerptChars = 64,
                    ),
                )
                assertEquals(2, bounded.scannedRecords)
                assertTrue(bounded.truncated)
                assertEquals(listOf(6L, 5L), bounded.hits.map { hit -> hit.sequence })
            }
        }

    @Test
    fun `request filtered reads stay bounded and sequence ordered`() = withJournalDirectory {
        AgentEventJournal.open(it).use { journal ->
            val first = journal.beginRun(
                "request-a",
                1,
                "a".toByteArray(),
                "text/plain",
                "a",
            )
            first.appendText(AgentJournalKind.PUBLIC_AGENT_EVENT, "a-event")
            journal.beginRun("request-b", 2, "b".toByteArray(), "text/plain", "b")
            first.appendText(AgentJournalKind.FINAL, "a-final")

            val read = journal.readLatest(
                limit = 10,
                maxScannedRecords = 10,
                requestId = "request-a",
            )
            assertEquals(listOf(1L, 2L, 4L), read.records.map { record -> record.sequence })
            assertFalse(read.truncated)

            val bounded = journal.readLatest(
                limit = 10,
                maxScannedRecords = 2,
                requestId = "request-a",
            )
            assertEquals(listOf(4L), bounded.records.map { record -> record.sequence })
            assertTrue(bounded.truncated)
        }
    }

    @Test
    fun `memory search excludes the active run to avoid self recall`() = withJournalDirectory {
        AgentEventJournal.open(it).use { journal ->
            journal.beginRun(
                requestId = "historical-run",
                runGeneration = 1,
                payload = "用户历史偏好：冷静克制".toByteArray(),
                contentType = "text/plain",
                lexicalText = "用户历史偏好 冷静克制",
            )
            val current = journal.beginRun(
                requestId = "current-run",
                runGeneration = 2,
                payload = "请搜索冷静克制".toByteArray(),
                contentType = "text/plain",
                lexicalText = "请搜索冷静克制",
            )
            current.appendText(
                AgentJournalKind.TOOL_CALL,
                """{"name":"memory_search","query":"冷静克制"}""",
            )

            val result = journal.search(
                query = "冷静克制",
                access = AgentMemorySearchAccess.ENABLED,
                excludeRequestId = "current-run",
                excludeRunGeneration = 2,
            )

            assertEquals(listOf("historical-run"), result.hits.map { hit -> hit.requestId })
            assertEquals(3, result.scannedRecords)
        }
    }

    @Test
    fun `natural Chinese memory question recalls seeded preference record`() =
        withJournalDirectory { directory ->
            AgentEventJournal.open(directory).use { journal ->
                journal.beginRun(
                    requestId = "seeded-profile",
                    runGeneration = 1,
                    payload = "用户资料：我最喜欢的颜色是海军蓝，写作风格偏好简洁直接。".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "用户资料：我最喜欢的颜色是海军蓝，写作风格偏好简洁直接。",
                )
                journal.beginRun(
                    requestId = "unrelated",
                    runGeneration = 2,
                    payload = "普通天气对话".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "普通天气对话",
                )

                val result = journal.search(
                    query = "用户偏好 喜欢什么颜色",
                    access = AgentMemorySearchAccess.ENABLED,
                )

                assertEquals(listOf("seeded-profile"), result.hits.map { it.requestId })
                assertTrue(result.hits.single().excerpt.contains("海军蓝"))
            }
        }

    @Test
    fun `deferred frames preserve exact bytes and explicit flush forces once`() =
        withJournalDirectory { directory ->
            val forceCalls = AtomicInteger()
            val firstPayload = byteArrayOf(0, 1, -1, 42, 0)
            val expectedFirstPayload = firstPayload.copyOf()

            AgentEventJournal.openForTest(directory) { channel ->
                forceCalls.incrementAndGet()
                channel.force(true)
            }.use { journal ->
                val run = journal.beginRun(
                    requestId = "request-deferred",
                    runGeneration = 1,
                    payload = "snapshot".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "snapshot",
                )
                assertEquals(1, forceCalls.get())

                assertEquals(
                    2L,
                    run.append(
                        kind = AgentJournalKind.PROVIDER_OUTPUT,
                        payload = firstPayload,
                        contentType = "application/octet-stream",
                        lexicalText = "first raw chunk",
                        durable = false,
                    ).sequence,
                )
                firstPayload.fill(99)
                assertEquals(
                    3L,
                    run.append(
                        kind = AgentJournalKind.PROVIDER_OUTPUT,
                        payload = byteArrayOf(7, 8, 9),
                        contentType = "application/octet-stream",
                        lexicalText = "second raw chunk",
                        durable = false,
                    ).sequence,
                )
                assertEquals(1, forceCalls.get())
                assertArrayEquals(
                    expectedFirstPayload,
                    journal.readLatest(limit = 3).records[1].payload,
                )

                journal.flush()
                assertEquals(2, forceCalls.get())
                journal.flush()
                assertEquals(2, forceCalls.get())
            }
            assertEquals(2, forceCalls.get())

            AgentEventJournal.open(directory).use { reopened ->
                val records = reopened.readLatest(limit = 3).records
                assertEquals((1L..3L).toList(), records.map { it.sequence })
                assertArrayEquals(expectedFirstPayload, records[1].payload)
                assertArrayEquals(byteArrayOf(7, 8, 9), records[2].payload)
            }
        }

    @Test
    fun `durable append forces all preceding deferred frames in order`() =
        withJournalDirectory { directory ->
            val forceCalls = AtomicInteger()
            AgentEventJournal.openForTest(directory) { channel ->
                forceCalls.incrementAndGet()
                channel.force(true)
            }.use { journal ->
                val run = journal.beginRun(
                    requestId = "request-boundary",
                    runGeneration = 2,
                    payload = "snapshot".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "snapshot",
                )
                run.appendText(
                    kind = AgentJournalKind.PUBLIC_AGENT_EVENT,
                    text = "progress-1",
                    durable = false,
                )
                run.appendText(
                    kind = AgentJournalKind.PREVIEW,
                    text = "preview-2",
                    durable = false,
                )
                assertEquals(1, forceCalls.get())

                run.appendText(AgentJournalKind.FINAL, "final-3")
                assertEquals(2, forceCalls.get())
                assertEquals(
                    listOf(
                        AgentJournalKind.REQUEST_INPUT_SNAPSHOT,
                        AgentJournalKind.PUBLIC_AGENT_EVENT,
                        AgentJournalKind.PREVIEW,
                        AgentJournalKind.FINAL,
                    ),
                    journal.readLatest(limit = 4).records.map { it.kind },
                )
                journal.flush()
                assertEquals(2, forceCalls.get())
            }
        }

    @Test
    fun `close flushes deferred frames and flush failure poisons until reopen`() =
        withJournalDirectory { directory ->
            val closeForceCalls = AtomicInteger()
            AgentEventJournal.openForTest(directory) { channel ->
                closeForceCalls.incrementAndGet()
                channel.force(true)
            }.use { journal ->
                journal.beginRun(
                    requestId = "request-close",
                    runGeneration = 3,
                    payload = "snapshot".toByteArray(),
                    contentType = "text/plain",
                    lexicalText = "snapshot",
                ).appendText(
                    kind = AgentJournalKind.PROVIDER_OUTPUT,
                    text = "deferred",
                    durable = false,
                )
                assertEquals(1, closeForceCalls.get())
            }
            assertEquals(2, closeForceCalls.get())

            val failureForceCalls = AtomicInteger()
            val poisoned = AgentEventJournal.openForTest(directory) { channel ->
                if (failureForceCalls.incrementAndGet() == 2) {
                    throw java.io.IOException("injected force failure")
                }
                channel.force(true)
            }
            val run = poisoned.beginRun(
                requestId = "request-poison",
                runGeneration = 4,
                payload = "snapshot".toByteArray(),
                contentType = "text/plain",
                lexicalText = "snapshot",
            )
            run.appendText(
                kind = AgentJournalKind.PROVIDER_OUTPUT,
                text = "must remain a complete frame",
                durable = false,
            )
            assertThrows(AgentJournalException::class.java) { poisoned.flush() }
            assertThrows(IllegalStateException::class.java) { poisoned.stats() }
            assertThrows(IllegalStateException::class.java) {
                run.appendText(AgentJournalKind.FINAL, "must not retry")
            }
            poisoned.close()
            assertEquals(2, failureForceCalls.get())

            AgentEventJournal.open(directory).use { reopened ->
                assertEquals(
                    listOf(
                        "request-close",
                        "request-close",
                        "request-poison",
                        "request-poison",
                    ),
                    reopened.readLatest(limit = 4).records.map { it.requestId },
                )
            }
        }

    @Test
    fun `startup removes only an incomplete physical tail`() = withJournalDirectory {
        var committedSize = 0L
        AgentEventJournal.open(it).use { journal ->
            journal.beginRun(
                "request-1",
                1,
                "snapshot".toByteArray(),
                "text/plain",
                "snapshot",
            ).appendText(AgentJournalKind.FINAL, "done")
            committedSize = journal.stats().encodedBytes
        }

        val file = File(it, AgentEventJournal.DATA_FILE_NAME)
        RandomAccessFile(file, "rw").use { random ->
            random.seek(random.length())
            random.write(byteArrayOf(0x53, 0x41, 0x4a))
        }
        assertEquals(committedSize + 3, file.length())

        AgentEventJournal.open(it).use { recovered ->
            assertEquals(committedSize, recovered.stats().encodedBytes)
            assertEquals(2L, recovered.stats().lastSequence)
            recovered.beginRun(
                "request-2",
                2,
                "next".toByteArray(),
                "text/plain",
                "next",
            )
            assertEquals(3L, recovered.stats().lastSequence)
        }
    }

    @Test
    fun `checksum corruption fails closed without deleting bytes`() = withJournalDirectory {
        AgentEventJournal.open(it).use { journal ->
            journal.beginRun(
                "request-1",
                1,
                "payload".toByteArray(),
                "text/plain",
                "searchable payload",
            )
        }
        val file = File(it, AgentEventJournal.DATA_FILE_NAME)
        val originalSize = file.length()
        RandomAccessFile(file, "rw").use { random ->
            random.seek(originalSize - 1)
            val original = random.read()
            random.seek(originalSize - 1)
            random.write(original xor 0xff)
        }

        assertThrows(AgentJournalCorruptionException::class.java) {
            AgentEventJournal.open(it)
        }
        assertEquals(originalSize, file.length())
    }

    @Test
    fun `only one writer can own the journal`() = withJournalDirectory { directory ->
        AgentEventJournal.open(directory).use {
            assertThrows(AgentJournalInUseException::class.java) {
                AgentEventJournal.open(directory)
            }
        }
        AgentEventJournal.open(directory).use { reopened ->
            assertEquals(0L, reopened.stats().records)
        }
    }

    @Test
    fun `all required retention channels have stable wire identities`() {
        assertEquals(
            mapOf(
                AgentJournalKind.REQUEST_INPUT_SNAPSHOT to 1,
                AgentJournalKind.PROVIDER_INPUT to 2,
                AgentJournalKind.PUBLIC_AGENT_EVENT to 3,
                AgentJournalKind.PRIVATE_AGENT_EVENT to 4,
                AgentJournalKind.PROVIDER_OUTPUT to 5,
                AgentJournalKind.TOOL_CALL to 6,
                AgentJournalKind.TOOL_RESULT to 7,
                AgentJournalKind.PREVIEW to 8,
                AgentJournalKind.FINAL to 9,
                AgentJournalKind.ERROR to 10,
                AgentJournalKind.CANCELLED to 11,
            ),
            AgentJournalKind.entries.associateWith { kind -> kind.wireId },
        )
    }

    private fun withJournalDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("sense-agent-journal-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
