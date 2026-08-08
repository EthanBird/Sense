package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import io.github.ethanbird.senseime.brain.api.ActionSkillInvocation
import io.github.ethanbird.senseime.brain.api.ActionSkillResult
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Append-only, app-private raw evidence ledger for direct Action Skill calls. */
class ActionHistoryStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, STORE_DIRECTORY)
    private val file = File(root, STORE_FILE)
    private val lockFile = File(root, LOCK_FILE)

    fun appendStarted(invocation: ActionSkillInvocation, occurredAtEpochMs: Long): Result<Unit> =
        append(
            JSONObject()
                .put("schema", SCHEMA)
                .put("event_id", UUID.randomUUID().toString())
                .put("occurred_at_epoch_ms", occurredAtEpochMs)
                .put("request_id", invocation.requestId)
                .put("skill_id", invocation.skillId)
                .put("phase", "started")
                .put("arguments", JSONObject(invocation.arguments))
                .put("lexical_text", "${invocation.skillId} action started ${invocation.arguments}"),
        )

    fun appendSucceeded(result: ActionSkillResult): Result<Unit> = append(
        JSONObject()
            .put("schema", SCHEMA)
            .put("event_id", UUID.randomUUID().toString())
            .put("occurred_at_epoch_ms", result.observedAtEpochMs)
            .put("request_id", result.requestId)
            .put("skill_id", result.skillId)
            .put("phase", "succeeded")
            .put("title", result.title)
            .put("primary_value", result.primaryValue)
            .put("secondary_value", result.secondaryValue)
            .put("insert_text", result.insertText)
            .put("source_label", result.sourceLabel)
            .put("source_url", result.sourceUrl)
            .put("attributes", JSONObject(result.attributes))
            .put("raw_payload", result.rawPayload)
            .put(
                "lexical_text",
                listOf(
                    result.skillId,
                    result.title,
                    result.primaryValue,
                    result.secondaryValue,
                    result.insertText,
                    result.sourceLabel,
                    result.rawPayload,
                ).joinToString("\n"),
            ),
    )

    fun appendFailed(
        requestId: String,
        skillId: String,
        failure: Throwable,
        occurredAtEpochMs: Long,
    ): Result<Unit> = append(
        JSONObject()
            .put("schema", SCHEMA)
            .put("event_id", UUID.randomUUID().toString())
            .put("occurred_at_epoch_ms", occurredAtEpochMs)
            .put("request_id", requestId)
            .put("skill_id", skillId)
            .put("phase", "failed")
            .put("error_type", failure::class.java.name)
            .put("error_message", failure.message.orEmpty())
            .put("lexical_text", "$skillId action failed ${failure.message.orEmpty()}"),
    )

    fun search(query: String, maxResults: Int): ActionHistorySearchPage {
        require(maxResults in 1..20)
        val normalized = query.lowercase(Locale.ROOT).trim()
        if (normalized.isEmpty()) return ActionHistorySearchPage(emptyList(), 0, 0L, false)
        val terms = lexicalTerms(normalized)
        if (terms.isEmpty()) return ActionHistorySearchPage(emptyList(), 0, 0L, false)
        return withStoreLock {
            if (!file.exists()) return@withStoreLock ActionHistorySearchPage(emptyList(), 0, 0L, false)
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                val start = (length - MAX_SEARCH_BYTES).coerceAtLeast(0L)
                input.seek(start)
                val bytes = ByteArray((length - start).toInt())
                input.readFully(bytes)
                var text = bytes.toString(StandardCharsets.UTF_8)
                val truncated = start > 0L
                if (truncated) text = text.substringAfter('\n', "")
                var scanned = 0
                val hits = ArrayList<ActionHistorySearchHit>(maxResults)
                text.lineSequence().filter(String::isNotBlank).toList().asReversed().forEach { line ->
                    if (scanned >= MAX_SEARCH_RECORDS || hits.size >= maxResults) return@forEach
                    scanned += 1
                    runCatching { JSONObject(line) }.getOrNull()?.let { record ->
                        val candidate = record.optString("lexical_text").lowercase(Locale.ROOT)
                        val matched = terms.count(candidate::contains)
                        if (matched > 0) {
                            hits += ActionHistorySearchHit(
                                id = "action:${record.getString("event_id")}",
                                text = record.optString("lexical_text").take(MAX_EXCERPT_CHARS),
                                source = "ACTION_SKILL:${record.optString("skill_id")}",
                                requestId = record.optString("request_id"),
                                score = matched * 100 + if (candidate.contains(normalized)) 1_000 else 0,
                            )
                        }
                    }
                }
                ActionHistorySearchPage(
                    hits = hits.sortedByDescending(ActionHistorySearchHit::score).take(maxResults),
                    scannedRecords = scanned,
                    scannedBytes = bytes.size.toLong(),
                    truncated = truncated || scanned >= MAX_SEARCH_RECORDS,
                )
            }
        }
    }

    private fun append(record: JSONObject): Result<Unit> = runCatching {
        val encoded = (record.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_RECORD_BYTES)
        withStoreLock {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write(encoded)
                output.fd.sync()
            }
        }
    }

    private fun <T> withStoreLock(block: () -> T): T = synchronized(STORE_MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            error("Action history directory could not be created")
        }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
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
            if (han.size <= 8) terms += buildString { han.forEach { appendCodePoint(it) } }
            for (index in 0 until han.lastIndex) {
                terms += buildString {
                    appendCodePoint(han[index])
                    appendCodePoint(han[index + 1])
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
        return terms.distinct().take(16)
    }

    companion object {
        private const val SCHEMA = "sense.action.history.v1"
        private const val STORE_DIRECTORY = "agent/action-history"
        private const val STORE_FILE = "events.jsonl"
        private const val LOCK_FILE = "events.lock"
        private const val MAX_RECORD_BYTES = 256 * 1_024
        private const val MAX_SEARCH_BYTES = 2L * 1_024L * 1_024L
        private const val MAX_SEARCH_RECORDS = 2_000
        private const val MAX_EXCERPT_CHARS = 1_000
        private val STORE_MUTEX = Any()
    }
}

data class ActionHistorySearchHit(
    val id: String,
    val text: String,
    val source: String,
    val requestId: String,
    val score: Int,
)

data class ActionHistorySearchPage(
    val hits: List<ActionHistorySearchHit>,
    val scannedRecords: Int,
    val scannedBytes: Long,
    val truncated: Boolean,
)
