package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.measureNanoTime
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentSkillStoreTest {
    private lateinit var root: File
    private lateinit var repository: AgentSkillRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sense-agent-skills-").toFile()
        repository = AgentSkillRepository(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `first load durably seeds six built-ins and generation one`() {
        val catalog = repository.loadCatalog().getOrThrow()

        assertEquals(1L, catalog.generation)
        assertEquals(6, catalog.definitions.size)
        assertEquals(6, catalog.bindings.size)
        assertEquals(listOf(1L), repository.listCatalogGenerations().getOrThrow())
        catalog.definitions.forEach { definition ->
            assertEquals(listOf(1L), repository.listRevisions(definition.id).getOrThrow())
            assertEquals(
                definition,
                repository.readRevision(definition.id, 1L).getOrThrow(),
            )
        }
        assertEquals(catalog, repository.readCatalogGeneration(1L).getOrThrow())
        assertEquals("1", File(root, "CURRENT").readText().trim())
        assertEquals("1", highWaterFile().readText().trim())
    }

    @Test
    fun `unicode multiline codec round trips without delimiter ambiguity`() {
        val created = repository.apply(
            AgentSkillMutation.Create(
                id = "bilingual",
                name = "中英双语 = :",
                description = "保留换行、符号与 emoji 🌌",
                content = "# 文档\n\n键=值: value\n\t第二行仍保留 🌌",
                baseIntent = EditorIntent.REWRITE,
            ),
        ).getOrThrow().definition("bilingual")

        assertNotNull(created)
        assertEquals(
            created,
            repository.readRevision("bilingual", 1L).getOrThrow(),
        )
        assertEquals(
            created,
            AgentSkillRevisionCodec.decode(
                AgentSkillRevisionCodec.encode(requireNotNull(created)).toByteArray(),
            ),
        )
    }

    @Test
    fun `updates retain every document and catalog generation`() {
        repository.loadCatalog().getOrThrow()
        val second = repository.apply(
            AgentSkillMutation.Update("rewrite", content = "# v2\n更简洁。"),
        ).getOrThrow()
        val third = repository.apply(
            AgentSkillMutation.Update("rewrite", content = "# v3\n更正式。"),
        ).getOrThrow()

        assertEquals(listOf(1L, 2L, 3L), repository.listRevisions("rewrite").getOrThrow())
        assertEquals(listOf(1L, 2L, 3L), repository.listCatalogGenerations().getOrThrow())
        assertEquals("# v2\n更简洁。", repository.readRevision("rewrite", 2L).getOrThrow()?.content)
        assertEquals("# v3\n更正式。", repository.readRevision("rewrite", 3L).getOrThrow()?.content)
        assertEquals(2L, second.generation)
        assertEquals(3L, third.generation)
        assertEquals("3", highWaterFile().readText().trim())
        assertEquals(1L, repository.readCatalogGeneration(1L).getOrThrow()?.definition("rewrite")?.revision)
        assertEquals(2L, repository.readCatalogGeneration(2L).getOrThrow()?.definition("rewrite")?.revision)
    }

    @Test
    fun `bindings and active selection persist across repository instances`() {
        val slot = AgentSkillSlot('x'.code, AgentSkillDirection.LEFT)
        repository.loadCatalog().getOrThrow()
        repository.apply(AgentSkillMutation.Bind("translate", slot)).getOrThrow()
        val activated = repository.apply(AgentSkillMutation.ToggleActive(slot)).getOrThrow()

        val reopened = AgentSkillRepository(root).loadCatalog().getOrThrow()

        assertEquals(activated, reopened)
        assertEquals("translate", reopened.activeDefinition()?.id)
        assertEquals(slot, reopened.active?.slot)
    }

    @Test
    fun `repeated seed load is idempotent and never resets user binding or active state`() {
        val slot = AgentSkillSlot('x'.code, AgentSkillDirection.DOWN)
        repository.loadCatalog().getOrThrow()
        repository.apply(AgentSkillMutation.Bind("format", slot)).getOrThrow()
        val expected = repository.apply(AgentSkillMutation.ToggleActive(slot)).getOrThrow()
        val revisionBefore = revisionFile("format", 1L).readBytes()
        val catalogBefore = catalogFile(expected.generation).readBytes()

        repeat(4) {
            assertEquals(expected, AgentSkillRepository(root).loadCatalog().getOrThrow())
        }

        assertArrayEquals(revisionBefore, revisionFile("format", 1L).readBytes())
        assertArrayEquals(catalogBefore, catalogFile(expected.generation).readBytes())
        assertEquals((1L..expected.generation).toList(), repository.listCatalogGenerations().getOrThrow())
        assertEquals("format", repository.loadCatalog().getOrThrow().active?.skillId)
    }

    @Test
    fun `corrupt CURRENT recovers latest valid catalog and repairs pointer`() {
        repository.loadCatalog().getOrThrow()
        val latest = repository.apply(
            AgentSkillMutation.Update("format", description = "新的格式描述"),
        ).getOrThrow()
        File(root, "CURRENT").writeText("not-a-generation")
        var catalogScans = 0

        val recovered = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        ).loadCatalog().getOrThrow()

        assertEquals(latest, recovered)
        assertEquals(0, catalogScans)
        assertEquals("${latest.generation}", File(root, "CURRENT").readText().trim())
    }

    @Test
    fun `complete catalog newer than CURRENT is recovered instead of orphaned`() {
        repository.loadCatalog().getOrThrow()
        val latest = repository.apply(
            AgentSkillMutation.Update("rewrite", content = "# 已完整落盘但指针尚未更新"),
        ).getOrThrow()
        File(root, "CURRENT").writeText("1\n")
        var catalogScans = 0

        val recovered = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        ).loadCatalog().getOrThrow()

        assertEquals(latest, recovered)
        assertEquals(0, catalogScans)
        assertEquals(latest.generation.toString(), File(root, "CURRENT").readText().trim())
        assertEquals(
            "# 已完整落盘但指针尚未更新",
            recovered.definition("rewrite")?.content,
        )
    }

    @Test
    fun `crash injection preserves or recovers every immutable commit phase`() {
        AgentSkillCommitPoint.entries.forEach { point ->
            val phaseRoot = File(root, point.name.lowercase())
            AgentSkillRepository(phaseRoot).loadCatalog().getOrThrow()
            var injected = false
            val faulting = AgentSkillRepository(phaseRoot) { reached ->
                if (!injected && reached == point) {
                    injected = true
                    throw SimulatedCrash(point)
                }
            }

            val result = faulting.apply(
                AgentSkillMutation.Update(
                    id = "answer",
                    content = "# committed at ${point.name}",
                ),
            )

            assertTrue("Expected injected failure at $point", result.isFailure)
            val reopened = AgentSkillRepository(phaseRoot)
            val recovered = reopened.loadCatalog().getOrThrow()
            assertEquals("2", File(phaseRoot, "HIGH_WATER").readText().trim())
            if (point == AgentSkillCommitPoint.AFTER_GENERATION_RESERVED) {
                assertEquals(listOf(1L), reopened.listRevisions("answer").getOrThrow())
                assertEquals(1L, recovered.generation)
                assertEquals(1L, recovered.definition("answer")?.revision)
                assertFalse(File(File(phaseRoot, "catalogs"), "2.catalog").exists())
            } else if (point == AgentSkillCommitPoint.AFTER_REVISION) {
                assertEquals(listOf(1L, 2L), reopened.listRevisions("answer").getOrThrow())
                assertEquals(1L, recovered.generation)
                assertEquals(1L, recovered.definition("answer")?.revision)
                assertEquals(
                    "# committed at ${point.name}",
                    reopened.readRevision("answer", 2L).getOrThrow()?.content,
                )
            } else {
                assertEquals(listOf(1L, 2L), reopened.listRevisions("answer").getOrThrow())
                assertEquals(2L, recovered.generation)
                assertEquals(2L, recovered.definition("answer")?.revision)
                assertEquals(
                    "# committed at ${point.name}",
                    recovered.definition("answer")?.content,
                )
                assertEquals("2", File(phaseRoot, "CURRENT").readText().trim())
            }
        }
    }

    @Test
    fun `initialization reserves generation before every durable commit phase`() {
        AgentSkillCommitPoint.entries.forEach { point ->
            val phaseRoot = File(root, "initialize_${point.name.lowercase()}")
            var injected = false
            val faulting = AgentSkillRepository(phaseRoot) { reached ->
                if (!injected && reached == point) {
                    injected = true
                    throw SimulatedCrash(point)
                }
            }

            assertTrue(faulting.loadCatalog().isFailure)
            assertEquals("1", File(phaseRoot, "HIGH_WATER").readText().trim())

            val reopened = AgentSkillRepository(phaseRoot)
            val recovered = reopened.loadCatalog().getOrThrow()
            if (
                point == AgentSkillCommitPoint.AFTER_GENERATION_RESERVED ||
                point == AgentSkillCommitPoint.AFTER_REVISION
            ) {
                assertEquals(2L, recovered.generation)
                assertFalse(File(File(phaseRoot, "catalogs"), "1.catalog").exists())
                assertEquals("2", File(phaseRoot, "HIGH_WATER").readText().trim())
            } else {
                assertEquals(1L, recovered.generation)
                assertEquals("1", File(phaseRoot, "HIGH_WATER").readText().trim())
            }
            assertEquals(6, recovered.definitions.size)
            recovered.definitions.forEach { definition ->
                assertEquals(
                    listOf(1L),
                    reopened.listRevisions(definition.id).getOrThrow(),
                )
            }
        }
    }

    @Test
    fun `reservation hole is retained and next mutation skips it without catalog scan`() {
        val current = repository.loadCatalog().getOrThrow()
        highWaterFile().writeText("2\n")
        var catalogScans = 0
        val reopened = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )

        assertEquals(current, reopened.loadCatalog().getOrThrow())
        val updated = reopened.apply(
            AgentSkillMutation.Update("answer", content = "# generation three"),
        ).getOrThrow()

        assertEquals(3L, updated.generation)
        assertEquals("3", highWaterFile().readText().trim())
        assertFalse(catalogFile(2L).exists())
        assertTrue(catalogFile(3L).isFile)
        assertEquals(0, catalogScans)
    }

    @Test
    fun `legacy store without HIGH_WATER scans once and rebuilds the auxiliary pointer`() {
        val latest = repository.apply(
            AgentSkillMutation.Update("format", description = "legacy"),
        ).getOrThrow()
        assertTrue(highWaterFile().delete())
        var catalogScans = 0
        val migrated = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )

        assertEquals(latest, migrated.loadCatalog().getOrThrow())
        assertEquals(1, catalogScans)
        assertEquals("2", highWaterFile().readText().trim())

        assertEquals(latest, migrated.loadCatalog().getOrThrow())
        assertEquals(1, catalogScans)
    }

    @Test
    fun `malformed HIGH_WATER scans once repairs it and retains all catalog bytes`() {
        repository.loadCatalog().getOrThrow()
        val latest = repository.apply(
            AgentSkillMutation.Update("rewrite", content = "# immutable"),
        ).getOrThrow()
        val firstBytes = catalogFile(1L).readBytes()
        val latestBytes = catalogFile(2L).readBytes()
        highWaterFile().writeText("not-a-generation")
        var catalogScans = 0
        val reopened = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )

        assertEquals(latest, reopened.loadCatalog().getOrThrow())
        assertEquals(1, catalogScans)
        assertEquals("2", highWaterFile().readText().trim())
        assertArrayEquals(firstBytes, catalogFile(1L).readBytes())
        assertArrayEquals(latestBytes, catalogFile(2L).readBytes())
    }

    @Test
    fun `HIGH_WATER behind CURRENT triggers one repair scan`() {
        repository.loadCatalog().getOrThrow()
        val latest = repository.apply(
            AgentSkillMutation.Update("continue", content = "# current two"),
        ).getOrThrow()
        highWaterFile().writeText("1\n")
        var catalogScans = 0
        val reopened = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )

        assertEquals(latest, reopened.loadCatalog().getOrThrow())
        assertEquals(1, catalogScans)
        assertEquals("2", highWaterFile().readText().trim())
    }

    @Test
    fun `corrupt CURRENT ahead of HIGH_WATER scans and preserves the highest observed frontier`() {
        repository.loadCatalog().getOrThrow()
        val second = repository.apply(
            AgentSkillMutation.Update("continue", content = "# generation two remains reachable"),
        ).getOrThrow()
        repository.apply(
            AgentSkillMutation.Update("continue", content = "# generation three is corrupt"),
        ).getOrThrow()
        val catalogOneBytes = catalogFile(1L).readBytes()
        val catalogTwoBytes = catalogFile(2L).readBytes()
        val corruptCatalogThreeBytes = "corrupt-current-generation-three".toByteArray()
        catalogFile(3L).writeBytes(corruptCatalogThreeBytes)
        highWaterFile().writeText("1\n")
        var catalogScans = 0
        val reopened = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )

        val recovered = reopened.loadCatalog().getOrThrow()

        assertEquals(second, recovered)
        assertEquals(1, catalogScans)
        assertEquals("2", File(root, "CURRENT").readText().trim())
        assertEquals("3", highWaterFile().readText().trim())
        assertArrayEquals(catalogOneBytes, catalogFile(1L).readBytes())
        assertArrayEquals(catalogTwoBytes, catalogFile(2L).readBytes())
        assertArrayEquals(corruptCatalogThreeBytes, catalogFile(3L).readBytes())

        val next = reopened.apply(
            AgentSkillMutation.Update("continue", content = "# generation four"),
        ).getOrThrow()
        assertEquals(4L, next.generation)
        assertEquals("4", File(root, "CURRENT").readText().trim())
        assertEquals("4", highWaterFile().readText().trim())
        assertArrayEquals(corruptCatalogThreeBytes, catalogFile(3L).readBytes())
    }

    @Test
    fun `ten thousand catalog cold load and reservation use exact pointers without directory scan`() {
        val base = repository.loadCatalog().getOrThrow()
        val catalogs = File(root, "catalogs")
        for (generation in 2L..10_000L) {
            File(catalogs, "$generation.catalog").writeText(
                AgentSkillCatalogCodec.encode(
                    io.github.ethanbird.senseime.brain.api.AgentSkillCatalog(
                        generation = generation,
                        definitions = base.definitions,
                        bindings = base.bindings,
                        active = base.active,
                    ),
                ),
            )
        }
        File(root, "CURRENT").writeText("10000\n")
        highWaterFile().writeText("10000\n")
        var catalogScans = 0
        val cold = AgentSkillRepository(
            root = root,
            directoryScanObserver = {
                if (it == AgentSkillDirectoryScan.CATALOGS) catalogScans += 1
            },
        )
        lateinit var loaded: io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
        lateinit var committed: io.github.ethanbird.senseime.brain.api.AgentSkillCatalog

        val elapsedNanos = measureNanoTime {
            loaded = cold.loadCatalog().getOrThrow()
        }
        val mutationNanos = measureNanoTime {
            committed = cold.apply(
                AgentSkillMutation.Bind(
                    "answer",
                    AgentSkillSlot('q'.code, AgentSkillDirection.RIGHT),
                ),
            ).getOrThrow()
        }

        assertEquals(10_000L, loaded.generation)
        assertEquals(10_001L, committed.generation)
        assertEquals("10001", highWaterFile().readText().trim())
        assertEquals(0, catalogScans)
        assertTrue(
            "Exact-pointer cold load took ${elapsedNanos / 1_000_000L} ms",
            elapsedNanos < 2_000_000_000L,
        )
        assertTrue(
            "Exact-pointer reservation took ${mutationNanos / 1_000_000L} ms",
            mutationNanos < 5_000_000_000L,
        )
    }

    @Test
    fun `corrupt newest catalog falls back without deleting its revisions`() {
        repository.loadCatalog().getOrThrow()
        repository.apply(
            AgentSkillMutation.Update("continue", content = "# orphaned revision 2"),
        ).getOrThrow()
        val revisionBytes = revisionFile("continue", 2L).readBytes()
        catalogFile(2L).writeText("corrupt")

        val recovered = AgentSkillRepository(root).loadCatalog().getOrThrow()

        assertEquals(1L, recovered.generation)
        assertEquals(1L, recovered.definition("continue")?.revision)
        assertArrayEquals(revisionBytes, revisionFile("continue", 2L).readBytes())
        assertEquals(listOf(1L, 2L), repository.listRevisions("continue").getOrThrow())
    }

    @Test
    fun `conflicting orphan revision is preserved and skipped`() {
        repository.loadCatalog().getOrThrow()
        val orphan = revisionFile("rewrite", 2L)
        orphan.parentFile.mkdirs()
        val corruptBytes = "unfinished-revision".toByteArray()
        orphan.writeBytes(corruptBytes)

        val updated = repository.apply(
            AgentSkillMutation.Update("rewrite", content = "# accepted update"),
        ).getOrThrow()

        assertEquals(3L, updated.definition("rewrite")?.revision)
        assertArrayEquals(corruptBytes, orphan.readBytes())
        assertEquals(listOf(1L, 2L, 3L), repository.listRevisions("rewrite").getOrThrow())
        assertEquals("# accepted update", repository.readRevision("rewrite", 3L).getOrThrow()?.content)
    }

    @Test
    fun `orphan catalog generation is preserved and skipped`() {
        repository.loadCatalog().getOrThrow()
        val orphan = catalogFile(2L)
        orphan.writeText("unfinished-catalog")

        val updated = repository.apply(
            AgentSkillMutation.Bind(
                "answer",
                AgentSkillSlot('q'.code, AgentSkillDirection.RIGHT),
            ),
        ).getOrThrow()

        assertEquals(3L, updated.generation)
        assertEquals("3", highWaterFile().readText().trim())
        assertEquals("unfinished-catalog", orphan.readText())
        assertTrue(catalogFile(3L).isFile)
    }

    @Test
    fun `all corrupt catalogs reinitialize built-ins at a fresh generation without deletion`() {
        repository.loadCatalog().getOrThrow()
        val originalRevision = revisionFile("answer", 1L).readBytes()
        catalogFile(1L).writeText("broken")
        File(root, "CURRENT").writeText("1")

        val recovered = repository.loadCatalog().getOrThrow()

        assertEquals(2L, recovered.generation)
        assertEquals(6, recovered.definitions.size)
        assertArrayEquals(originalRevision, revisionFile("answer", 1L).readBytes())
        assertEquals("broken", catalogFile(1L).readText())
        assertTrue(catalogFile(2L).isFile)
    }

    @Test
    fun `last resort recovery restores newest valid custom documents`() {
        repository.loadCatalog().getOrThrow()
        repository.apply(
            AgentSkillMutation.Create(
                id = "user_owned",
                name = "用户 Skill",
                description = "必须在目录损坏恢复后继续可见。",
                content = "# v1",
            ),
        ).getOrThrow()
        repository.apply(
            AgentSkillMutation.Update("user_owned", content = "# v2 retained"),
        ).getOrThrow()
        catalogFile(1L).writeText("broken-1")
        catalogFile(2L).writeText("broken-2")
        catalogFile(3L).writeText("broken-3")
        File(root, "CURRENT").writeText("broken")

        val recovered = repository.loadCatalog().getOrThrow()

        assertEquals(4L, recovered.generation)
        assertEquals(2L, recovered.definition("user_owned")?.revision)
        assertEquals("# v2 retained", recovered.definition("user_owned")?.content)
        assertEquals(listOf(1L, 2L), repository.listRevisions("user_owned").getOrThrow())
        assertEquals("broken-3", catalogFile(3L).readText())
    }

    @Test
    fun `stale generation mutation fails without publishing history`() {
        val catalog = repository.loadCatalog().getOrThrow()

        val failure = repository.apply(
            AgentSkillMutation.Update(
                id = "answer",
                content = "new",
                expectedGeneration = catalog.generation + 1L,
            ),
        )

        assertTrue(failure.isFailure)
        assertEquals(listOf(1L), repository.listCatalogGenerations().getOrThrow())
        assertEquals(listOf(1L), repository.listRevisions("answer").getOrThrow())
    }

    @Test
    fun `keyboard activation retains a Settings commit made after picker projection`() {
        val projected = repository.loadCatalog().getOrThrow()
        val selected = projected.bindings.first()

        // Settings commits after the picker has frozen its generation and explicit ACTIVATE intent.
        val settingsCommit = AgentSkillRepository(root).apply(
            AgentSkillMutation.Update(
                id = "answer",
                description = "Settings 在选择与执行之间提交的描述",
                expectedGeneration = projected.generation,
            ),
        ).getOrThrow()

        val selectedCatalog = repository.applySelectionIntent(
            slot = selected.slot,
            selectedSkillId = selected.skillId,
            activate = true,
        ).getOrThrow()

        assertEquals(settingsCommit.generation + 1L, selectedCatalog.generation)
        assertEquals(
            "Settings 在选择与执行之间提交的描述",
            selectedCatalog.definition("answer")?.description,
        )
        assertEquals(selected.skillId, selectedCatalog.active?.skillId)
        assertEquals(selected.slot, selectedCatalog.active?.slot)
        assertEquals(
            selectedCatalog.generation.toString(),
            highWaterFile().readText().trim(),
        )
        assertEquals(
            (1L..selectedCatalog.generation).toList(),
            repository.listCatalogGenerations().getOrThrow(),
        )
    }

    @Test
    fun `keyboard intent wins deterministically after skill manage changes activation`() {
        val projected = repository.loadCatalog().getOrThrow()
        val selected = projected.bindings.first()
        val other = projected.bindings.first { it.skillId != selected.skillId }

        // skill_manage activates another Skill after the user has selected ACTIVATE.
        val toolCommit = AgentSkillRepository(root).apply(
            AgentSkillMutation.ToggleActive(
                slot = other.slot,
                expectedGeneration = projected.generation,
            ),
        ).getOrThrow()
        assertEquals(other.skillId, toolCommit.active?.skillId)

        val selectedCatalog = repository.applySelectionIntent(
            slot = selected.slot,
            selectedSkillId = selected.skillId,
            activate = true,
        ).getOrThrow()
        assertEquals(selected.skillId, selectedCatalog.active?.skillId)
        assertEquals(selected.slot, selectedCatalog.active?.slot)

        // A repeated picker selection captures DEACTIVATE. A later document update must survive,
        // while the explicit toggle-off intent still clears only this selected Skill.
        val updated = AgentSkillRepository(root).apply(
            AgentSkillMutation.Update(
                id = selected.skillId,
                description = "工具更新后仍完整保留",
                expectedGeneration = selectedCatalog.generation,
            ),
        ).getOrThrow()
        val deactivated = repository.applySelectionIntent(
            slot = selected.slot,
            selectedSkillId = selected.skillId,
            activate = false,
        ).getOrThrow()

        assertNull(deactivated.active)
        assertEquals("工具更新后仍完整保留", deactivated.definition(selected.skillId)?.description)
        assertEquals(updated.generation + 1L, deactivated.generation)
    }

    @Test
    fun `stale picker cannot activate a replacement binding and publishes no history`() {
        val projected = repository.loadCatalog().getOrThrow()
        val selected = projected.bindings.first()
        val replacement = projected.bindings.first { it.skillId != selected.skillId }
        val rebound = repository.apply(
            AgentSkillMutation.Bind(
                skillId = replacement.skillId,
                slot = selected.slot,
                expectedGeneration = projected.generation,
            ),
        ).getOrThrow()

        val result = repository.applySelectionIntent(
            slot = selected.slot,
            selectedSkillId = selected.skillId,
            activate = true,
        )

        assertTrue(result.isFailure)
        assertEquals(rebound, repository.loadCatalog().getOrThrow())
        assertEquals(
            (1L..rebound.generation).toList(),
            repository.listCatalogGenerations().getOrThrow(),
        )
    }

    @Test
    fun `parallel creates serialize through one catalog chain`() {
        repository.loadCatalog().getOrThrow()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 12).map { index ->
                executor.submit(
                    Callable {
                        AgentSkillRepository(root).apply(
                            AgentSkillMutation.Create(
                                id = "parallel_$index",
                                name = "Parallel $index",
                                description = "Parallel skill $index",
                                content = "# Parallel $index",
                            ),
                        ).getOrThrow()
                    },
                )
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val final = repository.loadCatalog().getOrThrow()
        assertEquals(13L, final.generation)
        assertEquals("13", highWaterFile().readText().trim())
        assertEquals(18, final.definitions.size)
        assertEquals((1L..13L).toList(), repository.listCatalogGenerations().getOrThrow())
    }

    @Test
    fun `separate JVM processes serialize through the operating system file lock`() {
        repository.loadCatalog().getOrThrow()
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin${File.separator}java",
        ).absolutePath
        val classPath = System.getProperty("java.class.path")
        val workerClass = AgentSkillProcessWorker::class.java.name
        val processes = listOf("process_a", "process_b").map { prefix ->
            ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                workerClass,
                root.absolutePath,
                prefix,
                "6",
            ).redirectErrorStream(true).start()
        }

        processes.forEach { process ->
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("Worker failed: $output", 0, process.waitFor())
        }

        val final = AgentSkillRepository(root).loadCatalog().getOrThrow()
        assertEquals(13L, final.generation)
        assertEquals("13", highWaterFile().readText().trim())
        assertEquals(18, final.definitions.size)
        assertEquals((1L..13L).toList(), repository.listCatalogGenerations().getOrThrow())
    }

    @Test
    fun `explicit history reads reject traversal malformed and oversized files`() {
        repository.loadCatalog().getOrThrow()

        assertTrue(repository.readRevision("../escape", 1L).isFailure)
        assertTrue(repository.readRevision("answer", 0L).isFailure)
        assertNull(repository.readRevision("answer", 99L).getOrThrow())
        assertNull(repository.readCatalogGeneration(99L).getOrThrow())

        val oversized = revisionFile("answer", 2L)
        oversized.writeBytes(ByteArray(AgentSkillRepository.MAX_REVISION_BYTES.toInt() + 1))
        assertTrue(repository.readRevision("answer", 2L).isFailure)
    }

    @Test
    fun `explicit history read rejects filename and document identity mismatch`() {
        val catalog = repository.loadCatalog().getOrThrow()
        val mismatched = requireNotNull(catalog.definition("rewrite")).copy(revision = 2L)
        val destination = revisionFile("answer", 2L)
        destination.parentFile.mkdirs()
        destination.writeText(AgentSkillRevisionCodec.encode(mismatched))

        assertTrue(repository.readRevision("answer", 2L).isFailure)
    }

    @Test
    fun `maximum CJK Skill content survives durable encoding and reload`() {
        repository.loadCatalog().getOrThrow()
        val content = "中".repeat(
            io.github.ethanbird.senseime.brain.api.AgentSkillPolicy.MAX_CONTENT_CHARS,
        )

        val saved = repository.apply(
            AgentSkillMutation.Update("answer", content = content),
        ).getOrThrow()
        val revision = requireNotNull(saved.definition("answer")).revision

        assertTrue(revisionFile("answer", revision).length() <= AgentSkillRepository.MAX_REVISION_BYTES)
        assertEquals(
            content,
            AgentSkillRepository(root).loadCatalog().getOrThrow()
                .definition("answer")
                ?.content,
        )
    }

    @Test
    fun `codecs reject unknown duplicated incomplete and invalid UTF8 fields`() {
        val catalogDocument = catalogFileAfterLoad()
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogCodec.decode(catalogDocument + "future=x\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogCodec.decode(catalogDocument + "generation=2\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillCatalogCodec.decode("schema_version=1\ngeneration=1\nactive=none\n".toByteArray())
        }
        assertThrows(Exception::class.java) {
            AgentSkillRevisionCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun `unbind and clear active retain documents while publishing state transitions`() {
        repository.loadCatalog().getOrThrow()
        val answerSlot = AgentSkillSlot('a'.code, AgentSkillDirection.UP)
        repository.apply(AgentSkillMutation.ToggleActive(answerSlot)).getOrThrow()
        val cleared = repository.apply(AgentSkillMutation.ClearActive()).getOrThrow()
        val unbound = repository.apply(AgentSkillMutation.UnbindSkill("answer")).getOrThrow()

        assertNull(cleared.active)
        assertFalse(unbound.bindings.any { it.skillId == "answer" })
        assertEquals(listOf(1L), repository.listRevisions("answer").getOrThrow())
        assertEquals(listOf(1L, 2L, 3L, 4L), repository.listCatalogGenerations().getOrThrow())
    }

    private fun catalogFileAfterLoad(): ByteArray {
        repository.loadCatalog().getOrThrow()
        return catalogFile(1L).readBytes()
    }

    private fun revisionFile(skillId: String, revision: Long): File =
        File(File(File(root, "documents"), skillId), "$revision.skill")

    private fun catalogFile(generation: Long): File =
        File(File(root, "catalogs"), "$generation.catalog")

    private fun highWaterFile(): File = File(root, "HIGH_WATER")

    private class SimulatedCrash(point: AgentSkillCommitPoint) :
        RuntimeException("simulated crash at $point")
}
