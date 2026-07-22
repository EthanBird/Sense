package io.github.ethanbird.senseime.service

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import io.github.ethanbird.senseime.core.CharacterBigramModel
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.UserLexicon
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import java.util.ArrayDeque
import org.json.JSONArray

class SenseInputMethodService : InputMethodService() {
    private var decoder: InputDecoder = FakeDecoder()
    private var adaptiveDecoder: AdaptivePinyinDecoder? = null
    private var userLexicon: UserLexicon? = null
    private val candidateSession = CandidateDecodeSession()
    private var composition = PinyinComposition()
    private var shifted = false
    private var chineseMode = true
    private var keyboardView: SenseKeyboardView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val candidateResultToken = Any()
    private var candidateRunner: LatestOnlyTaskRunner<CandidateDecodeRequest, ProgressivePinyinDecoding>? = null
    private var pendingSpaceRevision: Long? = null
    private val deferredInputs = ArrayDeque<DeferredInput>()
    private var drainingDeferredInputs = false
    private var destroyed = false
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardHistory = ArrayDeque<String>()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        capturePrimaryClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
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
        val activeDecoder = decoder
        candidateRunner = LatestOnlyTaskRunner(
            threadName = "sense-candidate-decoder",
            work = { request ->
                (activeDecoder as? ProgressivePinyinDecoder)?.decodeProgressively(
                    request.composition,
                    DECODE_CANDIDATE_LIMIT,
                ) ?: ProgressivePinyinDecoding(
                    revision = request.composition.revision,
                    remainingPinyin = request.composition.remainingPinyin,
                    wholeCandidates = activeDecoder.decode(
                        request.composition.remainingPinyin,
                        DECODE_CANDIDATE_LIMIT,
                    ),
                    prefixCandidates = emptyList(),
                )
            },
            deliver = { _, request, decoding ->
                postDecodedCandidates(request, decoding)
            },
            fail = { _, request, _ ->
                postDecodedCandidates(
                    request,
                    ProgressivePinyinDecoding(
                        revision = request.composition.revision,
                        remainingPinyin = request.composition.remainingPinyin,
                        wholeCandidates = emptyList(),
                        prefixCandidates = emptyList(),
                    ),
                )
            },
        )
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        loadClipboardHistory()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        capturePrimaryClipboard()
    }

    override fun onCreateInputView(): View = SenseKeyboardView(this).also { view ->
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        view.textListener = ::commitText
        view.clipboardActionListener = ::handleClipboardAction
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    override fun onFinishInput() {
        resetComposition(finishConnection = true)
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        pendingSpaceRevision = null
        deferredInputs.clear()
        mainHandler.removeCallbacksAndMessages(candidateResultToken)
        candidateRunner?.close()
        candidateRunner = null
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        userLexicon?.close()
        userLexicon = null
        adaptiveDecoder = null
        keyboardView = null
        super.onDestroy()
    }

    private fun handleKey(code: Int) {
        if (deferIfSpacePending(DeferredInput.Key(code))) return
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
            KeyCodes.ENTER -> handleEnter()

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

        if (character.lowercaseChar() !in 'a'..'z') {
            commitText(character.toString())
            return
        }

        composition = composition.type(character)
        updateConnectionComposition()
        render()
    }

    private fun handleBackspace() {
        if (composition.visibleText.isNotEmpty()) {
            composition = composition.backspace()
            updateConnectionComposition()
            render()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleSpace() {
        if (composition.visibleText.isEmpty()) {
            currentInputConnection?.commitText(" ", 1)
            return
        }
        if (composition.remainingPinyin.isEmpty()) {
            commitRawComposition()
            return
        }
        val decoding = currentDecoding()
        if (decoding == null) {
            pendingSpaceRevision = composition.revision
            if (candidateRunner == null) {
                pendingSpaceRevision = null
                commitRawComposition()
            }
            return
        }
        commitPrimary(decoding.wholeCandidates.firstOrNull())
    }

    private fun handleEnter() {
        if (composition.visibleText.isNotEmpty()) {
            // Enter confirms exactly what the user can see. It never auto-selects
            // a Chinese candidate and does not append a newline in this branch.
            commitRawComposition()
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun commitCandidate(revision: Long, sourceIndex: Int) {
        if (composition.visibleText.isEmpty()) return
        when (val choice = candidateSession.select(composition, revision, sourceIndex)) {
            is ProgressiveCandidateChoice.Whole -> commitPrimary(choice.candidate)
            is ProgressiveCandidateChoice.Prefix -> {
                val next = composition.acceptPrefix(revision, choice.value)
                if (next == composition) return
                composition = next
                updateConnectionComposition()
                render()
            }

            null -> Unit
        }
    }

    private fun commitText(text: String) {
        if (deferIfSpacePending(DeferredInput.Text(text))) return
        if (composition.visibleText.isNotEmpty()) {
            commitPrimary(currentDecoding()?.wholeCandidates?.firstOrNull())
        }
        currentInputConnection?.commitText(text, 1)
    }

    private fun toggleLanguage() {
        if (composition.visibleText.isNotEmpty()) commitRawComposition()
        chineseMode = !chineseMode
        shifted = false
        keyboardView?.setShifted(false)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun switchInputMethod() {
        if (composition.visibleText.isNotEmpty()) commitRawComposition()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun showClipboard() {
        capturePrimaryClipboard()
        keyboardView?.showClipboard(clipboardHistory.toList())
    }

    private fun handleClipboardAction(action: SenseKeyboardView.ClipboardAction, index: Int) {
        when (action) {
            SenseKeyboardView.ClipboardAction.CLEAR -> clipboardHistory.clear()
            SenseKeyboardView.ClipboardAction.DELETE -> {
                clipboardHistory.elementAtOrNull(index)?.let(clipboardHistory::remove)
            }

            SenseKeyboardView.ClipboardAction.REFRESH -> capturePrimaryClipboard()
        }
        persistClipboardHistory()
        keyboardView?.showClipboard(clipboardHistory.toList())
    }

    private fun capturePrimaryClipboard() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val raw = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (raw.isBlank()) return
        val text = raw.take(MAX_CLIPBOARD_TEXT_LENGTH)
        clipboardHistory.remove(text)
        clipboardHistory.addFirst(text)
        while (clipboardHistory.size > CLIPBOARD_HISTORY_LIMIT) clipboardHistory.removeLast()
        persistClipboardHistory()
    }

    private fun loadClipboardHistory() {
        val serialized = getSharedPreferences(CLIPBOARD_PREFERENCES, Context.MODE_PRIVATE)
            .getString(CLIPBOARD_HISTORY_KEY, null)
            ?: return
        runCatching {
            val values = JSONArray(serialized)
            for (index in 0 until minOf(values.length(), CLIPBOARD_HISTORY_LIMIT)) {
                val text = values.optString(index).take(MAX_CLIPBOARD_TEXT_LENGTH)
                if (text.isNotBlank() && !clipboardHistory.contains(text)) clipboardHistory.addLast(text)
            }
        }
    }

    private fun persistClipboardHistory() {
        val values = JSONArray()
        clipboardHistory.forEach { values.put(it) }
        getSharedPreferences(CLIPBOARD_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CLIPBOARD_HISTORY_KEY, values.toString())
            .apply()
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
        composition = composition.reset()
        pendingSpaceRevision = null
        deferredInputs.clear()
        shifted = false
        keyboardView?.setShifted(false)
        if (finishConnection) currentInputConnection?.finishComposingText()
        render()
    }

    private fun render() {
        val request = CandidateDecodeRequest(composition)
        val launch = candidateSession.begin(request.composition)
        if (launch.stateChanged) mainHandler.removeCallbacksAndMessages(candidateResultToken)
        keyboardView?.updateComposing(
            request.composition.revision,
            request.composition.visibleText,
            launch.presentation.snapshot.candidates.map { it.text },
        )
        if (chineseMode && launch.shouldDecode) {
            if (candidateRunner?.submit(request) == -1L) candidateRunner = null
        }
    }

    private fun postDecodedCandidates(
        request: CandidateDecodeRequest,
        decoding: ProgressivePinyinDecoding,
    ) {
        // Deliveries originate from one serial runner, so a later delivery can
        // safely coalesce an earlier callback before crossing to the main loop.
        // A same-revision view re-render does not remove this callback (see
        // CandidateDecodeLaunch.stateChanged), avoiding the old lost-result race.
        mainHandler.removeCallbacksAndMessages(candidateResultToken)
        mainHandler.postAtTime(
            { applyDecodedCandidates(request, decoding) },
            candidateResultToken,
            SystemClock.uptimeMillis(),
        )
    }

    private fun applyDecodedCandidates(
        request: CandidateDecodeRequest,
        decoding: ProgressivePinyinDecoding,
    ) {
        if (destroyed || !chineseMode || request.composition != composition) return
        val presentation = candidateSession.complete(
            requestedComposition = request.composition,
            decoding = decoding,
            limit = PRESENTATION_CANDIDATE_LIMIT,
        ) ?: return

        if (pendingSpaceRevision == composition.revision) {
            pendingSpaceRevision = null
            commitPrimary(decoding.wholeCandidates.firstOrNull())
            drainDeferredInputs()
            return
        }
        keyboardView?.updateComposing(
            composition.revision,
            composition.visibleText,
            presentation.snapshot.candidates.map { it.text },
        )
    }

    private fun currentDecoding(): ProgressivePinyinDecoding? = candidateSession.currentDecoding(composition)

    private fun commitPrimary(candidate: Candidate?): Boolean {
        if (composition.visibleText.isEmpty()) return false
        val rawInput = buildString {
            composition.acceptedSegments.forEach { append(it.consumedPinyin) }
            append(composition.remainingPinyin)
        }
        val output = composition.confirmPrimary(candidate)
        val learnable = candidate?.let { selected ->
            if (composition.acceptedSegments.isEmpty()) {
                selected
            } else if (
                selected.matchKind != CandidateMatchKind.BASE_PREFIX &&
                selected.matchKind != CandidateMatchKind.BASE_INITIALS
            ) {
                Candidate(
                    text = output,
                    score = selected.score,
                    canonicalPinyin = rawInput,
                    matchKind = CandidateMatchKind.BASE_COMPOSED,
                )
            } else {
                null
            }
        }
        return commitComposition(output, rawInput, learnable)
    }

    private fun commitRawComposition(): Boolean {
        if (composition.visibleText.isEmpty()) return false
        return commitComposition(composition.confirmRaw(), rawInput = null, learnable = null)
    }

    private fun commitComposition(
        output: String,
        rawInput: String?,
        learnable: Candidate?,
    ): Boolean {
        val committed = currentInputConnection?.commitText(output, 1) == true
        if (!committed) {
            pendingSpaceRevision = null
            return false
        }
        if (rawInput != null && learnable != null) adaptiveDecoder?.learn(rawInput, learnable)
        composition = composition.reset()
        pendingSpaceRevision = null
        shifted = false
        keyboardView?.setShifted(false)
        render()
        return true
    }

    private fun updateConnectionComposition() {
        if (composition.visibleText.isEmpty()) {
            currentInputConnection?.finishComposingText()
        } else {
            currentInputConnection?.setComposingText(composition.visibleText, 1)
        }
    }

    private fun deferIfSpacePending(input: DeferredInput): Boolean {
        if (pendingSpaceRevision == null) return false
        if (deferredInputs.size < MAX_DEFERRED_INPUT_EVENTS) {
            deferredInputs.addLast(input)
            return true
        }

        // A failed/very slow decoder must never make the keyboard stop accepting input.
        pendingSpaceRevision = null
        commitRawComposition()
        drainDeferredInputs()
        if (pendingSpaceRevision != null) {
            deferredInputs.addLast(input)
            return true
        }
        return false
    }

    private fun drainDeferredInputs() {
        if (drainingDeferredInputs || pendingSpaceRevision != null) return
        drainingDeferredInputs = true
        try {
            while (deferredInputs.isNotEmpty() && pendingSpaceRevision == null) {
                when (val input = deferredInputs.removeFirst()) {
                    is DeferredInput.Key -> handleKey(input.code)
                    is DeferredInput.Text -> commitText(input.text)
                }
            }
        } finally {
            drainingDeferredInputs = false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PINYIN_ASSET = "pinyin_lexicon.bin"
        const val PINYIN_BIGRAM_ASSET = "pinyin_bigrams.bin"
        const val PINYIN_SYLLABLES_ASSET = "pinyin_syllables.txt"
        const val DECODE_CANDIDATE_LIMIT = 255
        const val MAX_PROGRESSIVE_PREFIX_CANDIDATES = DECODE_CANDIDATE_LIMIT
        const val PRESENTATION_CANDIDATE_LIMIT = DECODE_CANDIDATE_LIMIT + MAX_PROGRESSIVE_PREFIX_CANDIDATES
        const val CLIPBOARD_HISTORY_LIMIT = 30
        const val MAX_CLIPBOARD_TEXT_LENGTH = 4096
        const val MAX_DEFERRED_INPUT_EVENTS = 512
        const val CLIPBOARD_PREFERENCES = "sense_clipboard_history"
        const val CLIPBOARD_HISTORY_KEY = "items"
        val FALLBACK_SYLLABLES = setOf("a", "ai", "an", "ang", "ao", "ba", "de", "ge", "hao", "ni", "ren", "shi", "wo", "xian", "yi")
    }
}

private data class CandidateDecodeRequest(
    val composition: PinyinComposition,
)

private sealed interface DeferredInput {
    data class Key(val code: Int) : DeferredInput
    data class Text(val text: String) : DeferredInput
}
