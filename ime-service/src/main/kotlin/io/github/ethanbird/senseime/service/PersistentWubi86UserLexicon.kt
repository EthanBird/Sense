package io.github.ethanbird.senseime.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.ethanbird.senseime.core.LearnedWubiCandidate
import io.github.ethanbird.senseime.core.MemoryWubiUserLexicon
import io.github.ethanbird.senseime.core.SerialPersistenceQueue
import io.github.ethanbird.senseime.core.WubiLearningEvidence
import io.github.ethanbird.senseime.core.WubiNegativeFeedback
import io.github.ethanbird.senseime.core.WubiUserLexicon
import io.github.ethanbird.senseime.core.WubiUserLexiconLimits
import io.github.ethanbird.senseime.core.WubiUserLexiconMutation
import java.util.concurrent.TimeUnit

/**
 * Wubi86 personalization with an isolated database and an in-memory decode path.
 *
 * [close] is a non-blocking seal-and-drain operation: mutations accepted before its lifecycle
 * lock are persisted in FIFO order, then storage is closed on the writer thread. Lookups remain
 * valid against the frozen memory snapshot; later mutations fail. Tests and process owners that
 * need a completion barrier may call [awaitPersistenceClosed] after [close].
 */
class PersistentWubi86UserLexicon private constructor(
    resources: PersistentWubi86UserLexiconResources,
) : WubiUserLexicon {
    constructor(context: Context) : this(
        createResources(context.applicationContext),
    )

    internal constructor(
        storage: Wubi86UserLexiconStorage,
        clock: () -> Long = System::currentTimeMillis,
        limits: WubiUserLexiconLimits = WubiUserLexiconLimits(),
        threadName: String = "sense-wubi86-user-lexicon-test",
        onError: (Throwable) -> Unit = {},
    ) : this(
        PersistentWubi86UserLexiconResourceFactory.create(
            openStorage = { storage },
            clock = clock,
            limits = limits,
            threadName = threadName,
            onError = onError,
        ),
    )

    private val lifecycleLock = Any()
    private val writer = resources.writer
    private val memory = resources.memory
    private var closed = false

    override fun lookup(prefix: String, limit: Int): List<LearnedWubiCandidate> =
        memory.lookup(prefix, limit)

    override fun record(
        canonicalCode: String,
        text: String,
        evidence: WubiLearningEvidence,
    ): LearnedWubiCandidate = synchronized(lifecycleLock) {
        check(!closed) { "Wubi86 user lexicon is closed" }
        memory.record(canonicalCode, text, evidence)
    }

    override fun demote(
        canonicalCode: String,
        text: String,
        feedback: WubiNegativeFeedback,
    ): LearnedWubiCandidate? = synchronized(lifecycleLock) {
        check(!closed) { "Wubi86 user lexicon is closed" }
        memory.demote(canonicalCode, text, feedback)
    }

    override fun forget(canonicalCode: String, text: String): Boolean =
        synchronized(lifecycleLock) {
            check(!closed) { "Wubi86 user lexicon is closed" }
            memory.forget(canonicalCode, text)
        }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            writer.close()
        }
    }

    fun awaitPersistenceClosed(timeout: Long, unit: TimeUnit): Boolean =
        writer.awaitClosed(timeout, unit)

    private companion object {
        const val TAG = "SenseWubi86Lexicon"
        const val WRITER_THREAD_NAME = "sense-wubi86-user-lexicon"

        fun createResources(context: Context): PersistentWubi86UserLexiconResources =
            PersistentWubi86UserLexiconResourceFactory.create(
                openStorage = { SQLiteWubi86UserLexiconStorage(context) },
                threadName = WRITER_THREAD_NAME,
                onError = { error ->
                    Log.e(TAG, "Wubi86 user lexicon persistence failed", error)
                },
            )
    }
}

internal data class PersistentWubi86UserLexiconResources(
    val writer: SerialPersistenceQueue<WubiUserLexiconMutation>,
    val memory: MemoryWubiUserLexicon,
)

internal interface Wubi86UserLexiconStorage : AutoCloseable {
    fun loadAll(): List<LearnedWubiCandidate>
    fun persist(mutation: WubiUserLexiconMutation)
}

/** Owns storage until the writer is created, then transfers storage ownership to that writer. */
internal object PersistentWubi86UserLexiconResourceFactory {
    fun create(
        openStorage: () -> Wubi86UserLexiconStorage,
        clock: () -> Long = System::currentTimeMillis,
        limits: WubiUserLexiconLimits = WubiUserLexiconLimits(),
        threadName: String,
        onError: (Throwable) -> Unit = {},
    ): PersistentWubi86UserLexiconResources {
        val storage = openStorage()
        var writer: SerialPersistenceQueue<WubiUserLexiconMutation>? = null
        try {
            val initial = storage.loadAll()
            val acquiredWriter = SerialPersistenceQueue(
                threadName = threadName,
                persist = storage::persist,
                closeStorage = storage::close,
                onError = onError,
            )
            writer = acquiredWriter
            val memory = MemoryWubiUserLexicon(
                initial = initial,
                clock = clock,
                limits = limits,
                onMutation = { mutation ->
                    check(acquiredWriter.submit(mutation)) {
                        "Wubi86 persistence writer is closed"
                    }
                },
            )
            return PersistentWubi86UserLexiconResources(acquiredWriter, memory)
        } catch (error: Throwable) {
            val acquiredWriter = writer
            if (acquiredWriter == null) {
                try {
                    storage.close()
                } catch (closeError: Throwable) {
                    if (closeError !== error) error.addSuppressed(closeError)
                }
            } else {
                // SerialPersistenceQueue owns storage now and closes it after accepted cleanup.
                acquiredWriter.close()
            }
            throw error
        }
    }
}

internal object Wubi86UserLexiconSchema {
    const val DATABASE_NAME = "sense_wubi86_user_lexicon.db"
    const val DATABASE_VERSION = 1
    const val TABLE_CANDIDATE = "wubi86_user_candidate"
    val COLUMNS = arrayOf(
        "canonical_code",
        "phrase",
        "use_count",
        "created_at_ms",
        "last_used_at_ms",
        "positive_evidence",
        "negative_evidence",
        "last_positive_evidence",
        "last_negative_at_ms",
    )
}

private class SQLiteWubi86UserLexiconStorage(context: Context) :
    SQLiteOpenHelper(
        context,
        Wubi86UserLexiconSchema.DATABASE_NAME,
        null,
        Wubi86UserLexiconSchema.DATABASE_VERSION,
    ),
    Wubi86UserLexiconStorage {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ${Wubi86UserLexiconSchema.TABLE_CANDIDATE} (
                canonical_code TEXT NOT NULL
                    CHECK(length(canonical_code) BETWEEN 1 AND 4)
                    CHECK(canonical_code NOT GLOB '*[^a-y]*'),
                phrase TEXT NOT NULL CHECK(length(phrase) BETWEEN 1 AND 64),
                use_count INTEGER NOT NULL CHECK(use_count > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                last_used_at_ms INTEGER NOT NULL CHECK(last_used_at_ms >= 0),
                positive_evidence REAL NOT NULL CHECK(positive_evidence >= 0),
                negative_evidence REAL NOT NULL CHECK(negative_evidence >= 0),
                last_positive_evidence REAL NOT NULL CHECK(last_positive_evidence >= 0),
                last_negative_at_ms INTEGER NOT NULL CHECK(last_negative_at_ms >= 0),
                PRIMARY KEY(canonical_code, phrase)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX wubi86_prefix_rank ON " +
                "${Wubi86UserLexiconSchema.TABLE_CANDIDATE}(" +
                "canonical_code, positive_evidence DESC, last_used_at_ms DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing Wubi86 user lexicon migration from $oldVersion to $newVersion")
    }

    override fun loadAll(): List<LearnedWubiCandidate> {
        val result = ArrayList<LearnedWubiCandidate>()
        val cursor = readableDatabase.query(
            Wubi86UserLexiconSchema.TABLE_CANDIDATE,
            Wubi86UserLexiconSchema.COLUMNS,
            null,
            null,
            null,
            null,
            null,
        )
        try {
            while (cursor.moveToNext()) {
                result += LearnedWubiCandidate(
                    canonicalCode = cursor.getString(0),
                    text = cursor.getString(1),
                    useCount = cursor.getInt(2),
                    createdAtMillis = cursor.getLong(3),
                    lastUsedAtMillis = cursor.getLong(4),
                    positiveEvidence = cursor.getFloat(5),
                    negativeEvidence = cursor.getFloat(6),
                    lastPositiveEvidence = cursor.getFloat(7),
                    lastNegativeAtMillis = cursor.getLong(8),
                )
            }
        } finally {
            cursor.close()
        }
        return result
    }

    override fun persist(mutation: WubiUserLexiconMutation) {
        when (mutation) {
            is WubiUserLexiconMutation.Upsert -> upsert(mutation.candidate)
            is WubiUserLexiconMutation.Delete -> delete(mutation.canonicalCode, mutation.text)
        }
    }

    private fun upsert(candidate: LearnedWubiCandidate) {
        val values = ContentValues().apply {
            put("canonical_code", candidate.canonicalCode)
            put("phrase", candidate.text)
            put("use_count", candidate.useCount)
            put("created_at_ms", candidate.createdAtMillis)
            put("last_used_at_ms", candidate.lastUsedAtMillis)
            put("positive_evidence", candidate.positiveEvidence)
            put("negative_evidence", candidate.negativeEvidence)
            put("last_positive_evidence", candidate.lastPositiveEvidence)
            put("last_negative_at_ms", candidate.lastNegativeAtMillis)
        }
        val changed = writableDatabase.update(
            Wubi86UserLexiconSchema.TABLE_CANDIDATE,
            values,
            "canonical_code = ? AND phrase = ?",
            arrayOf(candidate.canonicalCode, candidate.text),
        )
        if (changed == 0) {
            writableDatabase.insertOrThrow(
                Wubi86UserLexiconSchema.TABLE_CANDIDATE,
                null,
                values,
            )
        }
    }

    private fun delete(canonicalCode: String, text: String) {
        writableDatabase.delete(
            Wubi86UserLexiconSchema.TABLE_CANDIDATE,
            "canonical_code = ? AND phrase = ?",
            arrayOf(canonicalCode, text),
        )
    }
}
