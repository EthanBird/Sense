package io.github.ethanbird.senseime.ui

import android.content.Context
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInteractionControllerTest {
    @Test
    fun `AI hold scheduler is deterministic and flushes FIFO after ordinary cancellation`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val effects = RecordingGestureEffects()
        val coordinator = KeyboardGestureCoordinator(
            host = FakeInteractionHost(),
            density = 1f,
            metrics = KeyboardMetrics.fromDensity(1f),
            scaledTouchSlop = 8f,
            scheduler = scheduler,
            clock = clock,
            haptics = KeyboardHaptics { },
            actions = actions,
            effects = effects,
        )
        val space = Key(
            label = "space",
            action = KeyAction.EmitKey(KeyCodes.SPACE),
            bounds = RectF(0f, 0f, 100f, 40f),
        )

        coordinator.beginPointer(
            pointerId = 9,
            key = space,
            x = 50f,
            y = 20f,
            eventTimeMillis = 0L,
        )
        scheduler.advanceTo(1_000L)

        assertEquals(listOf("clear", "flush"), effects.events)
        assertEquals(listOf(1L), actions.aiStarts)
        assertTrue(coordinator.aiSurfaceState != null)
    }

    @Test
    fun `short Space tap removes stale delayed activation`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val coordinator = KeyboardGestureCoordinator(
            host = FakeInteractionHost(),
            density = 1f,
            metrics = KeyboardMetrics.fromDensity(1f),
            scaledTouchSlop = 8f,
            scheduler = scheduler,
            clock = clock,
            haptics = KeyboardHaptics { },
            actions = actions,
            effects = RecordingGestureEffects(),
        )
        val space = Key(
            label = "space",
            action = KeyAction.EmitKey(KeyCodes.SPACE),
            bounds = RectF(0f, 0f, 100f, 40f),
        )

        coordinator.beginPointer(3, space, 50f, 20f, eventTimeMillis = 100L)
        assertFalse(coordinator.finishAiPointer(3, eventTimeMillis = 102L))
        scheduler.advanceTo(2_000L)

        assertTrue(actions.aiStarts.isEmpty())
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `key dispatcher flush preserves primitive FIFO and removes posted drain`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val dispatcher = KeyboardActionDispatcher(
            host = FakeInteractionHost(),
            scheduler = scheduler,
            actions = actions,
            effects = object : KeyboardActionEffects {
                override fun stopPanelFling() = Unit
                override fun stopCandidateSettle() = Unit
            },
        )
        val delete = Key(
            label = "delete",
            action = KeyAction.EmitKey(KeyCodes.DELETE),
            bounds = RectF(0f, 0f, 40f, 40f),
        )

        dispatcher.dispatchDelete(delete)
        dispatcher.dispatchDelete(delete)
        dispatcher.flushKeys()
        scheduler.advanceTo(0L)

        assertEquals(listOf(KeyCodes.DELETE, KeyCodes.DELETE), actions.keys)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `hot move APIs publish primitive flags without changing frozen owner`() {
        val reducer = TouchInputReducer<String>(
            swipeThreshold = 20f,
            maximumHorizontalDrift = 30f,
        )
        val scrollPolicy = TouchInputReducer.GesturePolicy.verticalScroll(
            touchSlop = 5f,
            verticalDominanceRatio = 1.1f,
        )
        reducer.onPrimaryDown(1, "frozen", 10f, 20f)

        val flags = reducer.onMoveFlags(
            pointerId = 1,
            x = 10f,
            y = 10f,
            insideTapTarget = false,
            policy = scrollPolicy,
        )

        assertEquals("frozen", reducer.target(1))
        assertTrue(flags and TouchInputReducer.MOVE_TAP_SUPPRESSED != 0)
        assertTrue(flags and TouchInputReducer.MOVE_VERTICAL_SCROLL_LATCHED != 0)
        assertEquals(
            TouchInputReducer.Gesture.SWIPE_UP,
            reducer.onUpGesture(1, 10f, 8f, false, scrollPolicy),
        )
    }

    @Test
    fun `projected hit geometry clips viewport before applying scroll offset`() {
        assertTrue(
            KeyboardHitTestGeometry.projectedKeyContains(
                x = 20f,
                y = 25f,
                keyLeft = 10f,
                keyTop = 100f,
                keyRight = 50f,
                keyBottom = 140f,
                viewportLeft = 0f,
                viewportTop = 20f,
                viewportRight = 80f,
                viewportBottom = 60f,
                scrollOffset = 80f,
            ),
        )
        assertFalse(
            KeyboardHitTestGeometry.projectedKeyContains(
                x = 20f,
                y = 19f,
                keyLeft = 10f,
                keyTop = 90f,
                keyRight = 50f,
                keyBottom = 140f,
                viewportLeft = 0f,
                viewportTop = 20f,
                viewportRight = 80f,
                viewportBottom = 60f,
                scrollOffset = 80f,
            ),
        )
    }

    @Test
    fun `candidate drag hot path returns primitive latch and change flags`() {
        val state = CandidateStripScrollState(touchSlop = 4f)
        state.configure(
            maximumOffset = 120f,
            viewportExtent = 60f,
            snapOffsets = listOf(0f, 40f, 80f, 120f),
        )
        assertTrue(state.begin(7, x = 50f, y = 10f, eventTimeMillis = 0L))

        val flags = state.moveFlags(
            pointerId = 7,
            x = 35f,
            y = 11f,
            eventTimeMillis = 16L,
        )

        assertTrue(flags and CandidateStripScrollState.MOVE_DRAG_LATCHED != 0)
        assertTrue(flags and CandidateStripScrollState.MOVE_CHANGED != 0)
    }

    private class FakeClock : KeyboardInteractionClock {
        var nowMillis = 0L

        override fun uptimeMillis(): Long = nowMillis
    }

    private class FakeFrameScheduler(
        private val clock: FakeClock,
    ) : KeyboardFrameScheduler {
        private data class Entry(
            val task: Runnable,
            val dueMillis: Long,
            val sequence: Long,
        )

        private val entries = ArrayList<Entry>()
        private var sequence = 0L
        var invalidations = 0
            private set

        val pendingCount: Int
            get() = entries.size

        override fun post(task: Runnable) {
            schedule(task, clock.nowMillis)
        }

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            schedule(task, clock.nowMillis + delayMillis.coerceAtLeast(0L))
        }

        override fun postOnAnimation(task: Runnable) {
            schedule(task, clock.nowMillis + 16L)
        }

        override fun postOnAnimationDelayed(task: Runnable, delayMillis: Long) {
            schedule(task, clock.nowMillis + delayMillis.coerceAtLeast(0L))
        }

        override fun remove(task: Runnable) {
            entries.removeAll { it.task === task }
        }

        override fun invalidate() {
            invalidations += 1
        }

        override fun postInvalidateOnAnimation(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            invalidations += 1
        }

        override fun postInvalidateDelayed(delayMillis: Long) {
            invalidations += 1
        }

        override fun postInvalidateOnAnimation() {
            invalidations += 1
        }

        fun advanceTo(targetMillis: Long) {
            require(targetMillis >= clock.nowMillis)
            while (true) {
                val next = entries.minWithOrNull(
                    compareBy<Entry> { it.dueMillis }.thenBy { it.sequence },
                ) ?: break
                if (next.dueMillis > targetMillis) break
                entries.remove(next)
                clock.nowMillis = next.dueMillis
                next.task.run()
            }
            clock.nowMillis = targetMillis
        }

        private fun schedule(task: Runnable, dueMillis: Long) {
            entries += Entry(task, dueMillis, sequence++)
        }
    }

    private class RecordingGestureEffects : KeyboardGestureEffects {
        val events = ArrayList<String>()

        override fun clearOrdinaryInputFromGesture() {
            events += "clear"
        }

        override fun suspendPointerInputForSkillPicker() {
            events += "suspend"
        }

        override fun flushQueuedKeysBeforeAi() {
            events += "flush"
        }

        override fun canStartSkillGesture(key: Key): Boolean = true
    }

    private class RecordingActions : KeyboardInteractionActionSink {
        val keys = ArrayList<Int>()
        val aiStarts = ArrayList<Long>()

        override fun onKey(code: Int) {
            keys += code
        }

        override fun onCandidate(revision: Long, sourceIndex: Int) = Unit
        override fun onText(text: String) = Unit
        override fun onClipboardAction(action: KeyboardClipboardAction, index: Int) = Unit
        override fun onEditorAction(action: KeyboardEditorAction) = Unit
        override fun onSettingsAction() = Unit

        override fun onAiHoldStarted(generation: Long) {
            aiStarts += generation
        }

        override fun onAiHoldCancelled(generation: Long) = Unit
        override fun onAiStopRequested(generation: Long) = Unit
        override fun onSkillSelection(request: KeyboardSkillSelection) = Unit
    }

    private class FakeInteractionHost : KeyboardInteractionHost {
        override val interactionContext: Context
            get() = error("Context is outside this deterministic fixture")
        override val interactionWidth: Int = 400
        override val interactionHeight: Int = 300
        override val interactionIsShown: Boolean = true
        override val interactionScene: MutableKeyboardScene = MutableKeyboardScene()
        override val interactionCandidatePanel: CandidatePanel
            get() = error("CandidatePanel is outside this deterministic fixture")
        override var interactionClipboardItems: List<String> = emptyList()
        override val interactionAiGeometry = MutableAiSurfaceRenderGeometry()
        override val interactionFontScale: Float = 1f
        override var interactionPanel = KeyboardPanel.LETTERS
        override var interactionPrimaryMode = PrimaryKeyboardMode.QWERTY
        override var interactionEmojiGroupIndex = 0
        override var interactionSymbolCategoryIndex = 0
        override var interactionClipboardPageIndex = 0
        override val interactionEditorHasSelection: Boolean = false
        override val interactionEditorSelectionMode: Boolean = false
        override val interactionEditorCanPaste: Boolean = false
        override val interactionChineseMode: Boolean = true
        private var description: CharSequence? = null

        override fun interactionShowsCandidates(): Boolean = true
        override fun interactionCandidatesTakeToolbar(): Boolean = false
        override fun interactionCandidateToolbarSuppressed(): Boolean = false
        override fun interactionChromeBottom(): Float = 50f
        override fun interactionRelayoutCandidates() = Unit
        override fun interactionRebuildKeys() = Unit
        override fun interactionSetPanel(panel: KeyboardPanel) {
            interactionPanel = panel
        }

        override fun interactionPerformClick() = Unit
        override fun interactionAnnounce(message: String) = Unit
        override fun interactionReadContentDescription(): CharSequence? = description
        override fun interactionWriteContentDescription(value: CharSequence) {
            description = value
        }
    }
}
