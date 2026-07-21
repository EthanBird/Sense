package io.github.ethanbird.senseime.service

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputAction
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.InputReducer
import io.github.ethanbird.senseime.core.InputState
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import java.util.ArrayDeque

class SenseInputMethodService : InputMethodService() {
    private val reducer = InputReducer()
    private var decoder: InputDecoder = FakeDecoder()
    private var state = InputState()
    private var shifted = false
    private var chineseMode = true
    private var keyboardView: SenseKeyboardView? = null
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardHistory = ArrayDeque<String>()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        capturePrimaryClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        decoder = runCatching {
            assets.open(PINYIN_ASSET).use(PinyinDecoder::load)
        }.getOrElse { FakeDecoder() }
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        capturePrimaryClipboard()
    }

    override fun onCreateInputView(): View = SenseKeyboardView(this).also { view ->
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        view.textListener = ::commitText
        view.setChineseMode(chineseMode)
        keyboardView = view
        render()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposition(finishConnection = true)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        capturePrimaryClipboard()
    }

    override fun onFinishInput() {
        resetComposition(finishConnection = true)
        super.onFinishInput()
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        keyboardView = null
        super.onDestroy()
    }

    private fun handleKey(code: Int) {
        when (code) {
            KeyCodes.SHIFT -> {
                shifted = !shifted
                keyboardView?.setShifted(shifted)
            }

            KeyCodes.DELETE -> handleBackspace()
            KeyCodes.SPACE -> handleSpace()
            KeyCodes.COMMA -> commitText(if (chineseMode) "，" else ",")
            KeyCodes.PERIOD -> commitText(if (chineseMode) "。" else ".")
            KeyCodes.LANGUAGE -> toggleLanguage()
            KeyCodes.SWITCH_INPUT_METHOD -> switchInputMethod()
            KeyCodes.CLIPBOARD -> showClipboard()
            KeyCodes.HIDE -> requestHideSelf(0)
            KeyCodes.EDITOR -> sendCursorLeft()
            KeyCodes.VOICE -> openVoiceInput()
            KeyCodes.ENTER -> {
                if (state.composing.isNotEmpty()) commitCandidate(0)
                currentInputConnection?.commitText("\n", 1)
            }

            else -> if (code > 0) handleCharacter(code.toChar())
        }
    }

    private fun handleCharacter(character: Char) {
        if (!chineseMode) {
            val output = if (shifted) character.uppercaseChar() else character
            currentInputConnection?.commitText(output.toString(), 1)
            if (shifted) {
                shifted = false
                keyboardView?.setShifted(false)
            }
            return
        }

        state = reducer.reduce(state, InputAction.Type(character.lowercaseChar()))
        currentInputConnection?.setComposingText(state.composing, 1)
        render()
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
        if (state.composing.isNotEmpty()) commitCandidate(0) else currentInputConnection?.commitText(" ", 1)
    }

    private fun commitCandidate(index: Int) {
        if (state.composing.isEmpty()) return
        val candidate = decoder.decode(state.composing, CANDIDATE_LIMIT).getOrNull(index)?.text ?: state.composing
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

    private fun toggleLanguage() {
        if (state.composing.isNotEmpty()) commitCandidate(0)
        chineseMode = !chineseMode
        shifted = false
        keyboardView?.setShifted(false)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun switchInputMethod() {
        if (state.composing.isNotEmpty()) commitCandidate(0)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun showClipboard() {
        capturePrimaryClipboard()
        keyboardView?.showClipboard(clipboardHistory.toList())
    }

    private fun capturePrimaryClipboard() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        clipboardHistory.remove(text)
        clipboardHistory.addFirst(text)
        while (clipboardHistory.size > CLIPBOARD_HISTORY_LIMIT) clipboardHistory.removeLast()
    }

    private fun sendCursorLeft() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    private fun openVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (chineseMode) "zh-CN" else "en-US")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Voice recognition is optional and not present on every Android build.
        }
    }

    private fun resetComposition(finishConnection: Boolean) {
        state = reducer.reduce(state, InputAction.Reset)
        shifted = false
        keyboardView?.setShifted(false)
        if (finishConnection) currentInputConnection?.finishComposingText()
        render()
    }

    private fun render() {
        val candidates = if (chineseMode) decoder.decode(state.composing, CANDIDATE_LIMIT).map { it.text } else emptyList()
        keyboardView?.updateComposing(state.composing, candidates)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PINYIN_ASSET = "pinyin_lexicon.bin"
        const val CANDIDATE_LIMIT = 6
        const val CLIPBOARD_HISTORY_LIMIT = 8
    }
}
