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
            "cand-${index.toString().padStart(2, '0')}"
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

    @Test
    fun atomicPresentationPublishesFinalQwertyT9AndWubiScenes() {
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.QWERTY,
                legendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
            )
        }
        assertEquals(
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            characterRows(onMain { keyboard.panelKeysForTesting() }, 'a'..'z'),
        )
        assertEquals(
            "1",
            onMain { keyboard.panelKeysForTesting().single { it.code == 'q'.code }.visualLegend },
        )

        val beforeT9 = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.T9,
                legendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
            )
        }
        val t9Keys = onMain { keyboard.panelKeysForTesting() }
        assertEquals(beforeT9 + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        assertEquals(listOf("123", "456", "789"), characterRows(t9Keys, '1'..'9'))
        assertTrue(t9Keys.none { it.code in 'a'.code..'z'.code })
        assertEquals("ABC", t9Keys.single { it.code == '2'.code }.label)
        assertEquals(null, t9Keys.single { it.code == '2'.code }.visualLegend)

        val beforeWubi = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.QWERTY,
                legendMode = PrimaryKeyboardLegendMode.WUBI_86_ROOTS,
            )
        }
        val wubiKeys = onMain { keyboard.panelKeysForTesting() }
        assertEquals(beforeWubi + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        assertEquals(
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            characterRows(wubiKeys, 'a'..'z'),
        )
        assertEquals("金", wubiKeys.single { it.code == 'q'.code }.visualLegend)
        assertEquals("工", wubiKeys.single { it.code == 'a'.code }.visualLegend)
        assertEquals("山", wubiKeys.single { it.code == 'm'.code }.visualLegend)
        assertEquals("反查", wubiKeys.single { it.code == 'z'.code }.visualLegend)
        assertEquals(
            SwipeCharacterMap.forKey('q'.code, SwipeCharacterMode.CHINESE),
            wubiKeys.single { it.code == 'q'.code }.swipeOutput,
        )
        assertEquals(
            SwipeCharacterMap.forKey('z'.code, SwipeCharacterMode.CHINESE),
            wubiKeys.single { it.code == 'z'.code }.swipeOutput,
        )

        onMain {
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.QWERTY,
                legendMode = PrimaryKeyboardLegendMode.WUBI_86_ROOTS,
            )
        }
        assertEquals(beforeWubi + 1L, onMain { keyboard.keySceneBuildCountForTesting() })

        val beforeEnglish = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.setInputPresentation(
                chinese = false,
                mode = PrimaryKeyboardMode.QWERTY,
                legendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
            )
        }
        val englishKeys = onMain { keyboard.panelKeysForTesting() }
        assertEquals(beforeEnglish + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        assertEquals(
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            characterRows(englishKeys, 'a'..'z'),
        )
        assertEquals(
            SwipeCharacterMap.forKey('m'.code, SwipeCharacterMode.ENGLISH),
            englishKeys.single { it.code == 'm'.code }.visualLegend,
        )
    }

    @Test
    fun toolbarKeyboardEntrySelectsOneSchemeAndReturnsToLetters() {
        val selections = mutableListOf<KeyboardInputSchemeChoice>()
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.QWERTY,
                legendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
            )
            keyboard.inputSchemeSelectionListener =
                KeyboardInputSchemeSelectionListener { choice -> selections += choice }
        }
        val toolbarKeyboard = onMain {
            keyboard.toolbarKeysForTesting().single { it.icon == Icon.KEYBOARD }
        }
        val beforeOpen = onMain { keyboard.keySceneBuildCountForTesting() }

        tap(toolbarKeyboard)

        assertEquals(KeyboardPanel.INPUT_SCHEMES, onMain { keyboard.panelForTesting() })
        assertEquals(beforeOpen + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        val initialOptions = onMain { keyboard.panelKeysForTesting() }
            .filter { it.action is KeyAction.SelectInputScheme }
        assertEquals(3, initialOptions.size)
        assertEquals(
            KeyboardInputSchemeChoice.PINYIN_QWERTY,
            (initialOptions.single { it.selected }.action as KeyAction.SelectInputScheme).choice,
        )

        val wubi = initialOptions.single {
            (it.action as? KeyAction.SelectInputScheme)?.choice ==
                KeyboardInputSchemeChoice.WUBI_86
        }
        val beforeSelect = onMain { keyboard.keySceneBuildCountForTesting() }
        tap(wubi)

        assertEquals(listOf(KeyboardInputSchemeChoice.WUBI_86), onMain { selections.toList() })
        assertEquals(KeyboardPanel.LETTERS, onMain { keyboard.panelForTesting() })
        assertEquals(beforeSelect + 1L, onMain { keyboard.keySceneBuildCountForTesting() })

        val reopen = onMain {
            keyboard.toolbarKeysForTesting().single { it.icon == Icon.KEYBOARD }
        }
        tap(reopen)
        val reopenedOptions = onMain { keyboard.panelKeysForTesting() }
            .filter { it.action is KeyAction.SelectInputScheme }
        assertEquals(
            KeyboardInputSchemeChoice.WUBI_86,
            (reopenedOptions.single { it.selected }.action as KeyAction.SelectInputScheme).choice,
        )
        val close = onMain {
            keyboard.panelKeysForTesting().single {
                (it.action as? KeyAction.ShowPanel)?.panel == KeyboardPanel.LETTERS
            }
        }
        tap(close)
        assertEquals(KeyboardPanel.LETTERS, onMain { keyboard.panelForTesting() })
    }

    @Test
    fun atomicT9ComposingPublishesChoicesOnceAndRestoresPunctuationWhenEmpty() {
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.setInputPresentation(
                chinese = true,
                mode = PrimaryKeyboardMode.T9,
                legendMode = PrimaryKeyboardLegendMode.SWIPE_HINTS,
            )
            keyboard.updateT9Composing(1L, "2", values = null, choices = emptyList())
        }
        val beforeEmptyRevision = onMain { keyboard.keySceneBuildCountForTesting() }

        onMain {
            keyboard.updateT9Composing(2L, "28", values = null, choices = emptyList())
        }
        assertEquals(beforeEmptyRevision, onMain { keyboard.keySceneBuildCountForTesting() })

        val choices = listOf(
            T9PinyinChoice(canonical = "hun", preview = "hun'shen'x's"),
            T9PinyinChoice(canonical = "hunshen", preview = "hun'shen'xs"),
        )
        val beforeChoices = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.updateT9Composing(2L, "28", values = null, choices = choices)
        }
        assertEquals(beforeChoices + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        val choiceKeys = onMain { keyboard.panelKeysForTesting() }
            .filter { it.action is KeyAction.SelectT9PinyinChoice }
        assertEquals(listOf("hun", "hunshen"), choiceKeys.map(Key::label))
        assertEquals(listOf("hun'shen'x's", "hun'shen'xs"), choiceKeys.map(Key::visualLegend))
        assertTrue(choiceKeys.all { it.style == KeyStyle.T9_LEFT_RAIL })
        assertTrue(onMain { keyboard.panelKeysForTesting() }.none { it.action == KeyAction.None })

        val beforeRepeat = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.updateT9Composing(2L, "28", values = null, choices = choices)
        }
        assertEquals(beforeRepeat, onMain { keyboard.keySceneBuildCountForTesting() })

        val beforeClear = onMain { keyboard.keySceneBuildCountForTesting() }
        onMain {
            keyboard.updateT9Composing(3L, "", values = emptyList(), choices = emptyList())
        }
        assertEquals(beforeClear + 1L, onMain { keyboard.keySceneBuildCountForTesting() })
        val restored = onMain { keyboard.panelKeysForTesting() }
        assertTrue(restored.any { it.code == KeyCodes.COMMA })
        assertTrue(restored.any { it.code == KeyCodes.PERIOD })
        assertTrue(restored.any { it.code == KeyCodes.T9_REINPUT })
    }

    private fun tap(key: Key) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(
            action = MotionEvent.ACTION_DOWN,
            x = key.bounds.centerX(),
            y = key.bounds.centerY(),
            downTime = downTime,
        )
        dispatch(
            action = MotionEvent.ACTION_UP,
            x = key.bounds.centerX(),
            y = key.bounds.centerY(),
            downTime = downTime,
        )
    }

    private fun characterRows(keys: List<Key>, range: CharRange): List<String> = keys
        .asSequence()
        .filter { it.code in range.first.code..range.last.code }
        .groupBy { it.bounds.top }
        .toSortedMap()
        .values
        .map { row ->
            row.sortedBy { it.bounds.left }
                .joinToString(separator = "") { it.code.toChar().toString() }
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
