package io.github.ethanbird.senseime.service

import android.app.Activity
import io.github.ethanbird.senseime.core.EnglishLexicon
import io.github.ethanbird.senseime.core.UserLearningEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistentEnglishWordUsageStoreTest {
    @Test
    fun acceptedCustomWordSurvivesStoreRecreationAndBecomesExactCandidate() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        PersistentEnglishWordUsageStore(activity).record(
            "codex",
            UserLearningEvidence.EXPLICIT_SELECTION,
        )

        val reopened = PersistentEnglishWordUsageStore(activity)
        val lexicon = EnglishLexicon.fromWords(emptyList(), reopened)
        val candidates = lexicon.suggest("codex", 5)

        assertEquals("codex", candidates.single().text)
        assertTrue(checkNotNull(reopened.find("codex")).useCount >= 1)
    }
}
