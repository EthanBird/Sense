package io.github.ethanbird.senseime.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.ethanbird.senseime.agent.ui.AgentMessageRole
import io.github.ethanbird.senseime.agent.ui.AgentMessageUi
import io.github.ethanbird.senseime.agent.ui.AgentActionState
import io.github.ethanbird.senseime.agent.ui.AgentActionUi
import io.github.ethanbird.senseime.agent.ui.AgentConversationUi
import io.github.ethanbird.senseime.agent.ui.AgentToolKind
import io.github.ethanbird.senseime.agent.ui.AgentToolState
import io.github.ethanbird.senseime.agent.ui.AgentToolUi
import io.github.ethanbird.senseime.agent.ui.AgentUiActions
import io.github.ethanbird.senseime.agent.ui.AgentUiState
import io.github.ethanbird.senseime.ai.protocol.ActiveSkillInstructionV1
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.TextSelectionV1
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore
import io.github.ethanbird.senseime.brain.runtime.AgentHubMessageRole
import io.github.ethanbird.senseime.brain.runtime.AgentHubActionState
import io.github.ethanbird.senseime.brain.runtime.AgentHubCommandCallback
import io.github.ethanbird.senseime.brain.runtime.AgentHubCommandHandle
import io.github.ethanbird.senseime.brain.runtime.AgentHubCommandOutcome
import io.github.ethanbird.senseime.brain.runtime.AgentHubCommandOutcomeCode
import io.github.ethanbird.senseime.brain.runtime.AgentHubPort
import io.github.ethanbird.senseime.brain.runtime.AgentHubObserver
import io.github.ethanbird.senseime.brain.runtime.AgentHubProjection
import io.github.ethanbird.senseime.brain.runtime.AgentHubToolState
import io.github.ethanbird.senseime.brain.runtime.RemoteSenseAgentHubClient
import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesStore
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.ImeSettingsRoute
import io.github.ethanbird.senseime.core.AdaptivePinyinDecoder
import io.github.ethanbird.senseime.core.AdaptiveWubi86Decoder
import io.github.ethanbird.senseime.core.AssociationObservation
import io.github.ethanbird.senseime.core.AssociationSuggestion
import io.github.ethanbird.senseime.core.BinaryCharacterBigramModel
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.CharacterBigramModel
import io.github.ethanbird.senseime.core.CommitSequenceTracker
import io.github.ethanbird.senseime.core.CommittedTextUnit
import io.github.ethanbird.senseime.core.CuratedLexicalCandidateCatalog
import io.github.ethanbird.senseime.core.EnglishLexicon
import io.github.ethanbird.senseime.core.EnglishInputSession
import io.github.ethanbird.senseime.core.FakeDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.LearnedPhrase
import io.github.ethanbird.senseime.core.LocalAssociationEngine
import io.github.ethanbird.senseime.core.MemoryUserAssociationLexicon
import io.github.ethanbird.senseime.core.MemoryUserLexicon
import io.github.ethanbird.senseime.core.MemoryWubiUserLexicon
import io.github.ethanbird.senseime.core.PinyinComposition
import io.github.ethanbird.senseime.core.PinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoder
import io.github.ethanbird.senseime.core.ProgressivePinyinDecoding
import io.github.ethanbird.senseime.core.PinyinSyllableSegmenter
import io.github.ethanbird.senseime.core.SemanticCandidateCatalog
import io.github.ethanbird.senseime.core.SemanticCandidateMixer
import io.github.ethanbird.senseime.core.T9Composition
import io.github.ethanbird.senseime.core.T9PinyinChoice as CoreT9PinyinChoice
import io.github.ethanbird.senseime.core.T9SyllableIndex
import io.github.ethanbird.senseime.core.UserLearningEvidence
import io.github.ethanbird.senseime.core.UserAssociationLexicon
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
import io.github.ethanbird.senseime.ui.AiResultActionType
import io.github.ethanbird.senseime.ui.ActiveKeyboardSkill
import io.github.ethanbird.senseime.ui.KeyboardSkillBinding
import io.github.ethanbird.senseime.ui.KeyboardSkillDirection
import io.github.ethanbird.senseime.ui.KeyboardSkillSelection
import io.github.ethanbird.senseime.ui.KeyboardSkillSelectionListener
import io.github.ethanbird.senseime.ui.KeyboardSkillToggleAction
import io.github.ethanbird.senseime.ui.KeyboardLayoutContract
import io.github.ethanbird.senseime.ui.KeyboardInputSchemeChoice
import io.github.ethanbird.senseime.ui.KeyboardInputSchemeSelectionListener
import io.github.ethanbird.senseime.ui.PrimaryKeyboardMode
import io.github.ethanbird.senseime.ui.PrimaryKeyboardLegendMode
import io.github.ethanbird.senseime.ui.SenseKeyboardView
import io.github.ethanbird.senseime.ui.SenseKeyboardSurface
import io.github.ethanbird.senseime.ui.T9PinyinChoice as UiT9PinyinChoice
import io.github.ethanbird.senseime.ui.T9PinyinChoiceSelectionListener
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
    private var userAssociationLexicon: UserAssociationLexicon = MemoryUserAssociationLexicon()
    private var associationEngine = LocalAssociationEngine(
        userLexicon = userAssociationLexicon,
        characterBigrams = CharacterBigramModel.EMPTY,
    )
    private val associationSession = AssociationSession()
    private val associationDisplayLifecycle = AssociationDisplayLifecycle()
    private val commitSequenceTracker = CommitSequenceTracker()
    private val pendingAssociationObservations = ArrayDeque<AssociationObservation>()
    private val pendingPinyinLearnings = PendingPinyinLearningQueue(MAX_PENDING_PINYIN_LEARNINGS)
    private var associationPersistenceReady = false
    private val reliableCommitGuard = SynchronousEditorMutationGuard()
    private val compositionUpdateGuard = SynchronousEditorMutationGuard()
    private val reliableCommitSelectionFence =
        ReliableCommitSelectionFence(SystemClock::elapsedRealtime)
    private val candidateSession = CandidateDecodeSession()
    private var composition = PinyinComposition()
    private var compositionLeftContext = ""
    private val inputScheme = ChineseInputSchemeCoordinator()
    private lateinit var imePreferencesStore: ImePreferencesStore
    private var preferencesLoadGeneration = 0L
    private var wubiLoadRequested = false
    private var englishInput = EnglishInputSession(EnglishLexicon.EMPTY)
    private var englishLexicon = EnglishLexicon.EMPTY
    private lateinit var englishWordUsage: PersistentEnglishWordUsageStore
    private var englishShiftState = EnglishShiftState.LOWERCASE
    private var chineseMode = true
    private var keyboardView: SenseKeyboardView? = null
    private var keyboardSurface: SenseKeyboardSurface? = null
    private var imeRoot: SenseImeRootLayout? = null
    private val agentDraft = AgentDraftBuffer()
    private var agentDraftComposition = ""
    private var agentProjection = AgentHubProjection()
    private var agentRuntime: AgentHubPort? = null
    private var agentSubscription: AutoCloseable? = null
    private val agentCommandHandles = linkedMapOf<String, AgentHubCommandHandle>()
    private var agentCommandLifecycle = 0L
    private var selectedAgentToolIndex = 0
    private var agentHistoryVisible = false
    private var agentMenuVisible = false
    private var openAgentToolId: String? = null
    private var agentFrontVisible = false
    private var agentComposerVisible = false
    private var imeWindowVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val candidateResultToken = Any()
    private val alternativeCandidateResultToken = Any()
    private val associationDisplayToken = Any()
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
                get() = if (isAgentTextTarget()) agentDraft else currentInputConnection

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
        englishWordUsage = PersistentEnglishWordUsageStore(this)
        englishLexicon = EnglishLexicon.fromWords(emptyList(), englishWordUsage)
        val bootstrapLexicon = MemoryUserLexicon()
        userLexicon = bootstrapLexicon
        val bootstrapSegmenter = PinyinSyllableSegmenter(FALLBACK_SYLLABLES)
        adaptiveDecoder = AdaptivePinyinDecoder(
            base = FakeDecoder(),
            userLexicon = bootstrapLexicon,
            segmenter = bootstrapSegmenter,
            englishLexicon = englishLexicon,
        )
        decoderRuntime = CandidateDecoderRuntime(
            generation = nextGeneration(decoderRuntime.generation),
            decoder = requireNotNull(adaptiveDecoder),
            segmenter = bootstrapSegmenter,
            t9Index = T9SyllableIndex(FALLBACK_SYLLABLES),
        )
        englishInput = EnglishInputSession(englishLexicon, DECODE_CANDIDATE_LIMIT)
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
                        primary = CuratedLexicalCandidateCatalog.merge(
                            composing = request.composition.remainingPinyin,
                            primary = decoding.wholeCandidates,
                            limit = DECODE_CANDIDATE_LIMIT,
                        ),
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
                        composingLabel = fallbackAlternativeComposingLabel(request),
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
            onAssistantResult = { generation, preview, status, activities, actions ->
                keyboardView?.updateAiSurface(
                    generation = generation,
                    phase = io.github.ethanbird.senseime.ui.AiSurfacePhase.COMPLETE,
                    preview = preview,
                    statusText = status,
                    activities = activities,
                    resultActions = actions,
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
                    assets.open(ENGLISH_LEXICON_ASSET).use {
                        EnglishLexicon.load(it, usageStore = englishWordUsage)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "English lexicon load failed", error)
                }.getOrElse { EnglishLexicon.fromWords(emptyList(), englishWordUsage) }
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
                val associations = runCatching<UserAssociationLexicon> {
                    PersistentUserAssociationLexicon(this)
                }.onFailure { error ->
                    Log.e(TAG, "Persistent user association load failed", error)
                }.getOrElse { MemoryUserAssociationLexicon() }
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
                    bigramModel = bigramModel,
                    userAssociationLexicon = associations,
                )
            },
            publish = publish@{ loaded ->
                val learned = loaded.userLexicon
                if (destroyed) {
                    learned.close()
                    loaded.userAssociationLexicon.close()
                    return@publish
                }
                val previousLexicon = userLexicon
                val previousAssociations = userAssociationLexicon
                userLexicon = learned
                userAssociationLexicon = loaded.userAssociationLexicon
                associationEngine = LocalAssociationEngine(
                    userLexicon = loaded.userAssociationLexicon,
                    characterBigrams = loaded.bigramModel,
                )
                associationPersistenceReady = true
                while (pendingAssociationObservations.isNotEmpty()) {
                    val observation = pendingAssociationObservations.removeFirst()
                    runCatching {
                        associationEngine.observe(observation.context, observation.nextText)
                    }.onFailure { error ->
                        Log.e(TAG, "Queued user association replay failed", error)
                    }
                }
                pendingPinyinLearnings.drain().forEach { pending ->
                    runCatching {
                        loaded.adaptiveDecoder.learn(
                            pending.rawInput,
                            pending.candidate,
                            pending.evidence,
                        )
                    }.onFailure { error ->
                        Log.e(TAG, "Queued Pinyin personalization replay failed", error)
                    }
                }
                adaptiveDecoder = loaded.adaptiveDecoder
                decoderRuntime = loaded.runtime.copy(
                    generation = nextGeneration(decoderRuntime.generation),
                )
                productionDecoderReady = true
                localPersistenceAllowed = allowsLocalPersistence(currentEditorInfo)

                val pendingEnglish = englishInput.composing
                englishLexicon = loaded.englishLexicon
                englishInput = EnglishInputSession(loaded.englishLexicon, DECODE_CANDIDATE_LIMIT)
                pendingEnglish.forEach(englishInput::type)
                previousLexicon?.takeIf { it !== learned }?.close()
                previousAssociations
                    .takeIf { it !== loaded.userAssociationLexicon }
                    ?.close()
                render(forceDecode = chineseMode && hasActiveChineseComposition())
            },
        )
    }

    override fun onCreateInputView(): View = SenseImeRootLayout(this).also { root ->
        imeRoot?.release()
        imeRoot = root
        val surface = root.keyboardSurface
        keyboardSurface = surface
        root.setImeWindowVisible(imeWindowVisible)
        root.setKeyboardSizeProfile(inputScheme.preferences.toKeyboardSizeProfile())
        val view = surface.keyboardView
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            root.preferredKeyboardHeightPx(),
        )
        view.keyListener = SenseKeyboardView.KeyListener(::handleKey)
        view.candidateListener = ::commitCandidate
        view.associationDismissListener = ::dismissIdleAssociations
        view.textListener = ::commitText
        view.clipboardActionListener = ::handleClipboardAction
        view.editorActionListener = ::handleEditorAction
        view.settingsActionListener = ::openSenseHome
        view.agentActionListener = ::openAgentFront
        view.t9SideSymbolSettingsListener = ::openT9SideSymbolSettings
        view.inputSchemeSelectionListener =
            KeyboardInputSchemeSelectionListener(::handleInputSchemeSelection)
        view.t9PinyinChoiceSelectionListener =
            T9PinyinChoiceSelectionListener(::handleT9PinyinChoiceSelection)
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

            override fun onAiResultAction(
                generation: Long,
                action: AiResultActionType,
            ) {
                val currentView = keyboardView ?: return
                when (action) {
                    AiResultActionType.APPLY -> {
                        if (aiCoordinator.applyAssistantResult(generation)) {
                            currentView.exitAiSurface(generation)
                        }
                    }
                    AiResultActionType.COPY -> {
                        val answer = aiCoordinator.assistantResult(generation) ?: return
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText("Sense Agent result", answer),
                        )
                        aiCoordinator.dismissAssistantResult(generation)
                        currentView.exitAiSurface(generation)
                        Toast.makeText(this@SenseInputMethodService, "已复制 Agent 回答", Toast.LENGTH_SHORT)
                            .show()
                    }
                    AiResultActionType.DISMISS -> {
                        aiCoordinator.dismissAssistantResult(generation)
                        currentView.exitAiSurface(generation)
                    }
                }
            }
        }
        view.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        view.setShiftState(
            shifted = englishShiftState.uppercase,
            capsLocked = englishShiftState.capsLocked,
        )
        view.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
        view.setT9SideSymbols(inputScheme.preferences.t9SideSymbols)
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
        // Attach only the light projection observer. Provider, terminal and browser work still
        // live in :brain and are created only after an Agent run is explicitly started.
        ensureAgentRuntime()
        restoreAgentFront()
    }

    private fun openSenseHome() = openSenseSettings(initialSection = null)

    private fun openAgentFront() {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.CALLER_REQUESTED)
        if (!commitActiveRawComposition()) return
        ensureAgentRuntime()
        agentFrontVisible = true
        agentComposerVisible = false
        agentHistoryVisible = false
        agentMenuVisible = false
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        imeRoot?.showAgent(agentUiState(), agentUiActions, composing = false)
    }

    private val agentUiActions by lazy(LazyThreadSafetyMode.NONE) {
        AgentUiActions(
            onOpen = ::openAgentFront,
            onClose = ::closeAgentFront,
            onCloseComposer = ::finishAgentComposing,
            onHistory = ::toggleAgentHistory,
            onMore = {
                agentMenuVisible = !agentMenuVisible
                publishAgentUi()
            },
            onDismissMenu = {
                agentMenuVisible = false
                publishAgentUi()
            },
            onComposerTap = ::beginAgentComposing,
            onSend = ::sendAgentDraft,
            onStop = {
                submitAgentCommand { runtime, callback -> runtime.stopAsync(callback) }
            },
            onNewChat = ::startNewAgentConversation,
            onAdd = ::attachCurrentSelectionToAgentDraft,
            onSlash = {
                beginAgentComposing()
                commitToAgentDraft("/")
            },
            onVoice = {
                beginAgentComposing()
                openVoiceInput()
            },
            onToolPrevious = {
                selectedAgentToolIndex = (selectedAgentToolIndex - 1).coerceAtLeast(0)
                publishAgentUi()
            },
            onToolNext = {
                if (agentProjection.tools.isNotEmpty()) {
                    selectedAgentToolIndex =
                        (selectedAgentToolIndex + 1).coerceAtMost(agentProjection.tools.lastIndex)
                }
                publishAgentUi()
            },
            onToolOpen = { tool ->
                agentMenuVisible = false
                openAgentToolId = tool.id
                publishAgentUi()
            },
            onToolClose = {
                openAgentToolId = null
                publishAgentUi()
            },
            onCopyMessage = { message ->
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Sense Agent message", message.text),
                )
                Toast.makeText(this, "已复制回答", Toast.LENGTH_SHORT).show()
            },
            onInsertMessage = { message ->
                breakAssociationSequence()
                if (AgentExternalEditorWriter.insert(currentInputConnection, message.text)) {
                    closeAgentFront()
                    Toast.makeText(this, "已写入当前输入框", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "当前输入框未接受写入", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenConversation = { conversationId ->
                submitAgentCommand(
                    submit = { runtime, callback ->
                        runtime.openConversationAsync(conversationId, callback)
                    },
                    onAccepted = {
                        agentHistoryVisible = false
                        publishAgentUi()
                    },
                )
            },
            onGoldQuote = {
                submitAgentCommand { runtime, callback -> runtime.runGoldQuoteAsync(callback) }
            },
            onCancelAction = {
                submitAgentCommand { runtime, callback -> runtime.cancelActionAsync(callback) }
            },
            onDismissAction = {
                submitAgentCommand { runtime, callback -> runtime.dismissActionAsync(callback) }
            },
            onInsertAction = { action ->
                breakAssociationSequence()
                if (AgentExternalEditorWriter.insert(currentInputConnection, action.insertText)) {
                    closeAgentFront()
                    Toast.makeText(this, "行情已写入当前输入框", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "当前输入框未接受写入", Toast.LENGTH_SHORT).show()
                }
            },
            onAnalyzeAction = { action ->
                val prompt = "请结合上下文分析这条实时行情：${action.insertText}"
                submitAgentCommand { runtime, callback -> runtime.sendAsync(prompt, callback) }
            },
        )
    }

    private fun submitAgentCommand(
        onAccepted: (AgentHubCommandOutcome) -> Unit = {},
        submit: (AgentHubPort, AgentHubCommandCallback) -> AgentHubCommandHandle,
    ) {
        ensureAgentRuntime()
        val runtime = agentRuntime ?: return
        val lifecycle = agentCommandLifecycle
        var completedInline = false
        val callback = AgentHubCommandCallback { outcome ->
            completedInline = true
            agentCommandHandles.remove(outcome.clientCommandId)
            if (destroyed || lifecycle != agentCommandLifecycle) return@AgentHubCommandCallback
            when (outcome.code) {
                AgentHubCommandOutcomeCode.ACCEPTED -> onAccepted(outcome)
                AgentHubCommandOutcomeCode.REJECTED -> Toast.makeText(
                    this,
                    "Agent 请求未接受，请稍后重试",
                    Toast.LENGTH_SHORT,
                ).show()
                AgentHubCommandOutcomeCode.CANCELLED -> Toast.makeText(
                    this,
                    "Agent 请求已取消",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val handle = runCatching { submit(runtime, callback) }.getOrElse {
            Toast.makeText(this, "Agent 请求发送失败", Toast.LENGTH_SHORT).show()
            return
        }
        if (!completedInline) agentCommandHandles[handle.clientCommandId] = handle
    }

    private fun ensureAgentRuntime() {
        if (agentRuntime == null) {
            agentRuntime = RemoteSenseAgentHubClient.get(applicationContext)
        }
        if (agentSubscription != null) return
        agentSubscription = agentRuntime?.observe(
            AgentHubObserver { projection ->
                agentProjection = projection
                selectedAgentToolIndex = when {
                    projection.tools.isEmpty() -> 0
                    projection.tools.any { it.state == AgentHubToolState.RUNNING } ->
                        projection.tools.indexOfLast { it.state == AgentHubToolState.RUNNING }
                    else -> selectedAgentToolIndex.coerceIn(0, projection.tools.lastIndex)
                }
                if (projection.tools.none { it.id == openAgentToolId }) {
                    openAgentToolId = null
                }
                publishAgentUi()
            },
        )
    }

    private fun agentUiState(): AgentUiState {
        val title = agentProjection.messages
            .firstOrNull { it.role == AgentHubMessageRole.USER }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(18)
            ?.takeIf(String::isNotBlank)
            ?: "问候与求助"
        return AgentUiState(
            revision = agentProjection.revision,
            title = title,
            modelGroup = "Default Models",
            modelLabel = "Sense · 当前模型",
            loaded = agentProjection.loaded,
            running = agentProjection.running,
            status = agentProjection.status,
            messages = agentProjection.messages.map { message ->
                AgentMessageUi(
                    id = "${message.createdAtEpochMs}-${message.role}-${message.text.hashCode()}",
                    role = when (message.role) {
                        AgentHubMessageRole.USER -> AgentMessageRole.USER
                        AgentHubMessageRole.ASSISTANT -> AgentMessageRole.ASSISTANT
                    },
                    text = message.text,
                )
            },
            streamingText = agentProjection.preview,
            tools = agentProjection.tools.map { tool ->
                AgentToolUi(
                    id = tool.id,
                    kind = when (tool.toolName) {
                        "terminal_exec" -> AgentToolKind.TERMINAL
                        "browser_use" -> AgentToolKind.BROWSER
                        else -> AgentToolKind.GENERIC
                    },
                    title = when {
                        tool.state == AgentHubToolState.RUNNING &&
                            tool.toolName == "terminal_exec" -> "正在运行终端"
                        tool.state == AgentHubToolState.RUNNING &&
                            tool.toolName == "browser_use" -> "正在控制浏览器"
                        else -> tool.title
                    },
                    detail = tool.detail,
                    state = when (tool.state) {
                        AgentHubToolState.RUNNING -> AgentToolState.RUNNING
                        AgentHubToolState.COMPLETED -> AgentToolState.SUCCEEDED
                        AgentHubToolState.FAILED -> AgentToolState.FAILED
                    },
                )
            },
            selectedToolIndex = selectedAgentToolIndex,
            draft = agentDraft.displayText(agentDraftComposition),
            draftCursor = agentDraft.cursor + agentDraftComposition.length,
            composing = imeRoot?.mode == ImeFrontMode.AGENT_COMPOSING,
            inputTokens = agentProjection.inputTokens,
            outputTokens = agentProjection.outputTokens,
            historyVisible = agentHistoryVisible,
            menuVisible = agentMenuVisible,
            openToolId = openAgentToolId,
            conversations = agentProjection.conversations.map { conversation ->
                AgentConversationUi(
                    id = conversation.id,
                    title = conversation.title,
                    preview = conversation.preview,
                    messageCount = conversation.messageCount,
                    current = conversation.current,
                )
            },
            action = agentProjection.action?.let { action ->
                AgentActionUi(
                    requestId = action.requestId,
                    skillId = action.skillId,
                    title = action.title,
                    primaryValue = action.primaryValue,
                    secondaryValue = action.secondaryValue,
                    insertText = action.insertText,
                    sourceLabel = action.sourceLabel,
                    state = when (action.state) {
                        AgentHubActionState.RUNNING -> AgentActionState.RUNNING
                        AgentHubActionState.SUCCEEDED -> AgentActionState.SUCCEEDED
                        AgentHubActionState.FAILED -> AgentActionState.FAILED
                        AgentHubActionState.CANCELLED -> AgentActionState.CANCELLED
                    },
                    detail = action.detail,
                )
            },
            actionSkillsEnabled = agentProjection.actionSkillsEnabled,
        )
    }

    private fun publishAgentUi() {
        imeRoot?.renderAgent(agentUiState(), agentUiActions)
    }

    private fun beginAgentComposing() {
        ensureAgentRuntime()
        breakAssociationSequence()
        agentFrontVisible = true
        agentComposerVisible = true
        agentHistoryVisible = false
        agentMenuVisible = false
        openAgentToolId = null
        imeRoot?.showAgent(agentUiState(), agentUiActions, composing = true)
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun finishAgentComposing() {
        if (!isAgentTextTarget()) return
        if (!commitActiveRawComposition()) return
        breakAssociationSequence()
        agentComposerVisible = false
        imeRoot?.showAgent(agentUiState(), agentUiActions, composing = false)
    }

    private fun closeAgentFront() {
        if (isAgentTextTarget() && !commitActiveRawComposition()) return
        breakAssociationSequence()
        agentFrontVisible = false
        agentComposerVisible = false
        agentHistoryVisible = false
        agentMenuVisible = false
        openAgentToolId = null
        imeRoot?.showKeyboard()
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun sendAgentDraft() {
        if (isAgentTextTarget() && !commitActiveRawComposition()) return
        val message = agentDraft.text.trim()
        if (message.isEmpty()) return
        submitAgentCommand(
            submit = { runtime, callback -> runtime.sendAsync(message, callback) },
            onAccepted = {
                // An ACK may arrive after the user has edited a new draft. Only consume the exact
                // text accepted by :brain; newer IME input remains untouched.
                if (agentDraft.text.trim() == message && agentDraftComposition.isEmpty()) {
                    breakAssociationSequence()
                    agentDraft.clear()
                    agentComposerVisible = false
                    imeRoot?.showAgent(agentUiState(), agentUiActions, composing = false)
                }
            },
        )
    }

    private fun startNewAgentConversation() {
        if (agentProjection.running) return
        if (isAgentTextTarget() && !commitActiveRawComposition()) return
        val draftAtSubmit = agentDraft.text
        submitAgentCommand(
            submit = { runtime, callback -> runtime.clearConversationAsync(callback) },
            onAccepted = {
                breakAssociationSequence()
                if (agentDraft.text == draftAtSubmit && agentDraftComposition.isEmpty()) {
                    agentDraft.clear()
                }
                selectedAgentToolIndex = 0
                agentHistoryVisible = false
                agentMenuVisible = false
                openAgentToolId = null
                publishAgentUi()
            },
        )
    }

    private fun toggleAgentHistory() {
        if (isAgentTextTarget() && !commitActiveRawComposition()) return
        agentMenuVisible = false
        openAgentToolId = null
        agentHistoryVisible = !agentHistoryVisible
        agentFrontVisible = true
        agentComposerVisible = false
        imeRoot?.showAgent(agentUiState(), agentUiActions, composing = false)
    }

    private fun restoreAgentFront() {
        val root = imeRoot ?: return
        if (agentFrontVisible) {
            root.showAgent(
                state = agentUiState(),
                actions = agentUiActions,
                composing = agentComposerVisible,
            )
        } else {
            root.showKeyboard()
        }
    }

    private fun attachCurrentSelectionToAgentDraft() {
        beginAgentComposing()
        val selection = runCatching {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        }.getOrDefault("")
        if (selection.isBlank()) {
            Toast.makeText(this, "当前输入框没有选中文字", Toast.LENGTH_SHORT).show()
            return
        }
        commitToAgentDraft("\n\n> 当前选区\n$selection\n\n")
    }

    private fun commitToAgentDraft(text: String): Boolean {
        agentDraftComposition = ""
        val accepted = agentDraft.insert(text)
        publishAgentUi()
        return accepted
    }

    private fun isAgentTextTarget(): Boolean =
        imeRoot?.mode == ImeFrontMode.AGENT_COMPOSING

    private fun openT9SideSymbolSettings() =
        openSenseSettings(initialSection = ImeSettingsRoute.KEYBOARD_SECTION)

    private fun openSenseSettings(initialSection: String?) {
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.CALLER_REQUESTED)
        val launchIntent = Intent()
            .setClassName(packageName, SENSE_SETTINGS_ACTIVITY)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .apply {
                if (initialSection != null) {
                    putExtra(ImeSettingsRoute.EXTRA_INITIAL_SECTION, initialSection)
                }
            }
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
        if (isAgentTextTarget()) commitActiveRawComposition()
        restoreAgentFront()
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
        keyboardView?.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        reconcileAgentSkillsAndWatcher()
        render()
        restoreAgentFront()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        imeWindowVisible = true
        imeRoot?.setImeWindowVisible(true) ?: keyboardSurface?.setImeWindowVisible(true)
        render()
        /*
         * onStartInputView owns the watcher epoch rebuild. Window visibility still reconciles a
         * potentially missed CURRENT event, but must not immediately stop/start the fresh watch.
         */
        refreshAgentSkills()
        restoreAgentFront()
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
        val acknowledgesOwnCommit = selectionChanged && reliableCommitSelectionFence.acknowledge(
            editorSessionId = editorSessionId,
            connectionIdentity = currentInputConnection,
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
        )
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
            !reliableCommitGuard.isActive &&
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
        if (
            selectionChanged &&
            !hasAnyComposition() &&
            !reliableCommitGuard.isActive &&
            !compositionUpdateGuard.isActive &&
            !acknowledgesOwnCommit
        ) {
            breakAssociationSequence()
            render()
        }
    }

    override fun onDestroy() {
        destroyed = true
        imeWindowVisible = false
        imeRoot?.setImeWindowVisible(false) ?: keyboardSurface?.setImeWindowVisible(false)
        pendingDecodeCommit.clearAll()
        wubiOverflow.clear()
        mainHandler.removeCallbacks(pendingCommitTimeout)
        mainHandler.removeCallbacksAndMessages(candidateResultToken)
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        mainHandler.removeCallbacksAndMessages(associationDisplayToken)
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
        userAssociationLexicon.close()
        associationPersistenceReady = false
        pendingAssociationObservations.clear()
        pendingPinyinLearnings.clear()
        associationSession.clear()
        associationDisplayLifecycle.cancel()
        commitSequenceTracker.breakSequence()
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
        agentCommandLifecycle = nextGeneration(agentCommandLifecycle)
        agentCommandHandles.values.toList().forEach(AgentHubCommandHandle::close)
        agentCommandHandles.clear()
        agentSubscription?.close()
        agentSubscription = null
        /*
         * close() appends FileObserver teardown after every already-accepted read/mutation. A
         * graceful shutdown lets that FIFO drain without waiting on the IME main thread.
         */
        agentSkillIo.shutdown()
        aiApplicationToken = null
        imeRoot?.release()
        keyboardView = null
        keyboardSurface = null
        imeRoot = null
        super.onDestroy()
    }

    override fun onWindowHidden() {
        imeWindowVisible = false
        cancelAssociationPresentation()
        imeRoot?.setImeWindowVisible(false) ?: keyboardSurface?.setImeWindowVisible(false)
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.WINDOW_HIDDEN)
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val removedAssociation = cancelAssociationPresentation()
        cancelVoiceSession(exitSurface = true)
        cancelAndExitAi(HarnessCancelReason.CONFIGURATION_CHANGED)
        super.onConfigurationChanged(newConfig)
        if (removedAssociation) render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelAssociationPresentation()
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
        dismissAssociationForInteraction()
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
        if (isAgentTextTarget()) {
            when (code) {
                KeyCodes.HIDE -> {
                    finishAgentComposing()
                    return
                }
                KeyCodes.UNDO -> {
                    if (commitActiveRawComposition() && agentDraft.undo()) {
                        breakAssociationSequence()
                        publishAgentUi()
                    }
                    return
                }
                KeyCodes.REDO -> {
                    if (commitActiveRawComposition() && agentDraft.redo()) {
                        breakAssociationSequence()
                        publishAgentUi()
                    }
                    return
                }
            }
        }
        when (code) {
            KeyCodes.SHIFT -> {
                setEnglishShiftState(englishShiftState.onShiftPressed())
            }

            KeyCodes.DELETE -> handleBackspace()
            KeyCodes.T9_REINPUT -> handleT9Reinput()
            KeyCodes.SPACE -> handleSpace()
            KeyCodes.COMMA -> commitText(if (chineseMode) "，" else ",")
            KeyCodes.PERIOD -> commitText(if (chineseMode) "。" else ".")
            KeyCodes.UNDO -> performEditorHistoryCommand(
                actionId = android.R.id.undo,
                fallbackKeyCode = KeyEvent.KEYCODE_Z,
                fallbackMetaState = KeyEvent.META_CTRL_ON,
            )
            KeyCodes.REDO -> performEditorHistoryCommand(
                actionId = android.R.id.redo,
                fallbackKeyCode = KeyEvent.KEYCODE_Z,
                fallbackMetaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                secondaryFallbackKeyCode = KeyEvent.KEYCODE_Y,
                secondaryFallbackMetaState = KeyEvent.META_CTRL_ON,
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
            prepareTextReplacementFeedback()
        } else {
            null
        }
        if (!chineseMode) {
            val output = englishShiftState.applyTo(character)
            if (output.lowercaseChar() in 'a'..'z') {
                if (!englishCompositionEdits.type(output)) return
                completeReplacementFeedback(replacementFeedback)
                setEnglishShiftState(englishShiftState.afterAcceptedLetter())
                render()
            } else {
                commitText(output.toString())
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
                presentT9 = ::t9EditorCompositionText,
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
            if (deleteOneCodePointOrSelection()) {
                breakAssociationSequence()
                render()
            }
        }
    }

    private fun handleAlternativeBackspace() {
        if (
            inputScheme.backspace(
                publish = ::updateConnectionComposition,
                presentT9 = ::t9EditorCompositionText,
            )
        ) {
            render()
        }
    }

    private fun handleT9Reinput() {
        if (!chineseMode) return
        if (!inputScheme.clearT9Composition(::updateConnectionComposition)) return
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        clearPendingCommit()
        finishActiveComposingText()
        render()
    }

    private fun handleT9PinyinChoiceSelection(revision: Long, index: Int) {
        if (!chineseMode || inputScheme.scheme != ChineseInputScheme.PINYIN_T9) return
        if (
            !inputScheme.selectT9PinyinChoice(
                presentationRevision = revision,
                sourceIndex = index,
                publish = ::updateConnectionComposition,
                presentT9 = ::t9EditorCompositionText,
            )
        ) {
            return
        }
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        clearPendingCommit()
        render(forceDecode = true)
    }

    private fun handleInputSchemeSelection(choice: KeyboardInputSchemeChoice) {
        if (
            KeyboardInputSchemePreferencePlanner.plan(inputScheme.preferences, choice) ==
            KeyboardInputSchemePersistenceIntent.Unchanged
        ) {
            keyboardView?.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
            return
        }
        if (hasAnyComposition() && !commitActivePrimaryOrRaw()) {
            keyboardView?.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
            return
        }

        // Committing is a settings boundary and may apply a newer deferred snapshot. Plan again
        // from that state so an older pre-commit copy never overwrites another settings field.
        val intent = KeyboardInputSchemePreferencePlanner.plan(inputScheme.preferences, choice)
        if (intent !is KeyboardInputSchemePersistenceIntent.Persist) {
            keyboardView?.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
            return
        }
        val writeGeneration = nextGeneration(preferencesLoadGeneration)
        preferencesLoadGeneration = writeGeneration
        applyImePreferences(intent.preferences)
        preferencesIo.execute {
            val saved = imePreferencesStore.update { stored ->
                when (val currentIntent = KeyboardInputSchemePreferencePlanner.plan(stored, choice)) {
                    KeyboardInputSchemePersistenceIntent.Unchanged -> stored
                    is KeyboardInputSchemePersistenceIntent.Persist -> currentIntent.preferences
                }
            }
            mainHandler.post {
                if (destroyed || preferencesLoadGeneration != writeGeneration) return@post
                saved.onSuccess { persisted ->
                    acceptImePreferencesSnapshot(persisted)
                }.onFailure { error ->
                    Log.e(TAG, "IME preference save failed", error)
                }
            }
        }
    }

    private fun handleSpace() {
        if (!chineseMode) {
            if (englishInput.composing.isNotEmpty()) {
                if (!commitEnglishComposition(englishInput.defaultCommitCandidate)) return
                commitToActiveTextTarget(" ")
            } else {
                val feedback =
                    prepareTextReplacementFeedback()
                if (commitToActiveTextTarget(" ")) {
                    completeReplacementFeedback(feedback)
                    breakAssociationSequence()
                    render()
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
                prepareTextReplacementFeedback()
            if (commitToActiveTextTarget(" ")) {
                completeReplacementFeedback(feedback)
                breakAssociationSequence()
                render()
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
                prepareTextReplacementFeedback()
            if (commitToActiveTextTarget(" ")) {
                completeReplacementFeedback(feedback)
                breakAssociationSequence()
                render()
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
        if (isAgentTextTarget()) {
            if (hasAnyComposition()) {
                commitActiveRawComposition()
            } else {
                sendAgentDraft()
            }
            return
        }
        if (!chineseMode && englishInput.composing.isNotEmpty()) {
            commitEnglishComposition(candidate = null)
        } else if (chineseMode && hasActiveChineseComposition()) {
            // Enter confirms exactly what the user can see. It never auto-selects
            // a Chinese candidate and does not append a newline in this branch.
            val englishSelection = if (
                inputScheme.scheme == ChineseInputScheme.PINYIN_QWERTY &&
                composition.acceptedSegments.isEmpty()
            ) {
                ChineseEnglishEnterPolicy.select(
                    rawInput = composition.remainingPinyin,
                    candidates = currentDecoding()?.wholeCandidates.orEmpty(),
                )
            } else {
                null
            }
            if (englishSelection == null) {
                commitActiveRawComposition()
            } else {
                commitPrimary(
                    englishSelection.candidate,
                    UserLearningEvidence(
                        UserSelectionKind.EXPLICIT_SELECTION,
                        englishSelection.candidateRank,
                    ),
                )
            }
        } else {
            val feedback =
                prepareTextReplacementFeedback()
            if (sendDefaultEditorAction(true)) {
                personalizationFeedback.complete(feedback)
                breakAssociationSequence()
                render()
            } else if (currentInputConnection?.commitText("\n", 1) == true) {
                completeReplacementFeedback(feedback)
                breakAssociationSequence()
                render()
            }
        }
    }

    private fun commitCandidate(revision: Long, sourceIndex: Int) {
        if (!chineseMode) {
            englishInput.select(revision, sourceIndex)?.let(::commitEnglishComposition)
            return
        }
        if (!hasActiveChineseComposition()) {
            associationSession.select(
                requestedRevision = revision,
                sourceIndex = sourceIndex,
                editorSessionId = editorSessionId,
                connectionIdentity = activeAssociationConnectionIdentity(),
                context = captureAssociationContext(),
            )?.let(::commitAssociationCandidate)
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
                if (localPersistenceAllowed) progressiveLearnings.add(learning)
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
            prepareTextReplacementFeedback()
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
            prepareTextExpirationFeedback()
        } else {
            replacementFeedback
        }
        if (commitToActiveTextTarget(text)) {
            completeReplacementFeedback(feedback)
            breakAssociationSequence()
            render()
            // A punctuation/clipboard/tool commit after a learned word becomes the
            // newest host edit, so a later Backspace must not demote the older word.
        }
    }

    private fun toggleLanguage() {
        if (!commitActiveRawComposition()) return
        chineseMode = !chineseMode
        resetEnglishShiftState()
        keyboardView?.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
        render()
    }

    private fun setEnglishShiftState(next: EnglishShiftState) {
        if (englishShiftState == next) return
        englishShiftState = next
        keyboardView?.setShiftState(
            shifted = next.uppercase,
            capsLocked = next.capsLocked,
        )
    }

    private fun resetEnglishShiftState() {
        setEnglishShiftState(EnglishShiftState.LOWERCASE)
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
        if (isAgentTextTarget()) {
            editorSelectionState = EditorSelectionState(
                hasSelection = false,
            )
            publishEditorSelectionState()
            keyboardView?.setPanel(SenseKeyboardView.Panel.EDITOR)
            return
        }
        editorSelectionState = EditorSelectionState(
            hasSelection = hasHostSelection(selectionStart, selectionEnd),
        )
        publishEditorSelectionState()
        keyboardView?.setPanel(SenseKeyboardView.Panel.EDITOR)
    }

    private fun handleEditorAction(action: SenseKeyboardView.EditorAction) {
        if (isAgentTextTarget()) {
            handleAgentDraftEditorAction(action)
            return
        }
        when (action) {
            SenseKeyboardView.EditorAction.BACK -> {
                editorSelectionState = editorSelectionState.resetSelectionMode()
                publishEditorSelectionState()
                keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
            }

            SenseKeyboardView.EditorAction.TOGGLE_SELECTION -> {
                breakAssociationSequence()
                editorSelectionState = editorSelectionState.toggleSelectionMode()
                publishEditorSelectionState()
            }

            SenseKeyboardView.EditorAction.UP -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_UP)
            SenseKeyboardView.EditorAction.LEFT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_LEFT)
            SenseKeyboardView.EditorAction.RIGHT -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            SenseKeyboardView.EditorAction.DOWN -> sendDirectionalKey(KeyEvent.KEYCODE_DPAD_DOWN)
            SenseKeyboardView.EditorAction.HOME -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_HOME)
            SenseKeyboardView.EditorAction.END -> sendDirectionalKey(KeyEvent.KEYCODE_MOVE_END)
            SenseKeyboardView.EditorAction.DELETE -> {
                if (deleteOneCodePointOrSelection()) {
                    breakAssociationSequence()
                    render()
                }
            }
            SenseKeyboardView.EditorAction.COPY ->
                performEditorContextCommand(EditorContextCommand.COPY, android.R.id.copy)

            SenseKeyboardView.EditorAction.CUT ->
                performEditorContextCommand(EditorContextCommand.CUT, android.R.id.cut)

            SenseKeyboardView.EditorAction.PASTE ->
                performEditorContextCommand(EditorContextCommand.PASTE, android.R.id.paste)

            SenseKeyboardView.EditorAction.SELECT_ALL -> {
                breakAssociationSequence()
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
        }
    }

    private fun handleAgentDraftEditorAction(action: SenseKeyboardView.EditorAction) {
        var changed = false
        when (action) {
            SenseKeyboardView.EditorAction.BACK -> {
                keyboardView?.setPanel(SenseKeyboardView.Panel.LETTERS)
                return
            }
            SenseKeyboardView.EditorAction.UP,
            SenseKeyboardView.EditorAction.HOME,
            -> changed = agentDraft.moveHome()
            SenseKeyboardView.EditorAction.DOWN,
            SenseKeyboardView.EditorAction.END,
            -> changed = agentDraft.moveEnd()
            SenseKeyboardView.EditorAction.LEFT -> changed = agentDraft.moveCursor(-1)
            SenseKeyboardView.EditorAction.RIGHT -> changed = agentDraft.moveCursor(1)
            SenseKeyboardView.EditorAction.DELETE -> changed = agentDraft.deleteBackward()
            SenseKeyboardView.EditorAction.COPY -> {
                if (agentDraft.text.isNotEmpty()) {
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText("Sense Agent draft", agentDraft.text),
                    )
                    Toast.makeText(this, R.string.editor_copied, Toast.LENGTH_SHORT).show()
                }
            }
            SenseKeyboardView.EditorAction.CUT -> {
                if (agentDraft.text.isNotEmpty()) {
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText("Sense Agent draft", agentDraft.text),
                    )
                    changed = agentDraft.clear()
                    Toast.makeText(this, R.string.editor_cut, Toast.LENGTH_SHORT).show()
                }
            }
            SenseKeyboardView.EditorAction.PASTE -> {
                val clip = clipboardManager.primaryClip
                val value = clip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.toString()
                    .orEmpty()
                if (value.isNotEmpty()) changed = agentDraft.insert(value)
            }
            SenseKeyboardView.EditorAction.TOGGLE_SELECTION,
            SenseKeyboardView.EditorAction.SELECT_ALL,
            -> Toast.makeText(this, "Agent 草稿当前按光标编辑", Toast.LENGTH_SHORT).show()
        }
        if (changed) {
            breakAssociationSequence()
            publishAgentUi()
            render()
        }
    }

    private fun sendDirectionalKey(keyCode: Int) {
        breakAssociationSequence()
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
        if (accepted) {
            breakAssociationSequence()
            render()
        }
        val feedback = EditorHistoryFeedbackPolicy.afterAttempt(accepted)
        keyboardView?.let { view ->
            view.performHapticFeedback(
                when (feedback.haptic) {
                    EditorHistoryHaptic.CONFIRM -> HapticFeedbackConstants.KEYBOARD_TAP
                    EditorHistoryHaptic.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.REJECT
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                },
            )
        }
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
        if (isAgentTextTarget()) {
            val deleted = agentDraft.deleteBackward()
            if (deleted) publishAgentUi()
            return deleted
        }
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

    private fun prepareTextReplacementFeedback(): PersonalizationFeedbackWindow.Attempt? =
        if (isAgentTextTarget()) {
            null
        } else {
            personalizationFeedback.prepareReplacement(selectionStart, selectionEnd)
        }

    private fun prepareTextExpirationFeedback(): PersonalizationFeedbackWindow.Attempt? =
        if (isAgentTextTarget()) null else personalizationFeedback.prepareExpiration()

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
                -> {
                    completeReplacementFeedback(feedback)
                    breakAssociationSequence()
                    render()
                }

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
        val feedback = prepareTextExpirationFeedback()
        val committed = commitToActiveTextTarget(text)
        if (committed) {
            personalizationFeedback.complete(feedback)
            breakAssociationSequence()
            render()
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
        breakAssociationSequence()
        englishInput.reset()
        clearPendingCommit()
        resetEnglishShiftState()
        if (finishConnection) finishActiveComposingText()
        render()
    }

    private fun render(forceDecode: Boolean = false) {
        inputScheme.takePendingPreferences(compositionActive = hasAnyComposition())?.let { value ->
            applyImePreferences(value)
            return
        }
        if (!chineseMode) {
            associationSession.clear()
            keyboardView?.updateComposing(
                englishInput.revision,
                englishInput.composing,
                englishInput.candidates.map { it.text },
            )
            return
        }
        if (!hasActiveChineseComposition()) {
            renderIdleAssociations()
            return
        }
        associationSession.clear()
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

    private fun renderIdleAssociations() {
        mainHandler.removeCallbacksAndMessages(candidateResultToken)
        mainHandler.removeCallbacksAndMessages(alternativeCandidateResultToken)
        candidateSession.begin(
            composition = composition,
            decoderGeneration = decoderRuntime.generation,
        )
        if (!associationDisplayLifecycle.visible || !associationSurfaceEligible()) {
            if (associationDisplayLifecycle.visible) cancelAssociationPresentation()
            associationSession.clear()
            publishEmptyIdleCandidates()
            return
        }
        val context = captureAssociationContext()
        val suggestions = if (decodeContextAllowed && context.isNotEmpty()) {
            associationEngine.suggest(
                leftContext = context,
                limit = ASSOCIATION_CANDIDATE_LIMIT,
                includeUserHistory = localPersistenceAllowed,
            )
        } else {
            emptyList()
        }
        if (suggestions.isEmpty()) {
            cancelAssociationPresentation()
            associationSession.clear()
            publishEmptyIdleCandidates()
            return
        }
        val presentation = associationSession.publish(
            editorSessionId = editorSessionId,
            connectionIdentity = activeAssociationConnectionIdentity(),
            context = context,
            suggestions = suggestions,
        )
        keyboardView?.updateAssociations(
            presentation.revision,
            suggestions.map(AssociationSuggestion::text),
        )
    }

    private fun publishEmptyIdleCandidates() {
        if (inputScheme.scheme == ChineseInputScheme.PINYIN_T9) {
            keyboardView?.updateT9Composing(
                revision = activePresentationRevision(),
                text = "",
                values = emptyList(),
                choices = emptyList(),
            )
        } else {
            keyboardView?.updateComposing(
                composition.revision,
                "",
                emptyList(),
            )
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
        val label = if (key.scheme == ChineseInputScheme.PINYIN_T9) {
            inputScheme.editorComposingText
        } else {
            ready?.composingLabel ?: inputScheme.editorComposingText
        }
        if (key.scheme == ChineseInputScheme.PINYIN_T9) {
            val railChoices = ready?.uiT9PinyinChoices()
                ?: request.t9Composition
                    ?.let { composition ->
                        request.t9Index.choices(composition, T9_PINYIN_CHOICE_LIMIT)
                    }
                    .orEmpty()
                    .uiT9PinyinChoices()
            keyboardView?.updateT9Composing(
                revision = key.presentationRevision,
                text = label,
                values = if (launch.presentation.pending) {
                    null
                } else {
                    ready?.candidateLabels.orEmpty()
                },
                choices = railChoices,
            )
        } else if (launch.presentation.pending) {
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
        if (request.key.scheme == ChineseInputScheme.PINYIN_T9) {
            keyboardView?.updateT9Composing(
                revision = request.key.presentationRevision,
                text = inputScheme.editorComposingText,
                values = presentation.decoding?.candidateLabels.orEmpty(),
                choices = decoding.uiT9PinyinChoices(),
            )
        } else {
            keyboardView?.updateComposing(
                request.key.presentationRevision,
                decoding.composingLabel,
                presentation.decoding?.candidateLabels.orEmpty(),
            )
        }
    }

    private fun currentAlternativeDecoding(): AlternativeDecoding? = inputScheme.currentDecoding()

    private fun fallbackAlternativeComposingLabel(request: AlternativeDecodeRequest): String =
        if (request.key.scheme == ChineseInputScheme.PINYIN_T9) {
            request.t9Composition
                ?.let { composition ->
                    request.t9Index.paths(composition, T9_EDITOR_PATH_LIMIT)
                        .firstOrNull()
                        ?.formatted
                }
                ?: fallbackT9EditorText(request.t9Composition)
        } else {
            request.key.rawCode
        }

    private fun AlternativeDecoding.uiT9PinyinChoices(): List<UiT9PinyinChoice> =
        t9PinyinChoices.uiT9PinyinChoices()

    private fun List<CoreT9PinyinChoice>.uiT9PinyinChoices(): List<UiT9PinyinChoice> =
        take(T9_PINYIN_CHOICE_LIMIT).map { choice ->
            UiT9PinyinChoice(
                canonical = choice.canonicalPinyin,
                preview = choice.previewPinyin,
            )
        }

    private fun t9EditorCompositionText(composition: T9Composition): String {
        if (composition.rawDigits.isEmpty()) return ""
        return decoderRuntime.t9Index.paths(composition, T9_EDITOR_PATH_LIMIT)
            .firstOrNull()
            ?.formatted
            ?: fallbackT9EditorText(composition)
    }

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
        val effectiveEvidence = PinyinCommitLearningPolicy.evidence(
            hasAcceptedSegments = composition.acceptedSegments.isNotEmpty(),
            requested = evidence,
        )
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
        val acceptedEnglish = candidate?.takeIf {
            it.matchKind == CandidateMatchKind.ENGLISH_EXACT ||
                it.matchKind == CandidateMatchKind.ENGLISH_PREFIX
        }
        val committed = commitComposition(
            output,
            rawInput,
            learnable,
            effectiveEvidence,
            composingLength,
        )
        if (committed && acceptedEnglish != null && localPersistenceAllowed) {
            englishLexicon.recordAccepted(output, effectiveEvidence)
        }
        return committed
    }

    private fun commitAlternativeCandidate(
        candidate: Candidate?,
        evidence: UserLearningEvidence = UserLearningEvidence.DEFAULT_ACCEPT,
    ): Boolean {
        val agentTarget = isAgentTextTarget()
        val connection = currentInputConnection.takeUnless { agentTarget }
        val commitSnapshot = AlternativeCommitSnapshot.capture(
            coordinator = inputScheme,
            hasCandidate = candidate != null,
            editorSessionId = editorSessionId,
            inputConnectionIdentity = connection,
        )
        val rawCode = commitSnapshot.rawCode
        if (rawCode.isEmpty()) return false
        val output = commitSnapshot.outputText(candidate?.text)
        val feedback = prepareTextExpirationFeedback()
        val wubiLearningTarget = wubiRuntime.candidateDecoder.takeIf {
            commitSnapshot.learningDomain == AlternativeLearningDomain.WUBI &&
                wubiLearningReady()
        }
        val pinyinLearningTarget = adaptiveDecoder.takeIf {
            commitSnapshot.learningDomain == AlternativeLearningDomain.PINYIN &&
                pinyinLearningReady()
        }
        val committedStart = when {
            agentTarget -> -1
            hasHostSelection(selectionStart, selectionEnd) -> minOf(selectionStart, selectionEnd)
            selectionStart >= 0 ->
                (selectionStart - commitSnapshot.editorComposingLength).coerceAtLeast(0)
            else -> -1
        }
        val committed = commitReliableLexicalText(
            text = output,
            expectedHostCursor = committedStart.takeIf { it >= 0 }?.plus(output.length),
        )
        if (!committed) {
            clearPendingCommit()
            return false
        }
        personalizationFeedback.complete(feedback)
        val sameEditor = !agentTarget &&
            commitSnapshot.isSameEditor(editorSessionId, currentInputConnection)
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
        } else {
            val pendingCandidate = candidate
            val pendingCanonical = pendingCandidate?.canonicalPinyin
            if (
                localPersistenceAllowed &&
                commitSnapshot.learningDomain == AlternativeLearningDomain.PINYIN &&
                !pendingCanonical.isNullOrEmpty()
            ) {
                pendingPinyinLearnings.add(
                    PendingPinyinLearning(
                        rawInput = pendingCanonical,
                        candidate = pendingCandidate,
                        evidence = evidence,
                    ),
                )
            }
        }
        recordReliableCommit(
            text = output,
            canonicalPinyin = candidate?.canonicalPinyin.takeIf {
                commitSnapshot.learningDomain == AlternativeLearningDomain.PINYIN
            },
            committedStart = committedStart,
            committedEnd = committedEnd,
        )
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
        resetEnglishShiftState()
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
        val feedback = prepareTextExpirationFeedback()
        val committed = commitReliableLexicalText(output, expectedHostCursor = null)
        if (!committed) return false
        personalizationFeedback.complete(feedback)
        englishInput.reset()
        breakAssociationSequence()
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
        val agentTarget = isAgentTextTarget()
        val connection = currentInputConnection.takeUnless { agentTarget }
        val committedStart = when {
            agentTarget -> -1
            hasHostSelection(selectionStart, selectionEnd) -> minOf(selectionStart, selectionEnd)
            selectionStart >= 0 -> (selectionStart - composingLength).coerceAtLeast(0)
            else -> -1
        }
        val committed = commitReliableLexicalText(
            text = output,
            expectedHostCursor = committedStart.takeIf { it >= 0 }?.plus(output.length),
        )
        if (!committed) {
            if (composition == committingComposition) {
                clearPendingCommit()
            }
            return false
        }
        val committedEnd = committedStart.takeIf { it >= 0 }?.plus(output.length) ?: -1
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
                    val sameEditor = !agentTarget &&
                        editorSessionId == committedEditorSessionId &&
                            currentInputConnection === connection
                    if (sameEditor) {
                        personalizationFeedback.remember(
                            learned,
                            start = committedStart,
                            endExclusive = committedEnd,
                        )
                    }
                }
            }
        } else if (localPersistenceAllowed) {
            stagedProgressiveLearnings.forEach { pending ->
                pendingPinyinLearnings.add(
                    PendingPinyinLearning(
                        rawInput = pending.rawInput,
                        candidate = pending.candidate,
                        evidence = pending.evidence,
                    ),
                )
            }
            if (rawInput != null && learnable != null) {
                pendingPinyinLearnings.add(
                    PendingPinyinLearning(
                        rawInput = rawInput,
                        candidate = learnable,
                        evidence = evidence,
                    ),
                )
            }
        }
        recordReliableCommit(
            text = output,
            canonicalPinyin = rawInput,
            committedStart = committedStart,
            committedEnd = committedEnd,
        )
        if (composition == committingComposition) {
            composition = composition.reset()
            compositionLeftContext = ""
            progressiveLearnings.clear()
            clearPendingCommit()
            resetEnglishShiftState()
            render()
        }
        return true
    }

    private fun updateConnectionComposition(visibleText: String): Boolean {
        if (isAgentTextTarget()) {
            agentDraftComposition = visibleText
            publishAgentUi()
            return true
        }
        val update = editorComposingTextUpdate(visibleText)
        return compositionUpdateGuard.duringMutation {
            currentInputConnection?.setComposingText(update.text, update.newCursorPosition) == true
        }
    }

    private fun captureDecodeLeftContext(): String {
        if (!decodeContextAllowed) return ""
        if (isAgentTextTarget()) {
            return agentDraft.contextBeforeCursor(MAX_DECODE_CONTEXT_CHARS)
        }
        return runCatching {
            currentInputConnection
                ?.getTextBeforeCursor(MAX_DECODE_CONTEXT_CHARS, 0)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun captureAssociationContext(): String {
        if (!decodeContextAllowed) return ""
        return if (isAgentTextTarget()) {
            agentDraft.contextBeforeCursor(MAX_ASSOCIATION_CONTEXT_CHARS)
        } else {
            runCatching {
                currentInputConnection
                    ?.getTextBeforeCursor(MAX_ASSOCIATION_CONTEXT_CHARS, 0)
                    ?.toString()
                    .orEmpty()
            }.getOrDefault("")
        }
    }

    private fun activeAssociationConnectionIdentity(): Any? =
        if (isAgentTextTarget()) agentDraft else currentInputConnection

    private fun scheduleAssociationPresentation() {
        mainHandler.removeCallbacksAndMessages(associationDisplayToken)
        associationSession.clear()
        if (!chineseMode || !decodeContextAllowed) {
            associationDisplayLifecycle.cancel()
            return
        }
        val ticket = associationDisplayLifecycle.arm()
        mainHandler.postAtTime(
            { revealAssociationPresentation(ticket) },
            associationDisplayToken,
            SystemClock.uptimeMillis() + ASSOCIATION_REVEAL_DELAY_MS,
        )
    }

    private fun revealAssociationPresentation(ticket: Long) {
        if (!associationDisplayLifecycle.reveal(ticket)) return
        if (!associationSurfaceEligible()) {
            cancelAssociationPresentation()
            return
        }
        render()
        if (!associationDisplayLifecycle.visible || associationSession.current == null) return
        mainHandler.postAtTime(
            { expireAssociationPresentation(ticket) },
            associationDisplayToken,
            SystemClock.uptimeMillis() + ASSOCIATION_AUTO_HIDE_MS,
        )
    }

    private fun expireAssociationPresentation(ticket: Long) {
        if (!associationDisplayLifecycle.expire(ticket)) return
        associationSession.clear()
        render()
    }

    private fun dismissIdleAssociations() {
        if (cancelAssociationPresentation()) render()
    }

    private fun dismissAssociationForInteraction() {
        if (cancelAssociationPresentation()) render()
    }

    /** Returns whether a visible strip was removed and therefore needs repainting. */
    private fun cancelAssociationPresentation(): Boolean {
        val wasVisible = associationDisplayLifecycle.visible
        mainHandler.removeCallbacksAndMessages(associationDisplayToken)
        associationDisplayLifecycle.cancel()
        associationSession.clear()
        return wasVisible
    }

    private fun associationSurfaceEligible(): Boolean =
        !destroyed &&
            imeWindowVisible &&
            chineseMode &&
            !hasActiveChineseComposition() &&
            imeRoot?.mode != ImeFrontMode.AGENT_READING &&
            keyboardView?.acceptsAssociationPresentation() == true

    private fun commitAssociationCandidate(suggestion: AssociationSuggestion) {
        val removedVisibleStrip = cancelAssociationPresentation()
        val start = when {
            isAgentTextTarget() -> -1
            hasHostSelection(selectionStart, selectionEnd) -> minOf(selectionStart, selectionEnd)
            else -> selectionStart
        }
        val feedback = prepareTextExpirationFeedback()
        if (
            !commitReliableLexicalText(
                text = suggestion.text,
                expectedHostCursor = start.takeIf { it >= 0 }?.plus(suggestion.text.length),
            )
        ) {
            if (removedVisibleStrip) render()
            return
        }
        personalizationFeedback.complete(feedback)
        recordReliableCommit(
            text = suggestion.text,
            canonicalPinyin = null,
            committedStart = start,
            committedEnd = start.takeIf { it >= 0 }?.plus(suggestion.text.length) ?: -1,
        )
        render()
    }

    private fun recordReliableCommit(
        text: String,
        canonicalPinyin: String?,
        committedStart: Int,
        committedEnd: Int,
    ) {
        if (!decodeContextAllowed) {
            breakAssociationSequence()
            return
        }
        val outcome = commitSequenceTracker.record(
            CommittedTextUnit(
                text = text,
                canonicalPinyin = canonicalPinyin,
                editorSessionId = editorSessionId,
                committedAtMillis = SystemClock.elapsedRealtime(),
                start = committedStart,
                endExclusive = committedEnd,
            ),
        )
        if (localPersistenceAllowed) {
            outcome.association?.let { observation ->
                if (!associationPersistenceReady) {
                    if (pendingAssociationObservations.size >= MAX_PENDING_ASSOCIATIONS) {
                        pendingAssociationObservations.removeFirst()
                    }
                    pendingAssociationObservations.addLast(observation)
                }
                runCatching {
                    associationEngine.observe(observation.context, observation.nextText)
                }.onFailure { error ->
                    Log.e(TAG, "User association update failed", error)
                }
            }
        }
        scheduleAssociationPresentation()
    }

    private fun breakAssociationSequence() {
        commitSequenceTracker.breakSequence()
        cancelAssociationPresentation()
        associationSession.clear()
        reliableCommitSelectionFence.clear()
    }

    private fun commitReliableLexicalText(text: String, expectedHostCursor: Int?): Boolean {
        reliableCommitSelectionFence.expect(
            editorSessionId = editorSessionId,
            connectionIdentity = currentInputConnection.takeUnless { isAgentTextTarget() },
            cursor = expectedHostCursor ?: -1,
        )
        val committed = reliableCommitGuard.duringMutation {
            commitToActiveTextTarget(text)
        }
        if (!committed) reliableCommitSelectionFence.clear()
        return committed
    }

    private fun commitToActiveTextTarget(text: String): Boolean =
        if (isAgentTextTarget()) {
            commitToAgentDraft(text)
        } else {
            currentInputConnection?.commitText(text, 1) == true
        }

    private fun finishActiveComposingText() {
        if (isAgentTextTarget()) {
            agentDraftComposition = ""
            publishAgentUi()
        } else {
            currentInputConnection?.finishComposingText()
        }
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

    private fun activeKeyboardInputSchemeChoice(): KeyboardInputSchemeChoice =
        when (inputScheme.scheme) {
            ChineseInputScheme.PINYIN_T9 -> KeyboardInputSchemeChoice.PINYIN_T9
            ChineseInputScheme.PINYIN_QWERTY -> KeyboardInputSchemeChoice.PINYIN_QWERTY
            ChineseInputScheme.WUBI_86 -> KeyboardInputSchemeChoice.WUBI_86
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
                    acceptImePreferencesSnapshot(value)
                }.onFailure { error ->
                    Log.e(TAG, "IME preference load failed", error)
                }
            }
        }
    }

    /** Height and rail symbols are composition-independent and may update before a scheme boundary. */
    private fun acceptImePreferencesSnapshot(value: ImePreferencesV1) {
        applyKeyboardAppearancePreferences(value)
        inputScheme.acceptLoadedPreferences(
            value = value,
            compositionActive = hasAnyComposition(),
        )?.let(::applyImePreferences)
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
        applyKeyboardAppearancePreferences(value)
        keyboardView?.setInputPresentation(
            chineseMode,
            activePrimaryKeyboardMode(),
            activePrimaryKeyboardLegendMode(),
        )
        keyboardView?.setSelectedInputSchemeChoice(activeKeyboardInputSchemeChoice())
        render(forceDecode = schemeChanged)
    }

    private fun applyKeyboardAppearancePreferences(value: ImePreferencesV1) {
        imeRoot?.setKeyboardSizeProfile(value.toKeyboardSizeProfile())
            ?: keyboardSurface?.setKeyboardSizeProfile(value.toKeyboardSizeProfile())
        keyboardView?.setT9SideSymbols(value.t9SideSymbols)
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
        const val T9_PINYIN_CHOICE_LIMIT = 8
        const val T9_EDITOR_PATH_LIMIT = 1
        const val MAX_PROGRESSIVE_PREFIX_CANDIDATES = DECODE_CANDIDATE_LIMIT
        const val PRESENTATION_CANDIDATE_LIMIT = DECODE_CANDIDATE_LIMIT + MAX_PROGRESSIVE_PREFIX_CANDIDATES
        const val ASSOCIATION_CANDIDATE_LIMIT = 8
        const val ASSOCIATION_REVEAL_DELAY_MS = 420L
        const val ASSOCIATION_AUTO_HIDE_MS = 4_500L
        const val CLIPBOARD_HISTORY_LIMIT = 30
        const val MAX_CLIPBOARD_TEXT_LENGTH = 4096
        const val MAX_VOICE_PREVIEW_CHARS = 1024
        const val MAX_DECODE_CONTEXT_CHARS = 2
        const val MAX_ASSOCIATION_CONTEXT_CHARS = 16
        const val MAX_PENDING_ASSOCIATIONS = 64
        const val MAX_PENDING_PINYIN_LEARNINGS = 64
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
    val bigramModel: CharacterBigramModel,
    val userAssociationLexicon: UserAssociationLexicon,
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
