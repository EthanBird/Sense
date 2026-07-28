package io.github.ethanbird.senseime.ui

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class SenseKeyboardViewLayoutDeviceTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var activity: SkillKeyboardTestActivity
    private lateinit var keyboard: SenseKeyboardView

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent().apply {
            setClassName(
                instrumentation.targetContext.packageName,
                SkillKeyboardTestActivity::class.java.name,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity = instrumentation.startActivitySync(intent) as SkillKeyboardTestActivity
        instrumentation.waitForIdleSync()
        keyboard = onMain { activity.keyboardView }
        assertTrue(
            waitUntil(2_000L) {
                onMain { keyboard.isShown && keyboard.width > 0 && keyboard.height > 0 }
            },
        )
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun emojiDragAndFlingReuseTheExistingKeyScene() {
        onMain { keyboard.setPanel(SenseKeyboardView.Panel.EMOJI) }
        instrumentation.waitForIdleSync()
        val viewport = requireNotNull(
            onMain { keyboard.scrollViewportBoundsForTesting(ScrollPanel.EMOJI) },
        )
        val sceneBuildsBefore = onMain { keyboard.keySceneBuildCountForTesting() }
        val downTime = SystemClock.uptimeMillis()

        dispatch(
            action = MotionEvent.ACTION_DOWN,
            x = viewport.centerX(),
            y = viewport.bottom - viewport.height() * 0.18f,
            downTime = downTime,
        )
        dispatch(
            action = MotionEvent.ACTION_MOVE,
            x = viewport.centerX(),
            y = viewport.top + viewport.height() * 0.18f,
            downTime = downTime,
        )

        assertTrue(
            "Emoji drag did not move the content-space projection",
            onMain { keyboard.scrollOffsetForTesting(ScrollPanel.EMOJI) } > 0f,
        )
        assertEquals(
            "A drag rebuilt the complete key scene",
            sceneBuildsBefore,
            onMain { keyboard.keySceneBuildCountForTesting() },
        )

        dispatch(
            action = MotionEvent.ACTION_UP,
            x = viewport.centerX(),
            y = viewport.top + viewport.height() * 0.18f,
            downTime = downTime,
        )
        SystemClock.sleep(240L)
        instrumentation.waitForIdleSync()

        assertEquals(
            "A panel fling rebuilt the complete key scene",
            sceneBuildsBefore,
            onMain { keyboard.keySceneBuildCountForTesting() },
        )
    }

    @Test
    fun candidateDragAndSettleReuseTheExistingCandidateScene() {
        val values = List(40) { index -> "候选词-${index.toString().padStart(2, '0')}-扩展" }
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.updateComposing(revision = 1L, text = "ceshi", values = values)
        }
        instrumentation.waitForIdleSync()
        val viewport = requireNotNull(
            onMain { keyboard.candidateViewportBoundsForTesting() },
        )
        assertTrue(onMain { keyboard.candidateMaximumOffsetForTesting() } > 0f)
        val sceneBuildsBefore = onMain { keyboard.candidateSceneBuildCountForTesting() }
        val downTime = SystemClock.uptimeMillis()

        dispatch(
            action = MotionEvent.ACTION_DOWN,
            x = viewport.right - viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )
        dispatch(
            action = MotionEvent.ACTION_MOVE,
            x = viewport.left + viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )

        assertTrue(
            "Candidate drag did not move the content-space projection",
            onMain { keyboard.candidateScrollOffsetForTesting() } > 0f,
        )
        assertEquals(
            "A candidate drag rebuilt measured candidate slots",
            sceneBuildsBefore,
            onMain { keyboard.candidateSceneBuildCountForTesting() },
        )

        dispatch(
            action = MotionEvent.ACTION_UP,
            x = viewport.left + viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )
        SystemClock.sleep(240L)
        instrumentation.waitForIdleSync()

        assertEquals(
            "Candidate settling rebuilt measured candidate slots",
            sceneBuildsBefore,
            onMain { keyboard.candidateSceneBuildCountForTesting() },
        )
    }

    @Test
    fun candidateTapAfterDragUsesTheProjectedSourceIndexWithoutRebuildingTheScene() {
        val revision = 73L
        val values = List(48) { index ->
            "candidate-${index.toString().padStart(2, '0')}-extended"
        }
        val selections = mutableListOf<Pair<Long, Int>>()
        onMain {
            keyboard.candidateListener = { selectedRevision, sourceIndex ->
                selections += selectedRevision to sourceIndex
            }
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.updateComposing(revision = revision, text = "candidate", values = values)
        }
        instrumentation.waitForIdleSync()

        val viewport = requireNotNull(
            onMain { keyboard.candidateViewportBoundsForTesting() },
        )
        assertTrue(onMain { keyboard.candidateMaximumOffsetForTesting() } > 0f)
        val sceneBuildsBefore = onMain { keyboard.candidateSceneBuildCountForTesting() }
        val downTime = SystemClock.uptimeMillis()

        dispatch(
            action = MotionEvent.ACTION_DOWN,
            x = viewport.right - viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )
        SystemClock.sleep(100L)
        dispatch(
            action = MotionEvent.ACTION_MOVE,
            x = viewport.left + viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )
        SystemClock.sleep(100L)
        dispatch(
            action = MotionEvent.ACTION_UP,
            x = viewport.left + viewport.width() * 0.12f,
            y = viewport.centerY(),
            downTime = downTime,
        )
        SystemClock.sleep(240L)
        instrumentation.waitForIdleSync()

        assertTrue(
            "Candidate drag did not move the content-space projection",
            onMain { keyboard.candidateScrollOffsetForTesting() } > 0f,
        )
        assertEquals(
            "Candidate drag rebuilt measured candidate slots",
            sceneBuildsBefore,
            onMain { keyboard.candidateSceneBuildCountForTesting() },
        )
        assertTrue("Candidate drag was interpreted as a tap", onMain { selections.isEmpty() })

        val target = onMain { findFullyVisibleCandidateCenter(viewport) }
        assertTrue("Drag did not expose a later candidate", target.sourceIndex > 0)
        assertEquals(
            target.sourceIndex,
            onMain { keyboard.candidateSourceIndexAtForTesting(target.x, target.y) },
        )

        val tapDownTime = SystemClock.uptimeMillis()
        dispatch(
            action = MotionEvent.ACTION_DOWN,
            x = target.x,
            y = target.y,
            downTime = tapDownTime,
        )
        assertEquals(
            "Candidate press rebuilt measured candidate slots",
            sceneBuildsBefore,
            onMain { keyboard.candidateSceneBuildCountForTesting() },
        )
        dispatch(
            action = MotionEvent.ACTION_UP,
            x = target.x,
            y = target.y,
            downTime = tapDownTime,
        )

        assertEquals(listOf(revision to target.sourceIndex), onMain { selections.toList() })
        assertEquals(
            "Candidate tap rebuilt measured candidate slots",
            sceneBuildsBefore,
            onMain { keyboard.candidateSceneBuildCountForTesting() },
        )
    }

    @Test
    fun changingTheSurfaceProfileUpdatesItsExactParentHeight() {
        val profile = KeyboardSizeProfile(
            portraitHeightDp = 300f,
            landscapeHeightDp = 300f,
        )

        onMain { activity.keyboardSurface.setKeyboardSizeProfile(profile) }

        val expected = profile.preferredHeightPx(
            isLandscape =
                activity.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE,
            density = activity.resources.displayMetrics.density,
        )
        assertTrue(
            waitUntil(2_000L) {
                onMain {
                    activity.keyboardSurface.layoutParams.height == expected &&
                        activity.keyboardSurface.height == expected &&
                        keyboard.height == expected
                }
            },
        )
    }

    private fun findFullyVisibleCandidateCenter(
        viewport: android.graphics.RectF,
    ): CandidateTapTarget {
        val y = viewport.centerY()
        val firstX = viewport.left.toInt() + 1
        val lastX = viewport.right.toInt() - 1
        val spans = mutableListOf<CandidateSpan>()
        var activeIndex: Int? = null
        var activeStart = firstX

        for (x in firstX..lastX + 1) {
            val sourceIndex = if (x <= lastX) {
                keyboard.candidateSourceIndexAtForTesting(x.toFloat(), y)
            } else {
                null
            }
            if (sourceIndex != activeIndex) {
                activeIndex?.let { index ->
                    spans += CandidateSpan(
                        sourceIndex = index,
                        left = activeStart.toFloat(),
                        right = x.toFloat(),
                    )
                }
                activeIndex = sourceIndex
                activeStart = x
            }
        }

        val span = spans
            .asSequence()
            .filter { it.left > firstX && it.right < lastX }
            .maxByOrNull { it.right - it.left }
        return requireNotNull(span) {
            "No fully visible candidate remained after horizontal drag"
        }.let {
            CandidateTapTarget(
                sourceIndex = it.sourceIndex,
                x = (it.left + it.right) / 2f,
                y = y,
            )
        }
    }

    private fun dispatch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
    ) {
        onMain {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                x,
                y,
                0,
            )
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(16L)
        }
        return condition()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Thread.currentThread() == activity.mainLooper.thread) return block()
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private data class CandidateSpan(
        val sourceIndex: Int,
        val left: Float,
        val right: Float,
    )

    private data class CandidateTapTarget(
        val sourceIndex: Int,
        val x: Float,
        val y: Float,
    )
}
