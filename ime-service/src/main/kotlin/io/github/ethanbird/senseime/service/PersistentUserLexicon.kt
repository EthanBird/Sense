package io.github.ethanbird.senseime.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.SerialPersistenceQueue
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserLexicon
import io.github.ethanbird.senseime.core.UserNegativeFeedback

/**
 * Hot lookups stay in a pure Kotlin snapshot; SQLite is only the durable journal.
 * Upserts and deletions are serialized off the IME main thread as absolute snapshots.
 */
class PersistentUserLexicon private constructor(
    resources: PersistentUserLexiconResources,
) : UserLexicon {
    constructor(context: Context) : this(
        createResources(context.applicationContext),
    )

    private val lifecycleLock = Any()
    private val writer = resources.writer
    private val memory = resources.memory
    private var closed = false

    override fun lookup(code: String, limit: Int): List<LearnedPhrase> = memory.lookup(code, limit)

    override fun record(
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String>,
        evidence: UserLearningEvidence,
    ): LearnedPhrase = synchronized(lifecycleLock) {
        check(!closed) { "User lexicon is closed" }
        memory.record(fullPinyin, initials, text, aliases, evidence)
    }

    override fun demote(
        fullPinyin: String,
        text: String,
        feedback: UserNegativeFeedback,
    ): LearnedPhrase? = synchronized(lifecycleLock) {
        check(!closed) { "User lexicon is closed" }
        memory.demote(fullPinyin, text, feedback)
    }

    override fun forget(fullPinyin: String, text: String): Boolean = synchronized(lifecycleLock) {
        check(!closed) { "User lexicon is closed" }
        memory.forget(fullPinyin, text)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            writer.close()
        }
    }

    private companion object {
        fun createResources(context: Context): PersistentUserLexiconResources =
            PersistentUserLexiconResourceInitializer.initialize(
                openStorage = { UserLexiconDatabase(context) },
                load = UserLexiconDatabase::loadAll,
                openWriter = { database ->
                    SerialPersistenceQueue(
                        threadName = "sense-user-lexicon",
                        persist = database::persistMutation,
                        closeStorage = database::close,
                        onError = { error ->
                            Log.e(TAG, "User lexicon persistence failed", error)
                        },
                    )
                },
                build = { initial, writer ->
                    PersistentUserLexiconResources(
                        writer = writer,
                        memory = MemoryUserLexicon(
                            initial = initial,
                            onRecord = { phrase ->
                                check(
                                    writer.submit(UserLexiconPersistence.Upsert(phrase)),
                                ) { "User lexicon is closed" }
                            },
                            onForget = { fullPinyin, text ->
                                check(
                                    writer.submit(
                                        UserLexiconPersistence.Delete(fullPinyin, text),
                                    ),
                                ) { "User lexicon is closed" }
                            },
                        ),
                    )
                },
                closeStorage = UserLexiconDatabase::close,
                closeWriter = SerialPersistenceQueue<UserLexiconPersistence>::close,
            )

        const val TAG = "SenseUserLexicon"
    }
}

private data class PersistentUserLexiconResources(
    val writer: SerialPersistenceQueue<UserLexiconPersistence>,
    val memory: MemoryUserLexicon,
)

/**
 * Transfers storage ownership to the writer only after the initial read
 * succeeds. Every failure path closes the resource that currently owns storage.
 */
internal object PersistentUserLexiconResourceInitializer {
    fun <Storage : Any, Initial, Writer : Any, Result> initialize(
        openStorage: () -> Storage,
        load: (Storage) -> Initial,
        openWriter: (Storage) -> Writer,
        build: (Initial, Writer) -> Result,
        closeStorage: (Storage) -> Unit,
        closeWriter: (Writer) -> Unit,
    ): Result {
        val storage = openStorage()
        var writer: Writer? = null
        try {
            val initial = load(storage)
            val acquiredWriter = openWriter(storage)
            writer = acquiredWriter
            return build(initial, acquiredWriter)
        } catch (error: Throwable) {
            val acquiredWriter = writer
            if (acquiredWriter == null) {
                closeAfterFailure(error) { closeStorage(storage) }
            } else {
                val writerCloseSucceeded = closeAfterFailure(error) {
                    closeWriter(acquiredWriter)
                }
                if (!writerCloseSucceeded) {
                    closeAfterFailure(error) { closeStorage(storage) }
                }
            }
            throw error
        }
    }

    private inline fun closeAfterFailure(
        primary: Throwable,
        close: () -> Unit,
    ): Boolean = try {
        close()
        true
    } catch (closeError: Throwable) {
        if (closeError !== primary) primary.addSuppressed(closeError)
        false
    }
}

private sealed interface UserLexiconPersistence {
    data class Upsert(val phrase: LearnedPhrase) : UserLexiconPersistence
    data class Delete(val fullPinyin: String, val text: String) : UserLexiconPersistence
}

private class UserLexiconDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PHRASE (
                full_pinyin TEXT NOT NULL,
                phrase TEXT NOT NULL,
                initials TEXT NOT NULL,
                use_count INTEGER NOT NULL CHECK(use_count > 0),
                created_at_ms INTEGER NOT NULL,
                last_used_at_ms INTEGER NOT NULL,
                aliases TEXT NOT NULL DEFAULT '',
                positive_evidence REAL NOT NULL DEFAULT 0,
                negative_evidence REAL NOT NULL DEFAULT 0,
                last_positive_evidence REAL NOT NULL DEFAULT 0.18,
                last_negative_at_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(full_pinyin, phrase)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX user_phrase_initials_rank ON $TABLE_PHRASE(initials, use_count DESC, last_used_at_ms DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_PHRASE ADD COLUMN aliases TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_PHRASE ADD COLUMN positive_evidence REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PHRASE ADD COLUMN negative_evidence REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PHRASE ADD COLUMN last_positive_evidence REAL NOT NULL DEFAULT 0.18")
            db.execSQL("ALTER TABLE $TABLE_PHRASE ADD COLUMN last_negative_at_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $TABLE_PHRASE SET positive_evidence = use_count")
        }
    }

    fun loadAll(): List<LearnedPhrase> {
        val values = ArrayList<LearnedPhrase>()
        val cursor = readableDatabase.query(
            TABLE_PHRASE,
            COLUMNS,
            null,
            null,
            null,
            null,
            null,
        )
        try {
            while (cursor.moveToNext()) {
                values += LearnedPhrase(
                    fullPinyin = cursor.getString(0),
                    text = cursor.getString(1),
                    initials = cursor.getString(2),
                    useCount = cursor.getInt(3),
                    createdAtMillis = cursor.getLong(4),
                    lastUsedAtMillis = cursor.getLong(5),
                    aliases = cursor.getString(6)
                        .split(',')
                        .filter(String::isNotEmpty)
                        .toSet(),
                    positiveEvidence = cursor.getFloat(7),
                    negativeEvidence = cursor.getFloat(8),
                    lastPositiveEvidence = cursor.getFloat(9),
                    lastNegativeAtMillis = cursor.getLong(10),
                )
            }
        } finally {
            cursor.close()
        }
        return values
    }

    fun persistMutation(mutation: UserLexiconPersistence) {
        when (mutation) {
            is UserLexiconPersistence.Upsert -> persist(mutation.phrase)
            is UserLexiconPersistence.Delete -> delete(mutation.fullPinyin, mutation.text)
        }
    }

    private fun persist(phrase: LearnedPhrase) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = ContentValues().apply {
                put("initials", phrase.initials)
                put("use_count", phrase.useCount)
                put("last_used_at_ms", phrase.lastUsedAtMillis)
                put("aliases", phrase.aliases.sorted().joinToString(","))
                put("positive_evidence", phrase.positiveEvidence)
                put("negative_evidence", phrase.negativeEvidence)
                put("last_positive_evidence", phrase.lastPositiveEvidence)
                put("last_negative_at_ms", phrase.lastNegativeAtMillis)
            }
            val changed = db.update(
                TABLE_PHRASE,
                update,
                "full_pinyin = ? AND phrase = ?",
                arrayOf(phrase.fullPinyin, phrase.text),
            )
            if (changed == 0) {
                val insert = ContentValues(update).apply {
                    put("full_pinyin", phrase.fullPinyin)
                    put("phrase", phrase.text)
                    put("created_at_ms", phrase.createdAtMillis)
                }
                db.insertOrThrow(TABLE_PHRASE, null, insert)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun delete(fullPinyin: String, text: String) {
        writableDatabase.delete(
            TABLE_PHRASE,
            "full_pinyin = ? AND phrase = ?",
            arrayOf(fullPinyin, text),
        )
    }

    private companion object {
        const val DATABASE_NAME = "sense_user_lexicon.db"
        const val DATABASE_VERSION = 3
        const val TABLE_PHRASE = "user_phrase"
        val COLUMNS = arrayOf(
            "full_pinyin",
            "phrase",
            "initials",
            "use_count",
            "created_at_ms",
            "last_used_at_ms",
            "aliases",
            "positive_evidence",
            "negative_evidence",
            "last_positive_evidence",
            "last_negative_at_ms",
        )
    }
}
