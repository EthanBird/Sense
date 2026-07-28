package io.github.ethanbird.senseime

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import io.github.ethanbird.senseime.ai.protocol.EditorIntent
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot
import io.github.ethanbird.senseime.brain.api.CredentialEndpointScope
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCompatibilityIssue
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderPreset
import io.github.ethanbird.senseime.brain.api.ProviderPresetCatalog
import io.github.ethanbird.senseime.brain.api.ProviderPresetId
import io.github.ethanbird.senseime.brain.api.ProviderReasoningStrength
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettings
import io.github.ethanbird.senseime.brain.runtime.AgentToolSettingsStore
import io.github.ethanbird.senseime.brain.runtime.AgentSkillStore
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestEvent
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestFailure
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestPhase
import io.github.ethanbird.senseime.brain.runtime.ProviderSettingsStore
import io.github.ethanbird.senseime.brain.runtime.SenseAiProviderTestClient
import io.github.ethanbird.senseime.speech.SpeechProviderCredentialRequirement
import io.github.ethanbird.senseime.speech.SpeechProviderCredentialPolicy
import io.github.ethanbird.senseime.speech.SpeechProviderPreset
import io.github.ethanbird.senseime.speech.SpeechProviderPresetCatalog
import io.github.ethanbird.senseime.speech.SpeechProviderRuntimeCapability
import io.github.ethanbird.senseime.speech.SpeechProviderSettingsStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class SettingsActivity : ComponentActivity() {
    private val navigation = SettingsNavigationState()
    private val sectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateBack()
        }
    }
    private lateinit var screenScroll: ScrollView
    private lateinit var screenContent: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var providerPreset: Spinner
    private lateinit var providerName: EditText
    private lateinit var providerBaseUrl: EditText
    private lateinit var providerModel: EditText
    private lateinit var providerApiKey: EditText
    private lateinit var providerApiStyle: Spinner
    private lateinit var providerStructuredOutput: Spinner
    private lateinit var providerThinkingMode: Spinner
    private lateinit var providerStreaming: Switch
    private lateinit var providerAdvanced: Switch
    private lateinit var providerAdvancedFields: LinearLayout
    private lateinit var providerStatus: TextView
    private lateinit var providerTestButton: Button
    private val providerStore by lazy { ProviderSettingsStore(this) }
    private lateinit var providerTestClient: SenseAiProviderTestClient
    private var providerTestRunning = false
    private var providerUiLoaded = false
    private var selectedProviderPresetPosition = 0
    private var loadedProviderCredentialScope: String? = null
    private lateinit var speechPreset: Spinner
    private lateinit var speechLanguage: Spinner
    private lateinit var speechApiKey: EditText
    private lateinit var speechEndpoint: EditText
    private lateinit var speechModel: EditText
    private lateinit var speechAdvanced: Switch
    private lateinit var speechAdvancedFields: LinearLayout
    private lateinit var speechPermissionButton: Button
    private lateinit var speechStatus: TextView
    private val speechStore by lazy { SpeechProviderSettingsStore(this) }
    private var speechUiLoaded = false
    private var selectedSpeechPresetPosition = 0
    private var loadedSpeechCredentialScope: String? = null
    private var speechPermissionRequestInFlight = false
    private var speechPermissionDeniedOnce = false
    private lateinit var agentToolsMaster: Switch
    private lateinit var agentToolWebSearch: Switch
    private lateinit var agentToolWebRead: Switch
    private lateinit var agentToolCalculator: Switch
    private lateinit var agentToolLocalMemoryRecall: Switch
    private lateinit var agentToolSkillRead: Switch
    private lateinit var agentToolSkillManage: Switch
    private lateinit var agentToolsStatus: TextView
    private val agentToolStore by lazy { AgentToolSettingsStore(this) }
    private var agentToolsUiLoaded = false
    private var agentToolsLoadInFlight = false
    private var agentToolsLoadViewGeneration = -1L
    private var agentToolsSaveRunning = false
    private var applyingAgentToolSettings = false
    private var lastSavedAgentToolSettings = AgentToolSettings()
    private val agentSkillStore by lazy { AgentSkillStore(this) }
    private val agentSkillDraftRecoveryStore by lazy { SkillDraftRecoveryStore(this) }
    private val agentSkillIoSession by lazy {
        SkillSettingsIoSession(
            workerExecutor = AGENT_SKILL_IO_EXECUTOR,
            uiExecutor = Executor { command -> runOnUiThread(command) },
        )
    }
    private val agentSkillDurabilityBarrier by lazy {
        SkillSettingsDurabilityBarrier<
            SkillDraftLifecycleSaveKey,
            SkillDraftRecoveryStore.SaveOutcome,
        >(
            workerExecutor = AGENT_SKILL_IO_EXECUTOR,
            timeoutMillis = DRAFT_LIFECYCLE_FLUSH_TIMEOUT_MS,
        )
    }
    private var agentSkillCatalog: AgentSkillCatalog? = null
    private var agentSkillDraftSession = SkillDraftSessionState()
    private var agentSkillDraftRestoreCompleted = false
    @Volatile
    private var agentSkillDraftRestoreWorkerCompleted = false
    @Volatile
    private var agentSkillDraftRestoreWorkerState: SkillDraftSessionState? = null
    @Volatile
    private var agentSkillDraftRestoreWorkerFailure: Throwable? = null
    private var agentSkillDraftChangedWhileRestoring = false
    private var agentSkillDraftSaveRequestedWhileRestoring = false
    private var agentSkillViewGeneration = 0L
    private var agentSkillCatalogLoadInFlight = false
    private var agentSkillCatalogLoadViewGeneration = -1L
    private var agentSkillMutationRunning = false
    private var creatingAgentSkill = false
    private var applyingAgentSkillUi = false
    private var agentSkillEditorAttached = false
    private var agentSkillEditorHydrated = false
    private var agentSkillDraftRecoveryError: String? = null
    private var agentSkillDraftRecoveryNotice: String? = null
    private var agentSkillDraftRecoveryWriteAuthorized = true
    private var agentSkillHistoryDegradedMessage: String? = null
    private var pendingSkillReplacement: PendingSkillReplacement? = null
    private var pendingSkillDocumentConflict: PendingSkillDocumentConflict? = null
    private lateinit var agentSkillSelector: Spinner
    private lateinit var agentSkillId: EditText
    private lateinit var agentSkillName: EditText
    private lateinit var agentSkillDescription: EditText
    private lateinit var agentSkillContent: EditText
    private lateinit var agentSkillIntent: Spinner
    private lateinit var agentSkillKey: Spinner
    private lateinit var agentSkillDirection: Spinner
    private lateinit var agentSkillRevisionSelector: Spinner
    private lateinit var agentSkillHistoryPreview: TextView
    private lateinit var agentSkillRestoreRevisionButton: Button
    private lateinit var agentSkillViewRevisionButton: Button
    private var agentSkillRevisionNumbers: List<Long> = emptyList()
    private var viewedHistoricalRevision: AgentSkillDefinition? = null
    private lateinit var agentSkillCreateButton: Button
    private lateinit var agentSkillDiscardButton: Button
    private lateinit var agentSkillSaveButton: Button
    private lateinit var agentSkillBindButton: Button
    private lateinit var agentSkillUnbindSlotButton: Button
    private lateinit var agentSkillUnbindAllButton: Button
    private lateinit var agentSkillSlotOccupancy: TextView
    private lateinit var agentSkillBindingSummary: TextView
    private lateinit var agentSkillStatus: TextView
    private val persistAgentSkillDraftRunnable = Runnable {
        captureAgentSkillDraftFromViews()
        persistAgentSkillDraftSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, sectionBackCallback)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.sense_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        navigation.restore(savedInstanceState?.getString(STATE_SECTION))
        requestAgentSkillDraftSessionRestore(
            savedInstanceState?.getByteArray(STATE_SKILL_DRAFTS)?.copyOf(),
        )
        providerTestClient = SenseAiProviderTestClient(this, ::onProviderConnectionTestEvent)
        setContentView(buildContent())
        renderCurrentSection()
    }

    override fun onResume() {
        super.onResume()
        when (navigation.current) {
            SettingsSection.HOME -> updateStatus()
            SettingsSection.PROVIDER -> if (!providerUiLoaded) loadProviderSettings()
            SettingsSection.TOOLS -> if (!agentToolsUiLoaded) loadAgentToolSettings()
            SettingsSection.SKILLS -> loadAgentSkillsPreservingDraft()
            SettingsSection.VOICE -> {
                if (!speechUiLoaded) loadSpeechSettings()
                updateSpeechPermissionButton()
            }
            else -> Unit
        }
    }

    override fun onStop() {
        captureAgentSkillDraftFromViews()
        // Always enqueue a terminal snapshot after every previously accepted debounced write.
        // Equality with the last completed snapshot cannot prove that an older write is not still
        // queued (for example A -> queued B -> user reverts to A -> stop).
        persistAgentSkillDraftSessionForLifecycle()
        if (::providerTestClient.isInitialized) providerTestClient.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (::screenContent.isInitialized) {
            screenContent.removeCallbacks(persistAgentSkillDraftRunnable)
        }
        // Android dispatches onStop before onDestroy; that single barrier owns the final snapshot.
        // Do not enqueue a duplicate 3.5-second wait if the original durability work timed out.
        agentSkillIoSession.close()
        if (::providerTestClient.isInitialized) providerTestClient.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureAgentSkillDraftFromViews()
        outState.putString(STATE_SECTION, navigation.current.name)
        val bundled = if (agentSkillDraftRestoreCompleted) {
            SkillDraftSessionCodec.encodeForSavedState(
                agentSkillDraftSession,
                MAX_BUNDLE_DRAFT_BYTES,
            )
        } else {
            null
        }
        if (bundled != null) {
            // This is the exact current buffer, not merely the last fsynced version.
            outState.putByteArray(STATE_SKILL_DRAFTS, bundled)
            persistAgentSkillDraftSession()
        } else {
            /*
             * Android may kill the process as soon as this callback returns. Large sessions cannot
             * safely enter Bundle/Binder, so join the process-wide serial lane long enough to
             * publish one crash-consistent recovery snapshot. Settings lifecycle durability takes
             * precedence over avoiding a bounded main-thread wait; the IME hot path is unaffected.
             */
            persistAgentSkillDraftSessionForLifecycle()
                ?.encodedSnapshot
                ?.takeIf { it.size <= MAX_BUNDLE_DRAFT_BYTES }
                ?.copyOf()
                ?.let { outState.putByteArray(STATE_SKILL_DRAFTS, it) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): View {
        screenContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
        }
        screenScroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sense_background))
            isFillViewport = true
            addView(screenContent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    screenContent.setPadding(
                        dp(22),
                        dp(30) + bars.top,
                        dp(22),
                        dp(30) + bars.bottom,
                    )
                    insets
                }
            }
        }
        return screenScroll
    }

    private fun renderCurrentSection() {
        if (!::screenContent.isInitialized) return
        sectionBackCallback.isEnabled = navigation.current != SettingsSection.HOME
        captureAgentSkillDraftFromViews()
        agentSkillEditorAttached = false
        agentSkillEditorHydrated = false
        agentSkillViewGeneration += 1L
        screenContent.removeAllViews()
        when (navigation.current) {
            SettingsSection.HOME -> renderHome()
            SettingsSection.PROVIDER -> {
                renderDetailHeader(R.string.settings_provider_title, R.string.settings_provider_summary)
                providerUiLoaded = false
                screenContent.addView(card(R.string.ai_provider_title, providerForm()).withTop(dp(20)))
                loadProviderSettings()
            }
            SettingsSection.SOUL -> renderSoul()
            SettingsSection.TOOLS -> {
                renderDetailHeader(R.string.settings_tools_title, R.string.settings_tools_summary)
                agentToolsUiLoaded = false
                screenContent.addView(card(R.string.agent_tools_title, agentToolsForm()).withTop(dp(20)))
                loadAgentToolSettings()
            }
            SettingsSection.SKILLS -> renderSkills()
            SettingsSection.VOICE -> {
                renderDetailHeader(R.string.settings_voice_title, R.string.settings_voice_summary)
                speechUiLoaded = false
                screenContent.addView(
                    card(R.string.speech_provider_title, speechProviderForm()).withTop(dp(20)),
                )
                loadSpeechSettings()
                updateSpeechPermissionButton()
            }
            SettingsSection.ABOUT -> renderAbout()
        }
        screenScroll.post { screenScroll.scrollTo(0, 0) }
    }

    private fun renderHome() {
        addBrandHeader(showTagline = true)
        statusText = text(R.string.ime_disabled, 16f, R.color.sense_primary, Typeface.BOLD)
        screenContent.addView(card(R.string.ime_status_title, statusText).withTop(dp(26)))
        screenContent.addView(primaryButton(R.string.enable_ime) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.withTop(dp(14)))
        screenContent.addView(secondaryButton(R.string.switch_ime) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }.withTop(dp(10)))

        screenContent.addView(
            text(R.string.settings_categories_title, 13f, R.color.sense_secondary, Typeface.BOLD)
                .withTop(dp(28)),
        )
        addCategory(
            SettingsSection.PROVIDER,
            R.string.settings_provider_title,
            R.string.settings_provider_summary,
        )
        addCategory(
            SettingsSection.SOUL,
            R.string.settings_soul_title,
            R.string.settings_soul_summary,
        )
        addCategory(
            SettingsSection.TOOLS,
            R.string.settings_tools_title,
            R.string.settings_tools_summary,
        )
        addCategory(
            SettingsSection.SKILLS,
            R.string.settings_skills_title,
            R.string.settings_skills_summary,
        )
        addCategory(
            SettingsSection.VOICE,
            R.string.settings_voice_title,
            R.string.settings_voice_summary,
        )
        addCategory(
            SettingsSection.ABOUT,
            R.string.settings_about_title,
            R.string.settings_about_summary,
        )
        screenContent.addView(text(R.string.version_label, 12f, R.color.sense_secondary).withTop(dp(24)))
        updateStatus()
    }

    private fun addBrandHeader(showTagline: Boolean) {
        screenContent.addView(
            text(R.string.brand_english, 12f, R.color.sense_accent, Typeface.BOLD).apply {
                letterSpacing = 0.24f
            },
        )
        screenContent.addView(
            text(R.string.brand_chinese, 34f, R.color.sense_primary, Typeface.BOLD).withTop(dp(5)),
        )
        if (showTagline) {
            screenContent.addView(
                text(R.string.brand_tagline, 15f, R.color.sense_secondary).withTop(dp(8)),
            )
            screenContent.addView(badge().withTop(dp(18)))
        }
    }

    private fun addCategory(section: SettingsSection, titleRes: Int, summaryRes: Int) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(summaryRes, 13f, R.color.sense_secondary),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(this@SettingsActivity).apply {
                text = "›"
                textSize = 28f
                setTextColor(getColor(R.color.sense_accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        screenContent.addView(
            card(titleRes, body).apply {
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                contentDescription = "${getString(titleRes)}，${getString(summaryRes)}"
                setOnClickListener { showSection(section) }
            }.withTop(dp(12)),
        )
    }

    private fun showSection(section: SettingsSection) {
        captureAgentSkillDraftFromViews()
        persistAgentSkillDraftSession()
        cancelProviderTestBeforeLeavingSection()
        navigation.open(section)
        renderCurrentSection()
    }

    private fun navigateBack() {
        captureAgentSkillDraftFromViews()
        persistAgentSkillDraftSession()
        cancelProviderTestBeforeLeavingSection()
        when (navigation.back()) {
            SettingsBackResult.CONSUMED -> renderCurrentSection()
            SettingsBackResult.EXIT_ACTIVITY -> {
                sectionBackCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun cancelProviderTestBeforeLeavingSection() {
        if (
            SettingsSectionExitPolicy.shouldCancelProviderTest(
                navigation.current,
                providerTestRunning,
            )
        ) {
            providerTestClient.cancel()
        }
    }

    private fun renderDetailHeader(titleRes: Int, summaryRes: Int) {
        screenContent.addView(TextView(this).apply {
            setText(R.string.settings_back)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.sense_accent))
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.settings_back_accessibility,
                getString(titleRes),
            )
            setOnClickListener { navigateBack() }
        })
        screenContent.addView(text(titleRes, 30f, R.color.sense_primary, Typeface.BOLD).withTop(dp(14)))
        screenContent.addView(text(summaryRes, 14f, R.color.sense_secondary).withTop(dp(7)))
    }

    private fun renderSoul() {
        renderDetailHeader(R.string.settings_soul_title, R.string.settings_soul_summary)
        screenContent.addView(
            card(
                R.string.soul_retention_title,
                text(R.string.soul_retention_body, 14f, R.color.sense_secondary),
            ).withTop(dp(20)),
        )
        screenContent.addView(
            card(
                R.string.soul_event_memory_title,
                text(R.string.soul_event_memory_body, 14f, R.color.sense_secondary),
            ).withTop(dp(12)),
        )
        screenContent.addView(
            card(
                R.string.offline_title,
                text(R.string.offline_body, 14f, R.color.sense_secondary),
            ).withTop(dp(12)),
        )
    }

    private fun renderSkills() {
        renderDetailHeader(R.string.settings_skills_title, R.string.settings_skills_summary)
        screenContent.addView(
            card(
                R.string.skills_documents_title,
                agentSkillsForm(),
            ).withTop(dp(20)),
        )
        setAgentSkillEditorEnabled(false)
        loadAgentSkillsPreservingDraft()
    }

    private fun agentSkillsForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(R.string.skills_body, 13f, R.color.sense_secondary))

        agentSkillSelector = accessibleSpinner(R.string.skills_select)
        addView(
            labeledField(R.string.skills_select, agentSkillSelector).withTop(dp(14)),
        )
        agentSkillCreateButton = secondaryButton(
            R.string.skills_create_new,
            ::startCreatingAgentSkill,
        )
        addView(agentSkillCreateButton.withTop(dp(8)))
        agentSkillDiscardButton = secondaryButton(R.string.skills_discard_draft) {
            discardCurrentAgentSkillDraft()
        }
        addView(agentSkillDiscardButton.withTop(dp(8)))

        agentSkillId = editField(R.string.skills_id, "lowercase_id").apply {
            isSaveEnabled = false
        }
        agentSkillName = editField(R.string.skills_name, "例如：周报").apply {
            isSaveEnabled = false
        }
        agentSkillDescription = editField(
            R.string.skills_description,
            "默认 Agent 可看到的简短能力描述",
        ).apply {
            isSaveEnabled = false
        }
        agentSkillContent = multiLineEditField(
            R.string.skills_content,
            "# Skill\n写下完整指令、约束与工作流程",
        ).apply {
            isSaveEnabled = false
        }
        agentSkillIntent = accessibleSpinner(R.string.skills_base_intent).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SKILL_INTENTS.map(::skillIntentLabel),
            )
        }
        agentSkillKey = accessibleSpinner(R.string.skills_key).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SkillKeyOptions.all.map(SkillKeyOption::label),
            )
        }
        agentSkillDirection = accessibleSpinner(R.string.skills_direction).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AgentSkillDirection.entries.map(::skillDirectionLabel),
            )
        }

        addView(labeledField(R.string.skills_id, agentSkillId).withTop(dp(14)))
        addView(labeledField(R.string.skills_name, agentSkillName).withTop(dp(10)))
        addView(
            labeledField(R.string.skills_description, agentSkillDescription).withTop(dp(10)),
        )
        addView(labeledField(R.string.skills_content, agentSkillContent).withTop(dp(10)))
        addView(labeledField(R.string.skills_base_intent, agentSkillIntent).withTop(dp(10)))
        addView(
            text(R.string.skills_binding_hint, 12f, R.color.sense_secondary).withTop(dp(14)),
        )
        addView(labeledField(R.string.skills_key, agentSkillKey).withTop(dp(8)))
        addView(labeledField(R.string.skills_direction, agentSkillDirection).withTop(dp(10)))
        agentSkillSlotOccupancy =
            text(R.string.skills_slot_unbound, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        addView(agentSkillSlotOccupancy.withTop(dp(10)))
        agentSkillSaveButton = primaryButton(R.string.skills_save, ::saveAgentSkill)
        addView(agentSkillSaveButton.withTop(dp(14)))
        agentSkillBindButton = secondaryButton(R.string.skills_bind, ::bindSelectedAgentSkill)
        addView(agentSkillBindButton.withTop(dp(8)))
        agentSkillUnbindSlotButton = secondaryButton(
            R.string.skills_unbind_slot,
            ::unbindSelectedAgentSkillSlot,
        )
        addView(agentSkillUnbindSlotButton.withTop(dp(8)))
        agentSkillUnbindAllButton = secondaryButton(
            R.string.skills_unbind_all,
            ::unbindSelectedAgentSkill,
        )
        addView(agentSkillUnbindAllButton.withTop(dp(8)))

        agentSkillRevisionSelector = accessibleSpinner(R.string.skills_history_select)
        addView(
            labeledField(R.string.skills_history_select, agentSkillRevisionSelector)
                .withTop(dp(18)),
        )
        agentSkillViewRevisionButton = secondaryButton(
            R.string.skills_history_view,
            ::viewSelectedAgentSkillRevision,
        )
        addView(agentSkillViewRevisionButton.withTop(dp(8)))
        agentSkillRestoreRevisionButton = secondaryButton(
            R.string.skills_history_restore,
            ::restoreSelectedAgentSkillRevision,
        )
        addView(agentSkillRestoreRevisionButton.withTop(dp(8)))
        agentSkillHistoryPreview =
            text(R.string.skills_history_preview_empty, 12f, R.color.sense_secondary).apply {
                setTextIsSelectable(true)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        addView(agentSkillHistoryPreview.withTop(dp(10)))

        agentSkillBindingSummary =
            text(R.string.skills_bindings_none, 12f, R.color.sense_secondary)
        addView(agentSkillBindingSummary.withTop(dp(12)))
        agentSkillStatus = text(
            R.string.skills_loading_body,
            12f,
            R.color.sense_secondary,
        ).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(agentSkillStatus.withTop(dp(10)))

        agentSkillSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (applyingAgentSkillUi) return
                val definition = agentSkillCatalog?.definitions?.getOrNull(position) ?: return
                captureAgentSkillDraftFromViews()
                clearAgentSkillConfirmations()
                agentSkillDraftSession = agentSkillDraftSession.selectExisting(
                    definition,
                    agentSkillCatalog?.bindings
                        ?.firstOrNull { it.skillId == definition.id }
                        ?.slot,
                )
                if (!agentSkillDraftRestoreCompleted) {
                    agentSkillDraftChangedWhileRestoring = true
                }
                renderAgentSkillDraft(requireNotNull(agentSkillDraftSession.current()))
                persistAgentSkillDraftSession()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val documentWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!applyingAgentSkillUi) onAgentSkillDraftEdited()
            }
        }
        listOf(
            agentSkillId,
            agentSkillName,
            agentSkillDescription,
            agentSkillContent,
        ).forEach { it.addTextChangedListener(documentWatcher) }
        agentSkillIntent.onItemSelectedListener = simpleSelectionListener {
            onAgentSkillDraftEdited()
        }
        agentSkillKey.onItemSelectedListener = simpleSelectionListener {
            onAgentSkillSlotSelectionChanged()
        }
        agentSkillDirection.onItemSelectedListener = simpleSelectionListener {
            onAgentSkillSlotSelectionChanged()
        }
        agentSkillRevisionSelector.onItemSelectedListener = simpleSelectionListener {
            viewedHistoricalRevision = null
            updateAgentSkillHistoryControls()
        }
        agentSkillEditorAttached = true
        agentSkillEditorHydrated = false
    }

    private fun loadAgentSkillsPreservingDraft(
        afterLoadMessage: Int? = null,
    ) {
        if (!::agentSkillStatus.isInitialized) return
        val viewGeneration = agentSkillViewGeneration
        if (
            agentSkillCatalogLoadInFlight &&
            agentSkillCatalogLoadViewGeneration == viewGeneration
        ) {
            return
        }
        captureAgentSkillDraftFromViews()
        agentSkillCatalogLoadInFlight = true
        agentSkillCatalogLoadViewGeneration = viewGeneration
        agentSkillStatus.setText(R.string.skills_loading_body)
        agentSkillStatus.setTextColor(getColor(R.color.sense_secondary))
        setAgentSkillEditorEnabled(false)
        val accepted = agentSkillIoSession.refresh(
            channel = SKILL_IO_CATALOG_CHANNEL,
            operation = { agentSkillStore.loadCatalog().getOrThrow() },
        ) { result ->
            agentSkillCatalogLoadInFlight = false
            if (!isCurrentAgentSkillView(viewGeneration)) return@refresh
            result
                .onSuccess { catalog ->
                    applyAgentSkillCatalog(catalog)
                    val conflict = currentAgentSkillConflict()
                    when {
                        agentSkillHistoryDegradedMessage != null -> {
                            agentSkillStatus.text = getString(
                                R.string.skills_history_degraded,
                                agentSkillHistoryDegradedMessage.orEmpty(),
                            )
                            agentSkillStatus.setTextColor(
                                getColor(android.R.color.holo_red_dark),
                            )
                        }
                        agentSkillDraftRecoveryError != null -> {
                            agentSkillStatus.text = getString(
                                R.string.skills_draft_recovery_degraded,
                                agentSkillDraftRecoveryError.orEmpty(),
                            )
                            agentSkillStatus.setTextColor(
                                getColor(android.R.color.holo_red_dark),
                            )
                        }
                        agentSkillDraftRecoveryNotice != null -> {
                            agentSkillStatus.text = agentSkillDraftRecoveryNotice
                            agentSkillStatus.setTextColor(getColor(R.color.sense_accent))
                        }
                        afterLoadMessage != null -> {
                            agentSkillStatus.setText(afterLoadMessage)
                            agentSkillStatus.setTextColor(getColor(R.color.sense_accent))
                        }
                        conflict != null -> showAgentSkillRevisionConflict(conflict)
                        else -> {
                            agentSkillStatus.text = getString(
                                R.string.skills_ready,
                                catalog.definitions.size,
                                catalog.bindings.size,
                            )
                            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
                        }
                    }
                    setAgentSkillEditorEnabled(!agentSkillMutationRunning)
                }
                .onFailure { error ->
                    agentSkillStatus.text = getString(
                        R.string.skills_load_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    setAgentSkillEditorEnabled(false)
                }
        }
        if (!accepted) {
            agentSkillCatalogLoadInFlight = false
            if (isCurrentAgentSkillView(viewGeneration)) {
                agentSkillStatus.text = getString(
                    R.string.skills_load_degraded,
                    getString(R.string.skills_not_ready),
                )
                agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                setAgentSkillEditorEnabled(false)
            }
        }
    }

    private fun applyAgentSkillCatalog(catalog: AgentSkillCatalog) {
        agentSkillCatalog = catalog
        agentSkillDraftSession = agentSkillDraftSession.reconcile(catalog)
        if (agentSkillDraftSession.current() == null) {
            agentSkillDraftSession = if (catalog.definitions.isEmpty()) {
                agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
            } else {
                val first = catalog.definitions.first()
                agentSkillDraftSession.selectExisting(
                    first,
                    catalog.bindings.firstOrNull { it.skillId == first.id }?.slot,
                )
            }
        }
        applyingAgentSkillUi = true
        try {
            agentSkillSelector.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                catalog.definitions.map { definition ->
                    getString(
                        R.string.skills_selector_item,
                        definition.name,
                        definition.revision,
                    )
                },
            )
            val record = requireNotNull(agentSkillDraftSession.current())
            record.sourceSkillId?.let { selectedId ->
                val index = catalog.definitions.indexOfFirst { it.id == selectedId }
                if (index >= 0) agentSkillSelector.setSelection(index)
            }
            renderAgentSkillDraft(record)
        } finally {
            applyingAgentSkillUi = false
        }
        persistAgentSkillDraftSession()
    }

    private fun startCreatingAgentSkill() {
        captureAgentSkillDraftFromViews()
        agentSkillDraftRecoveryWriteAuthorized = true
        clearAgentSkillConfirmations()
        agentSkillDraftSession = agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
        if (!agentSkillDraftRestoreCompleted) {
            agentSkillDraftChangedWhileRestoring = true
        }
        renderAgentSkillDraft(requireNotNull(agentSkillDraftSession.current()))
        agentSkillStatus.setText(R.string.skills_creating)
        agentSkillStatus.setTextColor(getColor(R.color.sense_accent))
        persistAgentSkillDraftSession()
    }

    private fun renderAgentSkillDraft(record: SkillEditorDraftRecord) {
        creatingAgentSkill = record.creating
        applyingAgentSkillUi = true
        try {
            setAgentSkillEditorEnabled(!agentSkillMutationRunning)
            applyAgentSkillInputCapacity(record.draft)
            agentSkillId.setText(record.draft.id)
            agentSkillId.isEnabled = record.creating
            agentSkillName.setText(record.draft.name)
            agentSkillDescription.setText(record.draft.description)
            agentSkillContent.setText(record.draft.content)
            agentSkillIntent.setSelection(
                SKILL_INTENTS.indexOf(record.draft.baseIntent).coerceAtLeast(0),
            )
            agentSkillKey.setSelection(
                SkillKeyOptions.indexOf(record.bindingSelection.slot?.keyCode),
            )
            agentSkillDirection.setSelection(record.bindingSelection.slot?.direction?.ordinal ?: 0)
            if (record.creating) {
                agentSkillBindingSummary.setText(R.string.skills_bindings_new)
                clearAgentSkillHistory()
            } else {
                updateAgentSkillBindingSummary(requireNotNull(record.sourceSkillId))
                loadAgentSkillRevisionList(record.sourceSkillId)
            }
            updateAgentSkillSlotOccupancy()
            updateAgentSkillConfirmationControls()
        } finally {
            applyingAgentSkillUi = false
        }
        agentSkillEditorHydrated = true
    }

    private fun applyAgentSkillInputCapacity(draft: SkillSettingsDraft) {
        fun EditText.rejectBeyond(
            policyLimit: Int,
            retainedLength: Int,
            fieldLabel: String,
        ) {
            val maximumAcceptedLength = SkillDraftInputCapacity.maximumAcceptedLength(
                policyLimit = policyLimit,
                retainedLength = retainedLength,
            )
            filters = arrayOf(
                InputFilter { source, start, end, destination, destinationStart, destinationEnd ->
                    val accepted = SkillDraftInputCapacity.acceptsWholeEdit(
                        maximumAcceptedLength = maximumAcceptedLength,
                        currentLength = destination.length,
                        replacedLength = destinationEnd - destinationStart,
                        incomingLength = end - start,
                    )
                    if (accepted) {
                        null
                    } else {
                        agentSkillStatus.post {
                            showAgentSkillError(
                                "$fieldLabel 最多 $policyLimit 个字符；本次输入未写入",
                            )
                        }
                        // Return the exact replaced slice to reject the whole edit, never a prefix.
                        destination.subSequence(destinationStart, destinationEnd)
                    }
                },
            )
        }
        agentSkillId.rejectBeyond(AgentSkillPolicy.MAX_ID_CHARS, draft.id.length, "Skill ID")
        agentSkillName.rejectBeyond(AgentSkillPolicy.MAX_NAME_CHARS, draft.name.length, "名称")
        agentSkillDescription.rejectBeyond(
            AgentSkillPolicy.MAX_DESCRIPTION_CHARS,
            draft.description.length,
            "简短描述",
        )
        agentSkillContent.rejectBeyond(
            AgentSkillPolicy.MAX_CONTENT_CHARS,
            draft.content.length,
            "Skill 文档",
        )
    }

    private fun saveAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog ?: return showAgentSkillError(
            getString(R.string.skills_not_ready),
        )
        captureAgentSkillDraftFromViews()
        val record = agentSkillDraftSession.current() ?: return showAgentSkillError(
            getString(R.string.skills_not_ready),
        )
        val draft = currentAgentSkillDraft()
        draft.validationError()?.let {
            showAgentSkillError(it)
            return
        }
        val existing = record.sourceSkillId?.let(catalog::definition)
        if (!record.creating && record.conflictsWith(existing)) {
            val currentRevision = existing?.revision ?: -1L
            val confirmation = PendingSkillDocumentConflict(
                skillId = requireNotNull(record.sourceSkillId),
                sourceRevision = requireNotNull(record.sourceRevision),
                latestRevision = currentRevision,
                draft = draft,
            )
            if (pendingSkillDocumentConflict != confirmation) {
                pendingSkillDocumentConflict = confirmation
                updateAgentSkillConfirmationControls()
                showAgentSkillRevisionConflict(record)
                return
            }
        }
        if (
            record.creating &&
            requireIntentionalSlotReplacement(
                operation = SkillReplacementOperation.CREATE,
                targetSkillId = draft.id.trim(),
                slot = draft.bindingSlot,
            )
        ) {
            return
        }
        val mutation = if (record.creating) {
            draft.createMutation(catalog.generation)
        } else {
            val current = existing ?: return showAgentSkillError(
                getString(R.string.skills_not_ready),
            )
            val update = draft.updateMutation(current, catalog.generation)
            if (
                update.name == null &&
                update.description == null &&
                update.content == null &&
                update.baseIntent == null
            ) {
                agentSkillStatus.setText(R.string.skills_no_document_changes)
                agentSkillStatus.setTextColor(getColor(R.color.sense_secondary))
                return
            }
            update
        }
        submitAgentSkillMutation(mutation) { savedCatalog ->
            val savedId = draft.id.trim()
            clearAgentSkillConfirmations()
            agentSkillDraftSession = agentSkillDraftSession.acceptSaved(savedCatalog, savedId)
            applyAgentSkillCatalog(savedCatalog)
            agentSkillStatus.setText(R.string.skills_saved)
            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
        }
    }

    private fun bindSelectedAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog ?: return
        val skill = selectedAgentSkill() ?: return showAgentSkillError(
            getString(R.string.skills_save_before_binding),
        )
        val slot = selectedAgentSkillSlot() ?: return showAgentSkillError(
            getString(R.string.skills_choose_key),
        )
        if (
            requireIntentionalSlotReplacement(
                operation = SkillReplacementOperation.BIND,
                targetSkillId = skill.id,
                slot = slot,
            )
        ) {
            return
        }
        submitAgentSkillMutation(
            AgentSkillMutation.Bind(
                skillId = skill.id,
                slot = slot,
                expectedGeneration = catalog.generation,
            ),
        ) { updatedCatalog ->
            clearAgentSkillConfirmations()
            applyAgentSkillCatalog(updatedCatalog)
            agentSkillStatus.setText(R.string.skills_bound)
            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
        }
    }

    private fun unbindSelectedAgentSkillSlot() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog ?: return
        val skill = selectedAgentSkill() ?: return
        val slot = selectedAgentSkillSlot() ?: return showAgentSkillError(
            getString(R.string.skills_choose_key),
        )
        val binding = catalog.binding(slot)
        if (binding?.skillId != skill.id) {
            return showAgentSkillError(getString(R.string.skills_slot_not_bound_to_selected))
        }
        submitAgentSkillMutation(
            AgentSkillMutation.Unbind(slot, expectedGeneration = catalog.generation),
        ) { updatedCatalog ->
            clearAgentSkillConfirmations()
            applyAgentSkillCatalog(updatedCatalog)
            agentSkillStatus.setText(R.string.skills_unbound)
            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
        }
    }

    private fun unbindSelectedAgentSkill() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog ?: return
        val skill = selectedAgentSkill() ?: return
        if (catalog.bindings.none { it.skillId == skill.id }) {
            agentSkillStatus.setText(R.string.skills_bindings_none)
            agentSkillStatus.setTextColor(getColor(R.color.sense_secondary))
            return
        }
        submitAgentSkillMutation(
            AgentSkillMutation.UnbindSkill(
                skillId = skill.id,
                expectedGeneration = catalog.generation,
            ),
        ) { updatedCatalog ->
            clearAgentSkillConfirmations()
            applyAgentSkillCatalog(updatedCatalog)
            agentSkillStatus.setText(R.string.skills_unbound_all)
            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
        }
    }

    private fun currentAgentSkillDraft(): SkillSettingsDraft = SkillSettingsDraft(
        id = agentSkillId.text.toString(),
        name = agentSkillName.text.toString(),
        description = agentSkillDescription.text.toString(),
        content = agentSkillContent.text.toString(),
        baseIntent = SKILL_INTENTS[
            agentSkillIntent.selectedItemPosition.coerceIn(0, SKILL_INTENTS.lastIndex)
        ],
        bindingSlot = selectedAgentSkillSlot(),
    )

    private fun selectedAgentSkillSlot(): AgentSkillSlot? {
        val keyCode = SkillKeyOptions.all
            .getOrNull(agentSkillKey.selectedItemPosition)
            ?.keyCode
            ?: return null
        val direction = AgentSkillDirection.entries[
            agentSkillDirection.selectedItemPosition.coerceIn(
                0,
                AgentSkillDirection.entries.lastIndex,
            )
        ]
        return AgentSkillSlot(keyCode, direction)
    }

    private fun selectedAgentSkill(): AgentSkillDefinition? {
        val sourceId = agentSkillDraftSession.current()?.sourceSkillId ?: return null
        return agentSkillCatalog?.definition(sourceId)
    }

    private fun emptyAgentSkillDraft(): SkillSettingsDraft = SkillSettingsDraft(
        id = "",
        name = "",
        description = "",
        content = "",
        baseIntent = SKILL_INTENTS.first(),
        bindingSlot = null,
    )

    private fun captureAgentSkillDraftFromViews(
        bindingSelectionExplicit: Boolean = false,
    ) {
        if (!::agentSkillId.isInitialized) return
        if (
            !SkillDraftCapturePolicy.shouldCapture(
                editorAttached = agentSkillEditorAttached,
                editorHydrated = agentSkillEditorHydrated,
                applyingStateToUi = applyingAgentSkillUi,
                hasCurrentDraft = agentSkillDraftSession.current() != null,
            )
        ) {
            return
        }
        val captured = agentSkillDraftSession.capture(
            draft = currentAgentSkillDraft(),
            bindingSelectionExplicit = bindingSelectionExplicit,
        )
        if (!agentSkillDraftRestoreCompleted && captured != agentSkillDraftSession) {
            agentSkillDraftChangedWhileRestoring = true
        }
        agentSkillDraftSession = captured
    }

    private fun onAgentSkillDraftEdited() {
        if (applyingAgentSkillUi) return
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        clearAgentSkillConfirmations()
        updateAgentSkillSlotOccupancy()
        scheduleAgentSkillDraftPersistence()
    }

    private fun onAgentSkillSlotSelectionChanged() {
        if (applyingAgentSkillUi) return
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews(bindingSelectionExplicit = true)
        clearAgentSkillConfirmations()
        updateAgentSkillSlotOccupancy()
        scheduleAgentSkillDraftPersistence()
    }

    private fun discardCurrentAgentSkillDraft() {
        val catalog = agentSkillCatalog ?: return showAgentSkillError(
            getString(R.string.skills_not_ready),
        )
        agentSkillDraftRecoveryWriteAuthorized = true
        captureAgentSkillDraftFromViews()
        agentSkillDraftSession = agentSkillDraftSession.discardCurrent(catalog)
        if (agentSkillDraftSession.current() == null) {
            agentSkillDraftSession = agentSkillDraftSession.beginCreate(emptyAgentSkillDraft())
        }
        if (!agentSkillDraftRestoreCompleted) {
            agentSkillDraftChangedWhileRestoring = true
        }
        clearAgentSkillConfirmations()
        applyAgentSkillCatalog(catalog)
        agentSkillStatus.setText(R.string.skills_draft_discarded)
        agentSkillStatus.setTextColor(getColor(R.color.sense_secondary))
    }

    private fun currentAgentSkillConflict(): SkillEditorDraftRecord? {
        val record = agentSkillDraftSession.current() ?: return null
        val latest = record.sourceSkillId?.let { agentSkillCatalog?.definition(it) }
        return record.takeIf { it.conflictsWith(latest) }
    }

    private fun showAgentSkillRevisionConflict(record: SkillEditorDraftRecord) {
        val latestRevision = record.sourceSkillId
            ?.let { agentSkillCatalog?.definition(it)?.revision }
        agentSkillStatus.text = getString(
            R.string.skills_revision_conflict,
            record.sourceRevision ?: 0L,
            latestRevision?.toString() ?: getString(R.string.skills_revision_missing),
        )
        agentSkillStatus.setTextColor(getColor(R.color.sense_accent))
    }

    private fun requireIntentionalSlotReplacement(
        operation: SkillReplacementOperation,
        targetSkillId: String,
        slot: AgentSkillSlot?,
    ): Boolean {
        val catalog = agentSkillCatalog ?: return true
        val occupancy = catalog.occupancy(slot, targetSkillId)
        if (!occupancy.requiresReplacement) return false
        val confirmation = PendingSkillReplacement(
            operation = operation,
            generation = catalog.generation,
            slot = requireNotNull(slot),
            incumbentSkillId = requireNotNull(occupancy.incumbentSkillId),
            targetSkillId = targetSkillId,
        )
        if (pendingSkillReplacement == confirmation) return false
        pendingSkillReplacement = confirmation
        updateAgentSkillConfirmationControls()
        agentSkillStatus.text = getString(
            R.string.skills_replace_confirmation,
            SkillKeyOptions.labelOf(confirmation.slot.keyCode),
            skillDirectionLabel(confirmation.slot.direction),
            occupancy.incumbentSkillName.orEmpty(),
        )
        agentSkillStatus.setTextColor(getColor(R.color.sense_accent))
        return true
    }

    private fun updateAgentSkillSlotOccupancy() {
        if (!::agentSkillSlotOccupancy.isInitialized) return
        val slot = selectedAgentSkillSlot()
        val targetId = agentSkillDraftSession.current()?.let { record ->
            record.sourceSkillId ?: record.draft.id.trim().takeIf { it.isNotEmpty() }
        }
        val occupancy = agentSkillCatalog?.occupancy(slot, targetId)
            ?: SkillSlotOccupancy(SkillSlotOccupancyKind.EMPTY, slot)
        agentSkillSlotOccupancy.text = when {
            slot == null -> getString(R.string.skills_slot_not_selected)
            occupancy.kind == SkillSlotOccupancyKind.EMPTY -> getString(
                R.string.skills_slot_empty,
                SkillKeyOptions.labelOf(slot.keyCode),
                skillDirectionLabel(slot.direction),
            )
            occupancy.kind == SkillSlotOccupancyKind.CURRENT_SKILL -> getString(
                R.string.skills_slot_current,
                SkillKeyOptions.labelOf(slot.keyCode),
                skillDirectionLabel(slot.direction),
                occupancy.incumbentSkillName.orEmpty(),
            )
            else -> getString(
                R.string.skills_slot_occupied,
                SkillKeyOptions.labelOf(slot.keyCode),
                skillDirectionLabel(slot.direction),
                occupancy.incumbentSkillName.orEmpty(),
            )
        }
        agentSkillSlotOccupancy.setTextColor(
            getColor(
                if (occupancy.requiresReplacement) {
                    R.color.sense_accent
                } else {
                    R.color.sense_secondary
                },
            ),
        )
    }

    private fun clearAgentSkillConfirmations() {
        pendingSkillReplacement = null
        pendingSkillDocumentConflict = null
        updateAgentSkillConfirmationControls()
    }

    private fun updateAgentSkillConfirmationControls() {
        if (!::agentSkillSaveButton.isInitialized || !::agentSkillBindButton.isInitialized) return
        agentSkillSaveButton.setText(
            if (
                pendingSkillDocumentConflict != null ||
                pendingSkillReplacement?.operation == SkillReplacementOperation.CREATE
            ) {
                R.string.skills_save_confirm
            } else {
                R.string.skills_save
            },
        )
        agentSkillBindButton.setText(
            if (pendingSkillReplacement?.operation == SkillReplacementOperation.BIND) {
                R.string.skills_bind_confirm
            } else {
                R.string.skills_bind
            },
        )
    }

    private fun handleAgentSkillMutationFailure(error: Throwable) {
        clearAgentSkillConfirmations()
        if (error.message.orEmpty().startsWith(SKILL_GENERATION_CONFLICT_PREFIX)) {
            loadAgentSkillsPreservingDraft(R.string.skills_generation_conflict_refreshed)
            return
        }
        agentSkillStatus.text = getString(
            R.string.skills_operation_degraded,
            error.message.orEmpty(),
        )
        agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
    }

    private fun loadAgentSkillRevisionList(skillId: String) {
        agentSkillHistoryDegradedMessage = null
        viewedHistoricalRevision = null
        agentSkillHistoryPreview.setText(R.string.skills_history_preview_empty)
        val viewGeneration = agentSkillViewGeneration
        agentSkillIoSession.refresh(
            channel = SKILL_IO_HISTORY_LIST_CHANNEL,
            operation = {
                agentSkillStore.listRevisions(skillId).getOrThrow().sortedDescending()
            },
        ) { result ->
            if (
                !isCurrentAgentSkillView(viewGeneration) ||
                selectedAgentSkill()?.id != skillId
            ) {
                return@refresh
            }
            result
                .onSuccess { revisions ->
                    agentSkillRevisionNumbers = revisions
                    val wasApplying = applyingAgentSkillUi
                    applyingAgentSkillUi = true
                    try {
                        val currentRevision = agentSkillCatalog?.definition(skillId)?.revision
                        if (currentRevision !in agentSkillRevisionNumbers) {
                            agentSkillHistoryDegradedMessage = getString(
                                R.string.skills_history_current_missing,
                                currentRevision ?: 0L,
                            )
                        }
                        agentSkillRevisionSelector.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            agentSkillRevisionNumbers.map { revision ->
                                getString(
                                    if (revision == currentRevision) {
                                        R.string.skills_history_item_current
                                    } else {
                                        R.string.skills_history_item
                                    },
                                    revision,
                                )
                            },
                        )
                        agentSkillRevisionSelector.setSelection(0)
                    } finally {
                        applyingAgentSkillUi = wasApplying
                    }
                    agentSkillHistoryDegradedMessage?.let { message ->
                        agentSkillStatus.text = getString(
                            R.string.skills_history_degraded,
                            message,
                        )
                        agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                    updateAgentSkillHistoryControls()
                }
                .onFailure { error ->
                    agentSkillRevisionNumbers = emptyList()
                    agentSkillHistoryDegradedMessage = error.message.orEmpty()
                    agentSkillStatus.text = getString(
                        R.string.skills_history_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    agentSkillHistoryPreview.text = getString(
                        R.string.skills_history_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillHistoryPreview.setTextColor(
                        getColor(android.R.color.holo_red_dark),
                    )
                    updateAgentSkillHistoryControls()
                }
        }
    }

    private fun clearAgentSkillHistory() {
        agentSkillHistoryDegradedMessage = null
        agentSkillRevisionNumbers = emptyList()
        viewedHistoricalRevision = null
        agentSkillRevisionSelector.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            emptyList<String>(),
        )
        agentSkillHistoryPreview.setText(R.string.skills_history_preview_new)
        agentSkillHistoryPreview.setTextColor(getColor(R.color.sense_secondary))
        updateAgentSkillHistoryControls()
    }

    private fun viewSelectedAgentSkillRevision() {
        val skill = selectedAgentSkill() ?: return showAgentSkillError(
            getString(R.string.skills_save_before_history),
        )
        val revision = agentSkillRevisionNumbers
            .getOrNull(agentSkillRevisionSelector.selectedItemPosition)
            ?: return showAgentSkillError(getString(R.string.skills_history_not_ready))
        val viewGeneration = agentSkillViewGeneration
        agentSkillViewRevisionButton.isEnabled = false
        agentSkillIoSession.refresh(
            channel = SKILL_IO_HISTORY_REVISION_CHANNEL,
            operation = { agentSkillStore.readRevision(skill.id, revision).getOrThrow() },
        ) { result ->
            if (
                !isCurrentAgentSkillView(viewGeneration) ||
                selectedAgentSkill()?.id != skill.id
            ) {
                return@refresh
            }
            agentSkillViewRevisionButton.isEnabled = !agentSkillMutationRunning
            result
                .onSuccess { historical ->
                    val definition = historical ?: run {
                        agentSkillHistoryDegradedMessage =
                            getString(R.string.skills_history_revision_missing, revision)
                        agentSkillStatus.text = getString(
                            R.string.skills_history_degraded,
                            agentSkillHistoryDegradedMessage.orEmpty(),
                        )
                        agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                        agentSkillHistoryPreview.text = agentSkillStatus.text
                        agentSkillHistoryPreview.setTextColor(
                            getColor(android.R.color.holo_red_dark),
                        )
                        updateAgentSkillHistoryControls()
                        return@onSuccess
                    }
                    viewedHistoricalRevision = definition
                    agentSkillHistoryPreview.text = getString(
                        R.string.skills_history_preview,
                        definition.revision,
                        definition.name,
                        definition.description,
                        skillIntentLabel(definition.baseIntent),
                        definition.content,
                    )
                    agentSkillHistoryPreview.setTextColor(getColor(R.color.sense_primary))
                    updateAgentSkillHistoryControls()
                }
                .onFailure { error ->
                    agentSkillHistoryDegradedMessage = error.message.orEmpty()
                    agentSkillStatus.text = getString(
                        R.string.skills_history_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    agentSkillHistoryPreview.text = getString(
                        R.string.skills_history_degraded,
                        error.message.orEmpty(),
                    )
                    agentSkillHistoryPreview.setTextColor(
                        getColor(android.R.color.holo_red_dark),
                    )
                    updateAgentSkillHistoryControls()
                }
        }
    }

    private fun restoreSelectedAgentSkillRevision() {
        agentSkillDraftRecoveryWriteAuthorized = true
        val catalog = agentSkillCatalog ?: return showAgentSkillError(
            getString(R.string.skills_not_ready),
        )
        val current = selectedAgentSkill() ?: return showAgentSkillError(
            getString(R.string.skills_save_before_history),
        )
        val historical = viewedHistoricalRevision ?: return showAgentSkillError(
            getString(R.string.skills_history_view_first),
        )
        if (historical.id != current.id || historical.revision == current.revision) {
            return showAgentSkillError(getString(R.string.skills_history_choose_old))
        }
        val mutation = historical.restoreAsNewRevision(current, catalog.generation)
        if (mutation == null) {
            agentSkillStatus.setText(R.string.skills_history_same_content)
            agentSkillStatus.setTextColor(getColor(R.color.sense_secondary))
            return
        }
        captureAgentSkillDraftFromViews()
        val hadUnsavedDraft = agentSkillDraftSession.current()?.documentDirty == true
        submitAgentSkillMutation(mutation) { updatedCatalog ->
            if (!hadUnsavedDraft) {
                agentSkillDraftSession =
                    agentSkillDraftSession.acceptSaved(updatedCatalog, current.id)
            }
            applyAgentSkillCatalog(updatedCatalog)
            agentSkillStatus.setText(
                if (hadUnsavedDraft) {
                    R.string.skills_history_restored_draft_retained
                } else {
                    R.string.skills_history_restored
                },
            )
            agentSkillStatus.setTextColor(getColor(R.color.sense_success))
        }
    }

    private fun updateAgentSkillHistoryControls() {
        if (!::agentSkillRestoreRevisionButton.isInitialized) return
        val currentRevision = selectedAgentSkill()?.revision
        agentSkillRestoreRevisionButton.isEnabled =
            !agentSkillMutationRunning &&
                viewedHistoricalRevision?.revision?.let { it != currentRevision } == true
    }

    private fun requestAgentSkillDraftSessionRestore(bundled: ByteArray?) {
        agentSkillIoSession.refresh(
            channel = SKILL_IO_DRAFT_RESTORE_CHANNEL,
            operation = {
                try {
                    val bundledState = bundled
                        ?.let {
                            runCatching { SkillDraftSessionCodec.decode(it) }.getOrNull()
                        }
                    val restored = bundledState
                        ?: agentSkillDraftRecoveryStore.load().getOrThrow()
                        ?: SkillDraftSessionState()
                    agentSkillDraftRestoreWorkerState = restored
                    restored
                } catch (error: Throwable) {
                    agentSkillDraftRestoreWorkerFailure = error
                    throw error
                } finally {
                    agentSkillDraftRestoreWorkerCompleted = true
                }
            },
        ) { result ->
            agentSkillDraftRestoreCompleted = true
            val changedWhileRestoring = agentSkillDraftChangedWhileRestoring
            result
                .onSuccess { restored ->
                    agentSkillDraftSession = if (changedWhileRestoring) {
                        mergeAgentSkillDraftSessions(restored, agentSkillDraftSession)
                    } else {
                        restored
                    }
                    agentSkillDraftRecoveryError = null
                }
                .onFailure { error ->
                    agentSkillDraftRecoveryError = error.message.orEmpty()
                    /*
                     * With no new input, keep the unreadable bytes untouched. If the user edited
                     * while recovery was running, the store's next save first preserves those
                     * unreadable bytes verbatim and then publishes the new draft.
                     */
                    agentSkillDraftRecoveryWriteAuthorized = changedWhileRestoring
                }
            agentSkillDraftChangedWhileRestoring = false
            if (
                agentSkillDraftSaveRequestedWhileRestoring &&
                agentSkillDraftRecoveryWriteAuthorized
            ) {
                agentSkillDraftSaveRequestedWhileRestoring = false
                persistAgentSkillDraftSession()
            }
        }
    }

    private fun scheduleAgentSkillDraftPersistence() {
        if (!::screenContent.isInitialized) return
        screenContent.removeCallbacks(persistAgentSkillDraftRunnable)
        screenContent.postDelayed(persistAgentSkillDraftRunnable, DRAFT_PERSIST_DEBOUNCE_MS)
    }

    private fun persistAgentSkillDraftSession() {
        if (!::screenContent.isInitialized) return
        screenContent.removeCallbacks(persistAgentSkillDraftRunnable)
        if (!agentSkillDraftRestoreCompleted) {
            agentSkillDraftSaveRequestedWhileRestoring = true
            return
        }
        if (!agentSkillDraftRecoveryWriteAuthorized) return
        val snapshot = agentSkillDraftSession
        agentSkillIoSession.execute(
            operation = {
                agentSkillDraftRecoveryStore.save(snapshot).getOrThrow()
            },
        ) { result ->
            result
                .onSuccess { outcome ->
                    agentSkillDraftRecoveryError = null
                    if (outcome.preservedUnreadableSnapshot) {
                        agentSkillDraftRecoveryNotice = getString(
                            R.string.skills_draft_unreadable_preserved,
                        )
                    }
                }
                .onFailure { error ->
                    agentSkillDraftRecoveryError = error.message.orEmpty()
                    if (::agentSkillStatus.isInitialized && agentSkillEditorAttached) {
                        agentSkillStatus.text = getString(
                            R.string.skills_draft_recovery_degraded,
                            error.message.orEmpty(),
                        )
                        agentSkillStatus.setTextColor(
                            getColor(android.R.color.holo_red_dark),
                        )
                    }
                }
        }
    }

    /**
     * Synchronous only at Android's saved-state boundary for a session too large for Bundle.
     *
     * Submitting to the same process executor waits behind every prior draft write and any
     * in-flight restore, then publishes the latest merged state last. This closes the otherwise
     * unavoidable edit → onSave → process-kill window without moving ordinary edits or IME input
     * onto a filesystem thread.
     */
    private fun persistAgentSkillDraftSessionForLifecycle():
        SkillDraftRecoveryStore.SaveOutcome? {
        if (!agentSkillDraftRecoveryWriteAuthorized) return null
        if (::screenContent.isInitialized) {
            screenContent.removeCallbacks(persistAgentSkillDraftRunnable)
        }
        val inMemorySnapshot = agentSkillDraftSession
        val restoreCompleted = agentSkillDraftRestoreCompleted
        val changedWhileRestoring = agentSkillDraftChangedWhileRestoring
        val saveKey = SkillDraftLifecycleSaveKey(
            snapshot = inMemorySnapshot,
            restoreCompleted = restoreCompleted,
            changedWhileRestoring = changedWhileRestoring,
        )
        return agentSkillDurabilityBarrier.execute(saveKey) {
            val finalSnapshot = if (restoreCompleted) {
                inMemorySnapshot
            } else {
                check(agentSkillDraftRestoreWorkerCompleted) {
                    "Skill draft restore did not precede the lifecycle save"
                }
                val restoreFailure = agentSkillDraftRestoreWorkerFailure
                if (restoreFailure != null && !changedWhileRestoring) {
                    throw restoreFailure
                }
                val restored = agentSkillDraftRestoreWorkerState ?: SkillDraftSessionState()
                if (changedWhileRestoring) {
                    mergeAgentSkillDraftSessions(restored, inMemorySnapshot)
                } else {
                    restored
                }
            }
            agentSkillDraftRecoveryStore.save(finalSnapshot).getOrThrow()
        }
            .onSuccess { outcome ->
                agentSkillDraftRecoveryError = null
                if (outcome.preservedUnreadableSnapshot) {
                    agentSkillDraftRecoveryNotice = getString(
                        R.string.skills_draft_unreadable_preserved,
                    )
                }
            }
            .onFailure { error ->
                agentSkillDraftRecoveryError = error.message.orEmpty()
            }
            .getOrNull()
    }

    private fun mergeAgentSkillDraftSessions(
        restored: SkillDraftSessionState,
        inMemory: SkillDraftSessionState,
    ): SkillDraftSessionState {
        if (inMemory.records.isEmpty()) return restored
        val mergedRecords = restored.records + inMemory.records
        return SkillDraftSessionState(
            selectedKey = inMemory.selectedKey ?: restored.selectedKey,
            records = mergedRecords,
        )
    }

    private fun submitAgentSkillMutation(
        mutation: AgentSkillMutation,
        onSuccess: (AgentSkillCatalog) -> Unit,
    ) {
        if (agentSkillMutationRunning) return
        val viewGeneration = agentSkillViewGeneration
        agentSkillMutationRunning = true
        setAgentSkillEditorEnabled(false)
        val accepted = agentSkillIoSession.execute(
            operation = { agentSkillStore.apply(mutation).getOrThrow() },
        ) { result ->
            agentSkillMutationRunning = false
            if (!isCurrentAgentSkillView(viewGeneration)) return@execute
            result
                .onSuccess(onSuccess)
                .onFailure(::handleAgentSkillMutationFailure)
            if (!agentSkillCatalogLoadInFlight) {
                setAgentSkillEditorEnabled(agentSkillCatalog != null)
            }
        }
        if (!accepted) {
            agentSkillMutationRunning = false
            if (isCurrentAgentSkillView(viewGeneration)) {
                setAgentSkillEditorEnabled(agentSkillCatalog != null)
                showAgentSkillError(getString(R.string.skills_not_ready))
            }
        }
    }

    private fun isCurrentAgentSkillView(viewGeneration: Long): Boolean =
        navigation.current == SettingsSection.SKILLS &&
            agentSkillEditorAttached &&
            agentSkillViewGeneration == viewGeneration

    private fun updateAgentSkillBindingSummary(skillId: String) {
        val bindings = agentSkillCatalog?.bindings.orEmpty().filter { it.skillId == skillId }
        agentSkillBindingSummary.text = if (bindings.isEmpty()) {
            getString(R.string.skills_bindings_none)
        } else {
            bindings.joinToString(
                prefix = getString(R.string.skills_bindings_prefix),
                separator = "\n",
            ) {
                "${SkillKeyOptions.labelOf(it.slot.keyCode)} · " +
                    skillDirectionLabel(it.slot.direction)
            }
        }
    }

    private fun setAgentSkillEditorEnabled(enabled: Boolean) {
        listOf(
            agentSkillSelector,
            agentSkillName,
            agentSkillDescription,
            agentSkillContent,
            agentSkillIntent,
            agentSkillKey,
            agentSkillDirection,
            agentSkillRevisionSelector,
            agentSkillCreateButton,
            agentSkillDiscardButton,
            agentSkillViewRevisionButton,
            agentSkillUnbindSlotButton,
            agentSkillUnbindAllButton,
        ).forEach { it.isEnabled = enabled }
        agentSkillId.isEnabled = enabled && creatingAgentSkill
        agentSkillSaveButton.isEnabled = enabled
        agentSkillBindButton.isEnabled = enabled
        agentSkillViewRevisionButton.isEnabled =
            enabled && agentSkillRevisionNumbers.isNotEmpty()
        if (enabled) {
            updateAgentSkillHistoryControls()
        } else {
            agentSkillRestoreRevisionButton.isEnabled = false
        }
    }

    private fun showAgentSkillError(error: Throwable) =
        showAgentSkillError(error.message.orEmpty())

    private fun showAgentSkillError(message: String) {
        agentSkillStatus.text = getString(R.string.skills_save_failed, message)
        agentSkillStatus.setTextColor(getColor(android.R.color.holo_red_dark))
    }

    private fun skillIntentLabel(intent: EditorIntent): String = when (intent) {
        EditorIntent.SMART_EDIT -> "智能编辑"
        EditorIntent.ANSWER -> "回答"
        EditorIntent.REWRITE -> "改写"
        EditorIntent.CONTINUE -> "续写"
        EditorIntent.TRANSLATE -> "翻译"
        EditorIntent.FORMAT -> "整理格式"
        EditorIntent.NO_CHANGE -> "不修改"
    }

    private fun skillDirectionLabel(direction: AgentSkillDirection): String = when (direction) {
        AgentSkillDirection.UP -> "上滑"
        AgentSkillDirection.RIGHT -> "右滑"
        AgentSkillDirection.DOWN -> "下滑"
        AgentSkillDirection.LEFT -> "左滑"
    }

    private fun renderAbout() {
        renderDetailHeader(R.string.settings_about_title, R.string.settings_about_summary)
        screenContent.addView(
            card(
                R.string.m0_title,
                text(R.string.m0_body, 15f, R.color.sense_secondary),
            ).withTop(dp(20)),
        )
        screenContent.addView(
            card(
                R.string.dictionary_notice_title,
                text(R.string.dictionary_notice_body, 13f, R.color.sense_secondary),
            ).apply {
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                setOnClickListener { showDictionaryNotice() }
            }.withTop(dp(12)),
        )
        screenContent.addView(text(R.string.version_label, 12f, R.color.sense_secondary).withTop(dp(24)))
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized || navigation.current != SettingsSection.HOME) return
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        statusText.setText(if (enabled) R.string.ime_enabled else R.string.ime_disabled)
        statusText.setTextColor(getColor(if (enabled) R.color.sense_success else R.color.sense_primary))
    }

    private fun providerForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(text(R.string.ai_provider_body, 13f, R.color.sense_secondary))
        providerPreset = accessibleSpinner(R.string.ai_provider_preset).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ProviderPresetCatalog.presets.map(ProviderPreset::displayName),
            )
        }
        providerName = editField(R.string.ai_provider_name, "OpenAI")
        providerBaseUrl = editField(R.string.ai_provider_base_url, ProviderProfile.DEFAULT_OPENAI_BASE_URL)
        providerModel = editField(R.string.ai_provider_model, DEFAULT_PROVIDER_MODEL)
        providerApiKey = editField(R.string.ai_provider_key, "sk-…").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
        }
        providerApiStyle = accessibleSpinner(R.string.ai_provider_style).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenAI Responses", "OpenAI-compatible Chat Completions"),
            )
        }
        providerStructuredOutput =
            accessibleSpinner(R.string.ai_provider_structured_output).apply {
                adapter = ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        "严格 JSON Schema（OpenAI Responses 推荐）",
                        "JSON Object（兼容服务推荐）",
                        "仅提示词约束（最广兼容）",
                    ),
                )
            }
        providerThinkingMode =
            accessibleSpinner(R.string.ai_provider_reasoning_strength).apply {
                adapter = ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        "快速 · 最低延迟",
                        "均衡 · Provider 自动判断",
                        "深度 · 更强推理",
                    ),
                )
            }
        providerStreaming = Switch(this@SettingsActivity).apply {
            setText(R.string.ai_provider_stream)
            isChecked = true
            setTextColor(getColor(R.color.sense_primary))
            minimumHeight = dp(48)
        }
        providerAdvanced = Switch(this@SettingsActivity).apply {
            setText(R.string.ai_provider_advanced)
            setTextColor(getColor(R.color.sense_primary))
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, _ -> updateProviderAdvancedVisibility() }
        }
        providerAdvancedFields = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeledField(R.string.ai_provider_name, providerName))
            addView(labeledField(R.string.ai_provider_base_url, providerBaseUrl).withTop(dp(10)))
            addView(labeledField(R.string.ai_provider_model, providerModel).withTop(dp(10)))
            addView(labeledField(R.string.ai_provider_style, providerApiStyle).withTop(dp(10)))
            addView(
                labeledField(
                    R.string.ai_provider_structured_output,
                    providerStructuredOutput,
                ).withTop(dp(10)),
            )
            addView(providerStreaming.withTop(dp(10)))
        }

        addView(labeledField(R.string.ai_provider_preset, providerPreset).withTop(dp(14)))
        addView(labeledField(R.string.ai_provider_key, providerApiKey).withTop(dp(10)))
        addView(
            labeledField(
                R.string.ai_provider_reasoning_strength,
                providerThinkingMode,
            ).withTop(dp(10)),
        )
        addView(providerAdvanced.withTop(dp(10)))
        addView(providerAdvancedFields.withTop(dp(10)))

        addView(secondaryButton(R.string.ai_provider_save, ::saveProviderSettings).withTop(dp(12)))
        providerTestButton = primaryButton(
            R.string.ai_provider_test,
            ::saveAndTestProviderConnection,
        )
        addView(providerTestButton.withTop(dp(8)))
        addView(secondaryButton(R.string.ai_provider_validate, ::validateSavedProvider).withTop(dp(8)))
        addView(
            secondaryButton(
                R.string.ai_provider_clear_key,
                ::clearProviderCredential,
            ).withTop(dp(8)),
        )
        providerStatus =
            text(R.string.ai_provider_not_configured, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        addView(providerStatus.withTop(dp(10)))

        providerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val changed = position != selectedProviderPresetPosition
                selectedProviderPresetPosition = position
                if (!providerUiLoaded || !changed) {
                    updateProviderAdvancedVisibility()
                    return
                }
                providerApiKey.text.clear()
                val preset = ProviderPresetCatalog.presets[position]
                if (preset.isCustom) {
                    providerAdvanced.isChecked = true
                } else {
                    providerAdvanced.isChecked = false
                    applyProviderPresetFields(preset)
                }
                updateProviderAdvancedVisibility()
                updateProviderKeyHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateProviderAdvancedVisibility()
    }

    private fun agentToolsForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(R.string.agent_tools_body, 13f, R.color.sense_secondary))

        agentToolsMaster = agentToolSwitch(R.string.agent_tools_master).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        agentToolWebSearch = agentToolSwitch(R.string.agent_tool_web_search)
        agentToolWebRead = agentToolSwitch(R.string.agent_tool_web_read)
        agentToolCalculator = agentToolSwitch(R.string.agent_tool_calculator)
        agentToolLocalMemoryRecall = agentToolSwitch(R.string.agent_tool_local_memory_recall)
        agentToolSkillRead = agentToolSwitch(R.string.agent_tool_skill_read)
        agentToolSkillManage = agentToolSwitch(R.string.agent_tool_skill_manage)

        addView(agentToolsMaster.withTop(dp(12)))
        addView(agentToolWebSearch.withTop(dp(8)))
        addView(agentToolWebRead.withTop(dp(8)))
        addView(agentToolCalculator.withTop(dp(8)))
        addView(agentToolLocalMemoryRecall.withTop(dp(8)))
        addView(agentToolSkillRead.withTop(dp(8)))
        addView(agentToolSkillManage.withTop(dp(8)))

        agentToolsStatus =
            text(R.string.agent_tools_status_loading, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        addView(agentToolsStatus.withTop(dp(10)))

        agentToolsMaster.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        agentToolWebSearch.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        agentToolWebRead.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        agentToolCalculator.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        agentToolLocalMemoryRecall.setOnCheckedChangeListener { _, _ ->
            onAgentToolSwitchChanged()
        }
        agentToolSkillRead.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        agentToolSkillManage.setOnCheckedChangeListener { _, _ -> onAgentToolSwitchChanged() }
        updateAgentToolSwitchAvailability()
    }

    private fun agentToolSwitch(labelRes: Int): Switch = Switch(this).apply {
        setText(labelRes)
        setTextColor(getColor(R.color.sense_primary))
        isChecked = true
        minimumHeight = dp(48)
    }

    private fun onAgentToolSwitchChanged() {
        if (
            !agentToolsUiLoaded ||
            agentToolsSaveRunning ||
            applyingAgentToolSettings
        ) {
            return
        }
        updateAgentToolSwitchAvailability()
        val settings = agentToolSettingsFromUi()
        val viewGeneration = agentSkillViewGeneration
        agentToolsSaveRunning = true
        setAgentToolSwitchesEnabled(false)
        val accepted = agentSkillIoSession.execute(
            operation = { agentToolStore.save(settings).getOrThrow() },
        ) { result ->
            agentToolsSaveRunning = false
            result
                .onSuccess {
                    lastSavedAgentToolSettings = settings
                    if (!isCurrentAgentToolsView(viewGeneration)) return@onSuccess
                    setAgentToolSwitchesEnabled(true)
                    updateAgentToolStatus(settings, saved = true)
                }
                .onFailure {
                    if (!isCurrentAgentToolsView(viewGeneration)) return@onFailure
                    applyAgentToolSettings(lastSavedAgentToolSettings)
                    agentToolsStatus.setText(R.string.agent_tools_status_save_failed)
                    agentToolsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    setAgentToolSwitchesEnabled(true)
                }
        }
        if (!accepted) {
            agentToolsSaveRunning = false
            if (isCurrentAgentToolsView(viewGeneration)) {
                applyAgentToolSettings(lastSavedAgentToolSettings)
                agentToolsStatus.setText(R.string.agent_tools_status_save_failed)
                agentToolsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                setAgentToolSwitchesEnabled(true)
            }
        }
    }

    private fun loadAgentToolSettings() {
        if (!::agentToolsStatus.isInitialized) return
        val viewGeneration = agentSkillViewGeneration
        if (
            agentToolsLoadInFlight &&
            agentToolsLoadViewGeneration == viewGeneration
        ) {
            return
        }
        agentToolsLoadInFlight = true
        agentToolsLoadViewGeneration = viewGeneration
        setAgentToolSwitchesEnabled(false)
        val accepted = agentSkillIoSession.refresh(
            channel = SETTINGS_IO_TOOLS_CHANNEL,
            operation = { agentToolStore.load().getOrThrow() },
        ) { result ->
            agentToolsLoadInFlight = false
            if (!isCurrentAgentToolsView(viewGeneration)) return@refresh
            result
                .onSuccess { settings ->
                    lastSavedAgentToolSettings = settings
                    setAgentToolSwitchesEnabled(true)
                    applyAgentToolSettings(settings)
                    agentToolsUiLoaded = true
                    updateAgentToolStatus(settings, saved = false)
                }
                .onFailure {
                    applyAgentToolSettings(AgentToolSettings())
                    agentToolsUiLoaded = false
                    agentToolsStatus.setText(R.string.agent_tools_status_invalid)
                    agentToolsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    setAgentToolSwitchesEnabled(false)
                }
        }
        if (!accepted) {
            agentToolsLoadInFlight = false
            if (isCurrentAgentToolsView(viewGeneration)) {
                agentToolsUiLoaded = false
                agentToolsStatus.setText(R.string.agent_tools_status_invalid)
                agentToolsStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                setAgentToolSwitchesEnabled(false)
            }
        }
    }

    private fun isCurrentAgentToolsView(viewGeneration: Long): Boolean =
        navigation.current == SettingsSection.TOOLS &&
            agentSkillViewGeneration == viewGeneration &&
            ::agentToolsStatus.isInitialized

    private fun applyAgentToolSettings(settings: AgentToolSettings) {
        applyingAgentToolSettings = true
        try {
            agentToolsMaster.isChecked = settings.masterEnabled
            agentToolWebSearch.isChecked = settings.webSearchEnabled
            agentToolWebRead.isChecked = settings.webFetchEnabled
            agentToolCalculator.isChecked = settings.calculatorEnabled
            agentToolLocalMemoryRecall.isChecked = settings.memorySearchEnabled
            agentToolSkillRead.isChecked = settings.skillReadEnabled
            agentToolSkillManage.isChecked = settings.skillManageEnabled
            updateAgentToolSwitchAvailability()
        } finally {
            applyingAgentToolSettings = false
        }
    }

    private fun agentToolSettingsFromUi(): AgentToolSettings = AgentToolSettings(
        masterEnabled = agentToolsMaster.isChecked,
        webSearchEnabled = agentToolWebSearch.isChecked,
        webFetchEnabled = agentToolWebRead.isChecked,
        calculatorEnabled = agentToolCalculator.isChecked,
        memorySearchEnabled = agentToolLocalMemoryRecall.isChecked,
        skillReadEnabled = agentToolSkillRead.isChecked,
        skillManageEnabled = agentToolSkillManage.isChecked,
    )

    private fun updateAgentToolSwitchAvailability() {
        if (!::agentToolsMaster.isInitialized) return
        val enabled = agentToolsMaster.isEnabled && agentToolsMaster.isChecked
        agentToolWebSearch.isEnabled = enabled
        agentToolWebRead.isEnabled = enabled
        agentToolCalculator.isEnabled = enabled
        agentToolLocalMemoryRecall.isEnabled = enabled
        agentToolSkillRead.isEnabled = enabled
        agentToolSkillManage.isEnabled = enabled
    }

    private fun setAgentToolSwitchesEnabled(enabled: Boolean) {
        agentToolsMaster.isEnabled = enabled
        agentToolWebSearch.isEnabled = enabled && agentToolsMaster.isChecked
        agentToolWebRead.isEnabled = enabled && agentToolsMaster.isChecked
        agentToolCalculator.isEnabled = enabled && agentToolsMaster.isChecked
        agentToolLocalMemoryRecall.isEnabled = enabled && agentToolsMaster.isChecked
        agentToolSkillRead.isEnabled = enabled && agentToolsMaster.isChecked
        agentToolSkillManage.isEnabled = enabled && agentToolsMaster.isChecked
    }

    private fun updateAgentToolStatus(settings: AgentToolSettings, saved: Boolean) {
        if (!settings.masterEnabled) {
            agentToolsStatus.setText(R.string.agent_tools_status_off)
        } else {
            agentToolsStatus.text = getString(
                if (saved) R.string.agent_tools_status_saved else R.string.agent_tools_status_ready,
                settings.enabledToolIds().size,
            )
        }
        agentToolsStatus.setTextColor(
            getColor(if (settings.masterEnabled) R.color.sense_success else R.color.sense_secondary),
        )
    }

    private fun speechProviderForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(R.string.speech_provider_body, 13f, R.color.sense_secondary))

        speechPreset = accessibleSpinner(R.string.speech_provider_preset).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SpeechProviderPresetCatalog.all.map(SpeechProviderPreset::displayName),
            )
        }
        speechLanguage = accessibleSpinner(R.string.speech_provider_language).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SPEECH_LANGUAGES.map { it.first },
            )
        }
        speechApiKey = editField(R.string.speech_provider_key, "可选；系统模式无需 Key").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
        }
        speechEndpoint = editField(R.string.speech_provider_endpoint, "https://…")
        speechModel = editField(R.string.speech_provider_model, "model")
        speechAdvanced = Switch(this@SettingsActivity).apply {
            setText(R.string.speech_provider_advanced)
            setTextColor(getColor(R.color.sense_primary))
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, _ -> updateSpeechAdvancedVisibility() }
        }
        speechAdvancedFields = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeledField(R.string.speech_provider_endpoint, speechEndpoint))
            addView(labeledField(R.string.speech_provider_model, speechModel).withTop(dp(10)))
        }

        addView(labeledField(R.string.speech_provider_preset, speechPreset).withTop(dp(14)))
        addView(labeledField(R.string.speech_provider_language, speechLanguage).withTop(dp(10)))
        addView(labeledField(R.string.speech_provider_key, speechApiKey).withTop(dp(10)))
        addView(speechAdvanced.withTop(dp(10)))
        addView(speechAdvancedFields.withTop(dp(10)))
        addView(secondaryButton(R.string.speech_provider_save, ::saveSpeechSettings).withTop(dp(12)))
        addView(
            secondaryButton(
                R.string.speech_provider_clear_key,
                ::clearSpeechCredential,
            ).withTop(dp(8)),
        )
        speechPermissionButton = primaryButton(
            R.string.speech_permission_grant,
            ::requestSpeechPermission,
        )
        addView(speechPermissionButton.withTop(dp(8)))
        speechStatus =
            text(R.string.speech_provider_not_configured, 12f, R.color.sense_secondary).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        addView(speechStatus.withTop(dp(10)))

        speechPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val changed = position != selectedSpeechPresetPosition
                selectedSpeechPresetPosition = position
                if (speechUiLoaded && changed) {
                    speechApiKey.text.clear()
                    applySpeechPresetFields(selectedSpeechPreset())
                    speechAdvanced.isChecked = false
                }
                updateSpeechAdvancedVisibility()
                updateSpeechKeyHint()
                updateSpeechCapabilityStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateSpeechAdvancedVisibility()
    }

    private fun selectedSpeechPreset(): SpeechProviderPreset =
        SpeechProviderPresetCatalog.all[
            speechPreset.selectedItemPosition.coerceIn(
                0,
                SpeechProviderPresetCatalog.all.lastIndex,
            )
        ]

    private fun applySpeechPresetFields(preset: SpeechProviderPreset) {
        speechEndpoint.setText(preset.defaultEndpointUrl.orEmpty())
        speechModel.setText(preset.defaultModel.orEmpty())
    }

    private fun updateSpeechAdvancedVisibility() {
        if (!::speechAdvancedFields.isInitialized || !::speechPreset.isInitialized) return
        val cloud = selectedSpeechPreset().defaultEndpointUrl != null
        speechAdvanced.isEnabled = cloud
        if (!cloud && speechAdvanced.isChecked) {
            speechAdvanced.isChecked = false
            return
        }
        speechAdvancedFields.visibility =
            if (cloud && speechAdvanced.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateSpeechCapabilityStatus() {
        if (!::speechStatus.isInitialized || !::speechPreset.isInitialized) return
        val preset = selectedSpeechPreset()
        if (preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
            speechStatus.setText(
                when {
                    !hasSpeechPermission() ->
                        R.string.speech_provider_permission_needed
                    preset.id == SpeechProviderPresetCatalog.SYSTEM ->
                        R.string.speech_provider_system_ready
                    else ->
                        R.string.speech_provider_cloud_ready
                },
            )
            speechStatus.setTextColor(
                getColor(
                    if (hasSpeechPermission()) R.color.sense_success
                    else R.color.sense_secondary,
                ),
            )
        } else {
            speechStatus.text = preset.capabilityNotice
                ?: getString(R.string.speech_provider_configuration_only)
            speechStatus.setTextColor(getColor(R.color.sense_secondary))
        }
    }

    private fun speechCredentialScope(
        preset: SpeechProviderPreset,
        endpointUrl: String?,
    ): String =
        "${preset.id}:${CredentialEndpointScope.normalize(endpointUrl.orEmpty())}"

    private fun updateSpeechKeyHint() {
        if (!::speechApiKey.isInitialized || !::speechPreset.isInitialized) return
        val preset = selectedSpeechPreset()
        if (preset.credentialRequirement == SpeechProviderCredentialRequirement.NONE) {
            speechApiKey.text.clear()
            speechApiKey.isEnabled = false
            speechApiKey.hint = getString(R.string.speech_provider_key_not_required)
            return
        }
        speechApiKey.isEnabled = true
        val currentScope = speechCredentialScope(
            preset = preset,
            endpointUrl = speechEndpoint.text.toString(),
        )
        val canPreserve = speechStore.hasCredential() &&
            loadedSpeechCredentialScope == currentScope
        speechApiKey.hint = getString(
            if (canPreserve) {
                R.string.speech_provider_key_saved
            } else {
                R.string.speech_provider_key_required
            },
        )
    }

    private fun saveSpeechSettings() {
        val preset = selectedSpeechPreset()
        val profile = preset.defaultProfile(selectedSpeechLanguageTag()).copy(
            endpointUrl = if (preset.defaultEndpointUrl == null) {
                null
            } else {
                speechEndpoint.text.toString().trim()
            },
            model = if (preset.defaultModel == null) {
                null
            } else {
                speechModel.text.toString().trim()
            },
        )
        val validation = profile.validate()
        if (!validation.isValid) {
            speechStatus.text = validation.errors.joinToString("\n") {
                "${it.path}: ${it.message}"
            }
            speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return
        }

        val enteredKey = speechApiKey.text.toString()
        val currentScope = speechCredentialScope(preset, profile.endpointUrl)
        val hasSavedCredential = speechStore.hasCredential()
        val canPreserveCredential =
            hasSavedCredential && loadedSpeechCredentialScope == currentScope
        val apiKey = when {
            preset.credentialRequirement == SpeechProviderCredentialRequirement.NONE ->
                CharArray(0)
            enteredKey.isEmpty() && !canPreserveCredential -> {
                speechStatus.setText(
                    if (hasSavedCredential) {
                        R.string.speech_provider_key_provider_changed
                    } else {
                        R.string.speech_provider_key_required_to_save
                    },
                )
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                return
            }
            enteredKey.isEmpty() -> null
            !SpeechProviderCredentialPolicy.isValid(enteredKey) -> {
                speechStatus.setText(R.string.speech_provider_key_invalid)
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                return
            }
            else -> enteredKey.toCharArray()
        }
        speechStore.save(profile, apiKey)
            .onSuccess {
                loadedSpeechCredentialScope = currentScope
                speechApiKey.text.clear()
                updateSpeechKeyHint()
                speechStatus.setText(
                    if (preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
                        R.string.speech_provider_saved
                    } else {
                        R.string.speech_provider_saved_configuration_only
                    },
                )
                speechStatus.setTextColor(
                    getColor(
                        if (preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
                            R.color.sense_success
                        } else {
                            R.color.sense_secondary
                        },
                    ),
                )
            }
            .onFailure {
                speechStatus.setText(R.string.speech_provider_save_failed)
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun clearSpeechCredential() {
        speechStore.loadProfile()
            .onSuccess { profile ->
                if (profile == null) {
                    speechStatus.setText(R.string.speech_provider_not_configured)
                    speechStatus.setTextColor(getColor(R.color.sense_secondary))
                    return@onSuccess
                }
                speechStore.save(profile, CharArray(0))
                    .onSuccess {
                        speechApiKey.text.clear()
                        updateSpeechKeyHint()
                        speechStatus.setText(R.string.speech_provider_key_cleared)
                        speechStatus.setTextColor(getColor(R.color.sense_success))
                    }
                    .onFailure {
                        speechStatus.setText(R.string.speech_provider_save_failed)
                        speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
            }
            .onFailure {
                speechStatus.setText(R.string.speech_provider_invalid)
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun loadSpeechSettings() {
        if (!::speechStatus.isInitialized) return
        speechStore.loadProfile()
            .onSuccess { profile ->
                val preset = profile?.let {
                    SpeechProviderPresetCatalog.find(it.presetId)
                } ?: SpeechProviderPresetCatalog.require(SpeechProviderPresetCatalog.SYSTEM)
                selectedSpeechPresetPosition = SpeechProviderPresetCatalog.all.indexOf(preset)
                    .coerceAtLeast(0)
                speechPreset.setSelection(selectedSpeechPresetPosition)
                applySpeechPresetFields(preset)
                if (profile != null) {
                    speechEndpoint.setText(profile.endpointUrl.orEmpty())
                    speechModel.setText(profile.model.orEmpty())
                    val languageIndex = SPEECH_LANGUAGES.indexOfFirst {
                        it.second == profile.languageTag
                    }
                    speechLanguage.setSelection(languageIndex.coerceAtLeast(0))
                }
                loadedSpeechCredentialScope = profile?.let {
                    speechCredentialScope(preset, it.endpointUrl)
                }
                speechAdvanced.isChecked = false
                speechUiLoaded = true
                updateSpeechAdvancedVisibility()
                updateSpeechKeyHint()
                updateSpeechCapabilityStatus()
            }
            .onFailure {
                loadedSpeechCredentialScope = null
                val preset =
                    SpeechProviderPresetCatalog.require(SpeechProviderPresetCatalog.SYSTEM)
                selectedSpeechPresetPosition = SpeechProviderPresetCatalog.all.indexOf(preset)
                    .coerceAtLeast(0)
                speechPreset.setSelection(selectedSpeechPresetPosition)
                applySpeechPresetFields(preset)
                speechAdvanced.isChecked = false
                speechUiLoaded = true
                updateSpeechAdvancedVisibility()
                updateSpeechKeyHint()
                speechStatus.setText(R.string.speech_provider_invalid)
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun selectedSpeechLanguageTag(): String =
        SPEECH_LANGUAGES[
            speechLanguage.selectedItemPosition.coerceIn(0, SPEECH_LANGUAGES.lastIndex)
        ].second

    private fun requestSpeechPermission() {
        if (hasSpeechPermission()) {
            updateSpeechPermissionButton()
            return
        }
        if (
            speechPermissionDeniedOnce &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        speechPermissionRequestInFlight = true
        updateSpeechPermissionButton()
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO,
        )
    }

    private fun hasSpeechPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateSpeechPermissionButton() {
        if (!::speechPermissionButton.isInitialized) return
        val granted = hasSpeechPermission()
        speechPermissionButton.setText(
            when {
                granted -> R.string.speech_permission_granted
                speechPermissionDeniedOnce &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                    R.string.speech_permission_open_settings
                else -> R.string.speech_permission_grant
            },
        )
        speechPermissionButton.isEnabled = !granted && !speechPermissionRequestInFlight
        updateSpeechCapabilityStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            speechPermissionRequestInFlight = false
            speechPermissionDeniedOnce =
                grantResults.isEmpty() ||
                grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
            updateSpeechPermissionButton()
            if (!hasSpeechPermission()) {
                speechStatus.setText(R.string.speech_permission_denied)
                speechStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }
    }

    private fun saveProviderSettings() {
        if (providerTestRunning) providerTestClient.cancel()
        persistProviderSettings()
    }

    private fun saveAndTestProviderConnection() {
        if (providerTestRunning) {
            providerTestClient.cancel()
            return
        }
        persistProviderSettings {
            providerTestRunning = true
            providerTestButton.setText(R.string.ai_provider_test_cancel)
            providerStatus.setText(R.string.ai_provider_test_starting)
            providerStatus.setTextColor(getColor(R.color.sense_secondary))
            providerTestClient.start()
        }
    }

    private fun persistProviderSettings(onSaved: (() -> Unit)? = null) {
        applyKnownProviderPreset()
        val profile = currentProviderProfile()
        if (!showProfileErrors(profile)) return

        val enteredKey = providerApiKey.text.toString()
        val currentScope = providerCredentialScope(profile)
        val hasSavedCredential = providerStore.hasCredential()
        val canPreserveCredential =
            hasSavedCredential && loadedProviderCredentialScope == currentScope
        val key = when {
            enteredKey.isEmpty() &&
                !selectedProviderPreset().isCustom &&
                !canPreserveCredential -> {
                providerStatus.setText(
                    if (hasSavedCredential) {
                        R.string.ai_provider_key_provider_changed
                    } else {
                        R.string.ai_provider_key_required
                    },
                )
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                return
            }
            enteredKey.isEmpty() && !canPreserveCredential ->
                if (hasSavedCredential) CharArray(0) else null
            enteredKey.isEmpty() -> null
            runCatching { ProviderCredential.Bearer(enteredKey) }.isFailure -> {
                providerStatus.setText(R.string.ai_provider_key_invalid)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                return
            }
            else -> enteredKey.toCharArray()
        }
        providerStore.save(profile, key)
            .onSuccess {
                loadedProviderCredentialScope = currentScope
                providerApiKey.text.clear()
                updateProviderKeyHint()
                providerStatus.setText(R.string.ai_provider_saved)
                providerStatus.setTextColor(getColor(R.color.sense_success))
                onSaved?.invoke()
            }
            .onFailure {
                providerStatus.setText(R.string.ai_provider_save_failed)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun currentProviderProfile(): ProviderProfile {
        val baseUrl = providerBaseUrl.text.toString().trim()
        val model = ProviderCompatibility.activeModelForSavedProfile(
            baseUrl = baseUrl,
            model = providerModel.text.toString().trim(),
        )
        if (model != providerModel.text.toString().trim()) {
            providerModel.setText(model)
        }
        val profile = ProviderProfile(
            id = "primary",
            displayName = providerName.text.toString().trim(),
            apiStyle = if (providerApiStyle.selectedItemPosition == 0) {
                ProviderApiStyle.OPENAI_RESPONSES
            } else {
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS
            },
            baseUrl = baseUrl,
            model = model,
            streaming = providerStreaming.isChecked,
            structuredOutput = when (providerStructuredOutput.selectedItemPosition) {
                0 -> StructuredOutputMode.JSON_SCHEMA
                1 -> StructuredOutputMode.JSON_OBJECT
                else -> StructuredOutputMode.PROMPT_ONLY
            },
        )
        return selectedReasoningStrength().applyTo(profile)
    }

    private fun providerCredentialScope(profile: ProviderProfile): String =
        CredentialEndpointScope.normalize(profile.baseUrl)

    private fun updateProviderKeyHint() {
        if (!::providerApiKey.isInitialized || !::providerPreset.isInitialized) return
        val profile = currentProviderProfile()
        val canPreserve = providerStore.hasCredential() &&
            loadedProviderCredentialScope == providerCredentialScope(profile)
        providerApiKey.hint = getString(
            when {
                canPreserve -> R.string.ai_provider_key_saved
                selectedProviderPreset().isCustom -> R.string.ai_provider_key_optional
                else -> R.string.ai_provider_key_required
            },
        )
    }

    private fun selectedReasoningStrength(): ProviderReasoningStrength =
        ProviderReasoningStrength.entries[
            providerThinkingMode.selectedItemPosition.coerceIn(
                0,
                ProviderReasoningStrength.entries.lastIndex,
            )
        ]

    private fun selectedProviderPreset(): ProviderPreset =
        ProviderPresetCatalog.presets[
            providerPreset.selectedItemPosition.coerceIn(
                0,
                ProviderPresetCatalog.presets.lastIndex,
            )
        ]

    private fun applyProviderPresetFields(preset: ProviderPreset) {
        providerName.setText(preset.providerName)
        providerBaseUrl.setText(preset.baseUrl)
        providerModel.setText(preset.model)
        providerApiStyle.setSelection(
            if (preset.apiStyle == ProviderApiStyle.OPENAI_RESPONSES) 0 else 1,
        )
        providerStructuredOutput.setSelection(
            when (preset.structuredOutput) {
                StructuredOutputMode.JSON_SCHEMA -> 0
                StructuredOutputMode.JSON_OBJECT -> 1
                StructuredOutputMode.PROMPT_ONLY -> 2
            },
        )
        providerStreaming.isChecked = true
    }

    private fun updateProviderAdvancedVisibility() {
        if (!::providerAdvancedFields.isInitialized) return
        val custom = ::providerPreset.isInitialized &&
            selectedProviderPreset().id == ProviderPresetId.CUSTOM
        if (custom && !providerAdvanced.isChecked) {
            providerAdvanced.isChecked = true
            return
        }
        providerAdvancedFields.visibility =
            if (providerAdvanced.isChecked || custom) View.VISIBLE else View.GONE
        providerAdvanced.isEnabled = !custom
    }

    /**
     * Applies the protocol required by a known official endpoint before Save/Test validation.
     *
     * DeepSeek's native terminal tool ignores the legacy structured-output selector, but it does
     * require Chat Completions. Users can therefore enter URL, model, and key without first
     * discovering two unrelated compatibility dropdowns.
     */
    private fun applyKnownProviderPreset() {
        if (!ProviderCompatibility.isOfficialDeepSeek(providerBaseUrl.text.toString().trim())) {
            return
        }
        providerApiStyle.setSelection(1)
        providerStructuredOutput.setSelection(1)
        val currentName = providerName.text.toString().trim()
        if (currentName.isEmpty() || currentName.equals("OpenAI", ignoreCase = true)) {
            providerName.setText("DeepSeek")
        }
    }

    private fun showProfileErrors(profile: ProviderProfile): Boolean {
        val validation = profile.validate()
        if (!validation.isValid) {
            providerStatus.text = validation.errors.joinToString("\n") { it.message }
            providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return false
        }

        val compatibilityIssues = ProviderCompatibility.issues(profile)
        if (compatibilityIssues.isNotEmpty()) {
            providerStatus.text = compatibilityIssues.joinToString("\n") { issue ->
                getString(
                    when (issue) {
                        ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_CHAT_COMPLETIONS ->
                            R.string.ai_provider_deepseek_chat_required
                        ProviderCompatibilityIssue.DEEPSEEK_REASONING_CONFIGURATION_UNSUPPORTED ->
                            R.string.ai_provider_deepseek_reasoning_unsupported
                    },
                )
            }
            providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return false
        }
        return true
    }

    private fun validateSavedProvider() {
        providerStore.load()
            .onSuccess { config ->
                providerStatus.setText(
                    if (config == null) R.string.ai_provider_not_configured
                    else R.string.ai_provider_local_valid,
                )
                providerStatus.setTextColor(
                    getColor(if (config == null) R.color.sense_secondary else R.color.sense_success),
                )
            }
            .onFailure {
                providerStatus.setText(R.string.ai_provider_invalid)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun onProviderConnectionTestEvent(event: ProviderConnectionTestEvent) {
        when (event) {
            ProviderConnectionTestEvent.Starting -> {
                providerStatus.setText(R.string.ai_provider_test_starting)
                providerStatus.setTextColor(getColor(R.color.sense_secondary))
            }

            is ProviderConnectionTestEvent.Progress -> {
                providerStatus.setText(
                    when (event.phase) {
                        ProviderConnectionTestPhase.CONNECTING ->
                            R.string.ai_provider_test_connecting
                        ProviderConnectionTestPhase.UNDERSTANDING ->
                            R.string.ai_provider_test_understanding
                        ProviderConnectionTestPhase.GENERATING ->
                            R.string.ai_provider_test_generating
                        ProviderConnectionTestPhase.VALIDATING ->
                            R.string.ai_provider_test_validating
                    },
                )
                providerStatus.setTextColor(getColor(R.color.sense_secondary))
            }

            is ProviderConnectionTestEvent.Succeeded -> {
                finishProviderTest()
                val inputTokens = event.inputTokens
                val outputTokens = event.outputTokens
                providerStatus.text = if (inputTokens != null && outputTokens != null) {
                    getString(
                        R.string.ai_provider_test_succeeded_with_usage,
                        event.elapsedMs / 1_000.0,
                        inputTokens,
                        outputTokens,
                    )
                } else {
                    getString(
                        R.string.ai_provider_test_succeeded,
                        event.elapsedMs / 1_000.0,
                    )
                }
                providerStatus.setTextColor(getColor(R.color.sense_success))
            }

            is ProviderConnectionTestEvent.Failed -> {
                finishProviderTest()
                providerStatus.setText(providerTestFailureMessage(event.failure))
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }

            ProviderConnectionTestEvent.Cancelled -> {
                finishProviderTest()
                providerStatus.setText(R.string.ai_provider_test_cancelled)
                providerStatus.setTextColor(getColor(R.color.sense_secondary))
            }
        }
    }

    private fun providerTestFailureMessage(failure: ProviderConnectionTestFailure): Int =
        when (failure) {
            ProviderConnectionTestFailure.NOT_CONFIGURED ->
                R.string.ai_provider_test_not_configured
            ProviderConnectionTestFailure.AUTHENTICATION ->
                R.string.ai_provider_test_authentication
            ProviderConnectionTestFailure.QUOTA ->
                R.string.ai_provider_test_quota
            ProviderConnectionTestFailure.CONFIGURATION ->
                R.string.ai_provider_test_configuration
            ProviderConnectionTestFailure.RATE_LIMIT ->
                R.string.ai_provider_test_rate_limit
            ProviderConnectionTestFailure.UNAVAILABLE ->
                R.string.ai_provider_test_unavailable
            ProviderConnectionTestFailure.NETWORK ->
                R.string.ai_provider_test_network
            ProviderConnectionTestFailure.TIMEOUT ->
                R.string.ai_provider_test_timeout
            ProviderConnectionTestFailure.PROTOCOL ->
                R.string.ai_provider_test_protocol
            ProviderConnectionTestFailure.INTERNAL ->
                R.string.ai_provider_test_internal
        }

    private fun finishProviderTest() {
        providerTestRunning = false
        providerTestButton.isEnabled = true
        providerTestButton.setText(R.string.ai_provider_test)
    }

    private fun clearProviderCredential() {
        providerStore.loadProfile()
            .onSuccess { profile ->
                if (profile == null) {
                    providerStatus.setText(R.string.ai_provider_not_configured)
                    providerStatus.setTextColor(getColor(R.color.sense_secondary))
                    return@onSuccess
                }
                providerStore.save(profile, CharArray(0))
                    .onSuccess {
                        providerApiKey.text.clear()
                        updateProviderKeyHint()
                        providerStatus.setText(R.string.ai_provider_key_cleared)
                        providerStatus.setTextColor(getColor(R.color.sense_success))
                    }
                    .onFailure {
                        providerStatus.setText(R.string.ai_provider_save_failed)
                        providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
            }
            .onFailure {
                providerStatus.setText(R.string.ai_provider_invalid)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun loadProviderSettings() {
        if (!::providerStatus.isInitialized) return
        providerStore.loadProfile()
            .onSuccess { profile ->
                if (profile == null) {
                    loadedProviderCredentialScope = null
                    val preset = ProviderPresetCatalog.default
                    selectedProviderPresetPosition =
                        ProviderPresetCatalog.presets.indexOf(preset)
                    providerPreset.setSelection(selectedProviderPresetPosition)
                    applyProviderPresetFields(preset)
                    providerThinkingMode.setSelection(0)
                    providerAdvanced.isChecked = false
                    providerUiLoaded = true
                    updateProviderAdvancedVisibility()
                    updateProviderKeyHint()
                    providerStatus.setText(R.string.ai_provider_not_configured)
                    providerStatus.setTextColor(getColor(R.color.sense_secondary))
                    return@onSuccess
                }
                val preset = ProviderPresetCatalog.detect(profile)
                selectedProviderPresetPosition =
                    ProviderPresetCatalog.presets.indexOf(preset)
                providerPreset.setSelection(selectedProviderPresetPosition)
                providerName.setText(profile.displayName)
                providerBaseUrl.setText(profile.baseUrl)
                providerModel.setText(profile.model)
                providerApiStyle.setSelection(
                    if (profile.apiStyle == ProviderApiStyle.OPENAI_RESPONSES) 0 else 1,
                )
                providerStructuredOutput.setSelection(
                    when (profile.structuredOutput) {
                        StructuredOutputMode.JSON_SCHEMA -> 0
                        StructuredOutputMode.JSON_OBJECT -> 1
                        StructuredOutputMode.PROMPT_ONLY -> 2
                    },
                )
                providerThinkingMode.setSelection(
                    ProviderReasoningStrength.from(profile).ordinal,
                )
                providerStreaming.isChecked = profile.streaming
                providerAdvanced.isChecked = preset.isCustom
                loadedProviderCredentialScope = providerCredentialScope(profile)
                providerUiLoaded = true
                updateProviderAdvancedVisibility()
                updateProviderKeyHint()
                providerStatus.setText(R.string.ai_provider_saved)
                providerStatus.setTextColor(getColor(R.color.sense_success))
            }
            .onFailure {
                loadedProviderCredentialScope = null
                val preset = ProviderPresetCatalog.default
                selectedProviderPresetPosition = ProviderPresetCatalog.presets.indexOf(preset)
                providerPreset.setSelection(selectedProviderPresetPosition)
                applyProviderPresetFields(preset)
                providerThinkingMode.setSelection(0)
                providerAdvanced.isChecked = false
                providerUiLoaded = true
                updateProviderAdvancedVisibility()
                updateProviderKeyHint()
                providerStatus.setText(R.string.ai_provider_invalid)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun editField(labelRes: Int, hintText: String): EditText = EditText(this).apply {
        hint = hintText
        contentDescription = getString(labelRes)
        id = View.generateViewId()
        minimumHeight = dp(48)
        textSize = 14f
        setSingleLine(true)
        setTextColor(getColor(R.color.sense_primary))
        setHintTextColor(getColor(R.color.sense_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(getColor(R.color.sense_background), dp(10).toFloat())
    }

    private fun multiLineEditField(labelRes: Int, hintText: String): EditText =
        editField(labelRes, hintText).apply {
            setSingleLine(false)
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
        }

    private fun labeledField(labelRes: Int, field: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (field.id == View.NO_ID) field.id = View.generateViewId()
        addView(
            text(labelRes, 12f, R.color.sense_secondary, Typeface.BOLD).apply {
                labelFor = field.id
            },
        )
        addView(field.withTop(dp(5)))
    }

    private fun accessibleSpinner(labelRes: Int): Spinner = Spinner(this).apply {
        id = View.generateViewId()
        minimumHeight = dp(48)
        contentDescription = getString(labelRes)
    }

    private fun simpleSelectionListener(action: () -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!applyingAgentSkillUi) action()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    private fun showDictionaryNotice() {
        val notice = runCatching {
            listOf(
                "NOTICE" to "NOTICE.txt",
                "Sense MIT LICENSE" to "LICENSE.txt",
                "Rime Apache 2.0" to "RIME-PINYIN-SIMP-LICENSE.txt",
                "CC-CEDICT NOTICE" to "CC-CEDICT-NOTICE.txt",
                "CC BY-SA 4.0" to "CC-BY-SA-4.0.txt",
            ).joinToString("\n\n") { (heading, fileName) ->
                "$heading\n${"=".repeat(heading.length)}\n${readAsset(fileName).trimEnd()}"
            }
        }.getOrElse {
            getString(R.string.dictionary_notice_load_error)
        }
        val body = TextView(this).apply {
            text = notice
            textSize = 12f
            setTextColor(getColor(R.color.sense_primary))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(body)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_notice_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun readAsset(fileName: String): String =
        assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun badge(): TextView = text(R.string.stage_badge, 12f, R.color.sense_accent, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(getColor(R.color.sense_surface), dp(18).toFloat(), getColor(R.color.sense_accent))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun card(titleRes: Int, body: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(18))
        background = rounded(getColor(R.color.sense_surface), dp(18).toFloat())
        addView(text(titleRes, 13f, R.color.sense_secondary, Typeface.BOLD))
        addView(body.withTop(dp(8)))
    }

    private fun primaryButton(textRes: Int, action: () -> Unit): Button = Button(this).apply {
        setText(textRes)
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(getColor(R.color.sense_accent), dp(14).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
    }

    private fun secondaryButton(textRes: Int, action: () -> Unit): Button = Button(this).apply {
        setText(textRes)
        isAllCaps = false
        textSize = 15f
        setTextColor(getColor(R.color.sense_primary))
        background = rounded(getColor(R.color.sense_surface), dp(14).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
    }

    private fun text(
        textRes: Int,
        size: Float,
        colorRes: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(this).apply {
        setText(textRes)
        textSize = size
        setTextColor(getColor(colorRes))
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setLineSpacing(0f, 1.16f)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val typedArray = obtainStyledAttributes(attrs)
        val drawable = typedArray.getDrawable(0)
        typedArray.recycle()
        return drawable
    }

    private fun <T : View> T.withTop(margin: Int): T = apply {
        val current = layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        current.topMargin = margin
        layoutParams = current
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class SkillReplacementOperation {
        CREATE,
        BIND,
    }

    private data class PendingSkillReplacement(
        val operation: SkillReplacementOperation,
        val generation: Long,
        val slot: AgentSkillSlot,
        val incumbentSkillId: String,
        val targetSkillId: String,
    )

    private data class PendingSkillDocumentConflict(
        val skillId: String,
        val sourceRevision: Long,
        val latestRevision: Long,
        val draft: SkillSettingsDraft,
    )

    private data class SkillDraftLifecycleSaveKey(
        val snapshot: SkillDraftSessionState,
        val restoreCompleted: Boolean,
        val changedWhileRestoring: Boolean,
    )

    companion object {
        private const val STATE_SECTION = "settings-section"
        private const val STATE_SKILL_DRAFTS = "settings-skill-drafts"
        private const val MAX_BUNDLE_DRAFT_BYTES = 192 * 1024
        private const val DRAFT_PERSIST_DEBOUNCE_MS = 400L
        private const val DRAFT_LIFECYCLE_FLUSH_TIMEOUT_MS = 3_500L
        private const val SKILL_GENERATION_CONFLICT_PREFIX = "Skill catalog changed:"
        private const val SKILL_IO_CATALOG_CHANNEL = "catalog"
        private const val SKILL_IO_HISTORY_LIST_CHANNEL = "history-list"
        private const val SKILL_IO_HISTORY_REVISION_CHANNEL = "history-revision"
        private const val SKILL_IO_DRAFT_RESTORE_CHANNEL = "draft-restore"
        private const val SETTINGS_IO_TOOLS_CHANNEL = "tools"
        private const val DEFAULT_PROVIDER_MODEL = "gpt-4.1-mini"
        private const val REQUEST_RECORD_AUDIO = 40
        private val AGENT_SKILL_IO_EXECUTOR = Executors.newSingleThreadExecutor { command ->
            Thread(command, "Sense-SkillSettings-IO").apply {
                isDaemon = true
            }
        }
        private val SKILL_INTENTS = EditorIntent.entries.filterNot {
            it == EditorIntent.NO_CHANGE
        }
        private val SPEECH_LANGUAGES = listOf(
            "普通话（中国大陆）" to "zh-CN",
            "粤语（香港）" to "zh-HK",
            "中文（台湾）" to "zh-TW",
            "English (US)" to "en-US",
        )
    }
}
