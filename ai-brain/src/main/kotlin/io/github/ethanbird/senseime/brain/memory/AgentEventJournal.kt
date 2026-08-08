package io.github.ethanbird.senseime.brain.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.PriorityQueue
import java.util.zip.CRC32

/**
 * Single-writer, app-private, append-only journal for complete Agent run retention.
 *
 * The Brain process owns the only instance. Android integration must pass a directory below the
 * application's private `filesDir`; this core class deliberately has no permission to choose an
 * external or shared-storage path.
 *
 * Writes are always enabled. [AgentMemorySearchAccess] gates only lexical recall and can never
 * suppress an append, truncate valid history, or delete an older record.
 */
class AgentEventJournal private constructor(
    private val dataChannel: FileChannel,
    private val lockChannel: FileChannel,
    private val writerLock: FileLock,
    private val forceData: (FileChannel) -> Unit,
) : Closeable {
    private var closed = false
    private var poisoned = false
    private var hasUnforcedWrites = false
    private var lastFrameOffset = NO_PREVIOUS_FRAME
    private var lastSequence = 0L
    private var recordCount = 0L

    /**
     * Starts one retained run by durably writing its complete input snapshot as sequence N.
     *
     * [payload] is persisted byte-for-byte. [lexicalText] is a caller-selected full textual view
     * used by bounded search; it is persisted without redaction or truncation.
     */
    @Synchronized
    fun beginRun(
        requestId: String,
        runGeneration: Long,
        payload: ByteArray,
        contentType: String,
        lexicalText: String,
        attributes: Map<String, String> = emptyMap(),
    ): AgentJournalRun {
        val inputRecord = appendLocked(
            AgentJournalDraft(
                requestId = requestId,
                runGeneration = runGeneration,
                kind = AgentJournalKind.REQUEST_INPUT_SNAPSHOT,
                contentType = contentType,
                payload = payload,
                lexicalText = lexicalText,
                attributes = attributes,
            ),
            durable = true,
        )
        return AgentJournalRun(
            journal = this,
            requestId = requestId,
            runGeneration = runGeneration,
            inputRecordSequence = inputRecord.sequence,
        )
    }

    /**
     * Durably appends one complete record and returns its globally stable journal sequence.
     *
     * A write or force failure poisons this instance because the caller cannot know whether the
     * final bytes reached storage. Close and reopen the journal before retrying; startup recovery
     * will keep a complete frame or remove only an incomplete physical tail.
     */
    @Synchronized
    fun append(draft: AgentJournalDraft): AgentJournalRecord =
        appendLocked(draft, durable = true)

    /**
     * Appends one complete frame without forcing storage before returning.
     *
     * The frame is encoded and written immediately, so every call retains its exact raw payload
     * and stable sequence instead of coalescing or replacing stream chunks. [flush], a later
     * durable [append], or [close] forces all preceding deferred frames in their original order.
     *
     * A write failure poisons the journal exactly like a durable append failure. Close and reopen
     * before retrying because the physical write outcome is indeterminate.
     */
    @Synchronized
    fun appendDeferred(draft: AgentJournalDraft): AgentJournalRecord =
        appendLocked(draft, durable = false)

    /**
     * Forces all successfully appended deferred frames to durable storage.
     *
     * A force failure poisons this instance. The failure is reported here; [close] still releases
     * the writer lock and channels but never retries journal I/O on a poisoned instance.
     */
    @Synchronized
    fun flush() {
        ensureUsable()
        forcePendingLocked()
    }

    /**
     * Reads newest matching records, returned in ascending sequence order.
     *
     * The scan is bounded even when [requestId] has no matches.
     */
    @Synchronized
    fun readLatest(
        limit: Int,
        maxScannedRecords: Int = DEFAULT_READ_SCAN_RECORDS,
        requestId: String? = null,
    ): AgentJournalReadResult {
        ensureUsable()
        require(limit in 1..MAX_READ_RESULTS)
        require(maxScannedRecords in 1..MAX_READ_SCAN_RECORDS)
        requestId?.let(::validateRequestId)

        val newestFirst = ArrayList<AgentJournalRecord>(limit)
        var offset = lastFrameOffset
        var scanned = 0
        while (
            offset != NO_PREVIOUS_FRAME &&
            scanned < maxScannedRecords &&
            newestFirst.size < limit
        ) {
            val stored = readFrame(offset)
            scanned += 1
            if (requestId == null || stored.record.requestId == requestId) {
                newestFirst += stored.record
            }
            offset = stored.previousOffset
        }
        newestFirst.reverse()
        return AgentJournalReadResult(
            records = newestFirst,
            scannedRecords = scanned,
            truncated = offset != NO_PREVIOUS_FRAME,
        )
    }

    /**
     * Performs local lexical recall over newest records under explicit hard bounds.
     *
     * Query access is fail-closed. Disabled access returns no data and performs no disk scan, while
     * append behavior remains unchanged.
     */
    @Synchronized
    fun search(
        query: String,
        access: AgentMemorySearchAccess,
        bounds: AgentMemorySearchBounds = AgentMemorySearchBounds(),
        excludeRequestId: String? = null,
        excludeRunGeneration: Long? = null,
    ): AgentMemorySearchResult {
        ensureUsable()
        bounds.validate()
        require((excludeRequestId == null) == (excludeRunGeneration == null)) {
            "excluded request identity must be complete"
        }
        excludeRequestId?.let(::validateRequestId)
        excludeRunGeneration?.let { require(it >= 0L) }
        if (access == AgentMemorySearchAccess.DISABLED) {
            return AgentMemorySearchResult(
                query = query,
                access = access,
                hits = emptyList(),
                scannedRecords = 0,
                scannedBytes = 0L,
                truncated = false,
            )
        }

        require(query.length in 1..MAX_QUERY_CHARS) {
            "query must contain 1..$MAX_QUERY_CHARS characters"
        }
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val queryTerms = boundedTerms(lexicalTerms(normalizedQuery).distinct(), MAX_QUERY_TERMS)
        require(queryTerms.isNotEmpty()) { "query must contain a letter or digit" }

        val heap = PriorityQueue<AgentMemorySearchHit>(
            compareBy<AgentMemorySearchHit> { it.score }
                .thenBy { it.sequence },
        )
        var offset = lastFrameOffset
        var scannedRecords = 0
        var scannedBytes = 0L
        var truncated = false

        while (
            offset != NO_PREVIOUS_FRAME &&
            scannedRecords < bounds.maxScannedRecords
        ) {
            val header = readHeader(offset)
            val encodedBytes = FRAME_HEADER_BYTES.toLong() + header.bodyLength
            if (encodedBytes > bounds.maxScannedBytes - scannedBytes) {
                truncated = true
                break
            }
            val stored = readFrame(offset, header)
            scannedRecords += 1
            scannedBytes += encodedBytes

            val isExcludedRun =
                excludeRequestId != null &&
                    stored.record.requestId == excludeRequestId &&
                    stored.record.runGeneration == excludeRunGeneration
            if (!isExcludedRun) {
                score(stored.record, normalizedQuery, queryTerms, bounds.maxExcerptChars)
                    ?.let { hit ->
                        if (heap.size < bounds.maxResults) {
                            heap += hit
                        } else {
                            val weakest = heap.peek()
                            if (HIT_BEST_FIRST.compare(hit, weakest) < 0) {
                                heap.poll()
                                heap += hit
                            }
                        }
                    }
            }
            offset = stored.previousOffset
        }
        if (offset != NO_PREVIOUS_FRAME) truncated = true

        return AgentMemorySearchResult(
            query = query,
            access = access,
            hits = heap.toList().sortedWith(HIT_BEST_FIRST),
            scannedRecords = scannedRecords,
            scannedBytes = scannedBytes,
            truncated = truncated,
        )
    }

    @Synchronized
    fun stats(): AgentJournalStats {
        ensureUsable()
        return AgentJournalStats(
            records = recordCount,
            lastSequence = lastSequence,
            encodedBytes = dataChannel.size(),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        var firstFailure: Throwable? = null
        if (!poisoned) {
            try {
                forcePendingLocked()
            } catch (failure: Throwable) {
                firstFailure = failure
            }
        }
        closed = true
        try {
            writerLock.release()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
        try {
            dataChannel.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
        try {
            lockChannel.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
        if (firstFailure != null) throw IOException("failed to close Agent journal", firstFailure)
    }

    private fun appendLocked(
        draft: AgentJournalDraft,
        durable: Boolean,
    ): AgentJournalRecord {
        ensureUsable()
        validateDraft(draft)
        val sequence = try {
            Math.addExact(lastSequence, 1L)
        } catch (failure: ArithmeticException) {
            throw AgentJournalException("journal sequence exhausted", failure)
        }
        val body = encodeBody(draft)
        require(body.size <= MAX_FRAME_BODY_BYTES) {
            "encoded Agent journal frame exceeds $MAX_FRAME_BODY_BYTES bytes"
        }

        val offset = try {
            dataChannel.size()
        } catch (failure: IOException) {
            poisoned = true
            throw AgentJournalException("cannot resolve Agent journal tail", failure)
        }
        val checksum = checksum(sequence, lastFrameOffset, body)
        val header = ByteBuffer.allocate(FRAME_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(FRAME_MAGIC)
            .putInt(FRAME_VERSION)
            .putInt(body.size)
            .putInt(checksum)
            .putLong(sequence)
            .putLong(lastFrameOffset)
            .flip() as ByteBuffer

        try {
            dataChannel.position(offset)
            writeFully(dataChannel, header)
            writeFully(dataChannel, ByteBuffer.wrap(body))
            hasUnforcedWrites = true
            if (durable) forcePendingLocked()
        } catch (failure: Throwable) {
            poisoned = true
            throw AgentJournalException(
                "Agent journal append outcome is indeterminate; reopen before retry",
                failure,
            )
        }

        lastSequence = sequence
        lastFrameOffset = offset
        recordCount = Math.addExact(recordCount, 1L)
        return draft.toRecord(sequence)
    }

    private fun forcePendingLocked() {
        if (!hasUnforcedWrites) return
        try {
            forceData(dataChannel)
            hasUnforcedWrites = false
        } catch (failure: Throwable) {
            poisoned = true
            throw AgentJournalException(
                "Agent journal flush outcome is indeterminate; reopen before retry",
                failure,
            )
        }
    }

    private fun recover() {
        var position = 0L
        var expectedPrevious = NO_PREVIOUS_FRAME
        var expectedSequence = 1L
        val size = dataChannel.size()
        while (position < size) {
            val remaining = size - position
            if (remaining < FRAME_HEADER_BYTES) {
                discardIncompleteTail(position)
                break
            }
            val header = readHeader(position)
            val frameEnd = checkedFrameEnd(position, header.bodyLength)
            if (frameEnd > size) {
                discardIncompleteTail(position)
                break
            }
            if (header.sequence != expectedSequence) {
                throw AgentJournalCorruptionException(
                    "non-contiguous sequence ${header.sequence} at offset $position; " +
                        "expected $expectedSequence",
                )
            }
            if (header.previousOffset != expectedPrevious) {
                throw AgentJournalCorruptionException(
                    "broken previous-frame link at offset $position",
                )
            }
            readFrame(position, header)
            lastFrameOffset = position
            lastSequence = header.sequence
            recordCount = Math.addExact(recordCount, 1L)
            expectedPrevious = position
            expectedSequence = try {
                Math.addExact(expectedSequence, 1L)
            } catch (failure: ArithmeticException) {
                throw AgentJournalCorruptionException("journal sequence overflow", failure)
            }
            position = frameEnd
        }
    }

    private fun discardIncompleteTail(validBytes: Long) {
        dataChannel.truncate(validBytes)
        dataChannel.force(true)
    }

    private fun readHeader(offset: Long): FrameHeader {
        require(offset >= 0)
        val bytes = ByteBuffer.allocate(FRAME_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        readFully(dataChannel, bytes, offset)
        bytes.flip()
        val magic = bytes.int
        val version = bytes.int
        val bodyLength = bytes.int
        val storedChecksum = bytes.int
        val sequence = bytes.long
        val previousOffset = bytes.long
        if (magic != FRAME_MAGIC) {
            throw AgentJournalCorruptionException("invalid frame magic at offset $offset")
        }
        if (version != FRAME_VERSION) {
            throw AgentJournalCorruptionException(
                "unsupported frame version $version at offset $offset",
            )
        }
        if (bodyLength !in 1..MAX_FRAME_BODY_BYTES) {
            throw AgentJournalCorruptionException(
                "invalid frame body length $bodyLength at offset $offset",
            )
        }
        if (sequence <= 0L) {
            throw AgentJournalCorruptionException("invalid sequence at offset $offset")
        }
        if (previousOffset != NO_PREVIOUS_FRAME && previousOffset !in 0 until offset) {
            throw AgentJournalCorruptionException(
                "invalid previous-frame offset at $offset",
            )
        }
        return FrameHeader(bodyLength, storedChecksum, sequence, previousOffset)
    }

    private fun readFrame(
        offset: Long,
        header: FrameHeader = readHeader(offset),
    ): StoredFrame {
        checkedFrameEnd(offset, header.bodyLength)
        val body = ByteBuffer.allocate(header.bodyLength)
        readFully(dataChannel, body, offset + FRAME_HEADER_BYTES)
        val bytes = body.array()
        if (checksum(header.sequence, header.previousOffset, bytes) != header.checksum) {
            throw AgentJournalCorruptionException("frame checksum mismatch at offset $offset")
        }
        return StoredFrame(
            record = decodeBody(header.sequence, bytes),
            previousOffset = header.previousOffset,
        )
    }

    private fun ensureUsable() {
        check(!closed) { "Agent journal is closed" }
        check(!poisoned) { "Agent journal must be reopened after an indeterminate append" }
    }

    private data class FrameHeader(
        val bodyLength: Int,
        val checksum: Int,
        val sequence: Long,
        val previousOffset: Long,
    )

    private data class StoredFrame(
        val record: AgentJournalRecord,
        val previousOffset: Long,
    )

    companion object {
        const val DATA_FILE_NAME = "agent-events-v1.journal"
        const val LOCK_FILE_NAME = "agent-events-v1.lock"
        const val MAX_FRAME_BODY_BYTES = 16 * 1024 * 1024

        private const val FRAME_MAGIC = 0x53414A31
        private const val FRAME_VERSION = 1
        private const val FRAME_HEADER_BYTES = 32
        private const val NO_PREVIOUS_FRAME = -1L
        private const val MAX_REQUEST_ID_BYTES = 1_024
        private const val MAX_CONTENT_TYPE_BYTES = 256
        private const val MAX_ATTRIBUTES = 128
        private const val MAX_ATTRIBUTE_KEY_BYTES = 1_024
        private const val MAX_QUERY_CHARS = 512
        private const val MAX_QUERY_TERMS = 24
        private const val DEFAULT_READ_SCAN_RECORDS = 10_000
        private const val MAX_READ_RESULTS = 10_000
        private const val MAX_READ_SCAN_RECORDS = 1_000_000

        private val HIT_BEST_FIRST =
            compareByDescending<AgentMemorySearchHit> { it.score }
                .thenByDescending { it.sequence }

        fun open(appPrivateDirectory: File): AgentEventJournal =
            openWithForce(appPrivateDirectory) { channel -> channel.force(true) }

        internal fun openForTest(
            appPrivateDirectory: File,
            forceData: (FileChannel) -> Unit,
        ): AgentEventJournal = openWithForce(appPrivateDirectory, forceData)

        private fun openWithForce(
            appPrivateDirectory: File,
            forceData: (FileChannel) -> Unit,
        ): AgentEventJournal {
            val directory = appPrivateDirectory.toPath()
            java.nio.file.Files.createDirectories(directory)
            require(java.nio.file.Files.isDirectory(directory)) {
                "Agent journal path is not a directory"
            }
            val dataChannel = FileChannel.open(
                directory.resolve(DATA_FILE_NAME),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lockChannel = try {
                FileChannel.open(
                    directory.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (failure: Throwable) {
                dataChannel.close()
                throw failure
            }
            val lock = try {
                lockChannel.tryLock()
                    ?: throw AgentJournalInUseException("Agent journal already has a writer")
            } catch (failure: OverlappingFileLockException) {
                dataChannel.close()
                lockChannel.close()
                throw AgentJournalInUseException("Agent journal already has a writer", failure)
            } catch (failure: Throwable) {
                dataChannel.close()
                lockChannel.close()
                throw failure
            }
            val journal = AgentEventJournal(
                dataChannel = dataChannel,
                lockChannel = lockChannel,
                writerLock = lock,
                forceData = forceData,
            )
            try {
                journal.recover()
            } catch (failure: Throwable) {
                try {
                    journal.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
            return journal
        }

        private fun validateDraft(draft: AgentJournalDraft) {
            validateRequestId(draft.requestId)
            require(draft.runGeneration >= 0L) { "runGeneration must be non-negative" }
            val contentTypeBytes = draft.contentType.toByteArray(StandardCharsets.UTF_8)
            require(draft.contentType.isNotBlank())
            require(contentTypeBytes.size <= MAX_CONTENT_TYPE_BYTES)
            require(draft.attributes.size <= MAX_ATTRIBUTES)
            draft.attributes.forEach { (key, _) ->
                require(key.isNotBlank()) { "attribute keys must be non-blank" }
                require(key.toByteArray(StandardCharsets.UTF_8).size <= MAX_ATTRIBUTE_KEY_BYTES)
            }
        }

        private fun validateRequestId(requestId: String) {
            require(requestId.isNotBlank()) { "requestId must be non-blank" }
            require(requestId.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_ID_BYTES)
        }

        private fun encodeBody(draft: AgentJournalDraft): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(draft.kind.wireId)
                output.writeLong(draft.runGeneration)
                output.writeUtf8(draft.requestId)
                output.writeUtf8(draft.contentType)
                output.writeUtf8(draft.lexicalText)
                val sortedAttributes = draft.attributes.toSortedMap()
                output.writeInt(sortedAttributes.size)
                sortedAttributes.forEach { (key, value) ->
                    output.writeUtf8(key)
                    output.writeUtf8(value)
                }
                output.writeInt(draft.payload.size)
                output.write(draft.payload)
            }
            return buffer.toByteArray()
        }

        private fun decodeBody(sequence: Long, body: ByteArray): AgentJournalRecord {
            try {
                DataInputStream(ByteArrayInputStream(body)).use { input ->
                    val kindWireId = input.readInt()
                    val kind = AgentJournalKind.fromWireId(kindWireId)
                        ?: throw AgentJournalCorruptionException(
                            "unknown Agent journal kind $kindWireId at sequence $sequence",
                        )
                    val runGeneration = input.readLong()
                    if (runGeneration < 0L) {
                        throw AgentJournalCorruptionException(
                            "negative run generation at sequence $sequence",
                        )
                    }
                    val requestId = input.readUtf8()
                    val contentType = input.readUtf8()
                    val lexicalText = input.readUtf8()
                    val attributeCount = input.readInt()
                    if (attributeCount !in 0..MAX_ATTRIBUTES) {
                        throw AgentJournalCorruptionException(
                            "invalid attribute count at sequence $sequence",
                        )
                    }
                    val attributes = LinkedHashMap<String, String>(attributeCount)
                    repeat(attributeCount) {
                        val key = input.readUtf8()
                        val value = input.readUtf8()
                        if (attributes.put(key, value) != null) {
                            throw AgentJournalCorruptionException(
                                "duplicate attribute at sequence $sequence",
                            )
                        }
                    }
                    val payloadLength = input.readInt()
                    if (payloadLength < 0 || payloadLength > input.available()) {
                        throw AgentJournalCorruptionException(
                            "invalid payload length at sequence $sequence",
                        )
                    }
                    val payload = ByteArray(payloadLength)
                    input.readFully(payload)
                    if (input.available() != 0) {
                        throw AgentJournalCorruptionException(
                            "trailing frame bytes at sequence $sequence",
                        )
                    }
                    return AgentJournalRecord(
                        sequence = sequence,
                        requestId = requestId,
                        runGeneration = runGeneration,
                        kind = kind,
                        contentType = contentType,
                        payload = payload,
                        lexicalText = lexicalText,
                        attributes = attributes,
                    )
                }
            } catch (failure: AgentJournalCorruptionException) {
                throw failure
            } catch (failure: EOFException) {
                throw AgentJournalCorruptionException(
                    "truncated body at sequence $sequence",
                    failure,
                )
            } catch (failure: IOException) {
                throw AgentJournalCorruptionException(
                    "invalid body at sequence $sequence",
                    failure,
                )
            }
        }

        private fun DataOutputStream.writeUtf8(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            writeInt(encoded.size)
            write(encoded)
        }

        private fun DataInputStream.readUtf8(): String {
            val length = readInt()
            if (length < 0 || length > available()) {
                throw AgentJournalCorruptionException("invalid UTF-8 field length")
            }
            val bytes = ByteArray(length)
            readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun checksum(
            sequence: Long,
            previousOffset: Long,
            body: ByteArray,
        ): Int {
            val identity = ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(sequence)
                .putLong(previousOffset)
                .array()
            return CRC32().run {
                update(identity)
                update(body)
                value.toInt()
            }
        }

        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) < 0) throw EOFException("journal write ended early")
            }
        }

        private fun readFully(
            channel: FileChannel,
            buffer: ByteBuffer,
            position: Long,
        ) {
            var cursor = position
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, cursor)
                if (read < 0) throw EOFException("journal read ended early")
                if (read == 0) throw EOFException("journal read made no progress")
                cursor = Math.addExact(cursor, read.toLong())
            }
        }

        private fun checkedFrameEnd(offset: Long, bodyLength: Int): Long =
            try {
                Math.addExact(
                    Math.addExact(offset, FRAME_HEADER_BYTES.toLong()),
                    bodyLength.toLong(),
                )
            } catch (failure: ArithmeticException) {
                throw AgentJournalCorruptionException("frame offset overflow", failure)
            }

        private fun score(
            record: AgentJournalRecord,
            normalizedQuery: String,
            queryTerms: List<String>,
            maxExcerptChars: Int,
        ): AgentMemorySearchHit? {
            val candidate = buildString {
                append(record.lexicalText)
                record.attributes.forEach { (key, value) ->
                    append('\n').append(key).append('=').append(value)
                }
            }
            val normalized = candidate.lowercase(Locale.ROOT)
            var score = 0
            var firstMatch = Int.MAX_VALUE
            var matchedTerms = 0
            queryTerms.forEach { term ->
                var count = 0
                var from = 0
                var termFirst = -1
                while (from <= normalized.length - term.length) {
                    val found = normalized.indexOf(term, from)
                    if (found < 0) break
                    if (termFirst < 0) termFirst = found
                    count += 1
                    if (count == 1_000) break
                    from = found + term.length.coerceAtLeast(1)
                }
                if (count > 0) {
                    matchedTerms += 1
                    score = Math.addExact(score, count)
                    firstMatch = minOf(firstMatch, termFirst)
                }
            }
            val minimumMatches = when (queryTerms.size) {
                1, 2 -> queryTerms.size
                // CJK bigrams deliberately trade strict conjunction for natural-language recall:
                // two independently matching concepts (for example 用户 + 颜色) are already
                // useful evidence even when the query says 喜欢什么 and the record says 偏好.
                else -> maxOf(2, (queryTerms.size + 3) / 4)
            }
            if (matchedTerms < minimumMatches) return null
            score = Math.addExact(score, matchedTerms * 100)
            score = Math.addExact(score, matchedTerms * 1_000 / queryTerms.size)
            val phrase = normalized.indexOf(normalizedQuery)
            if (phrase >= 0) {
                score = Math.addExact(score, 10_000)
                firstMatch = minOf(firstMatch, phrase)
            }
            return AgentMemorySearchHit(
                sequence = record.sequence,
                requestId = record.requestId,
                runGeneration = record.runGeneration,
                kind = record.kind,
                score = score,
                excerpt = excerpt(candidate, firstMatch, maxExcerptChars),
                attributes = record.attributes,
            )
        }

        private fun excerpt(value: String, matchOffset: Int, maxChars: Int): String {
            if (value.length <= maxChars) return value
            val safeMatch = matchOffset.coerceIn(0, value.length)
            val start = (safeMatch - maxChars / 3).coerceIn(0, value.length - maxChars)
            return value.substring(start, start + maxChars)
        }

        private fun lexicalTerms(value: String): List<String> {
            val terms = ArrayList<String>()
            val latin = StringBuilder()
            val han = ArrayList<Int>()

            fun flushLatin() {
                if (latin.isNotEmpty()) {
                    terms += latin.toString()
                    latin.setLength(0)
                }
            }

            fun flushHan() {
                if (han.isEmpty()) return
                if (han.size <= 8) {
                    terms += buildString { han.forEach { appendCodePoint(it) } }
                }
                if (han.size > 1) {
                    for (index in 0 until han.lastIndex) {
                        terms += buildString(4) {
                            appendCodePoint(han[index])
                            appendCodePoint(han[index + 1])
                        }
                    }
                }
                han.clear()
            }

            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                when {
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> {
                        flushLatin()
                        han += codePoint
                    }
                    Character.isLetterOrDigit(codePoint) -> {
                        flushHan()
                        latin.appendCodePoint(codePoint)
                    }
                    else -> {
                        flushLatin()
                        flushHan()
                    }
                }
                offset += Character.charCount(codePoint)
            }
            flushLatin()
            flushHan()
            return terms
        }

        private fun boundedTerms(terms: List<String>, maximum: Int): List<String> {
            if (terms.size <= maximum) return terms
            if (maximum == 1) return listOf(terms.first())
            return List(maximum) { index ->
                terms[index * (terms.lastIndex) / (maximum - 1)]
            }.distinct()
        }
    }
}

/** Request-scoped facade that cannot accidentally change request identity between events. */
class AgentJournalRun internal constructor(
    private val journal: AgentEventJournal,
    val requestId: String,
    val runGeneration: Long,
    /** Stable evidence id of the complete request snapshot written by [AgentEventJournal.beginRun]. */
    val inputRecordSequence: Long,
) {
    fun append(
        kind: AgentJournalKind,
        payload: ByteArray,
        contentType: String,
        lexicalText: String,
        attributes: Map<String, String> = emptyMap(),
        durable: Boolean = true,
    ): AgentJournalRecord {
        require(kind != AgentJournalKind.REQUEST_INPUT_SNAPSHOT) {
            "use beginRun for the input snapshot"
        }
        val draft = AgentJournalDraft(
            requestId = requestId,
            runGeneration = runGeneration,
            kind = kind,
            contentType = contentType,
            payload = payload,
            lexicalText = lexicalText,
            attributes = attributes,
        )
        return if (durable) journal.append(draft) else journal.appendDeferred(draft)
    }

    fun appendText(
        kind: AgentJournalKind,
        text: String,
        contentType: String = "text/plain; charset=utf-8",
        attributes: Map<String, String> = emptyMap(),
        durable: Boolean = true,
    ): AgentJournalRecord = append(
        kind = kind,
        payload = text.toByteArray(StandardCharsets.UTF_8),
        contentType = contentType,
        lexicalText = text,
        attributes = attributes,
        durable = durable,
    )

    fun flush() = journal.flush()
}

data class AgentJournalDraft(
    val requestId: String,
    val runGeneration: Long,
    val kind: AgentJournalKind,
    val contentType: String,
    val payload: ByteArray,
    val lexicalText: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    internal fun toRecord(sequence: Long): AgentJournalRecord = AgentJournalRecord(
        sequence = sequence,
        requestId = requestId,
        runGeneration = runGeneration,
        kind = kind,
        contentType = contentType,
        payload = payload.copyOf(),
        lexicalText = lexicalText,
        attributes = LinkedHashMap(attributes),
    )
}

data class AgentJournalRecord(
    val sequence: Long,
    val requestId: String,
    val runGeneration: Long,
    val kind: AgentJournalKind,
    val contentType: String,
    val payload: ByteArray,
    val lexicalText: String,
    val attributes: Map<String, String>,
)

enum class AgentJournalKind(val wireId: Int) {
    REQUEST_INPUT_SNAPSHOT(1),
    PROVIDER_INPUT(2),
    PUBLIC_AGENT_EVENT(3),
    PRIVATE_AGENT_EVENT(4),
    PROVIDER_OUTPUT(5),
    TOOL_CALL(6),
    TOOL_RESULT(7),
    PREVIEW(8),
    FINAL(9),
    ERROR(10),
    CANCELLED(11),
    /** Typed semantic sidecar. Its source_record_ids always point back to immutable raw evidence. */
    EXPERIENCE_EVENT(12),
    ;

    companion object {
        private val byWireId = entries.associateBy(AgentJournalKind::wireId)

        internal fun fromWireId(wireId: Int): AgentJournalKind? = byWireId[wireId]
    }
}

enum class AgentMemorySearchAccess {
    ENABLED,
    DISABLED,
}

data class AgentMemorySearchBounds(
    val maxResults: Int = 8,
    val maxScannedRecords: Int = 2_000,
    val maxScannedBytes: Long = 4L * 1024L * 1024L,
    val maxExcerptChars: Int = 512,
) {
    internal fun validate() {
        require(maxResults in 1..50)
        require(maxScannedRecords in 1..50_000)
        require(maxScannedBytes in 1L..64L * 1024L * 1024L)
        require(maxExcerptChars in 32..4_096)
    }
}

data class AgentMemorySearchHit(
    val sequence: Long,
    val requestId: String,
    val runGeneration: Long,
    val kind: AgentJournalKind,
    val score: Int,
    val excerpt: String,
    val attributes: Map<String, String>,
)

data class AgentMemorySearchResult(
    val query: String,
    val access: AgentMemorySearchAccess,
    val hits: List<AgentMemorySearchHit>,
    val scannedRecords: Int,
    val scannedBytes: Long,
    val truncated: Boolean,
)

data class AgentJournalReadResult(
    val records: List<AgentJournalRecord>,
    val scannedRecords: Int,
    val truncated: Boolean,
)

data class AgentJournalStats(
    val records: Long,
    val lastSequence: Long,
    val encodedBytes: Long,
)

open class AgentJournalException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class AgentJournalCorruptionException(
    message: String,
    cause: Throwable? = null,
) : AgentJournalException(message, cause)

class AgentJournalInUseException(
    message: String,
    cause: Throwable? = null,
) : AgentJournalException(message, cause)
