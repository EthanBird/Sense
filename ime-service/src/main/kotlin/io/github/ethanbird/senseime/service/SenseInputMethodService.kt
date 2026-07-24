package io.github.ethanbird.senseime.service

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
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
import io.github.ethanbird.senseime.service.ai.SenseAiEditorCoordinator
import io.github.ethanbird.senseime.service.ai.editor.EditorStaleReason
import io.github.ethanbird.senseime.speech.AndroidSpeechRecognizerController
import io.github.ethanbird.senseime.speech.CloudSpeechRecognitionController
import io.github.ethanbird.senseime.speech.CloudSpeechRecognitionListener
import io.github.ethanbird.senseime.speech.SpeechProviderProfile
import io.github.ethanbird.senseime.speech.SpeechProviderProtocol
import io.github.ethanbird.senseime.speech.SpeechProviderPresetCatalog
import io.github.ethanbird.senseime.speech.SpeechProviderSettingsStore
import io.github.ethanbird.senseime.speech.SpeechRecognitionEvent
import io.github.ethanbird.senseime.speech.SpeechRecognitionFailure
import io.github.ethanbird.senseime.speech.SpeechRecognitionFailureKind
import io.github.ethanbird.senseime.speech.SpeechRecognitionPhase
import io.github.ethanbird.senseime.speech.SpeechRecognitionReducer
import io.github.ethanbird.senseime.speech.SpeechRecognitionState
import io.github.ethanbird.senseime.speech.SpeechSessionIdSequence
import io.github.ethanbird.senseime.ui.KeyCodes
import io.github.ethanbird.senseime.ui.KeyboardLayoutContract
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import io.github.ethanbird.senseime.ui.VoiceSurfacePhase
import io.github.ethanbird.senseime.ui.VoiceSurfaceState
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
    private var editorSelectionState = EditorSelectionState()
    private var selectionStart = -1
    private var selectionEnd = -1
    private var learningAllowed = true
    private var clipboardHistoryAllowed = false
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var aiCoordinator: SenseAiEditorCoordinator
    private lateinit var speechController: AndroidSpeechRecognizerController
    private lateinit var cloudSpeechController: CloudSpeechRecognitionController
    private lateinit var speechSettingsStore: SpeechProviderSettingsStore
    private val voiceSessionIds = SpeechSessionIdSequence()
    private var cloudSpeechState = SpeechRecognitionState()
    private var activeVoiceSession: ActiveVoiceSession? = null
    private var voiceLaunchGeneration = 0L
    private var currentEditorInfo: EditorInfo? = null
    private var editorGeneration = 0L
    private var editorSessionId = 0L
    private var editorFieldIdentity = "editor-0"
    private var aiApplicationToken: Long? = null
    private val clipboardHistory = ArrayDeque<String>()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboardHistoryAllowed) capturePrimaryClipboard()
        publishEditorSelectionState()
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
        aiCoordinator = SenseAiEditorCoordinator(
            context = this,
            connection = { currentInputConnection },
            editorInfo = { currentEditorInfo },
            editorSelection = {
                if (selectionStart < 0 || selectionEnd < 0) {
                    null
                } else {
                    TextSelectionV1(
                        minOf(selectionStart, selectionEnd),
                        maxOf(selectionStart, selectionEnd),
                    )
                }
            },
            editorGeneration = { editorGeneration },
            fieldIdentity = { currentAiFieldIdentity() },
            pointerStillDown = { generation ->
                keyboardView?.let { view ->
                    view.isAiSurfaceActive() && view.activeAiGeneration() == generation
                } == true
            },
            onSurfaceUpdate = { generation, phase, preview, status ->
                keyboardView?.updateAiSurface(generation, phase, preview, status)
            },
            onOwnApplyWindow = { token, active ->
                aiApplicationToken = if (active) token else null
            },
        )
        speechSettingsStore = SpeechProviderSettingsStore(this)
        speechController = AndroidSpeechRecognizerController(
            context = this,
            listener = ::handleSystemSpeechRecognitionState,
        )
        cloudSpeechController = CloudSpeechRecognitionController(
            callbackExecutor = java.util.concurrent.Executor { command ->
                mainHandler.post(command)
            },
        )
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
        view.aiHoldListener = object : SenseKeyboardView.AiHoldListener {
            override fun onAiHoldStarted(generation: Long) {
                beginAiHold(generation)
            }

            override fun onAiHoldCancelled(generation: Long) {
                aiCoordinator.cancel(generation, HarnessCancelReason.POINTER_RELEASED)
            }

            override fun onAiStopRequested(generation: Long) {
                aiCoordinator.cancel(generation, HarnessCancelReason.CALLER_REQUESTED)
            }
        }
        view.setChineseMode(chineseMode)
        view.setEditorSelectionState(
            hasSelection = editorSelectionState.hasSelection,
            selectionMode = editorSelectionState.selectionMode,
            canPaste = clipboardManager.hasPrimaryClip(),
        )
        keyboardView = view
        render()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        cancelVoiceSession(exitSurface = true)
        invalidateAiForEditorChange(EditorStaleReason.START_INPUT)
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        editorGeneration = nextGeneration(editorGeneration)
        editorSessionId = nextGeneration(editorSessionId)
        editorFieldIdentity = "editor-$editorSessionId"
        val persistenceAllowed = allowsLocalPersistence(attribute)
        learningAllowed = persistenceAllowed
        clipboardHistoryAllowed = persistenceAllowed
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        editorSelectionState = EditorSelectionState(
            hasSelection = hasHostSelection(selectionStart, selectionEnd),
        )
        publishEditorSelectionState()
        resetComposition(finishConnection = true)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        if (clipboardHistoryAllowed) {
            capturePrimaryClipboard()
        } else {
            keyboardView?.updateClipboard(emptyList())
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        cancelVoiceSession(exitSurface = true)
        super.onStartInputView(info, restarting)
        keyboardView?.setChineseMode(chineseMode)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    override fun onFinishInput() {
        cancelVoiceSession(exitSurface = true)
        invalidateAiForEditorChange(EditorStaleReason.FINISH_INPUT)
        clipboardHistoryAllowed = false
        resetComposition(finishConnection = true)
        currentEditorInfo = null
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
        val selectionChanged = selectionStart != newSelStart || selectionEnd != newSelEnd
        if (selectionChanged) {
            val ownToken = aiApplicationToken
            val normalizedSelection = if (newSelStart < 0 || newSelEnd < 0) {
                null
            } else {
                TextSelectionV1(
                    minOf(newSelStart, newSelEnd),
                    maxOf(newSelStart, newSelEnd),
                )
            }
            if (aiCoordinator.markSelectionChanged(normalizedSelection, ownToken)) {
                editorGeneration = nextGeneration(editorGeneration)
            }
            if (activeVoiceSession != null) {
                cancelVoiceSession(exitSurface = true)
            }
        }
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
        val nextSelectionState = editorSelectionState.withHostSelection(
            hasHostSelection(newSelStart, newSelEnd),
        )
        if (nextSelectionState != editorSelectionState) {
            editorSelectionState = nextSelectionState
            publishEditorSelectionState()
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
        val voiceSession = activeVoiceSession
        activeVoiceSession = null
        cancelVoiceBackend(voiceSession)
        if (::speechController.isInitialized) speechController.destroy()
        if (::cloudSpeechController.isInitialized) cloudSpeechController.close()
        if (::aiCoordinator.isInitialized) aiCoordinator.close()
        aiApplicationToken = null
        keyboardView = null
        super.onDestroy()
    }

    override fun onWindowHidden() {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.WINDOW_HIDDEN)
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.CONFIGURATION_CHANGED)
        super.onConfigurationChanged(newConfig)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.WINDOW_HIDDEN)
        super.onFinishInputView(finishingInput)
    }

    private fun beginAiHold(generation: Long) {
        val view = keyboardView ?: return
        if (!view.isAiSurfaceActive() || view.activeAiGeneration() != generation) return
        val compositionSettled = when {
            !chineseMode && englishInput.composing.isNotEmpty() ->
                commitEnglishComposition(englishInput.defaultCommitCandidate)
            chineseMode && composition.visibleText.isNotEmpty() -> {
                val decoding = currentDecoding()
                if (decoding != null) {
                    commitPrimary(decoding.wholeCandidates.firstOrNull())
                } else {
                    // Long-press has already given the decoder 380 ms. A stalled
                    // worker must not make AI activation hang indefinitely.
                    commitRawComposition()
                }
            }
            else -> true
        }
        if (!compositionSettled) {
            view.updateAiSurface(
                generation,
                io.github.ethanbird.senseime.ui.AiSurfacePhase.ERROR,
                "",
                "无法确认当前输入，松开空格后重试",
            )
            return
        }
        // A composing commit can be acknowledged by the host on a later main-loop turn.
        // Capture only after that turn, and re-check that this exact hold still owns the surface.
        mainHandler.post {
            if (
                !destroyed &&
                view === keyboardView &&
                view.isAiSurfaceActive() &&
                view.activeAiGeneration() == generation
            ) {
                aiCoordinator.start(generation)
            }
        }
    }

    private fun invalidateAiForEditorChange(reason: EditorStaleReason) {
        if (!::aiCoordinator.isInitialized) return
        aiCoordinator.markEditorChanged(reason)
        keyboardView?.activeAiGeneration()?.let(keyboardView!!::exitAiSurface)
    }

    private fun cancelAndExitAi(reason: HarnessCancelReason) {
        val view = keyboardView ?: return
        val generation = view.activeAiGeneration() ?: return
        aiCoordinator.cancel(generation, reason)
        view.exitAiSurface(generation)
    }

    private fun nextGeneration(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L

    private fun currentAiFieldIdentity(): String {
        val connectionIdentity = currentInputConnection?.let(System::identityHashCode) ?: 0
        return "$editorFieldIdentity:$connectionIdentity"
    }

    private fun handleKey(code: Int) {
        when (code) {
            KeyCodes.VOICE -> {
                openVoiceInput()
                return
            }

            KeyCodes.VOICE_DONE -> {
                stopVoiceRecognition()
                return
            }

            KeyCodes.VOICE_CANCEL -> {
                cancelVoiceSession(exitSurface = true)
                return
            }

            KeyCodes.VOICE_RETRY -> {
                cancelVoiceSession(exitSurface = true)
                openVoiceInput()
                return
            }
        }
        if (activeVoiceSession != null || keyboardView?.isVoiceSurfaceActive() == true) {
            cancelVoiceSession(exitSurface = true)
        }
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
        editorSelectionState = EditorSelectionState(
            hasSelection = hasHostSelection(selectionStart, selectionEnd),
        )
        publishEditorSelectionState()
        keyboardView?.setPanel(SenseKeyboardView.Panel.EDITOR)
    }

    private fun handleEditorAction(action: SenseKeyboardView.EditorAction) {
        when (action) {
            SenseKeyboardView.EditorAction.BACK -> {
                editorSelectionState = editorSelectionState.resetSelectionMode()
                publishEditorSelectionState()
                keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
            }

            SenseKeyboardView.EditorAction.TOGGLE_SELECTION -> {
                editorSelectionState = editorSelectionState.toggleSelectionMode()
                publishEditorSelectionState()
            }

            SenseKeyboardView.EditorAction.UP -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_UP)
            SenseKeyboardView.EditorAction.LEFT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_LEFT)
            SenseKeyboardView.EditorAction.RIGHT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            SenseKeyboardView.EditorAction.DOWN -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_DOWN)
            SenseKeyboardView.EditorAction.HOME -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_HOME)
            SenseKeyboardView.EditorAction.END -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_END)
            SenseKeyboardView.EditorAction.DELETE -> deleteOneCodePointOrSelection()
            SenseKeyboardView.EditorAction.COPY ->
                performEditorContextCommand(EditorContextCommand.COPY, android.R.id.copy)

            SenseKeyboardView.EditorAction.CUT ->
                performEditorContextCommand(EditorContextCommand.CUT, android.R.id.cut)

            SenseKeyboardView.EditorAction.PASTE ->
                performEditorContextCommand(EditorContextCommand.PASTE, android.R.id.paste)

            SenseKeyboardView.EditorAction.SELECT_ALL -> {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
        }
    }

    private fun sendDirectionalKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val metaState = if (editorSelectionState.selectionMode) KeyEvent.META_SHIFT_ON else 0
        currentInputConnection?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
        )
        currentInputConnection?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState),
        )
    }

    private fun deleteOneCodePointOrSelection(): Boolean {
        val connection = currentInputConnection ?: return false
        if (hasHostSelection(selectionStart, selectionEnd)) {
            if (sendDeleteKeyEvents(connection)) return true
            // commitText replaces an active selection and is the most broadly
            // implemented fallback for editors that reject hardware key events.
            return connection.commitText("", 1)
        }
        if (connection.deleteSurroundingTextInCodePoints(1, 0)) return true
        return sendDeleteKeyEvents(connection)
    }

    private fun sendDeleteKeyEvents(connection: InputConnection): Boolean {
        val now = SystemClock.uptimeMillis()
        val downAccepted = connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0),
        )
        val upAccepted = connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0),
        )
        return downAccepted && upAccepted
    }

    private fun performEditorContextCommand(command: EditorContextCommand, actionId: Int) {
        val accepted = currentInputConnection?.performContextMenuAction(actionId) == true
        val outcome = EditorContextActionPolicy.resolve(command, accepted)
        if (outcome.resetSelectionMode) {
            editorSelectionState = editorSelectionState.resetSelectionMode()
            publishEditorSelectionState()
        }
        when (outcome.feedback) {
            EditorFeedback.COPIED -> Toast.makeText(
                this,
                R.string.editor_copied,
                Toast.LENGTH_SHORT,
            ).show()

            EditorFeedback.CUT -> Toast.makeText(
                this,
                R.string.editor_cut,
                Toast.LENGTH_SHORT,
            ).show()

            null -> Unit
        }
        if (outcome.leaveEditor) {
            keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        }
    }

    private fun publishEditorSelectionState() {
        keyboardView?.setEditorSelectionState(
            hasSelection = editorSelectionState.hasSelection,
            selectionMode = editorSelectionState.selectionMode,
            canPaste = ::clipboardManager.isInitialized && clipboardManager.hasPrimaryClip(),
        )
    }

    private fun hasHostSelection(start: Int, end: Int): Boolean =
        start >= 0 && end >= 0 && start != end

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
        if (!::speechController.isInitialized || currentInputConnection == null) return
        cancelVoiceSession(exitSurface = true)
        commitActiveRawComposition()
        val launchGeneration = nextGeneration(voiceLaunchGeneration)
        voiceLaunchGeneration = launchGeneration
        // As with AI snapshots, let a composing-text commit reach the host before binding the
        // speech session to editor generation/connection identity.
        mainHandler.post {
            if (
                !destroyed &&
                voiceLaunchGeneration == launchGeneration &&
                activeVoiceSession == null &&
                currentInputConnection != null
            ) {
                startVoiceInputNow()
            }
        }
    }

    private fun startVoiceInputNow() {
        val connection = currentInputConnection ?: return
        val storedResult = speechSettingsStore.load()
        val settingsReadFailed = storedResult.isFailure
        val stored = storedResult.getOrNull()
        val profile = stored?.profile
            ?: SpeechProviderPresetCatalog
                .require(SpeechProviderPresetCatalog.SYSTEM)
                .defaultProfile(if (chineseMode) "zh-CN" else "en-US")
        val preset = SpeechProviderPresetCatalog.find(profile.presetId)
        val providerName = if (settingsReadFailed) {
            "语音配置"
        } else {
            preset?.displayName ?: profile.displayName
        }
        val selection = ConfiguredSpeechProvider(
            profile = profile,
            apiKey = stored?.apiKey?.toCharArray(),
            displayName = providerName,
        )
        val backend = VoiceRecognitionBackend.forProfile(profile)
        val sessionId = voiceSessionIds.next()
        val session = ActiveVoiceSession(
            id = sessionId,
            editorGeneration = editorGeneration,
            connectionIdentity = System.identityHashCode(connection),
            providerName = selection.displayName,
            backend = backend,
        )
        activeVoiceSession = session
        keyboardView?.showVoiceSurface(
            VoiceSurfaceState(
                sessionId = sessionId,
                revision = initialVoiceRevision(backend),
                phase = VoiceSurfacePhase.STARTING,
                providerName = selection.displayName,
                statusText = "正在准备语音识别",
            ),
        )
        if (settingsReadFailed) {
            selection.eraseCredential()
            publishVoiceStartFailure(
                session = session,
                status = "语音配置无法读取，请到设置重新保存",
            )
            return
        }
        val safeFailureStatus = selection.safeStartFailureStatus()
        val started = try {
            startConfiguredSpeechRecognition(
                sessionId = sessionId,
                selection = selection,
                backend = backend,
            )
        } finally {
            selection.eraseCredential()
        }
        if (started.isFailure) {
            publishVoiceStartFailure(session, safeFailureStatus)
        }
    }

    /**
     * Sole dispatch point for speech provider execution. The credential stays inside the service
     * process and is never copied into VoiceSurfaceState, UI text, Toasts, or logs.
     */
    private fun startConfiguredSpeechRecognition(
        sessionId: Long,
        selection: ConfiguredSpeechProvider,
        backend: VoiceRecognitionBackend,
    ): Result<Unit> = when (backend) {
        VoiceRecognitionBackend.SYSTEM -> runCatching {
            speechController.start(sessionId, selection.profile)
            Unit
        }

        VoiceRecognitionBackend.CLOUD -> {
            val credential = selection.consumeCredential() ?: CharArray(0)
            try {
                cloudSpeechController.start(
                    sessionId = sessionId,
                    profile = selection.profile,
                    apiKey = credential,
                    listener = CloudSpeechRecognitionListener(
                        ::handleCloudSpeechRecognitionEvent,
                    ),
                )
            } finally {
                credential.fill('\u0000')
            }
        }
    }

    private fun initialVoiceRevision(backend: VoiceRecognitionBackend): Long = when (backend) {
        VoiceRecognitionBackend.SYSTEM -> speechController.state.revision
        VoiceRecognitionBackend.CLOUD -> cloudSpeechState.revision
    }

    private fun publishVoiceStartFailure(
        session: ActiveVoiceSession,
        status: String,
    ) {
        val started = SpeechRecognitionEvent.Started(
            sessionId = session.id,
            usingOnDeviceRecognizer = false,
        )
        val failed = SpeechRecognitionEvent.Failed(
            sessionId = session.id,
            failure = SpeechRecognitionFailure(
                kind = SpeechRecognitionFailureKind.CLIENT,
                message = status,
            ),
        )
        when (session.backend) {
            VoiceRecognitionBackend.CLOUD -> {
                handleCloudSpeechRecognitionEvent(started)
                handleCloudSpeechRecognitionEvent(failed)
            }
            VoiceRecognitionBackend.SYSTEM -> {
                val starting = SpeechRecognitionReducer.reduce(speechController.state, started)
                val terminal = SpeechRecognitionReducer.reduce(starting, failed)
                handleSpeechRecognitionState(terminal, VoiceRecognitionBackend.SYSTEM)
            }
        }
    }

    private fun handleSystemSpeechRecognitionState(state: SpeechRecognitionState) {
        handleSpeechRecognitionState(state, VoiceRecognitionBackend.SYSTEM)
    }

    private fun handleCloudSpeechRecognitionEvent(event: SpeechRecognitionEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleCloudSpeechRecognitionEvent(event) }
            return
        }
        val session = activeVoiceSession ?: return
        if (
            destroyed ||
            session.backend != VoiceRecognitionBackend.CLOUD ||
            event is SpeechRecognitionEvent.Destroyed ||
            event.sessionId != session.id
        ) {
            return
        }
        val next = SpeechRecognitionReducer.reduce(cloudSpeechState, event)
        if (next === cloudSpeechState || next == cloudSpeechState) return
        cloudSpeechState = next
        handleSpeechRecognitionState(next, VoiceRecognitionBackend.CLOUD)
    }

    private fun handleSpeechRecognitionState(
        state: SpeechRecognitionState,
        backend: VoiceRecognitionBackend,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleSpeechRecognitionState(state, backend) }
            return
        }
        val session = activeVoiceSession ?: return
        if (
            state.sessionId != session.id ||
            session.backend != backend ||
            destroyed
        ) {
            return
        }
        val connection = currentInputConnection
        if (
            connection == null ||
            editorGeneration != session.editorGeneration ||
            System.identityHashCode(connection) != session.connectionIdentity
        ) {
            cancelVoiceSession(exitSurface = true)
            return
        }

        when (state.phase) {
            SpeechRecognitionPhase.IDLE -> Unit
            SpeechRecognitionPhase.STARTING -> publishVoiceState(
                session = session,
                state = state,
                phase = VoiceSurfacePhase.STARTING,
                status = when {
                    state.usingOnDeviceRecognizer -> "正在启动设备端识别"
                    backend == VoiceRecognitionBackend.CLOUD ->
                        "正在连接${session.providerName}"
                    else -> "正在连接系统语音识别"
                },
            )

            SpeechRecognitionPhase.LISTENING -> publishVoiceState(
                session = session,
                state = state,
                phase = VoiceSurfacePhase.LISTENING,
                status = when {
                    state.usingOnDeviceRecognizer -> "正在聆听 · 设备端"
                    backend == VoiceRecognitionBackend.CLOUD ->
                        "正在聆听 · ${session.providerName}"
                    else -> "正在聆听"
                },
            )

            SpeechRecognitionPhase.PROCESSING -> publishVoiceState(
                session = session,
                state = state,
                phase = VoiceSurfacePhase.PROCESSING,
                status = "正在整理识别结果",
            )

            SpeechRecognitionPhase.ERROR -> publishVoiceState(
                session = session,
                state = state,
                phase = VoiceSurfacePhase.ERROR,
                status = state.failure?.message ?: "语音识别失败",
            )

            SpeechRecognitionPhase.COMPLETED -> completeVoiceInput(session, state)
            SpeechRecognitionPhase.CANCELLED,
            SpeechRecognitionPhase.DESTROYED,
            -> {
                activeVoiceSession = null
                keyboardView?.exitVoiceSurface(session.id)
            }
        }
    }

    private fun stopVoiceRecognition() {
        val session = activeVoiceSession ?: return
        when (session.backend) {
            VoiceRecognitionBackend.SYSTEM -> speechController.stop()
            VoiceRecognitionBackend.CLOUD -> cloudSpeechController.stop(session.id)
        }
    }

    private fun publishVoiceState(
        session: ActiveVoiceSession,
        state: SpeechRecognitionState,
        phase: VoiceSurfacePhase,
        status: String,
    ) {
        keyboardView?.updateVoiceSurface(
            VoiceSurfaceState(
                sessionId = session.id,
                revision = state.revision,
                phase = phase,
                providerName = session.providerName,
                visibleText = state.visibleText.take(MAX_VOICE_PREVIEW_CHARS),
                statusText = status,
                waveformLevel = state.waveformLevel,
                usingOnDeviceRecognizer = state.usingOnDeviceRecognizer,
            ),
        )
    }

    private fun completeVoiceInput(
        session: ActiveVoiceSession,
        state: SpeechRecognitionState,
    ) {
        val text = state.finalText.orEmpty()
        if (text.isBlank()) {
            publishVoiceState(
                session = session,
                state = state.copy(revision = state.revision + 1L),
                phase = VoiceSurfacePhase.ERROR,
                status = "没有识别到可输入的文字",
            )
            return
        }
        val committed = currentInputConnection?.commitText(text, 1) == true
        if (committed) {
            activeVoiceSession = null
            keyboardView?.exitVoiceSurface(session.id)
        } else {
            publishVoiceState(
                session = session,
                state = state.copy(revision = state.revision + 1L),
                phase = VoiceSurfacePhase.ERROR,
                status = "输入框拒绝写入，请重试",
            )
        }
    }

    private fun cancelVoiceSession(exitSurface: Boolean) {
        voiceLaunchGeneration = nextGeneration(voiceLaunchGeneration)
        val sessionId = activeVoiceSession?.id ?: keyboardView?.activeVoiceSessionId()
        val session = activeVoiceSession
        activeVoiceSession = null
        cancelVoiceBackend(session)
        if (exitSurface && sessionId != null) {
            keyboardView?.exitVoiceSurface(sessionId)
        }
    }

    private fun cancelVoiceBackend(session: ActiveVoiceSession?) {
        when (session?.backend) {
            VoiceRecognitionBackend.SYSTEM ->
                if (::speechController.isInitialized) speechController.cancel()
            VoiceRecognitionBackend.CLOUD ->
                if (::cloudSpeechController.isInitialized) {
                    cloudSpeechController.cancel(session.id)
                }
            null -> Unit
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
        const val MAX_VOICE_PREVIEW_CHARS = 1024
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

private data class ActiveVoiceSession(
    val id: Long,
    val editorGeneration: Long,
    val connectionIdentity: Int,
    val providerName: String,
    val backend: VoiceRecognitionBackend,
)

private class ConfiguredSpeechProvider(
    val profile: SpeechProviderProfile,
    apiKey: CharArray?,
    val displayName: String,
) {
    private var credential: CharArray? = apiKey
    private val hadCredential = apiKey?.isNotEmpty() == true

    fun consumeCredential(): CharArray? =
        credential.also { credential = null }

    fun eraseCredential() {
        credential?.fill('\u0000')
        credential = null
    }

    fun safeStartFailureStatus(): String {
        val preset = SpeechProviderPresetCatalog.find(profile.presetId)
        return when {
            preset?.canTranscribe == false ->
                preset.capabilityNotice ?: "当前语音提供商尚未启用"
            profile.protocol != SpeechProviderProtocol.ANDROID_SYSTEM && !hadCredential ->
                "请先在设置中配置语音 API Key"
            else -> "无法启动${displayName}，请检查语音配置后重试"
        }
    }
}

private enum class VoiceRecognitionBackend {
    SYSTEM,
    CLOUD,
    ;

    companion object {
        fun forProfile(profile: SpeechProviderProfile): VoiceRecognitionBackend =
            if (profile.protocol == SpeechProviderProtocol.ANDROID_SYSTEM) SYSTEM else CLOUD
    }
}
