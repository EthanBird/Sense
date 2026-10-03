package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch input for words lost to crowded mixed-spelling expansion. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorMixedRecallTest : ExternalEditorTestFixture() {
    @Test fun rareMixedWordSurvivesExpandedDictionary() = confirm("yisscp", "艺术收藏品")
    @Test fun lastSyllableFullSpellingRecallsTheKnownPhrase() = confirm("xqdluo", "心情低落")
    @Test fun middleAbbreviationRecallsTheKnownPhrase() = confirm("tcsfei", "停车收费")

    private fun confirm(raw: String, expected: String) {
        type(raw, 12)
        key(' ')
        await("Mixed word must commit to the real external editor") { text() == expected && !composing() }
        File(artifacts, "${name.methodName}.txt").appendText("typed=$raw\ncommitted=$expected\n")
        // Text mutation precedes the compositor frame; a teardown screenshot
        // taken immediately can still contain the prior composing spelling.
        instrumentation.waitForIdleSync()
        device.waitForIdle(1_000)
        SystemClock.sleep(100)
    }
}
