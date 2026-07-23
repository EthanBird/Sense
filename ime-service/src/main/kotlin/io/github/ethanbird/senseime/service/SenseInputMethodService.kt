package io.github.ethanbird.senseime.service

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
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
import io.github.ethanbird.senseime.core.EnglishLexicon
import io.github.ethanbird.senseime.core.EnglishInputSession
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.SemanticCandidateCatalog
import io.github.ethanbird.senseime.core.SemanticCandidateMixer
import io.github.ethanbird.senseime.core.UserLexicon
import io.github.ethanbird.senseime.core.editorComposingTextUpdate
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.KeyboardLayoutContract
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import java.util.ArrayDeque
import org.json.JSONArray

class SenseInputMethodService : InputMethodService() {
    private var decoder: InputDecoder = FakeDecoder()
    private var adaptiveDecoder: AdaptivePinyinDecoder? = null
    private var userLexicon: UserLexicon? = null
    private val candidateSession = CandidateDecodeSession()
    private var composition = PinyinComposition()
    private var englishInput = EnglishInputSession(EnglishLexicon.EMPTY)
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
    private var editorSelectionActive = false
    private var selectionStart = -1
    private var selectionEnd = -1
    private var learningAllowed = true
    private var clipboardHistoryAllowed = false
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardHistory = ArrayDeque<String>()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboardHistoryAllowed) capturePrimaryClipboard()
    }
    private val pendingCommitTimeout = Runnable {
        val revision = pendingSpaceRevision ?: return@Runnable
        if (composition.revision != revision) return@Runnable
        pendingSpaceRevision = null
        commitRawComposition()
        drainDeferredInputs()
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
        val englishLexicon = runCatching {
            assets.open(ENGLISH_LEXICON_ASSET).use { EnglishLexicon.load(it) }
        }.getOrElse { EnglishLexicon.EMPTY }
        englishInput = EnglishInputSession(englishLexicon, DECODE_CANDIDATE_LIMIT)
        val learned = runCatching<UserLexicon> { PersistentUserLexicon(this) }.getOrElse { MemoryUserLexicon() }
        userLexicon = learned
        adaptiveDecoder = AdaptivePinyinDecoder(
            baseDecoder,
            learned,
            PinyinSyllableSegmenter(syllables),
            englishLexicon,
        )
        decoder = adaptiveDecoder ?: baseDecoder
        val activeDecoder = decoder
        candidateRunner = LatestOnlyTaskRunner(
            threadName = "sense-candidate-decoder",
            work = { request ->
                val decoding = (activeDecoder as? ProgressivePinyinDecoder)?.decodeProgressively(
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
                decoding.copy(
                    wholeCandidates = SemanticCandidateMixer.merge(
                        primary = decoding.wholeCandidates,
                        semantic = SemanticCandidateCatalog.suggest(
                            request.composition.remainingPinyin,
                        ),
                        limit = DECODE_CANDIDATE_LIMIT,
                    ),
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
    }

    override fun onCreateInputView(): View = SenseKeyboardView(this).also { view ->
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val preferredHeight = KeyboardLayoutContract.preferredKeyboardHeightDp(landscape)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (preferredHeight * resources.displayMetrics.density).toInt(),
        )
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        view.textListener = ::commitText
        view.clipboardActionListener = ::handleClipboardAction
        view.editorActionListener = ::handleEditorAction
        view.setChineseMode(chineseMode)
        keyboardView = view
        render()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val persistenceAllowed = allowsLocalPersistence(attribute)
        learningAllowed = persistenceAllowed
        clipboardHistoryAllowed = persistenceAllowed
        editorSelectionActive = false
        selectionStart = -1
        selectionEnd = -1
        keyboardView?.setEditorSelectionActive(false)
        resetComposition(finishConnection = true)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        if (clipboardHistoryAllowed) {
            capturePrimaryClipboard()
        } else {
            keyboardView?.updateClipboard(emptyList())
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    override fun onFinishInput() {
        clipboardHistoryAllowed = false
        resetComposition(finishConnection = true)
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        val hasActiveComposition = if (chineseMode) {
            composition.visibleText.isNotEmpty()
        } else {
            englishInput.composing.isNotEmpty()
        }
        if (
            EditorCompositionSelectionPolicy.shouldCancelLocalComposition(
                hasActiveComposition = hasActiveComposition,
                newSelectionStart = newSelStart,
                newSelectionEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
            )
        ) {
            // Clear local state before asking the host to finish its span so a
            // synchronous selection callback cannot observe the stale session.
            resetComposition(finishConnection = true)
        }
        val nextSelectionActive = when {
            newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd -> true
            oldSelStart >= 0 && oldSelEnd >= 0 && oldSelStart != oldSelEnd -> false
            else -> editorSelectionActive
        }
        if (nextSelectionActive != editorSelectionActive) {
            editorSelectionActive = nextSelectionActive
            keyboardView?.setEditorSelectionActive(nextSelectionActive)
        }
    }

    override fun onDestroy() {
        destroyed = true
        pendingSpaceRevision = null
        mainHandler.removeCallbacks(pendingCommitTimeout)
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
            KeyCodes.EDITOR -> showEditor()
            KeyCodes.VOICE -> openVoiceInput()
            KeyCodes.ENTER -> handleEnter()

            else -> if (code > 0) handleCharacter(code.toChar())
        }
    }

    private fun handleCharacter(character: Char) {
        if (!chineseMode) {
            val output = if (shifted) character.uppercaseChar() else character
            if (output.lowercaseChar() in 'a'..'z') {
                englishInput.type(output)
                updateConnectionComposition(englishInput.composing)
                render()
            } else {
                commitText(output.toString())
            }
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
        updateConnectionComposition(composition.visibleText)
        render()
    }

    private fun handleBackspace() {
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            englishInput.backspace()
            updateConnectionComposition(englishInput.composing)
            render()
        } else if (composition.visibleText.isNotEmpty()) {
            composition = composition.backspace()
            updateConnectionComposition(composition.visibleText)
            render()
        } else {
            deleteOneCodePointOrSelection()
        }
    }

    private fun handleSpace() {
        if (!chineseMode) {
            if (englishInput.composing.isNotEmpty()) {
                if (!commitEnglishComposition(englishInput.defaultCommitCandidate)) return
            }
            currentInputConnection?.commitText(" ", 1)
            return
        }
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
            startPendingCommit(composition.revision)
            if (candidateRunner == null) {
                clearPendingCommit()
                commitRawComposition()
            }
            return
        }
        commitPrimary(decoding.wholeCandidates.firstOrNull())
    }

    private fun handleEnter() {
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            commitEnglishComposition(candidate = null)
        } else if (composition.visibleText.isNotEmpty()) {
            // Enter confirms exactly what the user can see. It never auto-selects
            // a Chinese candidate and does not append a newline in this branch.
            commitRawComposition()
        } else if (!sendDefaultEditorAction(true)) {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun commitCandidate(revision: Long, sourceIndex: Int) {
        if (!chineseMode) {
            englishInput.select(revision, sourceIndex)?.let(::commitEnglishComposition)
            return
        }
        if (composition.visibleText.isEmpty()) return
        when (val choice = candidateSession.select(composition, revision, sourceIndex)) {
            is ProgressiveCandidateChoice.Whole -> commitPrimary(choice.candidate)
            is ProgressiveCandidateChoice.Prefix -> {
                val next = composition.acceptPrefix(revision, choice.value)
                if (next == composition) return
                composition = next
                updateConnectionComposition(composition.visibleText)
                render()
            }

            null -> Unit
        }
    }

    private fun commitText(text: String) {
        if (deferIfSpacePending(DeferredInput.Text(text))) return
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            if (!commitEnglishComposition(englishInput.defaultCommitCandidate)) return
        }
        if (composition.visibleText.isNotEmpty()) {
            val decoding = currentDecoding()
            if (composition.remainingPinyin.isNotEmpty() && decoding == null) {
                startPendingCommit(composition.revision)
                deferredInputs.addLast(DeferredInput.Text(text))
                if (candidateRunner == null) {
                    clearPendingCommit()
                    commitRawComposition()
                    drainDeferredInputs()
                }
                return
            }
            commitPrimary(decoding?.wholeCandidates?.firstOrNull())
        }
        currentInputConnection?.commitText(text, 1)
    }

    private fun toggleLanguage() {
        if (chineseMode) {
            if (composition.visibleText.isNotEmpty()) commitRawComposition()
        } else if (englishInput.composing.isNotEmpty()) {
            commitEnglishComposition(candidate = null)
        }
        chineseMode = !chineseMode
        shifted = false
        keyboardView?.setShifted(false)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun switchInputMethod() {
        commitActiveRawComposition()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun showClipboard() {
        if (!clipboardHistoryAllowed) {
            keyboardView?.showClipboard(emptyList())
            return
        }
        capturePrimaryClipboard()
        keyboardView?.showClipboard(clipboardHistory.toList())
    }

    private fun showEditor() {
        commitActiveRawComposition()
        editorSelectionActive = selectionStart >= 0 && selectionStart != selectionEnd
        keyboardView?.setEditorSelectionActive(editorSelectionActive)
        keyboardView?.setPanel(SenseKeyboardView.Panel.EDITOR)
    }

    private fun handleEditorAction(action: SenseKeyboardView.EditorAction) {
        when (action) {
            SenseKeyboardView.EditorAction.BACK -> {
                editorSelectionActive = false
                keyboardView?.setEditorSelectionActive(false)
                keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
            }

            SenseKeyboardView.EditorAction.TOGGLE_SELECTION -> {
                editorSelectionActive = !editorSelectionActive
                keyboardView?.setEditorSelectionActive(editorSelectionActive)
            }

            SenseKeyboardView.EditorAction.UP -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_UP)
            SenseKeyboardView.EditorAction.LEFT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_LEFT)
            SenseKeyboardView.EditorAction.RIGHT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            SenseKeyboardView.EditorAction.DOWN -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_DOWN)
            SenseKeyboardView.EditorAction.HOME -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_HOME)
            SenseKeyboardView.EditorAction.END -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_END)
            SenseKeyboardView.EditorAction.DELETE -> deleteOneCodePointOrSelection()
            SenseKeyboardView.EditorAction.COPY -> {
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }

            SenseKeyboardView.EditorAction.CUT -> {
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
            }

            SenseKeyboardView.EditorAction.PASTE -> {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }

            SenseKeyboardView.EditorAction.SELECT_ALL -> {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
        }
    }

    private fun sendDirectionalKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val metaState = if (editorSelectionActive) KeyEvent.META_SHIFT_ON else 0
        currentInputConnection?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
        )
        currentInputConnection?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState),
        )
    }

    private fun deleteOneCodePointOrSelection() {
        val connection = currentInputConnection ?: return
        if (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd) {
            val now = SystemClock.uptimeMillis()
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        } else {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun handleClipboardAction(action: SenseKeyboardView.ClipboardAction, index: Int) {
        if (!clipboardHistoryAllowed) return
        when (action) {
            SenseKeyboardView.ClipboardAction.CLEAR -> clipboardHistory.clear()
            SenseKeyboardView.ClipboardAction.DELETE -> {
                clipboardHistory.elementAtOrNull(index)?.let(clipboardHistory::remove)
            }

            SenseKeyboardView.ClipboardAction.REFRESH -> capturePrimaryClipboard()
        }
        persistClipboardHistory()
        keyboardView?.updateClipboard(clipboardHistory.toList())
    }

    private fun capturePrimaryClipboard() {
        if (!clipboardHistoryAllowed) return
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
        englishInput.reset()
        clearPendingCommit()
        deferredInputs.clear()
        shifted = false
        keyboardView?.setShifted(false)
        if (finishConnection) currentInputConnection?.finishComposingText()
        render()
    }

    private fun render() {
        if (!chineseMode) {
            keyboardView?.updateComposing(
                englishInput.revision,
                englishInput.composing,
                englishInput.candidates.map { it.text },
            )
            return
        }
        val request = CandidateDecodeRequest(composition)
        val launch = candidateSession.begin(request.composition)
        if (launch.stateChanged) mainHandler.removeCallbacksAndMessages(candidateResultToken)
        if (launch.presentation.pending) {
            keyboardView?.updateComposition(
                request.composition.revision,
                request.composition.visibleText,
            )
        } else {
            keyboardView?.updateComposing(
                request.composition.revision,
                request.composition.visibleText,
                launch.presentation.snapshot.candidates.map { it.text },
            )
        }
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
            clearPendingCommit()
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

    private fun commitEnglishComposition(candidate: Candidate?): Boolean {
        if (englishInput.composing.isEmpty()) return false
        val output = candidate?.text ?: englishInput.composing
        val committed = currentInputConnection?.commitText(output, 1) == true
        if (!committed) return false
        englishInput.reset()
        shifted = false
        keyboardView?.setShifted(false)
        render()
        return true
    }

    private fun commitActiveRawComposition() {
        if (chineseMode) {
            if (composition.visibleText.isNotEmpty()) commitRawComposition()
        } else if (englishInput.composing.isNotEmpty()) {
            commitEnglishComposition(candidate = null)
        }
    }

    private fun commitComposition(
        output: String,
        rawInput: String?,
        learnable: Candidate?,
    ): Boolean {
        val committed = currentInputConnection?.commitText(output, 1) == true
        if (!committed) {
            clearPendingCommit()
            return false
        }
        if (learningAllowed && rawInput != null && learnable != null) {
            adaptiveDecoder?.learn(rawInput, learnable)
        }
        composition = composition.reset()
        clearPendingCommit()
        shifted = false
        keyboardView?.setShifted(false)
        render()
        return true
    }

    private fun updateConnectionComposition(visibleText: String) {
        val update = editorComposingTextUpdate(visibleText)
        currentInputConnection?.setComposingText(update.text, update.newCursorPosition)
    }

    private fun deferIfSpacePending(input: DeferredInput): Boolean {
        if (pendingSpaceRevision == null) return false
        if (deferredInputs.size < MAX_DEFERRED_INPUT_EVENTS) {
            deferredInputs.addLast(input)
            return true
        }

        // A failed/very slow decoder must never make the keyboard stop accepting input.
        clearPendingCommit()
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

    private fun startPendingCommit(revision: Long) {
        pendingSpaceRevision = revision
        mainHandler.removeCallbacks(pendingCommitTimeout)
        mainHandler.postDelayed(pendingCommitTimeout, PENDING_COMMIT_TIMEOUT_MS)
    }

    private fun clearPendingCommit() {
        pendingSpaceRevision = null
        mainHandler.removeCallbacks(pendingCommitTimeout)
    }

    private fun allowsLocalPersistence(info: EditorInfo?): Boolean {
        if (info == null) return true
        val noPersonalizedLearning =
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val passwordVariation = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in PASSWORD_TEXT_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        return EditorPrivacyPolicy.allowsPersistence(
            noPersonalizedLearning = noPersonalizedLearning,
            passwordVariation = passwordVariation,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PINYIN_ASSET = "pinyin_lexicon.bin"
        const val PINYIN_BIGRAM_ASSET = "pinyin_bigrams.bin"
        const val PINYIN_SYLLABLES_ASSET = "pinyin_syllables.txt"
        const val ENGLISH_LEXICON_ASSET = "english_lexicon.txt"
        const val DECODE_CANDIDATE_LIMIT = 255
        const val MAX_PROGRESSIVE_PREFIX_CANDIDATES = DECODE_CANDIDATE_LIMIT
        const val PRESENTATION_CANDIDATE_LIMIT = DECODE_CANDIDATE_LIMIT + MAX_PROGRESSIVE_PREFIX_CANDIDATES
        const val CLIPBOARD_HISTORY_LIMIT = 30
        const val MAX_CLIPBOARD_TEXT_LENGTH = 4096
        const val MAX_DEFERRED_INPUT_EVENTS = 512
        const val PENDING_COMMIT_TIMEOUT_MS = 120L
        const val CLIPBOARD_PREFERENCES = "sense_clipboard_history"
        const val CLIPBOARD_HISTORY_KEY = "items"
        val PASSWORD_TEXT_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
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
