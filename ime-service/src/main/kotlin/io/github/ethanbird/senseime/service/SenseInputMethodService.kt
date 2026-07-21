package io.github.ethanbird.senseime.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputAction
import io.github.ethanbird.senseime.core.InputReducer
import io.github.ethanbird.senseime.core.InputState
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.SenseKeyboardView

class SenseInputMethodService : InputMethodService() {
    private val reducer = InputReducer()
    private val decoder = FakeDecoder()
    private var state = InputState()
    private var shifted = false
    private var keyboardView: SenseKeyboardView? = null

    override fun onCreateInputView(): View = SenseKeyboardView(this).also { view ->
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(312),
        )
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        keyboardView = view
        render()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposition(finishConnection = true)
    }

    override fun onFinishInput() {
        resetComposition(finishConnection = true)
        super.onFinishInput()
    }

    override fun onDestroy() {
        keyboardView = null
        super.onDestroy()
    }

    private fun handleKey(code: Int) {
        when (code) {
            KeyCodes.SHIFT -> {
                shifted = !shifted
                keyboardView?.setShifted(shifted)
            }

            KeyCodes.SYMBOLS -> commitText(if (state.composing.isEmpty()) "，" else "'")
            KeyCodes.DELETE -> handleBackspace()
            KeyCodes.SPACE -> handleSpace()
            KeyCodes.ENTER -> {
                if (state.composing.isNotEmpty()) commitCandidate(0)
                currentInputConnection?.commitText("\n", 1)
            }

            else -> if (code > 0) {
                state = reducer.reduce(state, InputAction.Type(code.toChar()))
                currentInputConnection?.setComposingText(state.composing, 1)
                render()
            }
        }
    }

    private fun handleBackspace() {
        if (state.composing.isNotEmpty()) {
            state = reducer.reduce(state, InputAction.Backspace)
            if (state.composing.isEmpty()) {
                currentInputConnection?.finishComposingText()
            } else {
                currentInputConnection?.setComposingText(state.composing, 1)
            }
            render()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleSpace() {
        if (state.composing.isNotEmpty()) {
            commitCandidate(0)
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun commitCandidate(index: Int) {
        if (state.composing.isEmpty()) return
        val candidate = decoder.decode(state.composing).getOrNull(index)?.text ?: state.composing
        state = reducer.reduce(state, InputAction.Commit(candidate))
        currentInputConnection?.commitText(candidate, 1)
        shifted = false
        keyboardView?.setShifted(false)
        render()
    }

    private fun commitText(text: String) {
        if (state.composing.isNotEmpty()) commitCandidate(0)
        currentInputConnection?.commitText(text, 1)
    }

    private fun resetComposition(finishConnection: Boolean) {
        state = reducer.reduce(state, InputAction.Reset)
        shifted = false
        keyboardView?.setShifted(false)
        if (finishConnection) currentInputConnection?.finishComposingText()
        render()
    }

    private fun render() {
        val candidates = decoder.decode(state.composing).map { it.text }
        keyboardView?.updateComposing(state.composing, candidates)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

