package io.github.ethanbird.senseime.ui

internal interface KeyboardActionEffects {
    fun stopPanelFling()
    fun stopCandidateSettle()
    fun cancelCandidateInteraction() = Unit
}

/**
 * Semantic action boundary for terminal keyboard gestures.
 *
 * Pointer ownership never enters this class. It serializes ordinary key codes
 * through one primitive FIFO and preserves the explicit flush boundaries used
 * by candidate, text, editor, panel, and AI activation paths.
 */
internal class KeyboardActionDispatcher(
    private val host: KeyboardInteractionHost,
    private val scheduler: KeyboardFrameScheduler,
    private val actions: KeyboardInteractionActionSink,
    private val effects: KeyboardActionEffects,
) {
    private val keyEventQueue = KeyEventQueue(initialCapacity = 64)
    private var keyDispatchPosted = false

    private val keyDispatchRunnable = Runnable {
        keyDispatchPosted = false
        while (true) actions.onKey(keyEventQueue.poll() ?: break)
    }

    fun activate(
        target: FrozenTouchTarget,
        gesture: TouchInputReducer.Gesture,
    ) {
        when (target) {
            is FrozenTouchTarget.CandidateValue -> if (gesture == TouchInputReducer.Gesture.TAP) {
                flushKeys()
                actions.onCandidate(target.revision, target.sourceIndex)
            }

            is FrozenTouchTarget.CandidateControlValue ->
                if (gesture == TouchInputReducer.Gesture.TAP) {
                    activateCandidateControl(target.value)
                }

            // Continuous drag/fling is owned by PanelScrollController. The
            // semantic dispatcher intentionally has no page-step action.
            is FrozenTouchTarget.CandidateGridArea -> Unit

            is FrozenTouchTarget.CandidateStripArea -> Unit
            is FrozenTouchTarget.PanelScrollArea -> Unit
            is FrozenTouchTarget.KeyValue -> activateKeyGesture(target.key, gesture)
        }
    }

    fun dispatchDelete(key: Key) {
        when (deleteRepeatTarget(key)) {
            DeleteRepeatTarget.KEY -> enqueueKey(KeyCodes.DELETE)
            DeleteRepeatTarget.EDITOR -> {
                flushKeys()
                actions.onEditorAction(KeyboardEditorAction.DELETE)
            }

            null -> Unit
        }
    }

    fun deleteRepeatTarget(key: Key): DeleteRepeatTarget? =
        DeleteRepeatTargetPolicy.resolve(
            keyCode = key.code,
            editorActionIsDelete =
                (key.action as? KeyAction.Editor)?.action == KeyboardEditorAction.DELETE,
        )

    fun canStartSkillGesture(key: Key): Boolean =
        KeyboardSkillKeyPolicy.supportsKeyCode(key.code) &&
            deleteRepeatTarget(key) == null &&
            key.style != KeyStyle.CARD &&
            key.style != KeyStyle.EMOJI &&
            key.style != KeyStyle.CATEGORY &&
            key.style != KeyStyle.SYMBOL &&
            key.style != KeyStyle.SYMBOL_CATEGORY &&
            key.style != KeyStyle.T9_LEFT_RAIL &&
            key.scrollPanel == null &&
            isKeyEnabled(key)

    fun isKeyEnabled(key: Key): Boolean {
        if (key.style == KeyStyle.VOICE_PRIMARY && key.code == 0) return false
        return when ((key.action as? KeyAction.Editor)?.action) {
            KeyboardEditorAction.COPY,
            KeyboardEditorAction.CUT,
            -> host.interactionEditorHasSelection

            KeyboardEditorAction.PASTE -> host.interactionEditorCanPaste
            else -> true
        }
    }

    fun flushKeys() {
        if (keyEventQueue.pendingCount == 0) return
        scheduler.remove(keyDispatchRunnable)
        keyDispatchPosted = false
        while (true) actions.onKey(keyEventQueue.poll() ?: break)
    }

    fun detach() {
        scheduler.remove(keyDispatchRunnable)
        keyEventQueue.clear()
        keyDispatchPosted = false
    }

    private fun activateKeyGesture(
        key: Key,
        gesture: TouchInputReducer.Gesture,
    ) {
        if (!isKeyEnabled(key)) return
        if (deleteRepeatTarget(key) != null) return
        when {
            key.scrollPanel != null && gesture != TouchInputReducer.Gesture.TAP -> Unit
            key.style == KeyStyle.CARD &&
                gesture != TouchInputReducer.Gesture.TAP &&
                clipboardItems.isNotEmpty() -> {
                scrollClipboard(if (gesture == TouchInputReducer.Gesture.SWIPE_UP) 1 else -1)
            }

            gesture == TouchInputReducer.Gesture.SWIPE_UP && key.code > 0 -> {
                key.swipeOutput?.let {
                    flushKeys()
                    actions.onText(it)
                }
            }

            gesture == TouchInputReducer.Gesture.TAP -> activateKey(key)
        }
    }

    private fun activateCandidateControl(control: CandidateControl) {
        if (control == CandidateControl.DISMISS) {
            flushKeys()
            actions.onCandidateDismiss()
            return
        }
        val change = candidatePanel.activate(
            control = control,
            viewWidth = host.interactionWidth,
            viewHeight = host.interactionHeight,
            editorPanelVisible = host.interactionCandidateToolbarSuppressed(),
            fontScale = host.interactionFontScale,
        )
        if (change.cancelInteraction) effects.cancelCandidateInteraction()
        if (change.cancelSettle) effects.stopCandidateSettle()
        if (change.requiresKeySceneRebuild) host.interactionRebuildKeys()
        scheduler.invalidate()
    }

    private fun activateKey(key: Key) {
        when (val action = key.action) {
            is KeyAction.Editor -> {
                flushKeys()
                actions.onEditorAction(action.action)
            }

            is KeyAction.Clipboard -> activateClipboardAction(action.action, action.index)
            is KeyAction.SelectEmojiCategory -> {
                effects.stopPanelFling()
                host.interactionEmojiGroupIndex =
                    action.index.coerceIn(0, EmojiCatalog.categories.lastIndex)
                host.interactionScene.emojiScrollState.reset()
                host.interactionRebuildKeys()
                scheduler.invalidate()
            }

            is KeyAction.SelectSymbolCategory -> {
                effects.stopPanelFling()
                host.interactionSymbolCategoryIndex =
                    action.index.coerceIn(0, SymbolCatalog.categories.lastIndex)
                host.interactionScene.symbolGridScrollState.reset()
                host.interactionRebuildKeys()
                scheduler.invalidate()
            }

            is KeyAction.SelectT9PinyinChoice -> {
                flushKeys()
                actions.onT9PinyinChoiceSelected(action.revision, action.index)
            }

            KeyAction.OpenT9SideSymbolSettings -> {
                flushKeys()
                actions.onT9SideSymbolSettings()
            }

            is KeyAction.ShowPanel -> {
                flushKeys()
                host.interactionSetPanel(action.panel)
            }

            is KeyAction.SelectInputScheme -> {
                flushKeys()
                if (host.interactionInputSchemeChoice != action.choice) {
                    host.interactionInputSchemeChoice = action.choice
                    actions.onInputSchemeSelected(action.choice)
                }
                host.interactionSetPanel(KeyboardPanel.LETTERS)
            }

            is KeyAction.CommitText -> {
                flushKeys()
                actions.onText(action.text)
                if (host.interactionPanel == KeyboardPanel.CLIPBOARD) {
                    host.interactionSetPanel(KeyboardPanel.LETTERS)
                }
            }

            is KeyAction.EmitKey -> activateKeyCode(action.keyCode)
            KeyAction.None -> Unit
        }
    }

    private fun activateKeyCode(code: Int) {
        if (code == 0) return
        val toolboxRoute = if (code < 0) {
            KeyboardLayoutContract.toolboxActivationRoute(code)
        } else {
            null
        }
        toolboxRoute?.let { route ->
            flushKeys()
            when (route) {
                KeyboardLayoutContract.ToolboxActivationRoute.SYMBOLS_PANEL ->
                    host.interactionSetPanel(KeyboardPanel.SYMBOLS)

                KeyboardLayoutContract.ToolboxActivationRoute.EMOJI_PANEL ->
                    host.interactionSetPanel(KeyboardPanel.EMOJI)

                KeyboardLayoutContract.ToolboxActivationRoute.SERVICE_ACTION ->
                    enqueueKey(code)

                KeyboardLayoutContract.ToolboxActivationRoute.SETTINGS_CALLBACK ->
                    actions.onSettingsAction()

                KeyboardLayoutContract.ToolboxActivationRoute.AGENT_CALLBACK ->
                    actions.onAgentAction()
            }
            return
        }
        when (code) {
            KeyCodes.LETTERS -> {
                flushKeys()
                host.interactionSetPanel(KeyboardPanel.LETTERS)
            }

            KeyCodes.NUMBERS -> {
                flushKeys()
                host.interactionSetPanel(KeyboardPanel.NUMBERS)
            }

            KeyCodes.TOOLBOX -> {
                flushKeys()
                host.interactionSetPanel(KeyboardPanel.TOOLBOX)
            }

            else -> enqueueKey(code)
        }
    }

    private fun activateClipboardAction(action: KeyboardClipboardAction, index: Int) {
        when (action) {
            KeyboardClipboardAction.CLEAR -> {
                clipboardItems = emptyList()
                host.interactionClipboardPageIndex = 0
            }

            KeyboardClipboardAction.DELETE -> if (index in clipboardItems.indices) {
                clipboardItems =
                    clipboardItems.filterIndexed { itemIndex, _ -> itemIndex != index }
                val pages =
                    ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) /
                        CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
                host.interactionClipboardPageIndex =
                    host.interactionClipboardPageIndex.coerceAtMost(pages - 1)
            }

            KeyboardClipboardAction.REFRESH -> Unit
        }
        actions.onClipboardAction(action, index)
        host.interactionRebuildKeys()
        scheduler.invalidate()
    }

    private fun scrollClipboard(delta: Int) {
        val pageCount =
            ((clipboardItems.size + CLIPBOARD_ITEMS_PER_PAGE - 1) /
                CLIPBOARD_ITEMS_PER_PAGE).coerceAtLeast(1)
        host.interactionClipboardPageIndex =
            (host.interactionClipboardPageIndex + delta).coerceIn(0, pageCount - 1)
        host.interactionRebuildKeys()
        scheduler.invalidate()
    }

    private fun enqueueKey(code: Int) {
        keyEventQueue.offer(code)
        if (!keyDispatchPosted) {
            keyDispatchPosted = true
            scheduler.post(keyDispatchRunnable)
        }
    }

    private var clipboardItems: List<String>
        get() = host.interactionClipboardItems
        set(value) {
            host.interactionClipboardItems = value
        }

    private val candidatePanel: CandidatePanel
        get() = host.interactionCandidatePanel

    private companion object {
        const val CLIPBOARD_ITEMS_PER_PAGE = 3
    }
}
