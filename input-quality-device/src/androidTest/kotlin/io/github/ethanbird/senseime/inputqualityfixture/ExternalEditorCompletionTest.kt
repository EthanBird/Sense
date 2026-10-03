package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Source-aware completion ranking through the real keyboard and external editor. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorCompletionTest : ExternalEditorTestFixture() {
    @Test fun shortMixedSpellingCommitsTypedWordNotPredictedSentence() = confirm("nix", "你想")
    @Test fun existingShortMixedWordKeepsItsFirstSlot() = confirm("wox", "我想")
    @Test fun unfinishedFirstSyllableKeepsLiteralCompletionInsteadOfEditingItsLetters() = confirm("go", "公司")
    @Test fun singleInitialKeepsItsChinesePrefixEvidence() = confirm("f", "分")
    @Test fun rareExactWordDoesNotHideACommonUnfinishedWord() = confirm("wome", "我们")
    @Test fun completeCommonWordStillBeatsLongerPredictions() = confirm("zhege", "这个")
    @Test fun unfinishedPronounKeepsLiteralSpellingAheadOfUnrelatedRepairs() = confirm("nime", "你们")
    @Test fun incompleteCommonWordDoesNotBorrowTheCompositionCorrectionBoost() = confirm("renme", "人们")
    @Test fun deletingAKeyRecomputesTheIncompleteWordBeforeConfirmation() {
        type("nixi", 20); key('\b'); key(' ')
        finish("nixi + backspace", "你想")
    }
    @Test fun enterStillCommitsTheActualLatinKeysInsteadOfAnyCompletion() {
        type("nix",20);key('\n');finish("nix + enter","nix")
    }
    private fun confirm(raw:String,expected:String) {
        type(raw,20);key(' ');finish(raw,expected)
    }
    private fun finish(raw:String,expected:String) {
        await("Completion must not replace the typed word") {text()==expected && !composing()}
        File(artifacts,"${name.methodName}.txt").appendText("typed=$raw\ncommitted=$expected\n")
        instrumentation.waitForIdleSync();device.waitForIdle(1_000);SystemClock.sleep(100)
    }
}
