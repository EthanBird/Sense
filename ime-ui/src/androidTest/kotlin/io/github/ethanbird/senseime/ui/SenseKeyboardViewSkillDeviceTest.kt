package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class SenseKeyboardViewSkillDeviceTest {
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
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            /*
             * HyperOS rejects the instrumentation process's first background Activity start.
             * A shell prelaunch makes this debug-only test package foreground; startActivitySync
             * can then recreate and monitor the host normally.
             */
            val component =
                "${instrumentation.targetContext.packageName}/" +
                    SkillKeyboardTestActivity::class.java.name
            check(shell("am start -W -n $component").contains("Status: ok"))
        }
        activity = instrumentation.startActivitySync(intent) as SkillKeyboardTestActivity
        instrumentation.waitForIdleSync()
        keyboard = onMain { activity.keyboardView }
        assertTrue(
            "Keyboard test host did not attach a visible, measured View",
            waitUntil(2_000L) {
                onMain {
                    keyboard.isShown && keyboard.width > 0 && keyboard.height > 0
                }
            },
        )
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) {
            onMain {
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun downMoveUpConsumesOriginalKeyAndSelectingSameSkillTogglesOff() {
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.UP,
            id = "rewrite",
            label = "改写",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        val emittedKeys = CopyOnWriteArrayList<Int>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.keyListener = SenseKeyboardView.KeyListener {
                emittedKeys += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('g')
        val target = requireNotNull(
            pickerLayout(source, binding.direction).slot(binding.direction),
        ).bounds

        selectSkill(source, target)

        assertEquals(1, selections.size)
        assertEquals(KeyboardSkillToggleAction.ACTIVATE, selections.single().action)
        assertTrue("Picker release leaked the original G key", emittedKeys.isEmpty())

        onMain {
            keyboard.updateActiveKeyboardSkill(
                ActiveKeyboardSkill(
                    skillId = binding.skillId,
                    sourceKeyCode = binding.keyCode,
                    direction = binding.direction,
                ),
            )
        }
        selectSkill(source, target)

        assertEquals(2, selections.size)
        assertEquals(KeyboardSkillToggleAction.DEACTIVATE, selections.last().action)
        assertTrue("Second picker release leaked the original G key", emittedKeys.isEmpty())
    }

    @Test
    fun equivalentProjectionRefreshDuringArmedHoldKeepsThePickerScheduled() {
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.UP,
            id = "refresh-race",
            label = "refresh",
        )
        onMain {
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('g')
        val downTime = SystemClock.uptimeMillis()
        dispatch(
            MotionEvent.ACTION_DOWN,
            source.centerX(),
            source.centerY(),
            downTime,
        )
        SystemClock.sleep(180L)

        onMain {
            keyboard.updateKeyboardSkills(listOf(binding.copy()), active = null)
        }

        assertTrue(
            "Equivalent lifecycle projection cancelled the armed Skill hold",
            waitUntil(1_000L) {
                onMain {
                    keyboard.skillPickerOptionBoundsForTesting(binding.direction) != null
                }
            },
        )
        dispatch(
            MotionEvent.ACTION_CANCEL,
            source.centerX(),
            source.centerY(),
            downTime,
        )
    }

    @Test
    fun cancelClearsPickerWithoutSelectionOrOriginalKeyThenNextTapWorks() {
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.RIGHT,
            id = "translate",
            label = "翻译",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        val emittedKeys = CopyOnWriteArrayList<Int>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.keyListener = SenseKeyboardView.KeyListener {
                emittedKeys += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('g')
        val target = requireNotNull(
            pickerLayout(source, binding.direction).slot(binding.direction),
        ).bounds
        val downTime = beginSkillHold(source)
        dispatch(
            MotionEvent.ACTION_MOVE,
            target.centerX,
            target.centerY,
            downTime,
        )
        dispatch(
            MotionEvent.ACTION_CANCEL,
            target.centerX,
            target.centerY,
            downTime,
        )
        SystemClock.sleep(100L)
        instrumentation.waitForIdleSync()

        assertTrue("ACTION_CANCEL committed a Skill", selections.isEmpty())
        assertTrue("ACTION_CANCEL leaked the original G key", emittedKeys.isEmpty())
        assertFalse(
            keyboardDescription().contains("Skill 选择器"),
        )

        val tapDown = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, source.centerX(), source.centerY(), tapDown)
        dispatch(MotionEvent.ACTION_UP, source.centerX(), source.centerY(), tapDown)
        assertTrue(
            "Gesture cancellation left ordinary key dispatch wedged",
            waitUntil(2_000L) { emittedKeys == listOf('g'.code) },
        )
    }

    @Test
    fun productionAuroraSiblingNeverInterceptsKeyboardTouch() {
        val surface = onMain {
            requireNotNull(keyboard.parent as? SenseKeyboardSurface)
        }
        val emittedKeys = CopyOnWriteArrayList<Int>()
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.UP,
            id = "touch-through",
            label = "透传",
        )
        onMain {
            keyboard.keyListener = SenseKeyboardView.KeyListener { emittedKeys += it }
            keyboard.updateKeyboardSkills(
                listOf(binding),
                ActiveKeyboardSkill(
                    binding.skillId,
                    binding.keyCode,
                    binding.direction,
                ),
            )
        }
        val source = letterKeyBounds('g')
        assertTrue(
            "Active Aurora layer did not cover the physical G key",
            waitUntil(2_000L) {
                onMain {
                    activity.skillAuroraOverlay.width > 0 &&
                        activity.skillAuroraOverlay.height > 0
                }
            },
        )
        val downTime = SystemClock.uptimeMillis()

        onMain {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    source.centerX(),
                    source.centerY(),
                    0,
                )
                try {
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(surface.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            "The transparent Aurora sibling intercepted the production touch path",
            listOf('g'.code),
            emittedKeys.toList(),
        )
    }

    @Test
    fun leftEdgeDirectionalFanCommitsTheRenderedLeftSlot() {
        val binding = binding(
            keyCode = 'q'.code,
            direction = KeyboardSkillDirection.LEFT,
            id = "formal",
            label = "正式",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        val emittedKeys = CopyOnWriteArrayList<Int>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.keyListener = SenseKeyboardView.KeyListener {
                emittedKeys += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('q')
        val layout = pickerLayout(source, binding.direction)
        assertEquals(KeyboardSkillPickerLayoutMode.DIRECTIONAL_FAN, layout.mode)
        val renderedLeft = requireNotNull(layout.slot(KeyboardSkillDirection.LEFT)).bounds

        selectSkill(source, renderedLeft)

        assertEquals(1, selections.size)
        assertEquals(KeyboardSkillDirection.LEFT, selections.single().binding.direction)
        assertTrue("Edge fallback leaked the original Q key", emittedKeys.isEmpty())
    }

    @Test
    fun rightEdgeDirectionalFanCommitsTheRenderedRightSlot() {
        val binding = binding(
            keyCode = 'p'.code,
            direction = KeyboardSkillDirection.RIGHT,
            id = "concise",
            label = "精简",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('p')
        val layout = pickerLayout(source, binding.direction)
        assertEquals(KeyboardSkillPickerLayoutMode.DIRECTIONAL_FAN, layout.mode)

        selectSkill(
            source,
            requireNotNull(layout.slot(KeyboardSkillDirection.RIGHT)).bounds,
        )

        assertEquals(1, selections.size)
        assertEquals(KeyboardSkillDirection.RIGHT, selections.single().binding.direction)
    }

    @Test
    fun actionUpFinalCoordinateCanSelectWithoutAnIntermediateMove() {
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.DOWN,
            id = "continue",
            label = "续写",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('g')
        val target = requireNotNull(
            pickerLayout(source, binding.direction).slot(binding.direction),
        ).bounds
        val downTime = beginSkillHold(source)

        dispatch(MotionEvent.ACTION_UP, target.centerX, target.centerY, downTime)

        assertEquals(1, selections.size)
        assertEquals(KeyboardSkillDirection.DOWN, selections.single().binding.direction)
    }

    @Test
    fun secondaryPointerCannotStealOrLeakFromTheVisiblePicker() {
        val binding = binding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.UP,
            id = "multipointer",
            label = "多指",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        val emittedKeys = CopyOnWriteArrayList<Int>()
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.keyListener = SenseKeyboardView.KeyListener {
                emittedKeys += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = letterKeyBounds('g')
        val secondary = letterKeyBounds('a')
        val target = requireNotNull(
            pickerLayout(source, binding.direction).slot(binding.direction),
        ).bounds
        val downTime = beginSkillHold(source)

        dispatchTwoPointers(
            action = MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            downTime = downTime,
            primaryX = source.centerX(),
            primaryY = source.centerY(),
            secondaryX = secondary.centerX(),
            secondaryY = secondary.centerY(),
        )
        dispatchTwoPointers(
            action = MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            downTime = downTime,
            primaryX = source.centerX(),
            primaryY = source.centerY(),
            secondaryX = secondary.centerX(),
            secondaryY = secondary.centerY(),
        )
        dispatch(MotionEvent.ACTION_MOVE, target.centerX, target.centerY, downTime)
        dispatch(MotionEvent.ACTION_UP, target.centerX, target.centerY, downTime)

        assertEquals(1, selections.size)
        assertTrue("secondary pointer leaked an ordinary key", emittedKeys.isEmpty())
    }

    @Test
    fun skillBindingsCannotTakeSpaceDeleteOrPreActivationCharacterFlicks() {
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        val emittedText = CopyOnWriteArrayList<String>()
        val bindings = listOf(
            binding(
                keyCode = KeyCodes.SPACE,
                direction = KeyboardSkillDirection.UP,
                id = "reserved-space",
                label = "空格",
            ),
            binding(
                keyCode = KeyCodes.DELETE,
                direction = KeyboardSkillDirection.UP,
                id = "reserved-delete",
                label = "退格",
            ),
            binding(
                keyCode = 'g'.code,
                direction = KeyboardSkillDirection.UP,
                id = "flick-owner",
                label = "滑动",
            ),
        )
        onMain {
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.textListener = { emittedText += it }
            keyboard.updateKeyboardSkills(bindings, active = null)
        }

        val reservedHoldMillis =
            KeyboardSkillGestureSession.DEFAULT_LONG_PRESS_TIMEOUT_MILLIS +
                KeyboardSkillGestureSession.DEFAULT_ACTIVATION_CONFIRMATION_MILLIS +
                120L
        listOf(
            weightedLetterRowKeyBounds(
                rowIndex = 3,
                itemIndex = 3,
                weights = floatArrayOf(0.9f, 1.05f, 0.8f, 2.7f, 0.8f, 1f, 1.2f),
            ),
            weightedLetterRowKeyBounds(
                rowIndex = 2,
                itemIndex = 8,
                weights = floatArrayOf(1.25f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.25f),
            ),
        ).forEach { reserved ->
            val reservedDownTime = SystemClock.uptimeMillis()
            dispatch(
                MotionEvent.ACTION_DOWN,
                reserved.centerX(),
                reserved.centerY(),
                reservedDownTime,
            )
            SystemClock.sleep(reservedHoldMillis)
            instrumentation.waitForIdleSync()
            assertFalse(
                "Space/Delete binding opened a Skill picker",
                keyboardDescription().contains("Skill 选择器"),
            )
            dispatch(
                MotionEvent.ACTION_UP,
                reserved.centerX(),
                reserved.centerY(),
                reservedDownTime,
            )
        }
        assertTrue("reserved key hold selected a Skill", selections.isEmpty())

        val source = letterKeyBounds('g')
        val density = keyboardGeometry().density
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, source.centerX(), source.centerY(), downTime)
        dispatch(
            MotionEvent.ACTION_MOVE,
            source.centerX(),
            source.top - 32f * density,
            downTime,
        )
        dispatch(
            MotionEvent.ACTION_UP,
            source.centerX(),
            source.top - 32f * density,
            downTime,
        )

        assertTrue("ordinary upward flick stopped producing text", emittedText.isNotEmpty())
        assertTrue("pre-activation flick unexpectedly selected a Skill", selections.isEmpty())
        assertFalse(keyboardDescription().contains("Skill 选择器"))
    }

    @Test
    fun authoritativeAuroraReturnsToExactToolbarKeyAndNeverMigratesToPanelDuplicate() {
        val binding = binding(
            keyCode = KeyCodes.LETTERS,
            direction = KeyboardSkillDirection.UP,
            id = "toolbar-owner",
            label = "键盘",
        )
        val selections = CopyOnWriteArrayList<KeyboardSkillSelection>()
        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.NUMBERS)
            keyboard.skillSelectionListener = KeyboardSkillSelectionListener {
                selections += it
            }
            keyboard.updateKeyboardSkills(listOf(binding), active = null)
        }
        val source = toolbarKeyBounds(index = 1, itemCount = 6)
        val target = requireNotNull(
            pickerLayout(source, binding.direction).slot(binding.direction),
        ).bounds

        selectSkill(source, target)

        assertEquals(1, selections.size)
        assertNull("provisional owner must not light an optimistic Aurora", onMain {
            keyboard.activeSkillSourceBoundsForTesting()
        })
        onMain {
            keyboard.updateActiveKeyboardSkill(
                ActiveKeyboardSkill(
                    skillId = binding.skillId,
                    sourceKeyCode = binding.keyCode,
                    direction = binding.direction,
                ),
            )
        }
        assertBoundsEqual(
            source,
            requireNotNull(onMain { keyboard.activeSkillSourceBoundsForTesting() }),
        )

        onMain {
            keyboard.setPanel(SenseKeyboardView.Panel.SYMBOLS)
        }
        assertBoundsEqual(
            source,
            requireNotNull(onMain { keyboard.activeSkillSourceBoundsForTesting() }),
        )

        onMain {
            keyboard.updateComposing(1L, "ni", listOf("你"))
        }
        assertNull(
            "known hidden toolbar owner must not migrate to the panel LETTERS key",
            onMain { keyboard.activeSkillSourceBoundsForTesting() },
        )
        onMain {
            keyboard.updateComposing(2L, "", emptyList())
        }
        assertBoundsEqual(
            source,
            requireNotNull(onMain { keyboard.activeSkillSourceBoundsForTesting() }),
        )
    }

    @Test
    fun pickerAndAuthoritativeActivationExposeAnnouncementsAndDescription() {
        // UiAutomation is an accessibility service. Acquiring it makes the
        // platform deliver announceForAccessibility events without requiring
        // TalkBack to be installed or changing the user's enabled services.
        val automation = requireNotNull(instrumentation.uiAutomation) {
            "UiAutomation could not connect"
        }
        val manager = onMain {
            activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        }
        assertTrue(
            "UiAutomation accessibility service did not become active",
            waitUntil(2_000L) { onMain { manager.isEnabled } },
        )
        val announcements = CopyOnWriteArrayList<String>()
        val eventTypes = CopyOnWriteArrayList<Int>()
        automation.setOnAccessibilityEventListener { event ->
            eventTypes += event.eventType
            if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                announcements += event.text.joinToString(separator = "")
            }
        }
        try {
            val binding = binding(
                keyCode = 'g'.code,
                direction = KeyboardSkillDirection.UP,
                id = "rewrite",
                label = "改写",
            )
            onMain {
                keyboard.updateKeyboardSkills(listOf(binding), active = null)
            }
            assertEquals("先思键盘，未激活 Skill", keyboardDescription())
            val source = letterKeyBounds('g')
            val target = requireNotNull(
                pickerLayout(source, binding.direction).slot(binding.direction),
            ).bounds
            val downTime = beginSkillHold(source)
            assertTrue(
                keyboardDescription().contains("Skill 选择器，上：改写"),
            )
            dispatch(MotionEvent.ACTION_MOVE, target.centerX, target.centerY, downTime)
            assertTrue(
                keyboardDescription().contains("当前指向上：改写"),
            )
            dispatch(MotionEvent.ACTION_UP, target.centerX, target.centerY, downTime)
            onMain {
                keyboard.updateActiveKeyboardSkill(
                    ActiveKeyboardSkill(
                        skillId = binding.skillId,
                        sourceKeyCode = binding.keyCode,
                        direction = binding.direction,
                    ),
                )
            }
            assertEquals(
                "先思键盘，当前 Skill：改写",
                keyboardDescription(),
            )
            assertTrue(
                "System accessibility stream did not receive all Skill announcements: " +
                    announcements,
                waitUntil(2_000L) {
                    eventTypes.contains(AccessibilityEvent.TYPE_ANNOUNCEMENT) &&
                        announcements.any { it.contains("Skill 选择器") } &&
                        announcements.any { it == "上：改写" } &&
                        announcements.any { it == "已激活 Skill：改写" }
                },
            )

            onMain {
                keyboard.updateActiveKeyboardSkill(null)
            }
            assertEquals("先思键盘，未激活 Skill", keyboardDescription())
            assertTrue(
                "System accessibility stream did not receive Skill cancellation: " +
                    announcements,
                waitUntil(2_000L) {
                    announcements.any { it == "已取消 Skill：改写" }
                },
            )
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    @Test
    fun disabledAnimatorScaleKeepsAuroraVisibleButPixelStable() {
        val originalScale = shell("settings get global animator_duration_scale").trim()
        require(
            originalScale == "null" ||
                originalScale.matches(Regex("""[0-9]+(?:\.[0-9]+)?""")),
        ) { "Unexpected animator_duration_scale value: $originalScale" }
        try {
            shell("settings put global animator_duration_scale 0")
            assertTrue(
                "ValueAnimator did not observe the reduced-motion scale",
                waitUntil(3_000L) { !ValueAnimator.areAnimatorsEnabled() },
            )
            val binding = binding(
                keyCode = 'g'.code,
                direction = KeyboardSkillDirection.UP,
                id = "rewrite",
                label = "改写",
            )
            onMain {
                keyboard.updateKeyboardSkills(listOf(binding), active = null)
            }
            val inactive = renderSurface()
            onMain {
                keyboard.updateActiveKeyboardSkill(
                    ActiveKeyboardSkill(
                        binding.skillId,
                        binding.keyCode,
                        binding.direction,
                    ),
                )
            }
            assertTrue(
                "Reduced motion retained a recurring Aurora callback",
                waitUntil(2_000L) {
                    onMain {
                        val snapshot =
                            activity.skillAuroraOverlay.instrumentationSnapshot()
                        snapshot.active &&
                            !snapshot.frameCallbackPosted &&
                            snapshot.animatedDrawCalls == 0L &&
                            snapshot.staticDrawCalls >= 1L
                    }
                },
            )
            val firstActive = renderSurface()
            SystemClock.sleep(180L)
            val secondActive = renderSurface()

            assertFalse(
                "Active Skill did not draw an Aurora layer",
                inactive.contentEquals(firstActive),
            )
            assertArrayEquals(
                "Reduced motion must freeze, not remove, the Aurora layer",
                firstActive,
                secondActive,
            )

            shell("settings put global animator_duration_scale 1")
            assertTrue(
                "ValueAnimator did not observe restored motion",
                waitUntil(3_000L) { ValueAnimator.areAnimatorsEnabled() },
            )
            onMain {
                activity.skillAuroraOverlay.refreshMotionPreference()
            }
            assertTrue(
                "Aurora did not resume after reduced motion was disabled",
                waitUntil(2_000L) {
                    onMain {
                        val snapshot =
                            activity.skillAuroraOverlay.instrumentationSnapshot()
                        snapshot.active &&
                            snapshot.frameCallbackPosted &&
                            snapshot.animatedDrawCalls > 0L
                    }
                },
            )
        } finally {
            if (originalScale == "null") {
                shell("settings delete global animator_duration_scale")
            } else {
                shell("settings put global animator_duration_scale $originalScale")
            }
        }
    }

    private fun binding(
        keyCode: Int,
        direction: KeyboardSkillDirection,
        id: String,
        label: String,
    ) = KeyboardSkillBinding(
        keyCode = keyCode,
        direction = direction,
        skillId = id,
        label = label,
        description = "设备测试 Skill",
    )

    private fun selectSkill(source: RectF, target: KeyboardSkillPickerBounds) {
        val downTime = beginSkillHold(source)
        dispatch(MotionEvent.ACTION_MOVE, target.centerX, target.centerY, downTime)
        dispatch(MotionEvent.ACTION_UP, target.centerX, target.centerY, downTime)
        SystemClock.sleep(100L)
        instrumentation.waitForIdleSync()
    }

    private fun beginSkillHold(source: RectF): Long {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, source.centerX(), source.centerY(), downTime)
        SystemClock.sleep(
            KeyboardSkillGestureSession.DEFAULT_LONG_PRESS_TIMEOUT_MILLIS +
                KeyboardSkillGestureSession.DEFAULT_ACTIVATION_CONFIRMATION_MILLIS +
                120L,
        )
        instrumentation.waitForIdleSync()
        assertTrue(
            "Long press did not open the real View picker",
            keyboardDescription().contains("Skill 选择器"),
        )
        return downTime
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
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun dispatchTwoPointers(
        action: Int,
        downTime: Long,
        primaryX: Float,
        primaryY: Float,
        secondaryX: Float,
        secondaryY: Float,
    ) {
        onMain {
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val coordinates = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = primaryX
                    y = primaryY
                    pressure = 1f
                    size = 1f
                },
                MotionEvent.PointerCoords().apply {
                    x = secondaryX
                    y = secondaryY
                    pressure = 1f
                    size = 1f
                },
            )
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                2,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            try {
                assertTrue(keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun letterKeyBounds(character: Char): RectF {
        val geometry = keyboardGeometry()
        val density = geometry.density
        val rows = listOf("qwertyuiop", "asdfghjkl")
        val rowIndex = rows.indexOfFirst { character in it }
        require(rowIndex >= 0) { "Only first and second letter rows are used by this test" }
        val row = rows[rowIndex]
        val itemIndex = row.indexOf(character)
        val horizontalPadding = 6f * density
        val extraInset = if (rowIndex == 1) 18f * density else 0f
        val gap = 5f * density
        val candidateHeight = 45f * density
        val toolbarHeight = 42f * density
        val systemBarHeight = 52f * density
        val top = maxOf(candidateHeight, toolbarHeight) + 7f * density
        val bottom = geometry.height - systemBarHeight - 7f * density
        val rowHeight = (bottom - top - gap * 3f) / 4f
        val left = horizontalPadding + extraInset
        val right = geometry.width - horizontalPadding - extraInset
        val itemWidth = (right - left - gap * (row.length - 1)) / row.length
        val x = left + itemIndex * (itemWidth + gap)
        val y = top + rowIndex * (rowHeight + gap)
        return RectF(x, y, x + itemWidth, y + rowHeight)
    }

    private fun toolbarKeyBounds(index: Int, itemCount: Int): RectF {
        require(index in 0 until itemCount)
        val geometry = keyboardGeometry()
        val density = geometry.density
        val slot = geometry.width / itemCount.toFloat()
        return RectF(
            index * slot + 5f * density,
            3f * density,
            (index + 1) * slot - 5f * density,
            42f * density - 3f * density,
        )
    }

    private fun weightedLetterRowKeyBounds(
        rowIndex: Int,
        itemIndex: Int,
        weights: FloatArray,
    ): RectF {
        require(rowIndex in 0..3)
        require(itemIndex in weights.indices)
        val geometry = keyboardGeometry()
        val density = geometry.density
        val horizontalPadding = 6f * density
        val gap = 5f * density
        val top = maxOf(45f * density, 42f * density) + 7f * density
        val bottom = geometry.height - 52f * density - 7f * density
        val rowHeight = (bottom - top - gap * 3f) / 4f
        val totalWeight = weights.sum()
        val usable = geometry.width - horizontalPadding * 2f - gap * (weights.size - 1)
        var left = horizontalPadding
        repeat(itemIndex) { index ->
            left += usable * weights[index] / totalWeight + gap
        }
        val width = usable * weights[itemIndex] / totalWeight
        val rowTop = top + rowIndex * (rowHeight + gap)
        return RectF(left, rowTop, left + width, rowTop + rowHeight)
    }

    private fun assertBoundsEqual(expected: RectF, actual: RectF) {
        val tolerance = keyboardGeometry().density
        assertTrue(
            "bounds differ: expected=$expected actual=$actual",
            kotlin.math.abs(expected.left - actual.left) <= tolerance &&
                kotlin.math.abs(expected.top - actual.top) <= tolerance &&
                kotlin.math.abs(expected.right - actual.right) <= tolerance &&
                kotlin.math.abs(expected.bottom - actual.bottom) <= tolerance,
        )
    }

    private fun pickerLayout(
        source: RectF,
        direction: KeyboardSkillDirection,
    ): KeyboardSkillPickerLayout {
        val geometry = keyboardGeometry()
        val density = geometry.density
        val horizontalInset = minOf(6f * density, geometry.width * 0.1f)
        val verticalInset = minOf(4f * density, geometry.height * 0.1f)
        val chipWidth = minOf(
            76f * density,
            geometry.width * 0.24f,
            (geometry.width - horizontalInset * 2f).coerceAtLeast(1f),
        )
        val chipHeight = minOf(
            36f * density,
            (geometry.height - verticalInset * 2f).coerceAtLeast(1f),
        )
        val selectionDistance = maxOf(
            24f * density,
            geometry.touchSlop * 1.8f,
        )
        return KeyboardSkillPickerGeometry.layout(
            viewportWidth = geometry.width.toFloat(),
            viewportHeight = geometry.height.toFloat(),
            source = KeyboardSkillPickerBounds(
                source.left,
                source.top,
                source.right,
                source.bottom,
            ),
            enabledDirectionMask = 1 shl direction.ordinal,
            chipWidth = chipWidth,
            chipHeight = chipHeight,
            horizontalRadius = maxOf(82f * density, chipWidth + 6f * density),
            verticalRadius = maxOf(54f * density, chipHeight + 8f * density),
            horizontalInset = horizontalInset,
            verticalInset = verticalInset,
            minimumReachDistance = selectionDistance + 4f * density,
            gap = 4f * density,
        )
    }

    private fun renderSurface(): IntArray = onMain {
        val bitmap = Bitmap.createBitmap(
            activity.keyboardSurface.width,
            activity.keyboardSurface.height,
            Bitmap.Config.ARGB_8888,
        )
        try {
            activity.keyboardSurface.draw(Canvas(bitmap))
            IntArray(bitmap.width * bitmap.height).also { pixels ->
                bitmap.getPixels(
                    pixels,
                    0,
                    bitmap.width,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun keyboardDescription(): String = onMain {
        keyboard.contentDescription?.toString().orEmpty()
    }

    private fun keyboardGeometry(): KeyboardGeometry = onMain {
        KeyboardGeometry(
            width = keyboard.width,
            height = keyboard.height,
            density = keyboard.resources.displayMetrics.density,
            touchSlop = ViewConfiguration.get(keyboard.context).scaledTouchSlop,
        )
    }

    private fun shell(command: String): String {
        val descriptor = requireNotNull(instrumentation.uiAutomation) {
            "UiAutomation could not connect"
        }.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (condition()) return true
            SystemClock.sleep(20L)
        } while (SystemClock.uptimeMillis() < deadline)
        return condition()
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private data class KeyboardGeometry(
        val width: Int,
        val height: Int,
        val density: Float,
        val touchSlop: Int,
    )
}
