package io.github.ethanbird.senseime.service

import android.app.Activity
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserSelectionKind
import io.github.ethanbird.senseime.core.UserNegativeFeedback
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentContextSelectionTest {
    private lateinit var activity: Activity
    private val database = "sense_user_lexicon.db"
    @Before fun before() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.deleteDatabase(database)
    }
    @After fun after() { activity.deleteDatabase(database) }
    private fun close(store: PersistentUserLexicon) {
        store.close()
        assertTrue(store.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun contextSurvivesOrderedWritesReloadAndScopedRejection() {
        val first = PersistentUserLexicon(activity)
        val a = first.record("quanli", "ql", "权利", evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1, "人民"))
        first.record("quanli", "ql", "权利", evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1, "公民"))
        first.record("quanli", "ql", "权力", evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1, "获得"))
        close(first)
        val second = PersistentUserLexicon(activity)
        assertEquals("权利", second.lookupInContext("quanli", "人民", 1).single().text)
        assertEquals("权力", second.lookupInContext("quanli", "获得", 1).single().text)
        second.demoteInContext("quanli", "权利", UserNegativeFeedback.QUICK_DELETE, "人民", a.contextSelections["人民"])
        close(second)
        val third = PersistentUserLexicon(activity)
        assertFalse(third.lookupInContext("quanli", "人民", 8).any { it.preferredInContext })
        assertTrue(third.lookupInContext("quanli", "公民", 1).single().preferredInContext)
        assertTrue(third.forget("quanli", "权利"))
        close(third)
        val fourth = PersistentUserLexicon(activity)
        assertFalse(fourth.lookup("quanli", 8).any { it.text == "权利" })
        close(fourth)
    }

    @Test fun versionsOneThroughThreeMigrateWithoutLosingExistingWordsOrAliases() {
        for (version in 1..3) {
            activity.deleteDatabase(database)
            activity.openOrCreateDatabase(database, 0, null).use { db ->
                val extra = (if (version >= 2) ", aliases TEXT NOT NULL DEFAULT ''" else "") +
                    (if (version >= 3) ", positive_evidence REAL NOT NULL DEFAULT 0, negative_evidence REAL NOT NULL DEFAULT 0, last_positive_evidence REAL NOT NULL DEFAULT 0.18, last_negative_at_ms INTEGER NOT NULL DEFAULT 0" else "")
                db.execSQL("CREATE TABLE user_phrase (full_pinyin TEXT NOT NULL, phrase TEXT NOT NULL, initials TEXT NOT NULL, use_count INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, last_used_at_ms INTEGER NOT NULL$extra, PRIMARY KEY(full_pinyin, phrase)) WITHOUT ROWID")
                db.execSQL("INSERT INTO user_phrase(full_pinyin,phrase,initials,use_count,created_at_ms,last_used_at_ms) VALUES('quanli','权利','ql',5,100,200)")
                if (version >= 2) db.execSQL("UPDATE user_phrase SET aliases='qlx'")
                db.version = version
            }
            val migrated = PersistentUserLexicon(activity)
            val original = migrated.lookup("quanli", 8).single()
            assertEquals(5, original.useCount)
            assertEquals(100L, original.createdAtMillis)
            assertTrue(original.contextSelections.isEmpty())
            if (version >= 2) assertTrue("qlx" in original.aliases)
            migrated.record("quanli", "ql", "权利", evidence = UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, 1, "人民"))
            close(migrated)
            val reopened = PersistentUserLexicon(activity)
            assertTrue(reopened.lookupInContext("quanli", "人民", 1).single().preferredInContext)
            close(reopened)
        }
    }
}
