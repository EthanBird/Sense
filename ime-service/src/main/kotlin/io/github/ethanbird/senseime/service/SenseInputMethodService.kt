package io.github.ethanbird.senseime.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore
import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesStore
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.AdaptiveWubi86Decoder
import io.github.ethanbird.senseime.core.BinaryCharacterBigramModel
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.CharacterBigramModel
import io.github.ethanbird.senseime.core.EnglishLexicon
import io.github.ethanbird.senseime.core.EnglishInputSession
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.MemoryWubiUserLexicon
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.SemanticCandidateCatalog
import io.github.ethanbird.senseime.core.SemanticCandidateMixer
import io.github.ethanbird.senseime.core.T9SyllableIndex
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserLexicon
import io.github.ethanbird.senseime.core.UserNegativeFeedback
import io.github.ethanbird.senseime.core.UserSelectionKind
import io.github.ethanbird.senseime.core.Wubi86Decoder
import io.github.ethanbird.senseime.core.Wubi86Lexicon
import io.github.ethanbird.senseime.core.WubiLearningEvidence
import io.github.ethanbird.senseime.core.WubiNegativeFeedback
import io.github.ethanbird.senseime.core.WubiSelectionKind
import io.github.ethanbird.senseime.core.WubiUserLexicon
import io.github.ethanbird.senseime.core.editorComposingTextUpdate
import io.github.ethanbird.senseime.service.ai.SenseAiEditorCoordinator
import io.github.ethanbird.senseime.service.ai.AgentSkillRunSnapshot
import io.github.ethanbird.senseime.service.ai.SelectedAgentSkill
import io.github.ethanbird.senseime.service.ai.editor.EditorStaleReason
import io.github.ethanbird.senseime.speech.AndroidSpeechRecognizerController
import io.github.ethanbird.senseime.speech.CloudSpeechRecognitionController
import io.github.ethanbird.senseime.speech.CloudSpeechRecognitionListener
import io.github.ethanbird.senseime.speech.SpeechProviderCredentialRequirement
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
import io.github.ethanbird.senseime.ui.ActiveKeyboardSkill
import io.github.ethanbird.senseime.ui.KeyboardSkillBinding
import io.github.ethanbird.senseime.ui.KeyboardSkillDirection
import io.github.ethanbird.senseime.ui.KeyboardSkillSelection
import io.github.ethanbird.senseime.ui.KeyboardSkillSelectionListener
import io.github.ethanbird.senseime.ui.KeyboardSkillToggleAction
import io.github.ethanbird.senseime.ui.KeyboardLayoutContract
import io.github.ethanbird.senseime.ui.PrimaryKeyboardMode
import io.github.ethanbird.senseime.ui.PrimaryKeyboardLegendMode
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import io.github.ethanbird.senseime.ui.SenseKeyboardSurface
import io.github.ethanbird.senseime.ui.VoiceSurfacePhase
import io.github.ethanbird.senseime.ui.VoiceSurfaceState
import java.util.ArrayDeque
import java.util.concurrent.Executors
import org.json.JSONArray

class SenseInputMethodService : InputMethodService() {
    @Volatile
    private var decoderRuntime = CandidateDecoderRuntime(
        generation = 0L,
        decoder = FakeDecoder(),
        segmenter = PinyinSyllableSegmenter(FALLBACK_SYLLABLES),
        t9Index = T9SyllableIndex(FALLBACK_SYLLABLES),
    )
    @Volatile
    private var wubiRuntime = WubiDecoderRuntime(0L, null, null, null)
    private var adaptiveDecoder: AdaptivePinyinDecoder? = null
    private var userLexicon: UserLexicon? = null
    private val candidateSession = CandidateDecodeSession()
    private var composition = PinyinComposition()
    private var compositionLeftContext = ""
    private val inputScheme = ChineseInputSchemeCoordinator()
    private lateinit var imePreferencesStore: ImePreferencesStore
    private var preferencesLoadGeneration = 0L
    private var wubiLoadRequested = false
    private var englishInput = EnglishInputSession(EnglishLexicon.EMPTY)
    private var shifted = false
    private var chineseMode = true
    private var keyboardView: SenseKeyboardView? = null
    private var keyboardSurface: SenseKeyboardSurface? = null
    private var imeWindowVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val candidateResultToken = Any()
    private val alternativeCandidateResultToken = Any()
    private var candidateRunner: LatestOnlyTaskRunner<CandidateDecodeRequest, ProgressivePinyinDecoding>? = null
    private var alternativeCandidateRunner:
        LatestOnlyTaskRunner<AlternativeDecodeRequest, AlternativeDecoding>? = null
    private val wubiOverflow = WubiOverflowCoordinator()
    private val pendingDecodeCommit =
        PendingDecodeCommitCoordinator<DeferredInput>(MAX_DEFERRED_INPUT_EVENTS)
    private val progressiveLearnings = ProgressiveLearningQueue()
    private val personalizationFeedback = PersonalizationFeedbackWindow(SystemClock::elapsedRealtime)
    private val englishCompositionEdits = EnglishCompositionEditController(
        object : EnglishCompositionEditHost {
            override val englishSession: EnglishInputSession
                get() = englishInput
            override val editorSessionIdentity: Long
                get() = editorSessionId
            override val inputConnectionIdentity: Any?
                get() = currentInputConnection

            override fun publishEnglishComposition(text: String): Boolean =
                updateConnectionComposition(text)
        },
    )
    @Volatile
    private var destroyed = false
    private var editorSelectionState = EditorSelectionState()
    private var selectionStart = -1
    private var selectionEnd = -1
    private var localPersistenceAllowed = false
    private var productionDecoderReady = false
    private var decodeContextAllowed = true
    private var clipboardHistoryAllowed = false
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var aiCoordinator: SenseAiEditorCoordinator
    private lateinit var agentSkillStore: AgentSkillStore
    private lateinit var agentSkillDirectoryWatcher: AgentSkillDirectoryWatcher
    private lateinit var agentSkillProjection: AgentSkillProjectionCoordinator<AgentSkillCatalog>
    private var agentSkillCatalog: AgentSkillCatalog? = null
    private var pendingAgentSkillFailureFeedback: String? = null
    private val agentSkillIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-skills").apply { isDaemon = true }
    }
    private val decoderIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-decoder-loader").apply { isDaemon = true }
    }
    private val decoderRuntimePublisher = BackgroundRuntimePublisher(
        backgroundExecutor = decoderIo,
        publicationExecutor = java.util.concurrent.Executor { command ->
            mainHandler.post(command)
        },
    )
    private val schemeIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-input-scheme").apply { isDaemon = true }
    }
    private val preferencesIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-ime-preferences").apply { isDaemon = true }
    }
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
        resolvePendingCommitTimeout()
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        val bootstrapLexicon = MemoryUserLexicon()
        userLexicon = bootstrapLexicon
        val bootstrapSegmenter = PinyinSyllableSegmenter(FALLBACK_SYLLABLES)
        adaptiveDecoder = AdaptivePinyinDecoder(
            base = FakeDecoder(),
            userLexicon = bootstrapLexicon,
            segmenter = bootstrapSegmenter,
        )
        decoderRuntime = CandidateDecoderRuntime(
            generation = nextGeneration(decoderRuntime.generation),
            decoder = requireNotNull(adaptiveDecoder),
            segmenter = bootstrapSegmenter,
            t9Index = T9SyllableIndex(FALLBACK_SYLLABLES),
        )
        englishInput = EnglishInputSession(EnglishLexicon.EMPTY, DECODE_CANDIDATE_LIMIT)
        candidateRunner = LatestOnlyTaskRunner(
            threadName = "sense-candidate-decoder",
            work = { request, _ ->
                val activeDecoder = request.decoder
                val decoding = (activeDecoder as? ProgressivePinyinDecoder)?.decodeProgressively(
                    composition = request.composition,
                    leftContext = request.leftContext,
                    limit = DECODE_CANDIDATE_LIMIT,
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
        alternativeCandidateRunner = LatestOnlyTaskRunner(
            threadName = "sense-alternative-candidate-decoder",
            work = { request, shouldContinue ->
                AlternativeInputDecoder.decodeWhileCurrent(request, shouldContinue)
            },
            deliver = { _, request, decoding ->
                postAlternativeDecodedCandidates(request, decoding)
            },
            fail = { _, request, _ ->
                postAlternativeDecodedCandidates(
                    request,
                    AlternativeDecoding(
                        key = request.key,
                        composingLabel = request.key.rawCode,
                        candidates = emptyList(),
                        candidateLabels = emptyList(),
                    ),
                )
            },
        )
        imePreferencesStore = ImePreferencesStore(this)
        loadProductionDecoderAsync()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        loadClipboardHistory()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        agentSkillStore = AgentSkillStore(this)
        agentSkillDirectoryWatcher = AgentSkillDirectoryWatcher(
            directory = filesDir.resolve(AgentSkillStore.DIRECTORY_NAME),
            ownerIsAlive = { !destroyed },
        )
        agentSkillProjection = AgentSkillProjectionCoordinator(
            backgroundExecutor = agentSkillIo,
            deliveryExecutor = java.util.concurrent.Executor { command ->
                mainHandler.post(command)
            },
            load = { agentSkillStore.loadCatalog() },
            registerWatcher = agentSkillDirectoryWatcher::register,
            unregisterWatcher = agentSkillDirectoryWatcher::unregister,
            publish = { catalog ->
                pendingAgentSkillFailureFeedback = null
                agentSkillCatalog = catalog
                keyboardView?.let { view ->
                    view.clearSkillFeedback()
                    publishAgentSkills(view, catalog)
                }
            },
            reportFailure = ::reportAgentSkillProjectionFailure,
        )
        agentSkillProjection.start()
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
            skillSnapshot = ::agentSkillSnapshotForRun,
            onSurfaceUpdate = { generation, phase, preview, status, activities ->
                keyboardView?.updateAiSurface(
                    generation,
                    phase,
                    preview,
                    status,
                    activities,
                )
            },
            onOwnApplyWindow = { token, active ->
                aiApplicationToken = if (active) token else null
            },
            onAgentRunTerminal = ::refreshAgentSkills,
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

    /**
     * Keeps the IME lifecycle callback independent of the 35 MB Frost scan and
     * SQLite migration. Every candidate request captures one immutable decoder
     * publication and its generation. Results from the bootstrap generation are
     * therefore rejected even if they cross the main-thread callback after the
     * production decoder has already been published.
     */
    private fun loadProductionDecoderAsync() {
        decoderRuntimePublisher.load(
            build = {
                val bigramModel = runCatching<CharacterBigramModel> {
                    assets.open(PINYIN_BIGRAM_ASSET).use(BinaryCharacterBigramModel::load)
                }.onFailure { error ->
                    Log.e(TAG, "Bigram model load failed", error)
                }.getOrElse { CharacterBigramModel.EMPTY }
                val baseDecoder = runCatching<InputDecoder> {
                    assets.open(PINYIN_ASSET).use { PinyinDecoder.load(it, bigramModel) }
                }.onFailure { error ->
                    Log.e(TAG, "Pinyin lexicon load failed", error)
                }.getOrElse { FakeDecoder() }
                val syllables = runCatching {
                    assets.open(PINYIN_SYLLABLES_ASSET)
                        .bufferedReader()
                        .useLines { lines -> lines.toSet() }
                }.onFailure { error ->
                    Log.e(TAG, "Pinyin syllable inventory load failed", error)
                }.getOrElse { FALLBACK_SYLLABLES }
                val englishLexicon = runCatching {
                    assets.open(ENGLISH_LEXICON_ASSET).use { EnglishLexicon.load(it) }
                }.onFailure { error ->
                    Log.e(TAG, "English lexicon load failed", error)
                }.getOrElse { EnglishLexicon.EMPTY }
                val learned = runCatching<UserLexicon> {
                    PersistentUserLexicon(this)
                }.onFailure { error ->
                    Log.e(TAG, "Persistent user lexicon migration/load failed", error)
                }.getOrElse { MemoryUserLexicon() }
                val segmenter = PinyinSyllableSegmenter(syllables)
                val loadedDecoder = AdaptivePinyinDecoder(
                    base = baseDecoder,
                    userLexicon = learned,
                    segmenter = segmenter,
                    englishLexicon = englishLexicon,
                )
                val t9Index = runCatching {
                    T9SyllableIndex(syllables)
                }.onFailure { error ->
                    Log.e(TAG, "T9 syllable index build failed", error)
                }.getOrElse {
                    T9SyllableIndex(FALLBACK_SYLLABLES)
                }
                LoadedCandidateDecoderRuntime(
                    runtime = CandidateDecoderRuntime(
                        generation = 0L,
                        decoder = loadedDecoder,
                        segmenter = segmenter,
                        t9Index = t9Index,
                    ),
                    adaptiveDecoder = loadedDecoder,
                    userLexicon = learned,
                    englishLexicon = englishLexicon,
                )
            },
            publish = publish@{ loaded ->
                val learned = loaded.userLexicon
                if (destroyed) {
                    learned.close()
                    return@publish
                }
                val previousLexicon = userLexicon
                userLexicon = learned
                adaptiveDecoder = loaded.adaptiveDecoder
                decoderRuntime = loaded.runtime.copy(
                    generation = nextGeneration(decoderRuntime.generation),
                )
                productionDecoderReady = true
                localPersistenceAllowed = allowsLocalPersistence(currentEditorInfo)

                val pendingEnglish = englishInput.composing
                englishInput = EnglishInputSession(loaded.englishLexicon, DECODE_CANDIDATE_LIMIT)
                pendingEnglish.forEach(englishInput::type)
                previousLexicon?.takeIf { it !== learned }?.close()
                render(forceDecode = chineseMode && hasActiveChineseComposition())
            },
        )
    }

    override fun onCreateInputView(): View = SenseKeyboardSurface(this).also { surface ->
        keyboardSurface = surface
        surface.setImeWindowVisible(imeWindowVisible)
        val view = surface.keyboardView
        surface.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            surface.preferredHeightPx(),
        )
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        view.textListener = ::commitText
        view.clipboardActionListener = ::handleClipboardAction
        view.editorActionListener = ::handleEditorAction
        view.settingsActionListener = ::openSenseHome
        view.skillSelectionListener = KeyboardSkillSelectionListener(::handleSkillSelection)
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
        view.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        view.setEditorSelectionState(
            hasSelection = editorSelectionState.hasSelection,
            selectionMode = editorSelectionState.selectionMode,
            canPaste = clipboardManager.hasPrimaryClip(),
        )
        keyboardView = view
        publishAgentSkills(view)
        pendingAgentSkillFailureFeedback?.let { message ->
            pendingAgentSkillFailureFeedback = null
            view.showSkillFeedback(message)
        }
        render()
    }

    private fun openSenseHome() {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.CALLER_REQUESTED)
        val launchIntent = Intent()
            .setClassName(packageName, SENSE_SETTINGS_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(launchIntent) }
            .onFailure {
                Toast.makeText(this, "无法打开 Sense 设置", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshAgentSkills() {
        if (!::agentSkillProjection.isInitialized) return
        agentSkillProjection.refresh()
    }

    private fun reconcileAgentSkillsAndWatcher() {
        if (!::agentSkillProjection.isInitialized) return
        agentSkillProjection.refreshAndRebuildWatcher()
    }

    private fun publishAgentSkills(
        view: SenseKeyboardView,
        catalog: AgentSkillCatalog? = agentSkillCatalog,
    ) {
        if (catalog == null) {
            view.updateKeyboardSkills(emptyList(), null)
            return
        }
        val bindings = catalog.bindings.mapNotNull { binding ->
            val definition = catalog.definition(binding.skillId) ?: return@mapNotNull null
            KeyboardSkillBinding(
                keyCode = binding.slot.keyCode,
                direction = binding.slot.direction.toKeyboardDirection(),
                skillId = definition.id,
                label = definition.name,
                description = definition.description,
            )
        }
        val active = catalog.active?.let { activation ->
            ActiveKeyboardSkill(
                skillId = activation.skillId,
                sourceKeyCode = activation.slot.keyCode,
                direction = activation.slot.direction.toKeyboardDirection(),
            )
        }
        view.updateKeyboardSkills(bindings, active)
    }

    private fun handleSkillSelection(selection: KeyboardSkillSelection) {
        val requestedSlot = AgentSkillSlot(
            keyCode = selection.binding.keyCode,
            direction = selection.binding.direction.toAgentDirection(),
        )
        val requestedSkillId = selection.binding.skillId
        agentSkillProjection.submit(requestToken = selection.requestToken) {
            agentSkillStore.applySelectionIntent(
                slot = requestedSlot,
                selectedSkillId = requestedSkillId,
                activate = selection.action == KeyboardSkillToggleAction.ACTIVATE,
            )
        }
    }

    private fun reportAgentSkillProjectionFailure(failure: AgentSkillProjectionFailure) {
        val message = AgentSkillProjectionFailureText.message(failure.operation)
        val view = keyboardView
        if (view == null) {
            pendingAgentSkillFailureFeedback = message
        } else {
            pendingAgentSkillFailureFeedback = null
            failure.requestToken?.let(view::rejectPendingSkillSelection)
            view.showSkillFeedback(message)
        }
    }

    /**
     * The Space hold path is deliberately disk-free. The exact immutable catalog projected on
     * screen is captured from memory, so the aurora and the Agent instructions cannot disagree.
     * Settings and Brain writes refresh this cache through the serial background lane.
     */
    private fun agentSkillSnapshotForRun(): AgentSkillRunSnapshot? {
        val catalog = agentSkillCatalog ?: return null
        val activeSkill = catalog.activeDefinition()?.let { definition ->
            SelectedAgentSkill(
                baseIntent = definition.baseIntent,
                instruction = ActiveSkillInstructionV1(
                    id = definition.id,
                    revision = definition.revision,
                    catalogGeneration = catalog.generation,
                    name = definition.name,
                    description = definition.description,
                    content = definition.content,
                ),
            )
        }
        return AgentSkillRunSnapshot(
            catalogGeneration = catalog.generation,
            activeSkill = activeSkill,
        )
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
        localPersistenceAllowed = persistenceAllowed
        decodeContextAllowed = allowsTransientDecodeContext(attribute)
        clipboardHistoryAllowed = persistenceAllowed
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        editorSelectionState = EditorSelectionState(
            hasSelection = hasHostSelection(selectionStart, selectionEnd),
        )
        publishEditorSelectionState()
        resetComposition(finishConnection = true)
        refreshImePreferencesAsync(editorSessionId)
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
        refreshImePreferencesAsync(editorSessionId)
        keyboardView?.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        reconcileAgentSkillsAndWatcher()
        render()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        imeWindowVisible = true
        keyboardSurface?.setImeWindowVisible(true)
        /*
         * onStartInputView owns the watcher epoch rebuild. Window visibility still reconciles a
         * potentially missed CURRENT event, but must not immediately stop/start the fresh watch.
         */
        refreshAgentSkills()
    }

    override fun onFinishInput() {
        cancelVoiceSession(exitSurface = true)
        invalidateAiForEditorChange(EditorStaleReason.FINISH_INPUT)
        // Invalidate commit/replay snapshots before finishComposingText can re-enter the host.
        // getCurrentInputConnection may still expose the binding-level connection after finish.
        editorSessionId = nextGeneration(editorSessionId)
        editorFieldIdentity = "editor-$editorSessionId"
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
            hasActiveChineseComposition()
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
        imeWindowVisible = false
        keyboardSurface?.setImeWindowVisible(false)
        pendingDecodeCommit.clearAll()
        wubiOverflow.clear()
        mainHandler.removeCallbacks(pendingCommitTimeout)
        mainHandler.removeCallbacksAndMessages(candidateResultToken)
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        candidateRunner?.close()
        candidateRunner = null
        alternativeCandidateRunner?.close()
        alternativeCandidateRunner = null
        decoderIo.shutdownNow()
        schemeIo.shutdownNow()
        preferencesIo.shutdownNow()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        userLexicon?.close()
        userLexicon = null
        wubiRuntime.userLexicon?.close()
        wubiRuntime = WubiDecoderRuntime(
            generation = nextGeneration(wubiRuntime.generation),
            decoder = null,
            candidateDecoder = null,
            userLexicon = null,
        )
        adaptiveDecoder = null
        productionDecoderReady = false
        localPersistenceAllowed = false
        val voiceSession = activeVoiceSession
        activeVoiceSession = null
        cancelVoiceBackend(voiceSession)
        if (::speechController.isInitialized) speechController.destroy()
        if (::cloudSpeechController.isInitialized) cloudSpeechController.close()
        if (::aiCoordinator.isInitialized) aiCoordinator.close()
        if (::agentSkillProjection.isInitialized) {
            agentSkillProjection.close()
        }
        /*
         * close() appends FileObserver teardown after every already-accepted read/mutation. A
         * graceful shutdown lets that FIFO drain without waiting on the IME main thread.
         */
        agentSkillIo.shutdown()
        aiApplicationToken = null
        keyboardView = null
        keyboardSurface = null
        super.onDestroy()
    }

    override fun onWindowHidden() {
        imeWindowVisible = false
        keyboardSurface?.setImeWindowVisible(false)
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
        if (deferIfDecodeCommitPending(DeferredInput.AiHold(generation))) return
        cancelVoiceForEditorInput()
        val compositionSettled = when {
            !chineseMode && englishInput.composing.isNotEmpty() ->
                commitEnglishComposition(englishInput.defaultCommitCandidate)
            chineseMode && hasActiveChineseComposition() -> commitActivePrimaryOrRaw()
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
        if (deferIfDecodeCommitPending(DeferredInput.Key(code))) return
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
        cancelVoiceForEditorInput()
        when (code) {
            KeyCodes.SHIFT -> {
                shifted = !shifted
                keyboardView?.setShifted(shifted)
            }

            KeyCodes.DELETE -> handleBackspace()
            KeyCodes.SPACE -> handleSpace()
            KeyCodes.COMMA -> commitText(if (chineseMode) "，" else ",")
            KeyCodes.PERIOD -> commitText(if (chineseMode) "。" else ".")
            KeyCodes.UNDO -> performEditorHistoryCommand(
                actionId = android.R.id.undo,
                fallbackKeyCode = KeyEvent.KEYCODE_Z,
                fallbackMetaState = KeyEvent.META_CTRL_ON,
                successMessage = "已撤销",
            )
            KeyCodes.REDO -> performEditorHistoryCommand(
                actionId = android.R.id.redo,
                fallbackKeyCode = KeyEvent.KEYCODE_Z,
                fallbackMetaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                secondaryFallbackKeyCode = KeyEvent.KEYCODE_Y,
                secondaryFallbackMetaState = KeyEvent.META_CTRL_ON,
                successMessage = "已重做",
            )
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
        val replacementFeedback = if (
            !hasAnyComposition()
        ) {
            personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
        } else {
            null
        }
        if (!chineseMode) {
            val output = if (shifted) character.uppercaseChar() else character
            if (output.lowercaseChar() in 'a'..'z') {
                if (!englishCompositionEdits.type(output)) return
                completeReplacementFeedback(replacementFeedback)
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

        when (inputScheme.scheme) {
            ChineseInputScheme.PINYIN_QWERTY ->
                handlePinyinCharacter(character, replacementFeedback)
            ChineseInputScheme.PINYIN_T9 ->
                handleT9Character(character, replacementFeedback)
            ChineseInputScheme.WUBI_86 ->
                handleWubiCharacter(character, replacementFeedback)
        }
    }

    private fun handlePinyinCharacter(
        character: Char,
        replacementFeedback: PersonalizationFeedbackWindow.Attempt?,
    ) {
        if (character.lowercaseChar() !in 'a'..'z') {
            commitText(character.toString())
            return
        }
        val previous = composition
        val next = previous.type(character)
        val nextLeftContext = if (previous.visibleText.isEmpty()) {
            captureDecodeLeftContext()
        } else {
            compositionLeftContext
        }
        if (!updateConnectionComposition(next.visibleText)) return
        if (composition != previous) return
        composition = next
        compositionLeftContext = nextLeftContext
        completeReplacementFeedback(replacementFeedback)
        render()
    }

    private fun handleT9Character(
        character: Char,
        replacementFeedback: PersonalizationFeedbackWindow.Attempt?,
    ) {
        when (
            inputScheme.type(
                character = character,
                captureLeftContext = ::captureDecodeLeftContext,
                publish = ::updateConnectionComposition,
            )
        ) {
            AlternativeEditResult.UNHANDLED -> commitText(character.toString())
            AlternativeEditResult.CHANGED -> {
                completeReplacementFeedback(replacementFeedback)
                render()
            }
            AlternativeEditResult.CONSUMED,
            AlternativeEditResult.REJECTED,
            -> Unit
        }
    }

    private fun handleWubiCharacter(
        character: Char,
        replacementFeedback: PersonalizationFeedbackWindow.Attempt?,
    ) {
        val normalized = character.lowercaseChar()
        if (normalized !in 'a'..'z') {
            commitText(character.toString())
            return
        }
        when (
            val overflow = wubiOverflow.onCharacter(
                composition = inputScheme.wubi,
                character = normalized,
                presentationRevision = activePresentationRevision(),
                mode = inputScheme.preferences.wubiAutoCommitMode,
                decoding = currentAlternativeDecoding(),
            )
        ) {
            WubiOverflowAction.Continue -> Unit
            WubiOverflowAction.Reject -> return
            is WubiOverflowAction.Commit -> {
                if (commitAlternativeCandidate(overflow.candidate)) {
                    // Committing is a settings-safe composition boundary. Route the fifth key
                    // again so a concurrently loaded scheme preference is also respected.
                    handleCharacter(normalized)
                }
                return
            }
            is WubiOverflowAction.Await -> {
                startPendingWubiOverflow(
                    presentationRevision = overflow.presentationRevision,
                    replayKeyCode = normalized.code,
                )
                if (alternativeCandidateRunner == null) {
                    val candidates = wubiRuntime.candidateDecoder
                        ?.decode(inputScheme.wubi.code, DECODE_CANDIDATE_LIMIT)
                        .orEmpty()
                    val resolution = wubiOverflow.onDecoded(
                        overflow.presentationRevision,
                        candidates,
                    )
                    when (resolution) {
                        WubiOverflowAction.Continue,
                        is WubiOverflowAction.Await,
                        -> error("Synchronous Wubi overflow resolution did not terminate")
                        WubiOverflowAction.Reject,
                        is WubiOverflowAction.Commit,
                        -> resolveWubiOverflowAction(
                            action = resolution,
                            presentationRevision = overflow.presentationRevision,
                        )
                    }
                }
                return
            }
        }
        when (
            inputScheme.type(
                character = normalized,
                captureLeftContext = ::captureDecodeLeftContext,
                publish = ::updateConnectionComposition,
            )
        ) {
            AlternativeEditResult.UNHANDLED -> commitText(character.toString())
            AlternativeEditResult.CHANGED -> {
                completeReplacementFeedback(replacementFeedback)
                render()
            }
            AlternativeEditResult.CONSUMED,
            AlternativeEditResult.REJECTED,
            -> Unit
        }
    }

    private fun handleBackspace() {
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            if (englishCompositionEdits.backspace()) render()
        } else if (chineseMode && hasAlternativeComposition()) {
            handleAlternativeBackspace()
        } else if (chineseMode && hasActiveChineseComposition()) {
            val rollsBackProgressiveSelection =
                composition.remainingPinyin.isEmpty() && composition.acceptedSegments.isNotEmpty()
            val previous = composition
            val next = previous.backspace()
            if (!updateConnectionComposition(next.visibleText)) return
            if (composition != previous) return
            composition = next
            if (rollsBackProgressiveSelection) {
                progressiveLearnings.rollbackLast()
            }
            render()
        } else {
            deleteOneCodePointOrSelection()
        }
    }

    private fun handleAlternativeBackspace() {
        if (inputScheme.backspace(::updateConnectionComposition)) render()
    }

    private fun handleSpace() {
        if (!chineseMode) {
            if (englishInput.composing.isNotEmpty()) {
                if (!commitEnglishComposition(englishInput.defaultCommitCandidate)) return
                currentInputConnection?.commitText(" ", 1)
            } else {
                val feedback =
                    personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
                if (currentInputConnection?.commitText(" ", 1) == true) {
                    completeReplacementFeedback(feedback)
                }
            }
            return
        }
        if (inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY) {
            handleAlternativeSpace()
            return
        }
        if (composition.visibleText.isEmpty()) {
            val feedback =
                personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
            if (currentInputConnection?.commitText(" ", 1) == true) {
                completeReplacementFeedback(feedback)
            }
            return
        }
        if (composition.remainingPinyin.isEmpty()) {
            commitActiveRawComposition()
            return
        }
        val decoding = currentDecoding()
        if (decoding == null) {
            startPendingCommit(composition.revision)
            if (candidateRunner == null) {
                val completion = finishPendingCandidateCommit(composition.revision)
                commitAndReplayPending(completion, ::commitRawComposition)
            }
            return
        }
        commitPrimary(decoding.wholeCandidates.firstOrNull())
    }

    private fun handleAlternativeSpace() {
        if (!hasAlternativeComposition()) {
            val feedback =
                personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
            if (currentInputConnection?.commitText(" ", 1) == true) {
                completeReplacementFeedback(feedback)
            }
            return
        }
        val decoding = currentAlternativeDecoding()
        if (decoding == null) {
            startPendingCommit(activePresentationRevision())
            if (alternativeCandidateRunner == null) {
                val completion = finishPendingCandidateCommit(activePresentationRevision())
                commitAndReplayPending(completion, ::commitAlternativeRawComposition)
            }
            return
        }
        commitAlternativeCandidate(decoding.candidates.firstOrNull())
    }

    private fun handleEnter() {
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            commitEnglishComposition(candidate = null)
        } else if (chineseMode && hasActiveChineseComposition()) {
            // Enter confirms exactly what the user can see. It never auto-selects
            // a Chinese candidate and does not append a newline in this branch.
            commitActiveRawComposition()
        } else {
            val feedback =
                personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
            if (sendDefaultEditorAction(true)) {
                personalizationFeedback.complete(feedback)
            } else if (currentInputConnection?.commitText("\n", 1) == true) {
                completeReplacementFeedback(feedback)
            }
        }
    }

    private fun commitCandidate(revision: Long, sourceIndex: Int) {
        if (!chineseMode) {
            englishInput.select(revision, sourceIndex)?.let(::commitEnglishComposition)
            return
        }
        if (inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY) {
            if (!hasAlternativeComposition()) return
            val candidate = inputScheme.select(revision, sourceIndex) ?: return
            commitAlternativeCandidate(
                candidate,
                UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, sourceIndex.coerceAtLeast(0)),
            )
            return
        }
        if (composition.visibleText.isEmpty()) return
        when (val choice = candidateSession.select(composition, revision, sourceIndex)) {
            is ProgressiveCandidateChoice.Whole -> {
                val rank = currentDecoding()
                    ?.wholeCandidates
                    ?.indexOfFirst { it == choice.candidate }
                    ?.takeIf { it >= 0 }
                    ?: sourceIndex.coerceAtLeast(0)
                commitPrimary(
                    choice.candidate,
                    UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION, rank),
                )
            }
            is ProgressiveCandidateChoice.Prefix -> {
                val rank = currentDecoding()
                    ?.prefixCandidates
                    ?.indexOfFirst { it == choice.value }
                    ?.takeIf { it >= 0 }
                    ?: sourceIndex.coerceAtLeast(0)
                val next = composition.acceptPrefix(revision, choice.value)
                if (next == composition) return
                val previous = composition
                val learning = ProgressiveLearning(
                    rawInput = choice.value.consumedPinyin,
                    candidate = choice.candidate,
                    evidence = UserLearningEvidence(
                        UserSelectionKind.PROGRESSIVE_SELECTION,
                        rank,
                    ),
                )
                if (!updateConnectionComposition(next.visibleText)) return
                // An editor may synchronously invalidate the composition from
                // setComposingText. Never resurrect that stale transaction.
                if (composition != previous) return
                composition = next
                if (pinyinLearningReady()) progressiveLearnings.add(learning)
                render()
            }

            null -> Unit
        }
    }

    private fun commitText(text: String) {
        if (deferIfDecodeCommitPending(DeferredInput.Text(text))) return
        cancelVoiceForEditorInput()
        val replacementFeedback = if (
            !hasAnyComposition()
        ) {
            personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
        } else {
            null
        }
        var committedComposition = false
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            if (!commitEnglishComposition(englishInput.defaultCommitCandidate)) return
            committedComposition = true
        }
        if (
            chineseMode &&
            inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY &&
            hasAlternativeComposition()
        ) {
            val decoding = currentAlternativeDecoding()
            if (decoding == null) {
                startPendingCommit(
                    revision = activePresentationRevision(),
                    triggerInput = DeferredInput.Text(text),
                )
                if (alternativeCandidateRunner == null) {
                    val completion = finishPendingCandidateCommit(activePresentationRevision())
                    commitAndReplayPending(completion, ::commitAlternativeRawComposition)
                }
                return
            }
            if (!commitAlternativeCandidate(decoding.candidates.firstOrNull())) return
            committedComposition = true
        }
        if (composition.visibleText.isNotEmpty()) {
            val decoding = currentDecoding()
            if (composition.remainingPinyin.isNotEmpty() && decoding == null) {
                startPendingCommit(
                    revision = composition.revision,
                    triggerInput = DeferredInput.Text(text),
                )
                if (candidateRunner == null) {
                    val completion = finishPendingCandidateCommit(composition.revision)
                    commitAndReplayPending(completion, ::commitRawComposition)
                }
                return
            }
            if (!commitPrimary(decoding?.wholeCandidates?.firstOrNull())) return
            committedComposition = true
        }
        val feedback = if (committedComposition) {
            personalizationFeedback.prepareExpiration()
        } else {
            replacementFeedback
        }
        if (currentInputConnection?.commitText(text, 1) == true) {
            completeReplacementFeedback(feedback)
            // A punctuation/clipboard/tool commit after a learned word becomes the
            // newest host edit, so a later Backspace must not demote the older word.
        }
    }

    private fun toggleLanguage() {
        if (!commitActiveRawComposition()) return
        chineseMode = !chineseMode
        shifted = false
        keyboardView?.setShifted(false)
        keyboardView?.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun switchInputMethod() {
        if (!commitActiveRawComposition()) return
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
        if (!commitActiveRawComposition()) return
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

    private fun performEditorHistoryCommand(
        actionId: Int,
        fallbackKeyCode: Int,
        fallbackMetaState: Int,
        secondaryFallbackKeyCode: Int? = null,
        secondaryFallbackMetaState: Int = 0,
        successMessage: String,
    ) {
        if (!commitActiveRawComposition()) return
        val connection = currentInputConnection ?: return
        val accepted =
            connection.performContextMenuAction(actionId) ||
                sendShortcutKeyEvents(connection, fallbackKeyCode, fallbackMetaState) ||
                (
                    secondaryFallbackKeyCode != null &&
                        sendShortcutKeyEvents(
                            connection,
                            secondaryFallbackKeyCode,
                            secondaryFallbackMetaState,
                        )
                    )
        keyboardView?.showSkillFeedback(
            if (accepted) successMessage else "$successMessage · 当前输入框未响应",
        )
    }

    private fun sendShortcutKeyEvents(
        connection: InputConnection,
        keyCode: Int,
        metaState: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val downAccepted = connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
        )
        val upAccepted = connection.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState),
        )
        return downAccepted && upAccepted
    }

    private fun deleteOneCodePointOrSelection(): Boolean {
        val connection = currentInputConnection ?: return false
        val frozenSelectionStart = selectionStart
        val frozenSelectionEnd = selectionEnd
        if (hasHostSelection(frozenSelectionStart, frozenSelectionEnd)) {
            val feedback = personalizationFeedback.prepareReplacement(
                frozenSelectionStart,
                frozenSelectionEnd,
            )
            val deleted = if (sendDeleteKeyEvents(connection)) {
                true
            } else {
            // commitText replaces an active selection and is the most broadly
            // implemented fallback for editors that reject hardware key events.
                connection.commitText("", 1)
            }
            if (deleted) {
                completeReplacementFeedback(feedback)
            }
            return deleted
        }
        val feedback = personalizationFeedback.prepareQuickDelete(frozenSelectionStart)
        val deleted =
            connection.deleteSurroundingTextInCodePoints(1, 0) || sendDeleteKeyEvents(connection)
        if (deleted) {
            completePersonalizationFeedback(feedback, UserNegativeFeedback.QUICK_DELETE)
        }
        return deleted
    }

    private fun completeReplacementFeedback(
        attempt: PersonalizationFeedbackWindow.Attempt?,
    ) = completePersonalizationFeedback(
        attempt,
        UserNegativeFeedback.IMMEDIATE_REPLACEMENT,
    )

    private fun completePersonalizationFeedback(
        attempt: PersonalizationFeedbackWindow.Attempt?,
        feedback: UserNegativeFeedback,
    ) {
        val learned = personalizationFeedback.complete(attempt) ?: return
        runCatching {
            when (learned) {
                is PersonalizationLearningTarget.Pinyin ->
                    adaptiveDecoder?.demote(learned.phrase, feedback)
                is PersonalizationLearningTarget.Wubi ->
                    wubiRuntime.candidateDecoder?.demote(
                        composing = learned.rawCode,
                        candidate = learned.candidate,
                        feedback = when (feedback) {
                            UserNegativeFeedback.QUICK_DELETE -> WubiNegativeFeedback.QUICK_DELETE
                            UserNegativeFeedback.IMMEDIATE_REPLACEMENT ->
                                WubiNegativeFeedback.IMMEDIATE_REPLACEMENT
                            UserNegativeFeedback.MANUAL_DEMOTION ->
                                WubiNegativeFeedback.MANUAL_DEMOTION
                        },
                    )
            }
        }.onFailure { error ->
            Log.e(TAG, "Personalization feedback update failed", error)
        }
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
        val feedback = when (command) {
            EditorContextCommand.CUT,
            EditorContextCommand.PASTE,
            -> personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)

            EditorContextCommand.COPY -> null
        }
        val accepted = currentInputConnection?.performContextMenuAction(actionId) == true
        if (accepted) {
            when (command) {
                EditorContextCommand.CUT,
                EditorContextCommand.PASTE,
                -> completeReplacementFeedback(feedback)

                EditorContextCommand.COPY -> Unit
            }
        }
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
        if (!commitActiveRawComposition()) return
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

    /**
     * A voice launch is posted one main-loop turn after its composing commit. Input replayed in
     * that gap must invalidate the post just as input after an active launch cancels its session.
     */
    private fun cancelVoiceForEditorInput() {
        if (activeVoiceSession != null || keyboardView?.isVoiceSurfaceActive() == true) {
            cancelVoiceSession(exitSurface = true)
        } else {
            voiceLaunchGeneration = nextGeneration(voiceLaunchGeneration)
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
        val feedback = personalizationFeedback.prepareExpiration()
        val committed = currentInputConnection?.commitText(text, 1) == true
        if (committed) {
            personalizationFeedback.complete(feedback)
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
        compositionLeftContext = ""
        inputScheme.reset()
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        progressiveLearnings.clear()
        personalizationFeedback.clear()
        englishInput.reset()
        clearPendingCommit()
        shifted = false
        keyboardView?.setShifted(false)
        if (finishConnection) currentInputConnection?.finishComposingText()
        render()
    }

    private fun render(forceDecode: Boolean = false) {
        inputScheme.takePendingPreferences(compositionActive = hasAnyComposition())?.let { value ->
            applyImePreferences(value)
            return
        }
        if (!chineseMode) {
            keyboardView?.updateComposing(
                englishInput.revision,
                englishInput.composing,
                englishInput.candidates.map { it.text },
            )
            return
        }
        if (inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY) {
            renderAlternative(forceDecode)
            return
        }
        val runtime = decoderRuntime
        val request = CandidateDecodeRequest(
            composition = composition,
            leftContext = compositionLeftContext,
            decoderGeneration = runtime.generation,
            decoder = runtime.decoder,
            segmenter = runtime.segmenter,
        )
        val launch = candidateSession.begin(
            composition = request.composition,
            decoderGeneration = request.decoderGeneration,
            forceDecode = forceDecode,
        )
        if (launch.stateChanged) mainHandler.removeCallbacksAndMessages(candidateResultToken)
        val candidateBarText = CandidateBarCompositionPresenter.text(
            composition = request.composition,
            segmenter = request.segmenter,
            decodingPending = launch.presentation.pending,
            primaryCandidate = launch.presentation.snapshot.candidates.firstOrNull(),
        )
        if (launch.presentation.pending) {
            keyboardView?.updateComposition(
                request.composition.revision,
                candidateBarText,
            )
        } else {
            keyboardView?.updateComposing(
                request.composition.revision,
                candidateBarText,
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
        if (
            destroyed ||
            !chineseMode ||
            inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY ||
            request.composition != composition
        ) return
        val presentation = candidateSession.complete(
            requestedComposition = request.composition,
            decoding = decoding,
            limit = PRESENTATION_CANDIDATE_LIMIT,
            decoderGeneration = request.decoderGeneration,
        ) ?: return

        if (
            pendingDecodeCommit.intent == PendingDecodeCommit.Candidate(composition.revision)
        ) {
            val completion = finishPendingCandidateCommit(composition.revision)
            commitAndReplayPending(completion) {
                commitPrimary(decoding.wholeCandidates.firstOrNull())
            }
            return
        }
        keyboardView?.updateComposing(
            composition.revision,
            CandidateBarCompositionPresenter.text(
                composition = composition,
                segmenter = request.segmenter,
                decodingPending = presentation.pending,
                primaryCandidate = presentation.snapshot.candidates.firstOrNull(),
            ),
            presentation.snapshot.candidates.map { it.text },
        )
    }

    private fun currentDecoding(): ProgressivePinyinDecoding? {
        val runtime = decoderRuntime
        return candidateSession.currentDecoding(
            composition = composition,
            decoderGeneration = runtime.generation,
        )
    }

    private fun renderAlternative(forceDecode: Boolean) {
        val key = inputScheme.key()
        val pinyinRuntime = decoderRuntime
        val activeWubiRuntime = wubiRuntime
        val request = AlternativeDecodeRequest(
            key = key,
            t9Composition = inputScheme.t9.takeIf {
                inputScheme.scheme == ChineseInputScheme.PINYIN_T9
            },
            wubiComposition = inputScheme.wubi.takeIf {
                inputScheme.scheme == ChineseInputScheme.WUBI_86
            },
            t9Index = pinyinRuntime.t9Index,
            pinyinDecoder = pinyinRuntime.decoder,
            pinyinDecoderGeneration = pinyinRuntime.generation,
            wubiDecoder = activeWubiRuntime.decoder,
            wubiCandidateDecoder = activeWubiRuntime.candidateDecoder,
            wubiDecoderGeneration = activeWubiRuntime.generation,
            leftContext = inputScheme.leftContext,
            limit = DECODE_CANDIDATE_LIMIT,
        )
        val launch = inputScheme.begin(request, forceDecode)
        if (launch.stateChanged) {
            mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        }
        val ready = launch.presentation.decoding
        val label = ready?.composingLabel ?: key.rawCode
        if (launch.presentation.pending) {
            keyboardView?.updateComposition(key.presentationRevision, label)
        } else {
            keyboardView?.updateComposing(
                key.presentationRevision,
                label,
                ready?.candidateLabels.orEmpty(),
            )
        }
        val decoderReady = isAlternativeDecoderReady(
            scheme = request.key.scheme,
            wubiCandidateDecoderAvailable = activeWubiRuntime.candidateDecoder != null,
            wubiLoadInFlight = wubiLoadRequested,
        )
        if (launch.shouldDecode && decoderReady) {
            if (alternativeCandidateRunner?.submit(request) == -1L) {
                alternativeCandidateRunner = null
            }
        }
    }

    private fun postAlternativeDecodedCandidates(
        request: AlternativeDecodeRequest,
        decoding: AlternativeDecoding,
    ) {
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        mainHandler.postAtTime(
            { applyAlternativeDecodedCandidates(request, decoding) },
            alternativeCandidateResultToken,
            SystemClock.uptimeMillis(),
        )
    }

    private fun applyAlternativeDecodedCandidates(
        request: AlternativeDecodeRequest,
        decoding: AlternativeDecoding,
    ) {
        if (
            destroyed ||
            !chineseMode ||
            inputScheme.scheme == ChineseInputScheme.PINYIN_QWERTY ||
            request.key != inputScheme.key()
        ) {
            return
        }
        val presentation = inputScheme.complete(
            request = request,
            decoding = decoding,
            activePinyinGeneration = decoderRuntime.generation,
            activeWubiGeneration = wubiRuntime.generation,
        ) ?: return

        val overflow = wubiOverflow.onDecoded(
            presentationRevision = request.key.presentationRevision,
            candidates = decoding.candidates,
        )
        when (overflow) {
            WubiOverflowAction.Continue -> Unit
            is WubiOverflowAction.Await -> error("Decoded Wubi overflow cannot remain pending")
            WubiOverflowAction.Reject,
            is WubiOverflowAction.Commit,
            -> {
                resolveWubiOverflowAction(
                    action = overflow,
                    presentationRevision = request.key.presentationRevision,
                )
                return
            }
        }

        if (
            pendingDecodeCommit.intent ==
            PendingDecodeCommit.Candidate(request.key.presentationRevision)
        ) {
            val completion = finishPendingCandidateCommit(request.key.presentationRevision)
            commitAndReplayPending(completion) {
                commitAlternativeCandidate(decoding.candidates.firstOrNull())
            }
            return
        }
        if (inputScheme.shouldUniqueAtFourCommit()) {
            val exact = decoding.candidates.filter {
                it.matchKind == CandidateMatchKind.WUBI_EXACT
            }
            if (exact.size == 1 && commitAlternativeCandidate(exact.single())) return
        }
        keyboardView?.updateComposing(
            request.key.presentationRevision,
            decoding.composingLabel,
            presentation.decoding?.candidateLabels.orEmpty(),
        )
    }

    private fun currentAlternativeDecoding(): AlternativeDecoding? = inputScheme.currentDecoding()

    private fun commitPrimary(
        candidate: Candidate?,
        evidence: UserLearningEvidence = UserLearningEvidence.DEFAULT_ACCEPT,
    ): Boolean {
        if (composition.visibleText.isEmpty()) return false
        val composingLength = composition.visibleText.length
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
        return commitComposition(output, rawInput, learnable, evidence, composingLength)
    }

    private fun commitAlternativeCandidate(
        candidate: Candidate?,
        evidence: UserLearningEvidence = UserLearningEvidence.DEFAULT_ACCEPT,
    ): Boolean {
        val connection = currentInputConnection
        val commitSnapshot = AlternativeCommitSnapshot.capture(
            coordinator = inputScheme,
            hasCandidate = candidate != null,
            editorSessionId = editorSessionId,
            inputConnectionIdentity = connection,
        )
        val rawCode = commitSnapshot.rawCode
        if (rawCode.isEmpty()) return false
        val output = candidate?.text ?: rawCode
        val feedback = personalizationFeedback.prepareExpiration()
        val wubiLearningTarget = wubiRuntime.candidateDecoder.takeIf {
            commitSnapshot.learningDomain == AlternativeLearningDomain.WUBI &&
                wubiLearningReady()
        }
        val pinyinLearningTarget = adaptiveDecoder.takeIf {
            commitSnapshot.learningDomain == AlternativeLearningDomain.PINYIN &&
                pinyinLearningReady()
        }
        val committedStart = when {
            hasHostSelection(selectionStart, selectionEnd) -> minOf(selectionStart, selectionEnd)
            selectionStart >= 0 -> (selectionStart - rawCode.length).coerceAtLeast(0)
            else -> -1
        }
        val committed = connection?.commitText(output, 1) == true
        if (!committed) {
            clearPendingCommit()
            return false
        }
        personalizationFeedback.complete(feedback)
        val sameEditor = commitSnapshot.isSameEditor(editorSessionId, currentInputConnection)
        val committedEnd = committedStart.takeIf { it >= 0 }?.plus(output.length) ?: -1
        if (wubiLearningTarget != null && candidate != null) {
            runCatching {
                wubiLearningTarget.learn(
                    composing = rawCode,
                    candidate = candidate,
                    evidence = WubiLearningEvidence(
                        kind = when {
                            evidence.kind == UserSelectionKind.DEFAULT_ACCEPT ->
                                WubiSelectionKind.DEFAULT_ACCEPT
                            candidate.matchKind == CandidateMatchKind.WUBI_COMPLETION ->
                                WubiSelectionKind.COMPLETION_SELECTION
                            else -> WubiSelectionKind.EXPLICIT_SELECTION
                        },
                        selectedRank = evidence.selectedRank,
                    ),
                )
            }.onFailure { error ->
                Log.e(TAG, "Wubi86 personalization update failed", error)
            }.getOrNull()?.let {
                if (sameEditor) {
                    personalizationFeedback.rememberWubi(
                        rawCode = rawCode,
                        candidate = candidate,
                        start = committedStart,
                        endExclusive = committedEnd,
                    )
                }
            }
        }
        if (pinyinLearningTarget != null && candidate != null) {
            val canonical = candidate.canonicalPinyin
            if (!canonical.isNullOrEmpty()) {
                runCatching {
                    pinyinLearningTarget.learn(canonical, candidate, evidence)
                }.onFailure { error ->
                    Log.e(TAG, "Alternative Pinyin personalization update failed", error)
                }.getOrNull()?.let { learned ->
                    if (sameEditor) {
                        personalizationFeedback.remember(
                            learned,
                            start = committedStart,
                            endExclusive = committedEnd,
                        )
                    }
                }
            }
        }
        if (commitSnapshot.stillOwnsComposition(inputScheme.key())) {
            clearAlternativeCompositionAfterCommit()
        }
        return true
    }

    private fun commitAlternativeRawComposition(): Boolean = commitAlternativeCandidate(null)

    private fun commitActivePrimaryOrRaw(): Boolean = when {
        !chineseMode && englishInput.composing.isNotEmpty() ->
            commitEnglishComposition(englishInput.defaultCommitCandidate)
        inputScheme.scheme == ChineseInputScheme.PINYIN_QWERTY -> {
            val decoding = currentDecoding()
            if (decoding == null) commitRawComposition()
            else commitPrimary(decoding.wholeCandidates.firstOrNull())
        }
        else -> {
            val decoding = currentAlternativeDecoding()
            if (decoding == null) commitAlternativeRawComposition()
            else commitAlternativeCandidate(decoding.candidates.firstOrNull())
        }
    }

    private fun clearAlternativeCompositionAfterCommit() {
        inputScheme.clearAfterCommit()
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        clearPendingCommit()
        shifted = false
        keyboardView?.setShifted(false)
        render()
    }

    private fun commitRawComposition(): Boolean {
        if (composition.visibleText.isEmpty()) return false
        return commitComposition(
            composition.confirmRaw(),
            rawInput = null,
            learnable = null,
            evidence = UserLearningEvidence.DEFAULT_ACCEPT,
            composingLength = composition.visibleText.length,
        )
    }

    private fun commitEnglishComposition(candidate: Candidate?): Boolean {
        if (englishInput.composing.isEmpty()) return false
        val output = candidate?.text ?: englishInput.composing
        val feedback = personalizationFeedback.prepareExpiration()
        val committed = currentInputConnection?.commitText(output, 1) == true
        if (!committed) return false
        personalizationFeedback.complete(feedback)
        englishInput.reset()
        shifted = false
        keyboardView?.setShifted(false)
        render()
        return true
    }

    /** Returns true when there was nothing to settle or the host accepted the commit. */
    private fun commitActiveRawComposition(): Boolean = when {
        chineseMode && inputScheme.scheme == ChineseInputScheme.PINYIN_QWERTY ->
            composition.visibleText.isEmpty() || commitRawComposition()

        chineseMode -> !hasAlternativeComposition() || commitAlternativeRawComposition()
        else -> englishInput.composing.isEmpty() || commitEnglishComposition(candidate = null)
    }

    private fun commitComposition(
        output: String,
        rawInput: String?,
        learnable: Candidate?,
        evidence: UserLearningEvidence,
        composingLength: Int,
    ): Boolean {
        val committingComposition = composition
        val stagedProgressiveLearnings = progressiveLearnings.snapshotForCommit()
        val learningTarget = adaptiveDecoder.takeIf { pinyinLearningReady() }
        val committedEditorSessionId = editorSessionId
        val connection = currentInputConnection
        val committedStart = when {
            hasHostSelection(selectionStart, selectionEnd) -> minOf(selectionStart, selectionEnd)
            selectionStart >= 0 -> (selectionStart - composingLength).coerceAtLeast(0)
            else -> -1
        }
        val committed = connection?.commitText(output, 1) == true
        if (!committed) {
            if (composition == committingComposition) {
                clearPendingCommit()
            }
            return false
        }
        if (learningTarget != null) {
            stagedProgressiveLearnings.forEach { pending ->
                runCatching {
                    learningTarget.learn(
                        pending.rawInput,
                        pending.candidate,
                        pending.evidence,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Progressive personalization update failed", error)
                }
            }
            if (rawInput != null && learnable != null) {
                runCatching {
                    learningTarget.learn(rawInput, learnable, evidence)
                }.onFailure { error ->
                    Log.e(TAG, "Personalization update failed", error)
                }.getOrNull()?.let { learned ->
                    val sameEditor =
                        editorSessionId == committedEditorSessionId &&
                            currentInputConnection === connection
                    if (sameEditor) {
                        personalizationFeedback.remember(
                            learned,
                            start = committedStart,
                            endExclusive =
                                committedStart.takeIf { it >= 0 }?.plus(output.length) ?: -1,
                        )
                    }
                }
            }
        }
        if (composition == committingComposition) {
            composition = composition.reset()
            compositionLeftContext = ""
            progressiveLearnings.clear()
            clearPendingCommit()
            shifted = false
            keyboardView?.setShifted(false)
            render()
        }
        return true
    }

    private fun updateConnectionComposition(visibleText: String): Boolean {
        val update = editorComposingTextUpdate(visibleText)
        return currentInputConnection?.setComposingText(update.text, update.newCursorPosition) == true
    }

    private fun captureDecodeLeftContext(): String {
        if (!decodeContextAllowed) return ""
        return runCatching {
            currentInputConnection
                ?.getTextBeforeCursor(MAX_DECODE_CONTEXT_CHARS, 0)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun deferIfDecodeCommitPending(input: DeferredInput): Boolean {
        return when (pendingDecodeCommit.defer(input)) {
            DeferredInputOffer.NOT_PENDING -> false
            DeferredInputOffer.ACCEPTED -> true
            DeferredInputOffer.CAPACITY_REACHED -> {
                // Resolve the older transaction without exceeding the hard queue bound, then
                // preserve this input if draining happened to start a new pending transaction.
                resolvePendingCommitTimeout()
                when (pendingDecodeCommit.defer(input)) {
                    DeferredInputOffer.NOT_PENDING -> false
                    DeferredInputOffer.ACCEPTED,
                    DeferredInputOffer.CAPACITY_REACHED,
                    -> true
                }
            }
        }
    }

    private fun replayPendingCompletion(
        completion: FinishedPendingDecodeCommit<DeferredInput>,
        includeTrigger: Boolean = true,
    ) {
        val inputs = buildList {
            if (includeTrigger) completion.triggerInput?.let(::add)
            addAll(completion.followUpInputs)
        }
        inputs.forEach { input ->
            if (!deferIfDecodeCommitPending(input)) dispatchDeferredInput(input)
        }
    }

    private fun dispatchDeferredInput(input: DeferredInput) {
        when (input) {
            is DeferredInput.Key -> handleKey(input.code)
            is DeferredInput.Text -> commitText(input.text)
            is DeferredInput.AiHold -> beginAiHold(input.generation)
        }
    }

    private fun commitAndReplayPending(
        completion: FinishedPendingDecodeCommit<DeferredInput>,
        commit: () -> Boolean,
    ): Boolean {
        val committedEditorSessionId = editorSessionId
        val committedConnection = currentInputConnection
        val committed = commit()
        if (
            committed &&
            editorSessionId == committedEditorSessionId &&
            currentInputConnection === committedConnection
        ) {
            replayPendingCompletion(completion)
        }
        return committed
    }

    private fun startPendingCommit(
        revision: Long,
        triggerInput: DeferredInput? = null,
    ) {
        startPendingCommit(PendingDecodeCommit.Candidate(revision), triggerInput)
    }

    private fun startPendingWubiOverflow(
        presentationRevision: Long,
        replayKeyCode: Int,
    ) {
        startPendingCommit(
            intent = PendingDecodeCommit.WubiOverflow(presentationRevision),
            triggerInput = DeferredInput.Key(replayKeyCode),
        )
    }

    private fun startPendingCommit(
        intent: PendingDecodeCommit,
        triggerInput: DeferredInput?,
    ) {
        pendingDecodeCommit.start(intent, triggerInput)
        mainHandler.removeCallbacks(pendingCommitTimeout)
        mainHandler.postDelayed(pendingCommitTimeout, PENDING_COMMIT_TIMEOUT_MS)
    }

    private fun finishPendingCandidateCommit(
        presentationRevision: Long,
    ): FinishedPendingDecodeCommit<DeferredInput> {
        val completion = checkNotNull(pendingDecodeCommit.finish(presentationRevision)) {
            "No matching pending candidate transaction"
        }
        check(completion.intent is PendingDecodeCommit.Candidate) {
            "Candidate completion does not match the pending decode intent"
        }
        wubiOverflow.clear()
        mainHandler.removeCallbacks(pendingCommitTimeout)
        return completion
    }

    private fun resolveWubiOverflowAction(
        action: WubiOverflowAction,
        presentationRevision: Long,
    ) {
        check(action == WubiOverflowAction.Reject || action is WubiOverflowAction.Commit)
        val completion = checkNotNull(pendingDecodeCommit.finish(presentationRevision)) {
            "No matching pending Wubi overflow transaction"
        }
        check(completion.intent is PendingDecodeCommit.WubiOverflow) {
            "Wubi overflow completion does not match the pending decode intent"
        }
        wubiOverflow.clear()
        mainHandler.removeCallbacks(pendingCommitTimeout)

        when (action) {
            WubiOverflowAction.Reject ->
                replayPendingCompletion(completion, includeTrigger = false)
            is WubiOverflowAction.Commit ->
                commitAndReplayPending(completion) {
                    commitAlternativeCandidate(action.candidate)
                }
            WubiOverflowAction.Continue,
            is WubiOverflowAction.Await,
            -> error("Wubi overflow action is not terminal")
        }
    }

    private fun resolvePendingCommitTimeout() {
        val intent = pendingDecodeCommit.intent ?: return
        if (activePresentationRevision() != intent.presentationRevision) {
            clearPendingCommit()
            return
        }
        when (intent) {
            is PendingDecodeCommit.Candidate -> {
                val completion = finishPendingCandidateCommit(intent.presentationRevision)
                commitAndReplayPending(completion, ::commitActiveRawComposition)
            }
            is PendingDecodeCommit.WubiOverflow -> {
                when (val action = wubiOverflow.onTimeout(intent.presentationRevision)) {
                    WubiOverflowAction.Continue,
                    is WubiOverflowAction.Await,
                    -> clearPendingCommit()
                    WubiOverflowAction.Reject,
                    is WubiOverflowAction.Commit,
                    -> resolveWubiOverflowAction(action, intent.presentationRevision)
                }
            }
        }
    }

    private fun clearPendingCommit() {
        pendingDecodeCommit.clearAll()
        wubiOverflow.clear()
        mainHandler.removeCallbacks(pendingCommitTimeout)
    }

    private fun hasAnyComposition(): Boolean =
        englishInput.composing.isNotEmpty() || hasActiveChineseComposition()

    private fun pinyinLearningReady(): Boolean = isPinyinLearningReady(
        localPersistenceAllowed = localPersistenceAllowed,
        productionPinyinDecoderReady = productionDecoderReady,
    )

    private fun wubiLearningReady(): Boolean = isWubiLearningReady(
        localPersistenceAllowed = localPersistenceAllowed,
        adaptiveWubiDecoderReady = wubiRuntime.candidateDecoder != null,
    )

    private fun hasActiveChineseComposition(): Boolean = when (inputScheme.scheme) {
        ChineseInputScheme.PINYIN_QWERTY -> composition.visibleText.isNotEmpty()
        ChineseInputScheme.PINYIN_T9,
        ChineseInputScheme.WUBI_86,
        -> hasAlternativeComposition()
    }

    private fun hasAlternativeComposition(): Boolean = inputScheme.hasComposition

    private fun activePresentationRevision(): Long =
        if (chineseMode && inputScheme.scheme != ChineseInputScheme.PINYIN_QWERTY) {
            inputScheme.presentationRevision
        } else if (chineseMode) {
            composition.revision
        } else {
            englishInput.revision
        }

    private fun activePrimaryKeyboardMode(): PrimaryKeyboardMode =
        if (chineseMode && inputScheme.scheme == ChineseInputScheme.PINYIN_T9) {
            PrimaryKeyboardMode.T9
        } else {
            PrimaryKeyboardMode.QWERTY
        }

    private fun activePrimaryKeyboardLegendMode(): PrimaryKeyboardLegendMode =
        if (chineseMode && inputScheme.scheme == ChineseInputScheme.WUBI_86) {
            PrimaryKeyboardLegendMode.WUBI_86_ROOTS
        } else {
            PrimaryKeyboardLegendMode.SWIPE_HINTS
        }

    private fun refreshImePreferencesAsync(expectedEditorSessionId: Long) {
        val requestGeneration = nextGeneration(preferencesLoadGeneration)
        preferencesLoadGeneration = requestGeneration
        preferencesIo.execute {
            val loaded = imePreferencesStore.load()
            mainHandler.post {
                if (
                    destroyed ||
                    preferencesLoadGeneration != requestGeneration ||
                    editorSessionId != expectedEditorSessionId
                ) {
                    return@post
                }
                loaded.onSuccess { value ->
                    inputScheme.acceptLoadedPreferences(
                        value = value,
                        compositionActive = hasAnyComposition(),
                    )?.let(::applyImePreferences)
                }.onFailure { error ->
                    Log.e(TAG, "IME preference load failed", error)
                }
            }
        }
    }

    private fun applyImePreferences(value: ImePreferencesV1) {
        val schemeChanged = inputScheme.applyPreferences(value)
        if (schemeChanged) {
            composition = composition.reset()
            compositionLeftContext = ""
            mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
            clearPendingCommit()
        }
        if (inputScheme.scheme == ChineseInputScheme.WUBI_86) requestWubiDecoderLoad()
        keyboardView?.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        render(forceDecode = schemeChanged)
    }

    private fun requestWubiDecoderLoad() {
        if (wubiRuntime.decoder != null || wubiLoadRequested) return
        wubiLoadRequested = true
        schemeIo.execute {
            val loaded = runCatching {
                val userLexicon: WubiUserLexicon = runCatching {
                    PersistentWubi86UserLexicon(applicationContext)
                }.onFailure { error ->
                    Log.e(TAG, "Wubi86 personalization storage unavailable", error)
                }.getOrElse {
                    MemoryWubiUserLexicon()
                }
                try {
                    val decoder = assets.open(WUBI86_ASSET)
                        .buffered()
                        .use(Wubi86Lexicon::load)
                        .let(::Wubi86Decoder)
                    WubiDecoderRuntime(
                        generation = 0L,
                        decoder = decoder,
                        candidateDecoder = AdaptiveWubi86Decoder(decoder, userLexicon),
                        userLexicon = userLexicon,
                    )
                } catch (error: Throwable) {
                    userLexicon.close()
                    throw error
                }
            }
            mainHandler.post {
                if (destroyed) {
                    loaded.getOrNull()?.userLexicon?.close()
                    return@post
                }
                loaded.onSuccess { runtime ->
                    wubiRuntime.userLexicon?.close()
                    wubiRuntime = runtime.copy(
                        generation = nextGeneration(wubiRuntime.generation),
                    )
                    if (chineseMode && inputScheme.scheme == ChineseInputScheme.WUBI_86) {
                        render(forceDecode = true)
                    }
                }.onFailure { error ->
                    wubiLoadRequested = false
                    Log.e(TAG, "Wubi86 lexicon load failed", error)
                    if (chineseMode && inputScheme.scheme == ChineseInputScheme.WUBI_86) {
                        // Leave direct code entry usable after an asset failure: publish the
                        // bounded empty fallback instead of keeping the candidate bar pending.
                        render(forceDecode = true)
                    }
                }
            }
        }
    }

    private fun allowsLocalPersistence(info: EditorInfo?): Boolean {
        if (info == null) return true
        val noPersonalizedLearning =
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return EditorPrivacyPolicy.allowsPersistence(
            noPersonalizedLearning = noPersonalizedLearning,
            passwordVariation = isPasswordVariation(info),
        )
    }

    private fun allowsTransientDecodeContext(info: EditorInfo?): Boolean =
        info == null || !isPasswordVariation(info)

    private fun isPasswordVariation(info: EditorInfo): Boolean {
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in PASSWORD_TEXT_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun AgentSkillDirection.toKeyboardDirection(): KeyboardSkillDirection = when (this) {
        AgentSkillDirection.UP -> KeyboardSkillDirection.UP
        AgentSkillDirection.RIGHT -> KeyboardSkillDirection.RIGHT
        AgentSkillDirection.DOWN -> KeyboardSkillDirection.DOWN
        AgentSkillDirection.LEFT -> KeyboardSkillDirection.LEFT
    }

    private fun KeyboardSkillDirection.toAgentDirection(): AgentSkillDirection = when (this) {
        KeyboardSkillDirection.UP -> AgentSkillDirection.UP
        KeyboardSkillDirection.RIGHT -> AgentSkillDirection.RIGHT
        KeyboardSkillDirection.DOWN -> AgentSkillDirection.DOWN
        KeyboardSkillDirection.LEFT -> AgentSkillDirection.LEFT
    }

    private companion object {
        const val SENSE_SETTINGS_ACTIVITY =
            "io.github.ethanbird.senseime.SettingsActivity"
        const val TAG = "SenseInputMethod"
        const val PINYIN_ASSET = "pinyin_lexicon.bin"
        const val PINYIN_BIGRAM_ASSET = "pinyin_bigrams.bin"
        const val PINYIN_SYLLABLES_ASSET = "pinyin_syllables.txt"
        const val ENGLISH_LEXICON_ASSET = "english_lexicon.txt"
        const val WUBI86_ASSET = "wubi86_lexicon.bin"
        const val DECODE_CANDIDATE_LIMIT = 255
        const val MAX_PROGRESSIVE_PREFIX_CANDIDATES = DECODE_CANDIDATE_LIMIT
        const val PRESENTATION_CANDIDATE_LIMIT = DECODE_CANDIDATE_LIMIT + MAX_PROGRESSIVE_PREFIX_CANDIDATES
        const val CLIPBOARD_HISTORY_LIMIT = 30
        const val MAX_CLIPBOARD_TEXT_LENGTH = 4096
        const val MAX_VOICE_PREVIEW_CHARS = 1024
        const val MAX_DECODE_CONTEXT_CHARS = 2
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
    val leftContext: String,
    val decoderGeneration: Long,
    val decoder: InputDecoder,
    val segmenter: PinyinSyllableSegmenter,
)

private data class CandidateDecoderRuntime(
    val generation: Long,
    val decoder: InputDecoder,
    val segmenter: PinyinSyllableSegmenter,
    val t9Index: T9SyllableIndex,
)

private data class LoadedCandidateDecoderRuntime(
    val runtime: CandidateDecoderRuntime,
    val adaptiveDecoder: AdaptivePinyinDecoder,
    val userLexicon: UserLexicon,
    val englishLexicon: EnglishLexicon,
)

private data class WubiDecoderRuntime(
    val generation: Long,
    val decoder: Wubi86Decoder?,
    val candidateDecoder: AdaptiveWubi86Decoder?,
    val userLexicon: WubiUserLexicon?,
)

private sealed interface DeferredInput {
    data class Key(val code: Int) : DeferredInput
    data class Text(val text: String) : DeferredInput
    data class AiHold(val generation: Long) : DeferredInput
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
            preset?.credentialRequirement == SpeechProviderCredentialRequirement.API_KEY &&
                !hadCredential ->
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
