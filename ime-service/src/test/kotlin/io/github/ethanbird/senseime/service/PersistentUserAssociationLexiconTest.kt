package io.github.ethanbird.senseime.service

import android.app.Activity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentUserAssociationLexiconTest {
    private lateinit var activity: Activity

    @Before
    fun clearBefore() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.deleteDatabase("sense_user_associations.db")
    }

    @After
    fun clearAfter() {
        activity.deleteDatabase("sense_user_associations.db")
    }

    @Test
    fun associationSurvivesCloseAndReload() {
        PersistentUserAssociationLexicon(activity).use { store ->
            store.record("智能", "体")
            store.record("智能", "输入法")
            store.record("智能", "输入法")
        }

        PersistentUserAssociationLexicon(activity).use { reloaded ->
            assertEquals(
                listOf("输入法", "体"),
                reloaded.lookup("智能", 8).map { it.nextText },
            )
        }
    }
}
