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
    fun `key dispatcher commits explicit swipe output instead of the visual legend`() {
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
        val bounds = RectF(0f, 0f, 40f, 40f)
        val legendOnly = Key(
            label = "2",
            action = KeyAction.EmitKey('2'.code),
            bounds = bounds,
            visualLegend = "ABC",
        )
        val explicitSwipe = Key(
            label = "q",
            action = KeyAction.EmitKey('q'.code),
            bounds = bounds,
            visualLegend = "visible",
            swipeOutput = "1",
        )
        val policy = TouchInputReducer.GesturePolicy.upwardFlick(
            minimumDistance = 12f,
            verticalDominanceRatio = 1.15f,
        )

        dispatcher.activate(
            FrozenTouchTarget.KeyValue(legendOnly, policy),
            TouchInputReducer.Gesture.SWIPE_UP,
        )
        dispatcher.activate(
            FrozenTouchTarget.KeyValue(explicitSwipe, policy),
            TouchInputReducer.Gesture.SWIPE_UP,
        )

        assertEquals(listOf("1"), actions.texts)
    }

    @Test
    fun `t9 digit key remains eligible for long press skill arming`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val dispatcher = KeyboardActionDispatcher(
            host = FakeInteractionHost(),
            scheduler = scheduler,
            actions = RecordingActions(),
            effects = RecordingActionEffects(),
        )
        val key = Key(
            label = "ABC",
            action = KeyAction.EmitKey('2'.code),
            bounds = RectF(0f, 0f, 60f, 50f),
            visualLegend = "2",
            swipeOutput = "2",
            style = KeyStyle.T9_PRIMARY,
        )

        assertTrue(dispatcher.canStartSkillGesture(key))
    }

    @Test
    fun `scheme panel actions stay local and selection closes after one semantic callback`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val host = FakeInteractionHost()
        val dispatcher = KeyboardActionDispatcher(
            host = host,
            scheduler = scheduler,
            actions = actions,
            effects = RecordingActionEffects(),
        )
        val bounds = RectF(0f, 0f, 40f, 40f)

        dispatcher.activate(
            FrozenTouchTarget.KeyValue(
                Key(
                    label = "keyboard",
                    action = KeyAction.ShowPanel(KeyboardPanel.INPUT_SCHEMES, KeyCodes.LETTERS),
                    bounds = bounds,
                ),
                TouchInputReducer.GesturePolicy.tapOnly(),
            ),
            TouchInputReducer.Gesture.TAP,
        )
        assertEquals(KeyboardPanel.INPUT_SCHEMES, host.interactionPanel)

        dispatcher.activate(
            FrozenTouchTarget.KeyValue(
                Key(
                    label = "9键拼音",
                    action = KeyAction.SelectInputScheme(KeyboardInputSchemeChoice.PINYIN_T9),
                    bounds = bounds,
                ),
                TouchInputReducer.GesturePolicy.tapOnly(),
            ),
            TouchInputReducer.Gesture.TAP,
        )

        assertEquals(KeyboardInputSchemeChoice.PINYIN_T9, host.interactionInputSchemeChoice)
        assertEquals(KeyboardPanel.LETTERS, host.interactionPanel)
        assertEquals(listOf(KeyboardInputSchemeChoice.PINYIN_T9), actions.inputSchemes)
        assertTrue(actions.keys.isEmpty())

        host.interactionPanel = KeyboardPanel.INPUT_SCHEMES
        dispatcher.activate(
            FrozenTouchTarget.KeyValue(
                Key(
                    label = "9键拼音",
                    action = KeyAction.SelectInputScheme(KeyboardInputSchemeChoice.PINYIN_T9),
                    bounds = bounds,
                ),
                TouchInputReducer.GesturePolicy.tapOnly(),
            ),
            TouchInputReducer.Gesture.TAP,
        )
        assertEquals(listOf(KeyboardInputSchemeChoice.PINYIN_T9), actions.inputSchemes)
        assertEquals(KeyboardPanel.LETTERS, host.interactionPanel)
    }

    @Test
    fun `t9 pinyin choice dispatches revision and index without entering key FIFO`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val dispatcher = KeyboardActionDispatcher(
            host = FakeInteractionHost(),
            scheduler = scheduler,
            actions = actions,
            effects = RecordingActionEffects(),
        )
        val key = Key(
            label = "hun",
            action = KeyAction.SelectT9PinyinChoice(revision = 91L, index = 2),
            bounds = RectF(0f, 0f, 40f, 40f),
        )

        dispatcher.activate(
            FrozenTouchTarget.KeyValue(key, TouchInputReducer.GesturePolicy.tapOnly()),
            TouchInputReducer.Gesture.TAP,
        )

        assertEquals(listOf(91L to 2), actions.t9PinyinChoices)
        assertTrue(actions.keys.isEmpty())
    }

    @Test
    fun `t9 custom symbol entry uses its dedicated settings route`() {
        val clock = FakeClock()
        val scheduler = FakeFrameScheduler(clock)
        val actions = RecordingActions()
        val dispatcher = KeyboardActionDispatcher(
            host = FakeInteractionHost(),
            scheduler = scheduler,
            actions = actions,
            effects = RecordingActionEffects(),
        )
        val key = Key(
            label = "自定义设置",
            action = KeyAction.OpenT9SideSymbolSettings,
            bounds = RectF(0f, 0f, 40f, 40f),
        )

        dispatcher.activate(
            FrozenTouchTarget.KeyValue(key, TouchInputReducer.GesturePolicy.tapOnly()),
            TouchInputReducer.Gesture.TAP,
        )

        assertEquals(1, actions.t9SideSymbolSettingsCount)
        assertTrue(actions.keys.isEmpty())
        assertTrue(actions.texts.isEmpty())
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
    fun `horizontal projected hit geometry applies category offset inside viewport`() {
        assertTrue(
            KeyboardHitTestGeometry.projectedHorizontalKeyContains(
                x = 24f,
                y = 30f,
                keyLeft = 100f,
                keyTop = 20f,
                keyRight = 146f,
                keyBottom = 48f,
                viewportLeft = 8f,
                viewportTop = 20f,
                viewportRight = 88f,
                viewportBottom = 48f,
                scrollOffset = 80f,
            ),
        )
        assertFalse(
            KeyboardHitTestGeometry.projectedHorizontalKeyContains(
                x = 7f,
                y = 30f,
                keyLeft = 80f,
                keyTop = 20f,
                keyRight = 126f,
                keyBottom = 48f,
                viewportLeft = 8f,
                viewportTop = 20f,
                viewportRight = 88f,
                viewportBottom = 48f,
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

    @Test
    fun `candidate publication fence covers readiness and same revision content changes`() {
        val current = listOf("旧候选", "第二项")

        assertFalse(
            CandidatePointerFence.shouldCancel(
                previousReady = true,
                previousCandidates = current,
                nextReady = true,
                nextCandidates = current.toList(),
            ),
        )
        assertTrue(
            CandidatePointerFence.shouldCancel(
                previousReady = true,
                previousCandidates = current,
                nextReady = true,
                nextCandidates = listOf("新候选", "第二项"),
            ),
        )
        assertTrue(
            CandidatePointerFence.shouldCancel(
                previousReady = true,
                previousCandidates = current,
                nextReady = false,
                nextCandidates = null,
            ),
        )
        assertTrue(
            CandidatePointerFence.shouldCancel(
                previousReady = false,
                previousCandidates = current,
                nextReady = true,
                nextCandidates = current.toList(),
            ),
        )
    }

    @Test
    fun `candidate pointer classifier covers values controls and both drag surfaces`() {
        val bounds = RectF(0f, 0f, 40f, 40f)
        val policy = TouchInputReducer.GesturePolicy.tapOnly()
        val candidateTargets: List<FrozenTouchTarget> = listOf(
            FrozenTouchTarget.CandidateValue(7L, 0, bounds, policy),
            FrozenTouchTarget.CandidateControlValue(CandidateControl.EXPAND, bounds, policy),
            FrozenTouchTarget.CandidatePageArea(bounds, policy),
            FrozenTouchTarget.CandidateStripArea(bounds, policy),
        )
        val ordinaryTargets: List<FrozenTouchTarget> = listOf(
            FrozenTouchTarget.KeyValue(
                key = Key(
                    label = "a",
                    action = KeyAction.EmitKey('a'.code),
                    bounds = bounds,
                ),
                gesturePolicy = policy,
            ),
            FrozenTouchTarget.PanelScrollArea(ScrollPanel.EMOJI, bounds, policy),
        )

        assertTrue(candidateTargets.all { it.isCandidatePointerTarget() })
        assertTrue(ordinaryTargets.none { it.isCandidatePointerTarget() })
    }

    @Test
    fun `t9 rail classifier isolates the dynamic left column from physical input keys`() {
        val bounds = RectF(0f, 0f, 40f, 40f)
        val policy = TouchInputReducer.GesturePolicy.tapOnly()
        val leftRail = FrozenTouchTarget.KeyValue(
            key = Key(
                label = "hun",
                action = KeyAction.SelectT9PinyinChoice(7L, 0),
                bounds = bounds,
                style = KeyStyle.T9_LEFT_RAIL,
            ),
            gesturePolicy = policy,
        )
        val central = FrozenTouchTarget.KeyValue(
            key = Key(
                label = "GHI",
                action = KeyAction.EmitKey('4'.code),
                bounds = bounds,
                style = KeyStyle.T9_PRIMARY,
            ),
            gesturePolicy = policy,
        )
        val blankRailArea = FrozenTouchTarget.PanelScrollArea(
            panel = ScrollPanel.T9_LEFT_RAIL,
            bounds = bounds,
            gesturePolicy = policy,
        )

        assertTrue(leftRail.isT9PinyinRailPointerTarget())
        assertTrue(blankRailArea.isT9PinyinRailPointerTarget())
        assertFalse(central.isT9PinyinRailPointerTarget())
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

    private class RecordingActionEffects : KeyboardActionEffects {
        override fun stopPanelFling() = Unit
        override fun stopCandidateSettle() = Unit
    }

    private class RecordingActions : KeyboardInteractionActionSink {
        val keys = ArrayList<Int>()
        val texts = ArrayList<String>()
        val aiStarts = ArrayList<Long>()
        val inputSchemes = ArrayList<KeyboardInputSchemeChoice>()
        val t9PinyinChoices = ArrayList<Pair<Long, Int>>()
        var t9SideSymbolSettingsCount = 0

        override fun onKey(code: Int) {
            keys += code
        }

        override fun onCandidate(revision: Long, sourceIndex: Int) = Unit
        override fun onText(text: String) {
            texts += text
        }
        override fun onClipboardAction(action: KeyboardClipboardAction, index: Int) = Unit
        override fun onEditorAction(action: KeyboardEditorAction) = Unit
        override fun onSettingsAction() = Unit
        override fun onT9SideSymbolSettings() {
            t9SideSymbolSettingsCount += 1
        }
        override fun onInputSchemeSelected(choice: KeyboardInputSchemeChoice) {
            inputSchemes += choice
        }
        override fun onT9PinyinChoiceSelected(revision: Long, index: Int) {
            t9PinyinChoices += revision to index
        }

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
        override var interactionInputSchemeChoice = KeyboardInputSchemeChoice.PINYIN_QWERTY
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
