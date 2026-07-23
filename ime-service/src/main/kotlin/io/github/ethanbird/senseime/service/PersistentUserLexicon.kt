package io.github.ethanbird.senseime.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.SerialPersistenceQueue
import io.github.ethanbird.senseime.core.UserLexicon

/**
 * Hot lookups stay in a pure Kotlin snapshot; SQLite is only the durable journal.
 * Writes are serialized off the IME main thread and update absolute counters.
 */
class PersistentUserLexicon(context: Context) : UserLexicon {
    private val lifecycleLock = Any()
    private val database = UserLexiconDatabase(context.applicationContext)
    private val writer = SerialPersistenceQueue(
        threadName = "sense-user-lexicon",
        persist = database::persist,
        closeStorage = database::close,
        onError = { error -> Log.e(TAG, "User lexicon persistence failed", error) },
    )
    private val memory = MemoryUserLexicon(
        initial = database.loadAll(),
        onRecord = { phrase -> check(writer.submit(phrase)) { "User lexicon is closed" } },
    )
    private var closed = false

    override fun lookup(code: String, limit: Int): List<LearnedPhrase> = memory.lookup(code, limit)

    override fun record(
        fullPinyin: String,
        initials: String,
        text: String,
        aliases: Set<String>,
    ): LearnedPhrase = synchronized(lifecycleLock) {
        check(!closed) { "User lexicon is closed" }
        memory.record(fullPinyin, initials, text, aliases)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            writer.close()
        }
    }

    private companion object {
        const val TAG = "SenseUserLexicon"
    }
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
                )
            }
        } finally {
            cursor.close()
        }
        return values
    }

    fun persist(phrase: LearnedPhrase) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = ContentValues().apply {
                put("initials", phrase.initials)
                put("use_count", phrase.useCount)
                put("last_used_at_ms", phrase.lastUsedAtMillis)
                put("aliases", phrase.aliases.sorted().joinToString(","))
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

    private companion object {
        const val DATABASE_NAME = "sense_user_lexicon.db"
        const val DATABASE_VERSION = 2
        const val TABLE_PHRASE = "user_phrase"
        val COLUMNS = arrayOf(
            "full_pinyin",
            "phrase",
            "initials",
            "use_count",
            "created_at_ms",
            "last_used_at_ms",
            "aliases",
        )
    }
}
