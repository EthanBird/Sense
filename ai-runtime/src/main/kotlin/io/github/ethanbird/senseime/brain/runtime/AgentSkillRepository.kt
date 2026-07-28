package io.github.ethanbird.senseime.brain.runtime

import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentBuiltInSkills
import io.github.ethanbird.senseime.brain.api.AgentSkillActivation
import io.github.ethanbird.senseime.brain.api.AgentSkillBinding
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalogReducer
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/**
 * Filesystem implementation kept free of Android APIs so persistence, recovery, and corruption
 * behavior can be tested on the JVM.
 *
 * Layout:
 *
 * `documents/<skill-id>/<revision>.skill` — immutable Skill revisions
 * `catalogs/<generation>.catalog` — immutable complete catalog snapshots
 * `CURRENT` — atomically replaced pointer to the visible generation
 * `HIGH_WATER` — atomically replaced, monotonic reservation frontier
 * `store.lock` — cross-process serialization
 *
 * A generation is durably reserved in HIGH_WATER before its revision or catalog is written.
 * Documents and catalogs are never overwritten or removed. If the process dies before CURRENT
 * moves, the orphan or reservation hole is retained and future writes skip its generation.
 */
internal class AgentSkillRepository(
    private val root: File,
    private val directoryScanObserver: (AgentSkillDirectoryScan) -> Unit = {},
    private val commitObserver: (AgentSkillCommitPoint) -> Unit = {},
) {
    private val documentsDirectory = File(root, DOCUMENTS_DIRECTORY)
    private val catalogsDirectory = File(root, CATALOGS_DIRECTORY)
    private val currentFile = File(root, CURRENT_FILE)
    private val highWaterFile = File(root, HIGH_WATER_FILE)
    private val lockFile = File(root, LOCK_FILE)

    fun loadCatalog(): Result<AgentSkillCatalog> = runCatching {
        withStoreLock { loadOrRecoverCatalogLocked() }
    }

    fun apply(mutation: AgentSkillMutation): Result<AgentSkillCatalog> = runCatching {
        withStoreLock {
            val current = loadOrRecoverCatalogLocked()
            commitReducedLocked(AgentSkillCatalogReducer.apply(current, mutation))
        }
    }

    /**
     * Linearizes the keyboard's explicit activate/deactivate intent with Settings and Agent tool
     * mutations under the repository's cross-process lock.
     *
     * This intentionally does not accept an `expectedGeneration`: the picker already captured a
     * user intent from an older immutable projection. A concurrent document edit must be retained,
     * not turn the selection into a stale-generation failure. Activation still requires the exact
     * selected Skill to remain bound to the selected slot, so an old picker can never activate a
     * replacement binding. Deactivation targets the selected Skill identity and therefore remains
     * valid even when Settings moves or removes its source binding before this operation acquires
     * the lock.
     */
    fun applySelectionIntent(
        slot: AgentSkillSlot,
        selectedSkillId: String,
        activate: Boolean,
    ): Result<AgentSkillCatalog> = runCatching {
        withStoreLock {
            AgentSkillPolicy.requireValidId(selectedSkillId)
            val current = loadOrRecoverCatalogLocked()
            val desiredActive = if (activate) {
                val binding = current.binding(slot)
                require(binding?.skillId == selectedSkillId) {
                    "Selected Skill binding changed before activation"
                }
                AgentSkillActivation(slot, selectedSkillId)
            } else {
                current.active?.takeUnless { it.skillId == selectedSkillId }
            }
            if (desiredActive == current.active) {
                current
            } else {
                require(current.generation < Long.MAX_VALUE) {
                    "Skill catalog generation exhausted"
                }
                commitReducedLocked(
                    AgentSkillCatalogReducer.Result(
                        catalog = AgentSkillCatalog(
                            generation = current.generation + 1L,
                            definitions = current.definitions,
                            bindings = current.bindings,
                            active = desiredActive,
                        ),
                    ),
                )
            }
        }
    }

    fun readRevision(skillId: String, revision: Long): Result<AgentSkillDefinition?> = runCatching {
        withStoreLock {
            requireValidHistoryCoordinates(skillId, revision)
            val file = revisionFile(skillId, revision)
            if (!file.isFile) {
                null
            } else {
                AgentSkillRevisionCodec.decode(readLimited(file, MAX_REVISION_BYTES)).also {
                    require(it.id == skillId && it.revision == revision) {
                        "Skill revision filename/content mismatch"
                    }
                }
            }
        }
    }

    fun readCatalogGeneration(generation: Long): Result<AgentSkillCatalog?> = runCatching {
        withStoreLock {
            require(generation > 0L) { "Skill catalog generation must be positive" }
            val file = catalogFile(generation)
            if (!file.isFile) null else readCatalogLocked(generation)
        }
    }

    fun listRevisions(skillId: String): Result<List<Long>> = runCatching {
        withStoreLock {
            io.github.ethanbird.senseime.brain.api.AgentSkillPolicy.requireValidId(skillId)
            revisionNumbersLocked(skillId)
        }
    }

    fun listCatalogGenerations(): Result<List<Long>> = runCatching {
        withStoreLock { catalogGenerationsLocked() }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        ensureDirectories()
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun ensureDirectories() {
        listOf(root, documentsDirectory, catalogsDirectory).forEach { directory ->
            if (!directory.exists()) {
                if (!directory.mkdirs() && !directory.isDirectory) {
                    error("Unable to create Skill store directory: ${directory.name}")
                }
                directory.parentFile?.takeIf(File::isDirectory)?.let(::syncDirectory)
            }
            require(directory.isDirectory) { "Skill store path is not a directory: ${directory.name}" }
        }
    }

    private fun loadOrRecoverCatalogLocked(): AgentSkillCatalog {
        val currentGeneration = readCurrentGenerationOrNull()
        val current = currentGeneration?.let { generation ->
            runCatching { readCatalogLocked(generation) }.getOrNull()
        }
        val highWater = readHighWaterGenerationOrNull()

        /*
         * CURRENT == HIGH_WATER is the normal cold-load path: read exactly one catalog and its
         * referenced revisions. A crash after reservation leaves HIGH_WATER > CURRENT. Probe only
         * that exact generation: a complete catalog is rolled forward, while an absent/corrupt
         * catalog is a retained hole and CURRENT remains authoritative. Neither path lists or
         * sorts the history directory.
         */
        val pointersAreOrdered =
            currentGeneration == null || highWater == null || currentGeneration <= highWater
        if (highWater != null && pointersAreOrdered) {
            if (current != null && highWater == current.generation) {
                return current
            }
            if (current == null || highWater > current.generation) {
                runCatching { readCatalogLocked(highWater) }.getOrNull()?.let { recovered ->
                    if (currentGeneration != recovered.generation) {
                        writeCurrentLocked(recovered.generation)
                    }
                    return recovered
                }
                if (current != null) {
                    return current
                }
            }
        }

        return recoverBySingleCatalogScanLocked(
            current = current,
            observedCurrentGeneration = currentGeneration,
            observedHighWater = highWater,
        )
    }

    /**
     * Migration/corruption fallback. Missing or inconsistent auxiliary pointers cause exactly one
     * complete catalog-directory scan. The immutable history is never rewritten, compacted, or
     * deleted; only HIGH_WATER and CURRENT are rebuilt.
     */
    private fun recoverBySingleCatalogScanLocked(
        current: AgentSkillCatalog?,
        observedCurrentGeneration: Long?,
        observedHighWater: Long?,
    ): AgentSkillCatalog {
        val generations = catalogGenerationsLocked()
        val maximumKnownGeneration = listOfNotNull(
            generations.maxOrNull(),
            current?.generation,
            observedCurrentGeneration,
            observedHighWater,
        ).maxOrNull()
        if (maximumKnownGeneration != null) {
            writeHighWaterLocked(maximumKnownGeneration)
        }
        var recovered = current
        for (generation in generations.asReversed()) {
            val alreadyRecovered = recovered
            if (alreadyRecovered != null && generation <= alreadyRecovered.generation) {
                break
            }
            val candidate = runCatching { readCatalogLocked(generation) }.getOrNull()
            if (candidate != null) {
                // Descending traversal makes the first valid generation the newest one.
                recovered = candidate
                break
            }
        }
        if (recovered != null) {
            writeCurrentLocked(recovered.generation)
            return recovered
        }

        return initializeLocked()
    }

    private fun initializeLocked(): AgentSkillCatalog {
        val recoveredById = recoverLatestDefinitionsLocked().associateBy { it.id }
        val definitions = ArrayList<AgentSkillDefinition>()
        val revisionsToWrite = ArrayList<AgentSkillDefinition>()
        AgentBuiltInSkills.definitions.forEach { builtIn ->
            val definition = recoveredById[builtIn.id]
                ?: reserveRevisionLocked(builtIn).also(revisionsToWrite::add)
            definitions += definition
        }
        recoveredById.values
            .asSequence()
            .filterNot { recovered -> definitions.any { it.id == recovered.id } }
            .take(io.github.ethanbird.senseime.brain.api.AgentSkillPolicy.MAX_SKILLS - definitions.size)
            .forEach(definitions::add)
        val generation = reserveCatalogGenerationLocked(1L)
        val catalog = AgentSkillCatalog(
            generation = generation,
            definitions = definitions,
            bindings = AgentBuiltInSkills.bindings,
            active = null,
        )
        publishCatalogLocked(catalog, revisionsToWrite)
        return catalog
    }

    private fun commitReducedLocked(
        reduced: AgentSkillCatalogReducer.Result,
    ): AgentSkillCatalog {
        val adjustedDefinition = reduced.newRevision?.let(::reserveRevisionLocked)
        val nextGeneration = reserveCatalogGenerationLocked(reduced.catalog.generation)
        val nextDefinitions = if (
            adjustedDefinition != null &&
            adjustedDefinition.revision != reduced.newRevision?.revision
        ) {
            reduced.catalog.definitions.map {
                if (it.id == adjustedDefinition.id) adjustedDefinition else it
            }
        } else {
            reduced.catalog.definitions
        }
        val next = AgentSkillCatalog(
            generation = nextGeneration,
            definitions = nextDefinitions,
            bindings = reduced.catalog.bindings,
            active = reduced.catalog.active,
        )
        publishCatalogLocked(next, listOfNotNull(adjustedDefinition))
        return next
    }

    private fun publishCatalogLocked(
        catalog: AgentSkillCatalog,
        revisions: List<AgentSkillDefinition>,
    ) {
        revisions.forEach(::writeRevisionLocked)
        commitObserver(AgentSkillCommitPoint.AFTER_REVISION)
        writeCatalogLocked(catalog)
        commitObserver(AgentSkillCommitPoint.AFTER_CATALOG)
        commitObserver(AgentSkillCommitPoint.BEFORE_CURRENT)
        writeCurrentLocked(catalog.generation)
        commitObserver(AgentSkillCommitPoint.AFTER_CURRENT)
    }

    /**
     * Last-resort recovery when every catalog snapshot is corrupt. Valid documents are still
     * user data, so the newest readable revision of each custom Skill is restored to the fresh
     * catalog instead of silently falling out of the product.
     */
    private fun recoverLatestDefinitionsLocked(): List<AgentSkillDefinition> {
        directoryScanObserver(AgentSkillDirectoryScan.DOCUMENTS)
        return documentsDirectory.listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                val skillId = directory.name
                val validId = runCatching {
                    io.github.ethanbird.senseime.brain.api.AgentSkillPolicy.requireValidId(skillId)
                }.isSuccess
                if (!validId) {
                    null
                } else {
                    revisionNumbersLocked(skillId).asReversed().firstNotNullOfOrNull { revision ->
                        runCatching {
                            AgentSkillRevisionCodec.decode(
                                readLimited(revisionFile(skillId, revision), MAX_REVISION_BYTES),
                            )
                        }.getOrNull()?.takeIf {
                            it.id == skillId && it.revision == revision
                        }
                    }
                }
            }
            .sortedBy { it.id }
            .toList()
    }

    /**
     * Reuses an identical orphan written before a crash; skips a conflicting orphan without
     * overwriting it.
     */
    private fun reserveRevisionLocked(candidate: AgentSkillDefinition): AgentSkillDefinition {
        val candidateFile = revisionFile(candidate.id, candidate.revision)
        if (!candidateFile.exists()) return candidate
        val existing = runCatching {
            AgentSkillRevisionCodec.decode(readLimited(candidateFile, MAX_REVISION_BYTES))
        }.getOrNull()
        if (existing == candidate) return candidate

        val maximum = revisionNumbersLocked(candidate.id).maxOrNull() ?: candidate.revision
        require(maximum < Long.MAX_VALUE) { "Skill revision exhausted" }
        return candidate.copy(revision = maximum + 1L)
    }

    private fun reserveCatalogGenerationLocked(candidate: Long): Long {
        val highWater = readHighWaterGenerationOrNull() ?: 0L
        var reserved = maxOf(candidate, highWater.checkedIncrement("Skill catalog generation"))
        while (catalogFile(reserved).exists()) {
            reserved = reserved.checkedIncrement("Skill catalog generation")
        }
        writeHighWaterLocked(reserved)
        commitObserver(AgentSkillCommitPoint.AFTER_GENERATION_RESERVED)
        return reserved
    }

    private fun writeRevisionLocked(definition: AgentSkillDefinition) {
        val file = revisionFile(definition.id, definition.revision)
        writeImmutable(
            destination = file,
            document = AgentSkillRevisionCodec.encode(definition),
            maximumBytes = MAX_REVISION_BYTES,
            label = "Skill revision",
        )
    }

    private fun writeCatalogLocked(catalog: AgentSkillCatalog) {
        val file = catalogFile(catalog.generation)
        val encoded = AgentSkillCatalogCodec.encode(catalog)
        writeImmutable(
            destination = file,
            document = encoded,
            maximumBytes = MAX_CATALOG_BYTES,
            label = "Skill catalog",
        )
    }

    private fun writeImmutable(
        destination: File,
        document: String,
        maximumBytes: Long,
        label: String,
    ) {
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= maximumBytes) {
            "$label exceeds the durable encoding limit"
        }
        if (destination.exists()) {
            val existing = readLimited(destination, maximumBytes)
            require(existing.contentEquals(bytes)) {
                "Refusing to overwrite immutable Skill history: ${destination.name}"
            }
            return
        }
        destination.parentFile?.let { parent ->
            if (!parent.exists()) {
                if (!parent.mkdirs() && !parent.isDirectory) {
                    error("Unable to create Skill document directory")
                }
                parent.parentFile?.takeIf(File::isDirectory)?.let(::syncDirectory)
            }
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            moveAtomically(temporary, destination, replace = false)
            syncDirectory(requireNotNull(destination.parentFile))
        } finally {
            if (temporary.exists()) {
                // A failed temporary write is not user history and is never considered committed.
                temporary.delete()
            }
        }
    }

    private fun writeCurrentLocked(generation: Long) {
        writePointerLocked(currentFile, generation)
    }

    private fun writeHighWaterLocked(generation: Long) {
        val existing = readHighWaterGenerationOrNull()
        require(existing == null || generation >= existing) {
            "Refusing to move Skill generation HIGH_WATER backwards"
        }
        writePointerLocked(highWaterFile, generation)
    }

    private fun writePointerLocked(destination: File, generation: Long) {
        val bytes = "$generation\n".toByteArray(StandardCharsets.UTF_8)
        val temporary = File(root, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            moveAtomically(temporary, destination, replace = true)
            syncDirectory(root)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveAtomically(source: File, destination: File, replace: Boolean) {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallbackOptions = if (replace) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source.toPath(), destination.toPath(), *fallbackOptions)
        }
    }

    /**
     * File descriptor sync alone does not make a rename durable across sudden power loss. Force
     * the containing directory after every committed directory-entry change.
     */
    private fun syncDirectory(directory: File) {
        java.nio.channels.FileChannel.open(
            directory.toPath(),
            StandardOpenOption.READ,
        ).use { channel ->
            channel.force(true)
        }
    }

    private fun readCurrentGenerationOrNull(): Long? {
        return readGenerationPointerOrNull(currentFile, "CURRENT")
    }

    private fun readHighWaterGenerationOrNull(): Long? {
        return readGenerationPointerOrNull(highWaterFile, "HIGH_WATER")
    }

    private fun readGenerationPointerOrNull(file: File, label: String): Long? {
        if (!file.isFile) return null
        return runCatching {
            val source = readLimited(file, MAX_POINTER_BYTES).toString(StandardCharsets.UTF_8)
            val value = source.trim()
            require(value.matches(POSITIVE_NUMBER_PATTERN)) { "Malformed Skill $label pointer" }
            value.toLong().also { require(it > 0L) }
        }.getOrNull()
    }

    private fun readCatalogLocked(generation: Long): AgentSkillCatalog {
        val record = AgentSkillCatalogCodec.decode(
            readLimited(catalogFile(generation), MAX_CATALOG_BYTES),
        )
        require(record.generation == generation) { "Skill catalog filename/content mismatch" }
        val definitions = record.definitionRevisions.map { (id, revision) ->
            val file = revisionFile(id, revision)
            require(file.isFile) { "Skill catalog references a missing revision: $id@$revision" }
            val definition = AgentSkillRevisionCodec.decode(readLimited(file, MAX_REVISION_BYTES))
            require(definition.id == id && definition.revision == revision) {
                "Skill catalog revision identity mismatch"
            }
            definition
        }
        return AgentSkillCatalog(
            generation = record.generation,
            definitions = definitions,
            bindings = record.bindings,
            active = record.active,
        )
    }

    private fun revisionNumbersLocked(skillId: String): List<Long> {
        val directory = File(documentsDirectory, skillId)
        if (!directory.isDirectory) return emptyList()
        directoryScanObserver(AgentSkillDirectoryScan.DOCUMENT_REVISIONS)
        return directory.listFiles().orEmpty().mapNotNull { file ->
            REVISION_FILE_PATTERN.matchEntire(file.name)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }.distinct().sorted()
    }

    private fun catalogGenerationsLocked(): List<Long> {
        directoryScanObserver(AgentSkillDirectoryScan.CATALOGS)
        return catalogsDirectory.listFiles().orEmpty().mapNotNull { file ->
            CATALOG_FILE_PATTERN.matchEntire(file.name)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }.distinct().sorted()
    }

    private fun revisionFile(skillId: String, revision: Long): File =
        File(File(documentsDirectory, skillId), "$revision.skill")

    private fun catalogFile(generation: Long): File =
        File(catalogsDirectory, "$generation.catalog")

    private fun requireValidHistoryCoordinates(skillId: String, revision: Long) {
        io.github.ethanbird.senseime.brain.api.AgentSkillPolicy.requireValidId(skillId)
        require(revision > 0L) { "Skill revision must be positive" }
    }

    internal companion object {
        const val STORE_DIRECTORY = "agent-skills-v1"
        const val DOCUMENTS_DIRECTORY = "documents"
        const val CATALOGS_DIRECTORY = "catalogs"
        const val CURRENT_FILE = "CURRENT"
        const val HIGH_WATER_FILE = "HIGH_WATER"
        const val LOCK_FILE = "store.lock"
        /*
         * MAX_CONTENT_CHARS is measured in UTF-16 code units. A full CJK document can require
         * three UTF-8 bytes per unit and Base64 expands that by 4/3, so 192 KiB was insufficient.
         * Keep a 512 KiB encoded envelope and preflight writes against the same read limit.
         */
        const val MAX_REVISION_BYTES = 524_288L
        const val MAX_CATALOG_BYTES = 262_144L
        const val MAX_POINTER_BYTES = 64L
        val STORE_MUTEX = Any()
        val POSITIVE_NUMBER_PATTERN = Regex("[1-9][0-9]*")
        val REVISION_FILE_PATTERN = Regex("([1-9][0-9]*)\\.skill")
        val CATALOG_FILE_PATTERN = Regex("([1-9][0-9]*)\\.catalog")
    }
}

internal enum class AgentSkillCommitPoint {
    AFTER_GENERATION_RESERVED,
    AFTER_REVISION,
    AFTER_CATALOG,
    BEFORE_CURRENT,
    AFTER_CURRENT,
}

internal enum class AgentSkillDirectoryScan {
    DOCUMENTS,
    DOCUMENT_REVISIONS,
    CATALOGS,
}

private fun Long.checkedIncrement(label: String): Long {
    require(this < Long.MAX_VALUE) { "$label exhausted" }
    return this + 1L
}

internal data class AgentSkillCatalogRecord(
    val generation: Long,
    val definitionRevisions: List<Pair<String, Long>>,
    val bindings: List<AgentSkillBinding>,
    val active: AgentSkillActivation?,
)

internal object AgentSkillRevisionCodec {
    private const val SCHEMA_VERSION = 1
    private val REQUIRED_KEYS = setOf(
        "schema_version",
        "id",
        "revision",
        "built_in",
        "base_intent",
        "name",
        "description",
        "content",
    )

    fun encode(definition: AgentSkillDefinition): String = buildString {
        appendLine("schema_version=$SCHEMA_VERSION")
        appendLine("id=${SkillBase64.encode(definition.id)}")
        appendLine("revision=${definition.revision}")
        appendLine("built_in=${definition.builtIn}")
        appendLine("base_intent=${definition.baseIntent.wireValue}")
        appendLine("name=${SkillBase64.encode(definition.name)}")
        appendLine("description=${SkillBase64.encode(definition.description)}")
        appendLine("content=${SkillBase64.encode(definition.content)}")
    }

    fun decode(document: ByteArray): AgentSkillDefinition {
        val values = decodeUniqueKeyValues(document, REQUIRED_KEYS, "Skill revision")
        require(values.getValue("schema_version").toIntOrNull() == SCHEMA_VERSION) {
            "Unsupported Skill revision schema"
        }
        val intentValue = values.getValue("base_intent")
        val intent = EditorIntent.entries.firstOrNull { it.wireValue == intentValue }
            ?: throw IllegalArgumentException("Unknown Skill base intent: $intentValue")
        return AgentSkillDefinition(
            id = SkillBase64.decode(values.getValue("id")),
            revision = values.requiredPositiveLong("revision"),
            name = SkillBase64.decode(values.getValue("name")),
            description = SkillBase64.decode(values.getValue("description")),
            content = SkillBase64.decode(values.getValue("content")),
            baseIntent = intent,
            builtIn = values.strictBoolean("built_in"),
        )
    }
}

internal object AgentSkillCatalogCodec {
    private const val SCHEMA_VERSION = 1
    private const val NONE = "none"
    private val HEADER_KEYS = setOf("schema_version", "generation", "active")

    fun encode(catalog: AgentSkillCatalog): String = buildString {
        appendLine("schema_version=$SCHEMA_VERSION")
        appendLine("generation=${catalog.generation}")
        catalog.definitions.forEach { definition ->
            appendLine("definition=${SkillBase64.encode(definition.id)}:${definition.revision}")
        }
        catalog.bindings.forEach { binding ->
            appendLine(
                "binding=${binding.slot.keyCode}:${binding.slot.direction.wireValue}:" +
                    SkillBase64.encode(binding.skillId),
            )
        }
        val active = catalog.active
        if (active == null) {
            appendLine("active=$NONE")
        } else {
            appendLine(
                "active=${active.slot.keyCode}:${active.slot.direction.wireValue}:" +
                    SkillBase64.encode(active.skillId),
            )
        }
    }

    fun decode(document: ByteArray): AgentSkillCatalogRecord {
        val source = decodeUtf8(document)
        val headers = LinkedHashMap<String, String>()
        val definitions = ArrayList<Pair<String, Long>>()
        val bindings = ArrayList<AgentSkillBinding>()
        source.lineSequence().forEachIndexed { index, sourceLine ->
            val line = sourceLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.length) {
                "Malformed Skill catalog at line ${index + 1}"
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                "definition" -> definitions += decodeDefinitionReference(value)
                "binding" -> bindings += decodeBinding(value)
                in HEADER_KEYS -> require(headers.put(key, value) == null) {
                    "Duplicate Skill catalog field: $key"
                }
                else -> throw IllegalArgumentException("Unknown Skill catalog field: $key")
            }
        }
        require(headers.keys == HEADER_KEYS) { "Incomplete Skill catalog" }
        require(headers.getValue("schema_version").toIntOrNull() == SCHEMA_VERSION) {
            "Unsupported Skill catalog schema"
        }
        require(definitions.isNotEmpty()) { "Skill catalog contains no definitions" }
        val active = headers.getValue("active").let { value ->
            if (value == NONE) null else decodeActivation(value)
        }
        return AgentSkillCatalogRecord(
            generation = headers.requiredPositiveLong("generation"),
            definitionRevisions = definitions,
            bindings = bindings,
            active = active,
        )
    }

    private fun decodeDefinitionReference(value: String): Pair<String, Long> {
        val fields = value.split(':')
        require(fields.size == 2) { "Malformed Skill definition reference" }
        val id = SkillBase64.decode(fields[0])
        val revision = fields[1].toLongOrNull()
        require(revision != null && revision > 0L) { "Malformed Skill definition revision" }
        return id to revision
    }

    private fun decodeBinding(value: String): AgentSkillBinding {
        val (slot, skillId) = decodeSlotAndId(value)
        return AgentSkillBinding(slot, skillId)
    }

    private fun decodeActivation(value: String): AgentSkillActivation {
        val (slot, skillId) = decodeSlotAndId(value)
        return AgentSkillActivation(slot, skillId)
    }

    private fun decodeSlotAndId(value: String): Pair<AgentSkillSlot, String> {
        val fields = value.split(':')
        require(fields.size == 3) { "Malformed Skill slot reference" }
        val keyCode = fields[0].toIntOrNull()
            ?: throw IllegalArgumentException("Malformed Skill key code")
        val direction = AgentSkillDirection.fromWireValue(fields[1])
        val skillId = SkillBase64.decode(fields[2])
        return AgentSkillSlot(keyCode, direction) to skillId
    }
}

private object SkillBase64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): String {
        require(value.isNotEmpty()) { "Empty Base64 Skill field" }
        val bytes = try {
            decoder.decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed Base64 Skill field", error)
        }
        return decodeUtf8(bytes)
    }
}

private fun decodeUniqueKeyValues(
    document: ByteArray,
    requiredKeys: Set<String>,
    label: String,
): Map<String, String> {
    val values = LinkedHashMap<String, String>()
    decodeUtf8(document).lineSequence().forEachIndexed { index, sourceLine ->
        val line = sourceLine.trim()
        if (line.isEmpty()) return@forEachIndexed
        val separator = line.indexOf('=')
        require(separator > 0 && separator < line.length) {
            "Malformed $label at line ${index + 1}"
        }
        val key = line.substring(0, separator)
        require(key in requiredKeys) { "Unknown $label field: $key" }
        require(values.put(key, line.substring(separator + 1)) == null) {
            "Duplicate $label field: $key"
        }
    }
    require(values.keys == requiredKeys) { "Incomplete $label" }
    return values
}

private fun Map<String, String>.requiredPositiveLong(key: String): Long =
    getValue(key).toLongOrNull()?.takeIf { it > 0L }
        ?: throw IllegalArgumentException("$key must be a positive integer")

private fun Map<String, String>.strictBoolean(key: String): Boolean =
    when (val value = getValue(key)) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$key is not a boolean: $value")
    }

private fun decodeUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun readLimited(file: File, maximumBytes: Long): ByteArray {
    require(file.isFile) { "Missing Skill store file: ${file.name}" }
    val declaredLength = file.length()
    require(declaredLength in 0..maximumBytes) { "Skill store file is too large: ${file.name}" }
    FileInputStream(file).use { stream ->
        val output = ByteArrayOutputStream(declaredLength.toInt())
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximumBytes) { "Skill store file exceeded its read limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
