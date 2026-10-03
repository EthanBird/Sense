package io.github.ethanbird.senseime.inputqualityfixture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real queued touch input; asynchronous injection is not permission to omit delivery assertions. */
@RunWith(AndroidJUnit4::class)
class ExternalEditorBurstInputTest : ExternalEditorTestFixture() {
    @Test fun queuedTouchBurstKeepsEveryKeyAndCommitsTheLatestSentence() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("noLearning"))
        val cases = listOf(
            "nihao" to "你好", "woxihuanbeijing" to "我喜欢北京", "qingfageweizhigeiwo" to "请发个位置给我",
            "qingjianchayixiayoujian" to "请检查一下邮件", "zhegeyingyonghenhaoyong" to "这个应用很好用",
            "jintianyouyidianlei" to "今天有一点类", // unchanged known semantic error, not a gold accuracy label
            "tangmuhemalimeitianwanshangdoukandianshi" to "汤姆和玛丽每天晚上都看电视",
            "tangmumeiyouzhuyidaomalihuanlexinfaxing" to "汤姆没有注意到玛丽换了新发型",
        )
        val output = File(artifacts, "latency.tsv")
        output.writeText("round\tquery\texpectedBaselineOutput\tactual\tspaceToEditorMs\tpassed\n")
        val cadence = File(artifacts, "burst-cadence.tsv")
        cadence.writeText("round\tquery\ttargetIntervalMs\tactualStartIntervalsMs\tspaceInjectionStartMs\tfirstExpectedTextMs\n")
        val failures = mutableListOf<String>()
        repeat(2) { round ->
            for ((query, expected) in if (round == 0) cases else cases.reversed()) {
                onMain { activity.first.text.clear() }
                SystemClock.sleep(120)
                typingBounds = keyboardBounds()
                val firstExpectedText = AtomicLong(-1)
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (s?.toString() == expected) firstExpectedText.compareAndSet(-1, SystemClock.uptimeMillis())
                    }
                }
                onMain { activity.first.addTextChangedListener(watcher) }
                try {
                    val starts = mutableListOf<Long>()
                    for (char in query) {
                        val start = SystemClock.uptimeMillis(); starts += start
                        key(char, synchronous = false, waitForAnimations = false)
                        val rest = 32 - (SystemClock.uptimeMillis() - start)
                        if (rest > 0) SystemClock.sleep(rest)
                    }
                    val start = SystemClock.uptimeMillis()
                    key(' ', synchronous = false, waitForAnimations = false)
                    val deadline = start + 8_000
                    while ((text() != expected || composing()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
                    val actual = text()
                    val observed = firstExpectedText.get()
                    val passed = actual == expected && !composing() && observed >= start
                    val elapsed = if (observed >= start) observed - start else SystemClock.uptimeMillis() - start
                    output.appendText("$round\t$query\t$expected\t$actual\t$elapsed\t$passed\n")
                    cadence.appendText("$round\t$query\t32\t${starts.zipWithNext { a,b -> b-a }.joinToString(",")}\t$start\t$observed\n")
                    if (!passed) failures += "$query -> $actual"
                } finally { onMain { activity.first.removeTextChangedListener(watcher) } }
                SystemClock.sleep(150)
            }
        }
        assertTrue("Queued input lost or miscommitted: $failures", failures.isEmpty())
    }
}
