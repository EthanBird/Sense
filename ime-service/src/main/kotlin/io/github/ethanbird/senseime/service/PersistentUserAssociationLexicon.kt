package io.github.ethanbird.senseime.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.ethanbird.senseime.core.LearnedAssociation
import io.github.ethanbird.senseime.core.MemoryUserAssociationLexicon
import io.github.ethanbird.senseime.core.SerialPersistenceQueue
import io.github.ethanbird.senseime.core.UserAssociationLexicon
import java.util.concurrent.TimeUnit

/** In-memory association lookup with a serialized SQLite durability journal. */
class PersistentUserAssociationLexicon private constructor(
    resources: PersistentUserAssociationResources,
) : UserAssociationLexicon {
    constructor(context: Context) : this(createResources(context.applicationContext))

    private val lifecycleLock = Any()
    private val writer = resources.writer
    private val memory = resources.memory
    private var closed = false

    override fun lookup(context: String, limit: Int): List<LearnedAssociation> =
        memory.lookup(context, limit)

    override fun record(context: String, nextText: String): LearnedAssociation =
        synchronized(lifecycleLock) {
            check(!closed) { "User association lexicon is closed" }
            memory.record(context, nextText)
        }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            writer.close()
            if (!writer.awaitClosed(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out while draining user association persistence")
            }
        }
    }

    private companion object {
        fun createResources(context: Context): PersistentUserAssociationResources {
            val database = UserAssociationDatabase(context)
            try {
                val initial = database.loadAll()
                val writer = SerialPersistenceQueue<UserAssociationPersistence>(
                    threadName = "sense-user-associations",
                    persist = database::persistMutation,
                    closeStorage = database::close,
                    onError = { error -> Log.e(TAG, "User association persistence failed", error) },
                )
                return PersistentUserAssociationResources(
                    writer = writer,
                    memory = MemoryUserAssociationLexicon(
                        initial = initial,
                        onRecord = { value ->
                            check(writer.submit(UserAssociationPersistence.Upsert(value))) {
                                "User association lexicon is closed"
                            }
                        },
                        onRemove = { contextText, nextText ->
                            check(
                                writer.submit(
                                    UserAssociationPersistence.Delete(contextText, nextText),
                                ),
                            ) { "User association lexicon is closed" }
                        },
                    ),
                )
            } catch (error: Throwable) {
                runCatching(database::close).exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }

        const val TAG = "SenseUserAssociations"
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}

private data class PersistentUserAssociationResources(
    val writer: SerialPersistenceQueue<UserAssociationPersistence>,
    val memory: MemoryUserAssociationLexicon,
)

private sealed interface UserAssociationPersistence {
    data class Upsert(val value: LearnedAssociation) : UserAssociationPersistence
    data class Delete(val context: String, val nextText: String) : UserAssociationPersistence
}

private class UserAssociationDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ASSOCIATION (
                context_text TEXT NOT NULL,
                next_text TEXT NOT NULL,
                use_count INTEGER NOT NULL CHECK(use_count > 0),
                created_at_ms INTEGER NOT NULL,
                last_used_at_ms INTEGER NOT NULL,
                PRIMARY KEY(context_text, next_text)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX user_association_rank ON " +
                "$TABLE_ASSOCIATION(context_text, use_count DESC, last_used_at_ms DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadAll(): List<LearnedAssociation> {
        val values = ArrayList<LearnedAssociation>()
        val cursor = readableDatabase.query(
            TABLE_ASSOCIATION,
            COLUMNS,
            null,
            null,
            null,
            null,
            null,
        )
        try {
            while (cursor.moveToNext()) {
                values += LearnedAssociation(
                    context = cursor.getString(0),
                    nextText = cursor.getString(1),
                    useCount = cursor.getInt(2),
                    createdAtMillis = cursor.getLong(3),
                    lastUsedAtMillis = cursor.getLong(4),
                )
            }
        } finally {
            cursor.close()
        }
        return values
    }

    fun persistMutation(mutation: UserAssociationPersistence) {
        when (mutation) {
            is UserAssociationPersistence.Upsert -> persist(mutation.value)
            is UserAssociationPersistence.Delete -> delete(mutation.context, mutation.nextText)
        }
    }

    private fun persist(value: LearnedAssociation) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = ContentValues().apply {
                put("use_count", value.useCount)
                put("last_used_at_ms", value.lastUsedAtMillis)
            }
            val changed = db.update(
                TABLE_ASSOCIATION,
                update,
                "context_text = ? AND next_text = ?",
                arrayOf(value.context, value.nextText),
            )
            if (changed == 0) {
                val insert = ContentValues(update).apply {
                    put("context_text", value.context)
                    put("next_text", value.nextText)
                    put("created_at_ms", value.createdAtMillis)
                }
                db.insertOrThrow(TABLE_ASSOCIATION, null, insert)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun delete(context: String, nextText: String) {
        writableDatabase.delete(
            TABLE_ASSOCIATION,
            "context_text = ? AND next_text = ?",
            arrayOf(context, nextText),
        )
    }

    private companion object {
        const val DATABASE_NAME = "sense_user_associations.db"
        const val DATABASE_VERSION = 1
        const val TABLE_ASSOCIATION = "user_association"
        val COLUMNS = arrayOf(
            "context_text",
            "next_text",
            "use_count",
            "created_at_ms",
            "last_used_at_ms",
        )
    }
}
