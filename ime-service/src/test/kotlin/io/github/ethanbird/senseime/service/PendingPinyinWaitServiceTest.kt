package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import io.github.ethanbird.senseime.ui.KeyCodes
import android.app.Activity
import android.inputmethodservice.InputMethodService
import android.os.Looper
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/** Minimized production timer boundary; no decoder or timing-policy reimplementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingPinyinWaitServiceTest {
    @Test fun lateChineseResultStillCommitsOnceAfterSeveralSoftDeadlinesAndReplaysFifo() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        "nihao".forEach { f.key(it.code) }
        f.key(KeyCodes.SPACE)
        repeat(3) { invoke(f.service, "resolvePendingCommitTimeout") }
        assertEquals("woxihuanbeijing", f.editor.text.toString())
        f.deliver("我喜欢北京")
        assertEquals("我喜欢北京nihao", f.editor.text.toString())
        assertTrue(f.pending.isPending)
        f.deliver("你好")
        assertEquals("我喜欢北京你好", f.editor.text.toString())
        assertFalse(f.pending.isPending)
    }

    @Test fun explicitEnterDuringSlowWaitCommitsRawWithoutNewlineAndIgnoresOldResult() {
        val f = Fixture()
        val old = f.request()
        f.key(KeyCodes.SPACE)
        invoke(f.service, "resolvePendingCommitTimeout")
        f.key(KeyCodes.ENTER)
        assertEquals("woxihuanbeijing", f.editor.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(f.editor.text))
        assertFalse(f.pending.isPending)
        invoke(f.service, "applyDecodedCandidates", old, ProgressivePinyinDecoding(14, "woxihuanbeijing", listOf(Candidate("我喜欢北京")), emptyList()))
        assertEquals("woxihuanbeijing", f.editor.text.toString())
    }

    @Test fun slowBackspaceEditsPendingPinyinAndLateOldResultIsIgnored() {
        val f = Fixture()
        val old = f.request()
        f.key(KeyCodes.SPACE)
        invoke(f.service, "resolvePendingCommitTimeout")
        f.key(KeyCodes.DELETE)
        assertEquals("woxihuanbeijin", f.editor.text.toString())
        assertFalse(f.pending.isPending)
        invoke(f.service, "applyDecodedCandidates", old, ProgressivePinyinDecoding(14, "woxihuanbeijing", listOf(Candidate("我喜欢北京")), emptyList()))
        assertEquals("woxihuanbeijin", f.editor.text.toString())
    }

    @Test fun backspaceRemovesLatestQueuedCharacterBeforeEditingOriginalComposition() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        "ni".forEach { f.key(it.code) }
        invoke(f.service, "resolvePendingCommitTimeout")
        f.key(KeyCodes.DELETE)
        assertEquals(1, f.pending.deferredCount)
        assertEquals("woxihuanbeijing", f.editor.text.toString())
        f.deliver("我喜欢北京")
        assertEquals("我喜欢北京n", f.editor.text.toString())
    }

    @Test fun overfullQueueDoesNotFlushRawAndBackspaceFreesOneSlot() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        repeat(512) { f.key('n'.code) }
        f.key('x'.code)
        assertTrue(f.pending.isFull)
        assertTrue(f.pending.capacityRejected)
        assertEquals("woxihuanbeijing", f.editor.text.toString())
        f.key(KeyCodes.DELETE)
        f.key('i'.code)
        assertEquals(512, f.pending.deferredCount)
        assertTrue(f.pending.isPending)
    }

    @Test fun workerFailureIsNotAnEmptySuccessAndPreservesPendingSpace() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        invoke(f.service, "postCandidateDecodeFailure", f.request())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.pending.isPending)
        assertTrue(f.pending.needsAttention)
        assertEquals("woxihuanbeijing", f.editor.text.toString())
        assertTrue(BaseInputConnection.getComposingSpanStart(f.editor.text) >= 0)
    }

    @Test fun explicitRawRecoveryReplaysLaterChineseInputInsteadOfLosingIt() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        "nihao".forEach { f.key(it.code) }
        f.key(KeyCodes.SPACE)
        invoke(f.service, "resolvePendingCommitTimeout")
        f.key(KeyCodes.ENTER)
        assertEquals("woxihuanbeijingnihao", f.editor.text.toString())
        f.deliver("你好")
        assertEquals("woxihuanbeijing你好", f.editor.text.toString())
    }

    @Test fun backspaceEditsQueuedTextByUnicodeCodePointIncludingTrigger() {
        val f = Fixture()
        invoke(f.service, "commitText", "a😀")
        invoke(f.service, "resolvePendingCommitTimeout")
        f.key(KeyCodes.DELETE)
        f.deliver("我喜欢北京")
        assertEquals("我喜欢北京a", f.editor.text.toString())
    }

    @Test fun staleTimeoutNeverTouchesTheNewerComposition() {
        val f = Fixture()
        f.key(KeyCodes.SPACE)
        field(f.service, "composition").set(f.service, PinyinComposition(remainingPinyin = "nihao", revision = 20))
        invoke(f.service, "resolvePendingCommitTimeout")
        assertFalse(f.pending.isPending)
        assertEquals("nihao", (field(f.service, "composition").get(f.service) as PinyinComposition).remainingPinyin)
    }

    @Test fun slowSpaceKeepsItsTransactionAndFollowUpInputInsteadOfSubmittingRaw() {
        // No lifecycle init: the real worker is deliberately absent, as in a stalled runtime.
        val service = Robolectric.buildService(SenseInputMethodService::class.java).get()
        val composition = PinyinComposition(remainingPinyin = "woxihuanbeijing", revision = 14)
        field(service, "composition").set(service, composition)
        @Suppress("UNCHECKED_CAST")
        val pending = field(service, "pendingDecodeCommit").get(service) as PendingDecodeCommitCoordinator<Any>
        pending.start(PendingDecodeCommit.Candidate(14))
        pending.defer("next input sentinel")

        invoke(service, "resolvePendingCommitTimeout")

        assertEquals("Space must keep waiting for Chinese, not discard its transaction", PendingDecodeCommit.Candidate(14), pending.intent)
        assertEquals(1, pending.deferredCount)
        assertEquals(composition, field(service, "composition").get(service))
    }

    private fun field(service: SenseInputMethodService, name: String) =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun invoke(service: SenseInputMethodService, name: String, vararg args: Any) =
        service.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(service, *args)

    private inner class Fixture {
        val service = Robolectric.buildService(SenseInputMethodService::class.java).get()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val editor = EditText(activity)
        val connection = object : BaseInputConnection(editor, true) {
            override fun getEditable() = editor.editableText
        }
        @Suppress("UNCHECKED_CAST")
        val pending = field(service, "pendingDecodeCommit").get(service) as PendingDecodeCommitCoordinator<Any>
        init {
            InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(service, connection)
            field(service, "composition").set(service, PinyinComposition(remainingPinyin = "woxihuanbeijing", revision = 14))
            connection.setComposingText("woxihuanbeijing", 1)
        }
        fun key(code: Int) { invoke(service, "handleKey", code) }
        fun request(): Any {
            val composition = field(service, "composition").get(service) as PinyinComposition
            return Class.forName("io.github.ethanbird.senseime.service.CandidateDecodeRequest").declaredConstructors.single()
                .apply { isAccessible = true }.newInstance(composition, "", 0L, FakeDecoder(), PinyinSyllableSegmenter(listOf("wo", "xi", "huan", "bei", "jing", "ni", "hao")))
        }
        fun deliver(text: String) {
            val composition = field(service, "composition").get(service) as PinyinComposition
            val session = field(service, "candidateSession").get(service) as CandidateDecodeSession
            session.begin(composition, decoderGeneration = 0L, decoderReady = true)
            invoke(service, "applyDecodedCandidates", request(), ProgressivePinyinDecoding(composition.revision, composition.remainingPinyin, listOf(Candidate(text)), emptyList()))
        }
    }
}
