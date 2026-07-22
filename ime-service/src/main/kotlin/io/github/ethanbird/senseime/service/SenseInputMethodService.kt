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
import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.BinaryCharacterBigramModel
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.CandidateSnapshot
import io.github.ethanbird.senseime.core.CharacterBigramModel
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputAction
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.InputReducer
import io.github.ethanbird.senseime.core.InputState
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.UserLexicon
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import java.util.ArrayDeque

class SenseInputMethodService : InputMethodService() {
    private val reducer = InputReducer()
    private var decoder: InputDecoder = FakeDecoder()
    private var adaptiveDecoder: AdaptivePinyinDecoder? = null
    private var userLexicon: UserLexicon? = null
    private var renderedSnapshot = CandidateSnapshot.EMPTY
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
        val bigramModel = runCatching<CharacterBigramModel> {
            assets.open(PINYIN_BIGRAM_ASSET).use(BinaryCharacterBigramModel::load)
        }.getOrElse { CharacterBigramModel.EMPTY }
        val baseDecoder = runCatching {
            assets.open(PINYIN_ASSET).use { PinyinDecoder.load(it, bigramModel) }
        }.getOrElse { FakeDecoder() }
        val syllables = runCatching {
            assets.open(PINYIN_SYLLABLES_ASSET).bufferedReader().useLines { lines -> lines.toSet() }
        }.getOrElse { FALLBACK_SYLLABLES }
        val learned = runCatching<UserLexicon> { PersistentUserLexicon(this) }.getOrElse { MemoryUserLexicon() }
        userLexicon = learned
        adaptiveDecoder = AdaptivePinyinDecoder(baseDecoder, learned, PinyinSyllableSegmenter(syllables))
        decoder = adaptiveDecoder ?: baseDecoder
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
        userLexicon?.close()
        userLexicon = null
        adaptiveDecoder = null
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
                if (state.composing.isNotEmpty()) commitPrimaryOrRaw()
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
        if (state.composing.isNotEmpty()) commitPrimaryOrRaw() else currentInputConnection?.commitText(" ", 1)
    }

    private fun commitCandidate(revision: Long, sourceIndex: Int) {
        if (state.composing.isEmpty()) return
        val candidate = renderedSnapshot.select(state.revision, revision, sourceIndex) ?: return
        commitCandidate(candidate)
    }

    private fun commitPrimaryOrRaw() {
        if (state.composing.isEmpty()) return
        val rawInput = state.composing
        val candidate = renderedSnapshot
            .takeIf { it.revision == state.revision }
            ?.candidates
            ?.firstOrNull()
            ?: Candidate(
            text = rawInput,
            canonicalPinyin = rawInput,
            matchKind = CandidateMatchKind.BASE_EXACT,
        )
        commitCandidate(candidate)
    }

    private fun commitCandidate(candidate: Candidate) {
        val rawInput = state.composing
        val committed = currentInputConnection?.commitText(candidate.text, 1) == true
        if (!committed) return
        state = reducer.reduce(state, InputAction.Commit(candidate.text))
        adaptiveDecoder?.learn(rawInput, candidate)
        shifted = false
        keyboardView?.setShifted(false)
        render()
    }

    private fun commitText(text: String) {
        if (state.composing.isNotEmpty()) commitPrimaryOrRaw()
        currentInputConnection?.commitText(text, 1)
    }

    private fun toggleLanguage() {
        if (state.composing.isNotEmpty()) commitPrimaryOrRaw()
        chineseMode = !chineseMode
        shifted = false
        keyboardView?.setShifted(false)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun switchInputMethod() {
        if (state.composing.isNotEmpty()) commitPrimaryOrRaw()
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
        val candidates = if (chineseMode) decoder.decode(state.composing, CANDIDATE_LIMIT) else emptyList()
        renderedSnapshot = CandidateSnapshot(state.revision, candidates)
        keyboardView?.updateComposing(state.revision, state.composing, candidates.map { it.text })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PINYIN_ASSET = "pinyin_lexicon.bin"
        const val PINYIN_BIGRAM_ASSET = "pinyin_bigrams.bin"
        const val PINYIN_SYLLABLES_ASSET = "pinyin_syllables.txt"
        const val CANDIDATE_LIMIT = 32
        const val CLIPBOARD_HISTORY_LIMIT = 8
        val FALLBACK_SYLLABLES = setOf("a", "ai", "an", "ang", "ao", "ba", "de", "ge", "hao", "ni", "ren", "shi", "wo", "xian", "yi")
    }
}
