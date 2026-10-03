package io.github.ethanbird.senseime.ui

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import java.io.File
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
    fun explicitPinyinSeparatorKeepsGeometryAndOnlyRebuildsOnVisibilityTransitions() {
        onMain {
            keyboard.setInputPresentation(true, PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.SWIPE_HINTS)
            keyboard.updateComposing(1, "", emptyList())
            val shift = keyboard.panelKeysForTesting().single { it.code == KeyCodes.SHIFT }
            val geometry = android.graphics.RectF(shift.bounds)
            keyboard.updateComposition(2, "xi")
            val separator = keyboard.panelKeysForTesting().single { it.code == '\''.code }
            assertEquals("分词", separator.label)
            assertEquals(geometry, separator.bounds)
            val count = keyboard.keySceneBuildCountForTesting()
            keyboard.updateComposition(3, "xi'")
            keyboard.updateComposing(3, "xi'", listOf("西", "喜"))
            keyboard.updateComposing(4, "xi'an", listOf("西安"))
            assertEquals(count, keyboard.keySceneBuildCountForTesting())
            // Associations also occupy the toolbar: their empty composing state
            // must still restore Shift even when candidate chrome is unchanged.
            keyboard.updateAssociations(5, listOf("你好"))
            assertEquals(count + 1, keyboard.keySceneBuildCountForTesting())
            assertEquals(geometry, keyboard.panelKeysForTesting().single { it.code == KeyCodes.SHIFT }.bounds)
            keyboard.updateComposition(6, "xi")
            assertTrue(keyboard.panelKeysForTesting().any { it.code == '\''.code })
            keyboard.setInputPresentation(false, PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.SWIPE_HINTS)
            assertTrue(keyboard.panelKeysForTesting().any { it.code == KeyCodes.SHIFT })
            keyboard.setInputPresentation(true, PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.WUBI_86_ROOTS)
            assertTrue(keyboard.panelKeysForTesting().any { it.code == KeyCodes.SHIFT })
            keyboard.setInputPresentation(true, PrimaryKeyboardMode.T9, PrimaryKeyboardLegendMode.SWIPE_HINTS)
            assertTrue(keyboard.panelKeysForTesting().none { it.code == '\''.code })
        }
    }

    @Test
    fun largeFontRepeatedDrawingKeepsKeyAndCandidateScenesCached() {
        onMain {
            for (landscape in listOf(false, true)) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                    orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                val context = activity.createConfigurationContext(config)
                val view = SenseKeyboardView(context)
                val width = if (landscape) 1920 else 1080
                val height = KeyboardSizeProfile.DEFAULT.preferredHeightPx(landscape, context.resources.displayMetrics.density, 2f)
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                view.updateComposing(201L, "nihao", List(80) { "你好$it" })
                val keys = view.panelKeysForTesting().map { it.bounds.toShortString() }
                val candidateBuilds = view.candidateSceneBuildCountForTesting()
                val keyBuilds = view.keySceneBuildCountForTesting()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    repeat(30) { view.draw(canvas) }
                    assertEquals(candidateBuilds, view.candidateSceneBuildCountForTesting())
                    assertEquals(keyBuilds, view.keySceneBuildCountForTesting())
                    assertEquals(keys, view.panelKeysForTesting().map { it.bounds.toShortString() })
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test
    fun accessibleCandidateClickRejectsPendingAndReplacedBatchIds() {
        val selected = mutableListOf<Pair<Long, Int>>()
        onMain {
            keyboard.candidateListener = { revision, index -> selected += revision to index }
            keyboard.updateComposing(101L, "nihao", listOf("你好", "拟好"))
            val old = keyboard.accessibleCandidatesForTesting().first { it.text == "你好" }
            val provider = requireNotNull(keyboard.accessibilityNodeProvider)
            val node = requireNotNull(provider.createAccessibilityNodeInfo(old.id))
            assertEquals("你好", node.text.toString())
            assertTrue(node.isClickable)
            keyboard.updateComposition(102L, "nihaoa")
            assertTrue(!provider.performAction(old.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            keyboard.updateComposing(102L, "nihaoa", listOf("你好啊", "你好"))
            assertTrue(!provider.performAction(old.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            val current = keyboard.accessibleCandidatesForTesting().first { it.text == "你好" }
            assertTrue(old.id != current.id)
            assertTrue(provider.performAction(current.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(102L to 1), selected)
            keyboard.setPanel(SenseKeyboardView.Panel.EDITOR)
            assertTrue(keyboard.accessibleCandidatesForTesting().isEmpty())
            assertTrue(!provider.performAction(current.id, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
    }

    @Test
    fun accessibleCandidateScrollAndControlsUseTheContinuousScene() {
        onMain {
            keyboard.updateComposing(103L, "ceshi", List(100) { "候选词-$it" })
            val provider = requireNotNull(keyboard.accessibilityNodeProvider)
            val expand = keyboard.accessibleCandidatesForTesting().first { it.text == "展开候选" }
            assertTrue(provider.performAction(expand.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            val builds = keyboard.candidateSceneBuildCountForTesting()
            val old = keyboard.accessibleCandidatesForTesting().first { it.text == "候选词-0" }
            repeat(3) { assertTrue(keyboard.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)) }
            assertTrue(keyboard.expandedCandidateOffsetForTesting() > 0f)
            assertEquals(builds, keyboard.candidateSceneBuildCountForTesting())
            assertTrue(keyboard.accessibleCandidatesForTesting().none { it.id == old.id })
            assertTrue(!provider.performAction(old.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            repeat(3) { assertTrue(keyboard.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)) }
            assertEquals(0f, keyboard.expandedCandidateOffsetForTesting(), .01f)
            assertEquals(old.id, keyboard.accessibleCandidatesForTesting().first { it.text == "候选词-0" }.id)
            val collapse = keyboard.accessibleCandidatesForTesting().first { it.text == "收起候选" }
            assertTrue(provider.performAction(collapse.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(keyboard.accessibleCandidatesForTesting().any { it.text == "展开候选" })
            var dismissed = false
            keyboard.associationDismissListener = { dismissed = true; keyboard.updateComposing(105L, "", emptyList()) }
            keyboard.updateAssociations(104L, listOf("工作", "生活"))
            val dismiss = keyboard.accessibleCandidatesForTesting().first { it.text == "关闭联想" }
            assertTrue(provider.performAction(dismiss.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(dismissed)
            assertTrue(keyboard.accessibleCandidatesForTesting().isEmpty())
        }
    }

    @Test
    fun accessibleClickEventKeepsTheWordWhenCommitRemovesItsNode() {
        val automation = instrumentation.uiAutomation
        assertTrue(waitUntil(3_000) {
            onMain { keyboard.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isEnabled }
        })
        val event = automation.executeAndWaitForEvent({
            onMain {
                keyboard.updateComposing(106L, "nihao", listOf("你好"))
                keyboard.candidateListener = { _, _ -> keyboard.updateComposing(107L, "", emptyList()) }
                val old = keyboard.accessibleCandidatesForTesting().first { it.text == "你好" }
                val provider = requireNotNull(keyboard.accessibilityNodeProvider)
                assertTrue(provider.performAction(old.id, AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(!provider.performAction(old.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            }
        }, { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && it.contentDescription == "候选词，1，你好" }, 5_000)
        assertEquals(listOf("你好"), event.text.map { it.toString() })
        event.recycle()
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
        // The current T9 layout deliberately shows the digit above its letter group.
        assertEquals("2", t9Keys.single { it.code == '2'.code }.visualLegend)

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
        // The configurable symbol rail commits literal text, not fixed QWERTY key codes.
        assertTrue(restored.any { it.action == KeyAction.CommitText("，") })
        assertTrue(restored.any { it.action == KeyAction.CommitText("。") })
        assertTrue(restored.any { it.code == KeyCodes.T9_REINPUT })
    }

    @Test
    fun dailyFullPinyinCandidatesStayRevisionBoundAndScrollContinuously() {
        val selected = mutableListOf<Pair<Long, Int>>()
        val values = listOf("明天开始工作", "明天开始", "明天", "名天", "明") +
            List(90) { listOf("现在开始", "开始工作", "明天见", "今天", "工作计划")[it % 5] + (it + 1) }
        onMain {
            keyboard.candidateListener = { revision, index -> selected += revision to index }
            keyboard.setInputPresentation(true, PrimaryKeyboardMode.QWERTY, PrimaryKeyboardLegendMode.SWIPE_HINTS)
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.updateComposing(10L, "ming'tian'kai'shi'gong'zuo", values)
        }
        instrumentation.waitForIdleSync()
        saveKeyboardImage("full-pinyin-ready")
        val viewport = requireNotNull(onMain { keyboard.candidateViewportBoundsForTesting() })
        val firstX = viewport.left + 8f * keyboard.resources.displayMetrics.density
        assertEquals(0, onMain { keyboard.candidateSourceIndexAtForTesting(firstX, viewport.centerY()) })
        onMain { keyboard.updateComposition(11L, "ming'tian'kai'shi'gong'zuoz") }
        saveKeyboardImage("full-pinyin-pending")
        assertEquals(null, onMain { keyboard.candidateSourceIndexAtForTesting(firstX, viewport.centerY()) })
        tapAt(firstX, viewport.centerY())
        assertTrue(onMain { selected.isEmpty() })
        onMain { keyboard.updateComposing(11L, "ming'tian'kai'shi'gong'zuo", values) }
        tapAt(firstX, viewport.centerY())
        assertEquals(listOf(11L to 0), onMain { selected.toList() })
        val expand = requireNotNull(onMain { keyboard.candidateControlBoundsForTesting(CandidateControl.EXPAND) })
        tapAt(expand.centerX(), expand.centerY())
        val grid = requireNotNull(onMain { keyboard.expandedCandidateViewportForTesting() })
        saveKeyboardImage("full-pinyin-expanded")
        val sceneCount = onMain { keyboard.candidateSceneBuildCountForTesting() }
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, grid.centerX(), grid.bottom - grid.height() * .15f, downTime)
        SystemClock.sleep(60)
        dispatch(MotionEvent.ACTION_MOVE, grid.centerX(), grid.top + grid.height() * .15f, downTime)
        SystemClock.sleep(60)
        dispatch(MotionEvent.ACTION_UP, grid.centerX(), grid.top + grid.height() * .15f, downTime)
        assertTrue(onMain { keyboard.expandedCandidateOffsetForTesting() } > 0f)
        assertEquals(sceneCount, onMain { keyboard.candidateSceneBuildCountForTesting() })
        assertEquals(listOf(11L to 0), onMain { selected.toList() })
        val collapse = requireNotNull(onMain { keyboard.candidateControlBoundsForTesting(CandidateControl.COLLAPSE) })
        tapAt(collapse.centerX(), collapse.centerY())
        assertEquals(null, onMain { keyboard.expandedCandidateViewportForTesting() })
    }

    @Test
    fun candidateTouchOwnershipSurvivesDragButEndsOnPublicationAndCancellation() {
        val activityEvents = mutableListOf<Boolean>()
        val selections = mutableListOf<Pair<Long, Int>>()
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.candidateInteractionListener = { activityEvents += it }
            keyboard.candidateListener = { revision, index -> selections += revision to index }
            keyboard.updateAssociations(30L, List(20) { "联想词-$it" })
        }
        val bounds = onMain { keyboard.accessibleCandidatesForTesting().first().bounds }
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        val down = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, x, y, down)
        dispatch(MotionEvent.ACTION_MOVE, x - 100f, y, down)
        assertTrue("Exercise actual latched scrolling, not only a canceled tap",
            onMain { keyboard.candidateScrollOffsetForTesting() > 0f })
        assertEquals(listOf(true), onMain { activityEvents.toList() })
        onMain { keyboard.updateAssociations(31L, listOf("新词", "后续")) }
        assertEquals(listOf(true, false), onMain { activityEvents.toList() })
        dispatch(MotionEvent.ACTION_UP, x - 100f, y, down)
        assertTrue(onMain { selections.isEmpty() })
        val fresh = onMain { keyboard.accessibleCandidatesForTesting().first().bounds }
        val freshX = (fresh.left + fresh.right) / 2
        val freshY = (fresh.top + fresh.bottom) / 2
        val next = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, freshX, freshY, next)
        dispatch(MotionEvent.ACTION_CANCEL, freshX, freshY, next)
        assertEquals(listOf(true, false, true, false), onMain { activityEvents.toList() })
        assertTrue(onMain { selections.isEmpty() })
        tapAt(freshX, freshY)
        assertEquals(listOf(31L to 0), onMain { selections.toList() })
        assertEquals(listOf(true, false, true, false, true, false), onMain { activityEvents.toList() })
    }

    @Test
    fun associationStripReplacesToolbarAndDismissRestoresIt() {
        var dismissals = 0
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.LETTERS)
            keyboard.updateComposing(20L, "", emptyList())
            keyboard.associationDismissListener = {
                dismissals++
                keyboard.updateComposing(22L, "", emptyList())
            }
        }
        val originalToolbar = onMain { keyboard.toolbarKeysForTesting().map { it.code } }
        assertTrue(originalToolbar.isNotEmpty())
        onMain { keyboard.updateAssociations(21L, listOf("工作", "学习", "生活", "计划")) }
        assertTrue(onMain { keyboard.toolbarKeysForTesting().isEmpty() })
        saveKeyboardImage("association-strip")
        val dismiss = requireNotNull(onMain { keyboard.candidateControlBoundsForTesting(CandidateControl.DISMISS) })
        tapAt(dismiss.centerX(), dismiss.centerY())
        assertEquals(1, dismissals)
        assertEquals(originalToolbar, onMain { keyboard.toolbarKeysForTesting().map { it.code } })
        saveKeyboardImage("toolbar-restored")
    }

    private fun tapAt(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, x, y, downTime)
        dispatch(MotionEvent.ACTION_UP, x, y, downTime)
    }

    private fun saveKeyboardImage(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = onMain {
            Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888).also {
                keyboard.draw(Canvas(it))
            }
        }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "input-quality-screens").also { it.mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
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
