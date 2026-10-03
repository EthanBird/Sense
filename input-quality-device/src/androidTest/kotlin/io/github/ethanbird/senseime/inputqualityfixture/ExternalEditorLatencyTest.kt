package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A deterministic diagnostic workload, not a population estimate of typing or language quality. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorLatencyTest : ExternalEditorTestFixture() {
    @Test fun shortAndLongConfirmationLatencyKeepsTheCompleteBaselineOutput() {
        assertEquals("Latency runs must isolate personal learning", "true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val cases = listOf(
            "nihao" to "你好",
            "woxihuanbeijing" to "我喜欢北京",
            "qingfageweizhigeiwo" to "请发个位置给我",
            "qingjianchayixiayoujian" to "请检查一下邮件",
            "zhegeyingyonghenhaoyong" to "这个应用很好用",
            // Deliberately retain a known semantic error rather than selecting only easy gold.
            // This is unchanged baseline output, NOT an accuracy assertion for the intended 累.
            "jintianyouyidianlei" to "今天有一点类",
            "tangmuhemalimeitianwanshangdoukandianshi" to "汤姆和玛丽每天晚上都看电视",
            "tangmumeiyouzhuyidaomalihuanlexinfaxing" to "汤姆没有注意到玛丽换了新发型",
        )
        val output = File(artifacts, "latency.tsv")
        output.writeText("round\tquery\texpectedBaselineOutput\tactual\tspaceToEditorMs\tpassed\n")
        val failures = mutableListOf<String>()
        repeat(2) { round ->
            // Reverse the second traversal so short/long queries do not always occupy the same JIT phase.
            for ((query, expected) in if (round == 0) cases else cases.reversed()) {
                onMain { activity.first.text.clear() }
                SystemClock.sleep(120)
                typingBounds = keyboardBounds()
                type(query, 12)
                val start = SystemClock.uptimeMillis()
                key(' ')
                val end = start + 8_000
                while (composing() && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
                val elapsed = SystemClock.uptimeMillis() - start
                val actual = text()
                val passed = actual == expected && !composing()
                output.appendText("$round\t$query\t$expected\t$actual\t$elapsed\t$passed\n")
                if (!passed) failures += "$query -> $actual"
                SystemClock.sleep(150)
            }
        }
        assertTrue("Complete-output regressions: $failures", failures.isEmpty())
    }
}
