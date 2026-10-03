package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production model through real 26-key touch input and the external InputConnection. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorCorrectionTest : ExternalEditorTestFixture() {
    @Test fun missingKeyAtSentenceStartCanBeConfirmedAsChinese() = confirm("wbuxihuanzuoye", "我不喜欢作业")
    @Test fun missingKeyInsideTheSentenceCanBeConfirmedAsChinese() = confirm("wobuxhuanzuoye", "我不喜欢作业")
    @Test fun missingParticleKeyKeepsTheSentenceContext() = confirm("zhexieshiwomendshuzhuo", "这些是我们的书桌")
    @Test fun validExtraSyllableDoesNotBuryTheContextualRepair() = confirm("woobuxihuanzuoye", "我不喜欢作业")
    @Test fun validFuzzySyllableDoesNotBuryTheContextualRepair() = confirm("wobujieyizaiyuzongmanbu", "我不介意在雨中漫步")
    @Test fun unknownInterjectionDoesNotDisplaceTheObservedSentence() = confirm("geimianbaoderenbuhuie", "给面包的人不会饿")
    @Test fun unknownNameGlyphDoesNotDisplaceTheObservedPhrase() = confirm("ruguoniquwennaxiebainianrenrui", "如果你去问那些百年人瑞")
    @Test fun supplementalIntelligentAgentIsFirstWithoutLearning() = confirm("zhinengti", "智能体")
    @Test fun swappedKeysAcrossSyllablesKeepTheSentenceContext() = confirm("wobuxihuaznuoye", "我不喜欢作业")
    @Test fun swappedKeysAcrossWordBoundaryCanBeConfirmed() = confirm("wobujieyizaiyuzhonmganbu", "我不介意在雨中漫步")

    private fun confirm(typed: String, expected: String) {
        type(typed, 12)
        val started = SystemClock.uptimeMillis()
        key(' ')
        await("Corrected Chinese commits through real system input") { text() == expected && !composing() }
        File(artifacts, "${name.methodName}.txt").appendText(
            "space_to_committed_editor_ms=${SystemClock.uptimeMillis()-started}\nscope=single diagnostic system sample, not p95\n")
        SystemClock.sleep(400)
        assertEquals(expected,text())
    }
}
