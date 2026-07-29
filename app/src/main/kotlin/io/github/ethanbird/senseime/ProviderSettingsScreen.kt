package io.github.ethanbird.senseime

import android.app.Activity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.AdapterView
import io.github.ethanbird.senseime.brain.api.CredentialEndpointScope
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCompatibilityIssue
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderPreset
import io.github.ethanbird.senseime.brain.api.ProviderPresetCatalog
import io.github.ethanbird.senseime.brain.api.ProviderPresetId
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderReasoningStrength
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestEvent
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestFailure
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestPhase
import io.github.ethanbird.senseime.brain.runtime.ProviderSettingsStore
import io.github.ethanbird.senseime.brain.runtime.SenseAiProviderTestClient
import java.util.concurrent.Executor

internal data class ProviderSettingsSnapshot(
    val profile: ProviderProfile?,
    val hasCredential: Boolean,
)

internal interface ProviderSettingsRepository {
    fun load(): Result<ProviderSettingsSnapshot>
    fun save(
        profile: ProviderProfile,
        credential: CharArray?,
    ): Result<ProviderSettingsSnapshot>
    fun clearCredential(): Result<ProviderSettingsSnapshot>
    fun hasValidConfiguration(): Result<Boolean>
}

internal class RuntimeProviderSettingsRepository(
    activity: Activity,
) : ProviderSettingsRepository {
    private val applicationContext = activity.applicationContext
    private val store by lazy { ProviderSettingsStore(applicationContext) }

    override fun load(): Result<ProviderSettingsSnapshot> =
        store.loadProfile().map { profile ->
            ProviderSettingsSnapshot(profile, store.hasCredential())
        }

    override fun save(
        profile: ProviderProfile,
        credential: CharArray?,
    ): Result<ProviderSettingsSnapshot> =
        store.save(profile, credential).map {
            ProviderSettingsSnapshot(profile, store.hasCredential())
        }

    override fun clearCredential(): Result<ProviderSettingsSnapshot> =
        store.loadProfile().fold(
            onSuccess = { profile ->
                if (profile == null) {
                    Result.success(ProviderSettingsSnapshot(null, false))
                } else {
                    store.save(profile, CharArray(0)).map {
                        ProviderSettingsSnapshot(profile, false)
                    }
                }
            },
            onFailure = { Result.failure(it) },
        )

    override fun hasValidConfiguration(): Result<Boolean> =
        store.load().map { it != null }
}

internal class ProviderSettingsController(
    private val repository: ProviderSettingsRepository,
    private val tasks: SettingsTaskRunner,
) : AutoCloseable {
    private val generations = mutableMapOf<String, Long>()
    private var closed = false

    fun load(deliver: (Result<ProviderSettingsSnapshot>) -> Unit): Boolean =
        refresh(LOAD_CHANNEL, repository::load, deliver)

    fun save(
        profile: ProviderProfile,
        credential: CharArray?,
        deliver: (Result<ProviderSettingsSnapshot>) -> Unit,
    ): Boolean = execute(
        operation = SAVE_OPERATION,
        task = { repository.save(profile, credential).getOrThrow() },
        deliver = deliver,
    )

    fun clearCredential(
        deliver: (Result<ProviderSettingsSnapshot>) -> Unit,
    ): Boolean = execute(
        operation = CLEAR_CREDENTIAL_OPERATION,
        task = { repository.clearCredential().getOrThrow() },
        deliver = deliver,
    )

    fun validate(deliver: (Result<Boolean>) -> Unit): Boolean =
        refresh(VALIDATE_CHANNEL, repository::hasValidConfiguration, deliver)

    override fun close() {
        closed = true
        generations.clear()
        tasks.close()
    }

    private fun <T> refresh(
        channel: String,
        operation: () -> Result<T>,
        deliver: (Result<T>) -> Unit,
    ): Boolean {
        val requestGeneration = nextGeneration(channel)
        val accepted = tasks.refresh(
            channel = channel,
            operation = { operation().getOrThrow() },
        ) { result ->
            if (accepts(channel, requestGeneration)) deliver(result)
        }
        if (!accepted && accepts(channel, requestGeneration)) {
            deliver(Result.failure(IllegalStateException("Provider settings lane is closed")))
        }
        return accepted
    }

    private fun <T> execute(
        operation: String,
        task: () -> T,
        deliver: (Result<T>) -> Unit,
    ): Boolean {
        val requestGeneration = nextGeneration(operation)
        val accepted = tasks.execute(task) { result ->
            if (accepts(operation, requestGeneration)) deliver(result)
        }
        if (!accepted && accepts(operation, requestGeneration)) {
            deliver(Result.failure(IllegalStateException("Provider settings lane is closed")))
        }
        return accepted
    }

    private fun nextGeneration(operation: String): Long {
        val current = generations[operation] ?: 0L
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        generations[operation] = next
        return next
    }

    private fun accepts(operation: String, requestGeneration: Long): Boolean =
        !closed && generations[operation] == requestGeneration

    private companion object {
        const val LOAD_CHANNEL = "provider-load"
        const val VALIDATE_CHANNEL = "provider-validate"
        const val SAVE_OPERATION = "provider-save"
        const val CLEAR_CREDENTIAL_OPERATION = "provider-clear-credential"
    }
}

internal class ProviderSettingsViewBinding(
    val root: LinearLayout,
    val preset: Spinner,
    val name: EditText,
    val baseUrl: EditText,
    val model: EditText,
    val apiKey: EditText,
    val apiStyle: Spinner,
    val structuredOutput: Spinner,
    val thinkingMode: Spinner,
    val streaming: Switch,
    val advanced: Switch,
    val advancedFields: LinearLayout,
    val status: TextView,
    val testButton: Button,
)

internal class ProviderSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    repository: ProviderSettingsRepository =
        RuntimeProviderSettingsRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-ProviderSettings",
        uiExecutor = Executor { command -> activity.runOnUiThread(command) },
    ),
) : AutoCloseable {
    private var binding: ProviderSettingsViewBinding? = null
    private var uiLoaded = false
    private var selectedPresetPosition = 0
    private var loadedCredentialScope: String? = null
    private var hasSavedCredential = false
    private var testRunning = false
    private val controller = ProviderSettingsController(repository, tasks)
    private val testClient =
        SenseAiProviderTestClient(activity.applicationContext, ::onConnectionTestEvent)

    fun createView(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(views.text(R.string.ai_provider_body, 13f, R.color.sense_secondary))

        val preset = views.accessibleSpinner(R.string.ai_provider_preset).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                ProviderPresetCatalog.presets.map(ProviderPreset::displayName),
            )
        }
        val name = views.editField(R.string.ai_provider_name, "OpenAI")
        val baseUrl =
            views.editField(R.string.ai_provider_base_url, ProviderProfile.DEFAULT_OPENAI_BASE_URL)
        val model = views.editField(R.string.ai_provider_model, DEFAULT_PROVIDER_MODEL)
        val apiKey = views.secretField(R.string.ai_provider_key, "sk-…")
        val apiStyle = views.accessibleSpinner(R.string.ai_provider_style).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenAI Responses", "OpenAI-compatible Chat Completions"),
            )
        }
        val structuredOutput =
            views.accessibleSpinner(R.string.ai_provider_structured_output).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        "严格 JSON Schema（OpenAI Responses 推荐）",
                        "JSON Object（兼容服务推荐）",
                        "仅提示词约束（最广兼容）",
                    ),
                )
            }
        val thinkingMode =
            views.accessibleSpinner(R.string.ai_provider_reasoning_strength).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        "快速 · 最低延迟",
                        "均衡 · Provider 自动判断",
                        "深度 · 更强推理",
                    ),
                )
            }
        val streaming = views.switch(R.string.ai_provider_stream, checked = true)
        val advanced = views.switch(R.string.ai_provider_advanced)
        val advancedFields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(views.labeledField(R.string.ai_provider_name, name))
            addView(
                views.labeledField(R.string.ai_provider_base_url, baseUrl)
                    .withTop(views.dp(10)),
            )
            addView(
                views.labeledField(R.string.ai_provider_model, model)
                    .withTop(views.dp(10)),
            )
            addView(
                views.labeledField(R.string.ai_provider_style, apiStyle)
                    .withTop(views.dp(10)),
            )
            addView(
                views.labeledField(
                    R.string.ai_provider_structured_output,
                    structuredOutput,
                ).withTop(views.dp(10)),
            )
            addView(streaming.withTop(views.dp(10)))
        }

        root.addView(
            views.labeledField(R.string.ai_provider_preset, preset)
                .withTop(views.dp(14)),
        )
        root.addView(
            views.labeledField(R.string.ai_provider_key, apiKey)
                .withTop(views.dp(10)),
        )
        root.addView(
            views.labeledField(R.string.ai_provider_reasoning_strength, thinkingMode)
                .withTop(views.dp(10)),
        )
        root.addView(advanced.withTop(views.dp(10)))
        root.addView(advancedFields.withTop(views.dp(10)))
        root.addView(
            views.secondaryButton(R.string.ai_provider_save, ::save)
                .withTop(views.dp(12)),
        )
        val testButton =
            views.primaryButton(R.string.ai_provider_test, ::saveAndTestConnection)
        root.addView(testButton.withTop(views.dp(8)))
        root.addView(
            views.secondaryButton(R.string.ai_provider_validate, ::validateSaved)
                .withTop(views.dp(8)),
        )
        root.addView(
            views.secondaryButton(R.string.ai_provider_clear_key, ::clearCredential)
                .withTop(views.dp(8)),
        )
        val status =
            views.text(
                R.string.ai_provider_not_configured,
                12f,
                R.color.sense_secondary,
            ).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        root.addView(status.withTop(views.dp(10)))

        binding = ProviderSettingsViewBinding(
            root = root,
            preset = preset,
            name = name,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            apiStyle = apiStyle,
            structuredOutput = structuredOutput,
            thinkingMode = thinkingMode,
            streaming = streaming,
            advanced = advanced,
            advancedFields = advancedFields,
            status = status,
            testButton = testButton,
        )
        advanced.setOnCheckedChangeListener { _, _ -> updateAdvancedVisibility() }
        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val changed = position != selectedPresetPosition
                selectedPresetPosition = position
                if (!uiLoaded || !changed) {
                    updateAdvancedVisibility()
                    return
                }
                apiKey.text.clear()
                val selected = ProviderPresetCatalog.presets[position]
                if (selected.isCustom) {
                    advanced.isChecked = true
                } else {
                    advanced.isChecked = false
                    applyPresetFields(selected)
                }
                updateAdvancedVisibility()
                updateKeyHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateAdvancedVisibility()
        load()
        return root
    }

    fun onStop() {
        testClient.cancel()
    }

    override fun close() {
        testClient.cancel()
        testClient.close()
        controller.close()
        binding = null
    }

    private fun load() {
        val current = binding ?: return
        current.status.setText(R.string.ai_provider_not_configured)
        current.status.setTextColor(activity.getColor(R.color.sense_secondary))
        controller.load { result ->
            result
                .onSuccess(::applySnapshot)
                .onFailure {
                    applyDefaultProfile()
                    current.status.setText(R.string.ai_provider_invalid)
                    current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
        }
    }

    private fun applySnapshot(snapshot: ProviderSettingsSnapshot) {
        val current = binding ?: return
        hasSavedCredential = snapshot.hasCredential
        val profile = snapshot.profile
        if (profile == null) {
            applyDefaultProfile()
            current.status.setText(R.string.ai_provider_not_configured)
            current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            return
        }
        val preset = ProviderPresetCatalog.detect(profile)
        selectedPresetPosition = ProviderPresetCatalog.presets.indexOf(preset)
        current.preset.setSelection(selectedPresetPosition)
        current.name.setText(profile.displayName)
        current.baseUrl.setText(profile.baseUrl)
        current.model.setText(profile.model)
        current.apiStyle.setSelection(
            if (profile.apiStyle == ProviderApiStyle.OPENAI_RESPONSES) 0 else 1,
        )
        current.structuredOutput.setSelection(
            when (profile.structuredOutput) {
                StructuredOutputMode.JSON_SCHEMA -> 0
                StructuredOutputMode.JSON_OBJECT -> 1
                StructuredOutputMode.PROMPT_ONLY -> 2
            },
        )
        current.thinkingMode.setSelection(ProviderReasoningStrength.from(profile).ordinal)
        current.streaming.isChecked = profile.streaming
        current.advanced.isChecked = preset.isCustom
        loadedCredentialScope = credentialScope(profile)
        uiLoaded = true
        updateAdvancedVisibility()
        updateKeyHint()
        current.status.setText(R.string.ai_provider_saved)
        current.status.setTextColor(activity.getColor(R.color.sense_success))
    }

    private fun applyDefaultProfile() {
        val current = binding ?: return
        loadedCredentialScope = null
        hasSavedCredential = false
        val preset = ProviderPresetCatalog.default
        selectedPresetPosition = ProviderPresetCatalog.presets.indexOf(preset)
        current.preset.setSelection(selectedPresetPosition)
        applyPresetFields(preset)
        current.thinkingMode.setSelection(0)
        current.advanced.isChecked = false
        uiLoaded = true
        updateAdvancedVisibility()
        updateKeyHint()
    }

    private fun save() {
        if (testRunning) testClient.cancel()
        persist()
    }

    private fun saveAndTestConnection() {
        val current = binding ?: return
        if (testRunning) {
            testClient.cancel()
            return
        }
        persist {
            testRunning = true
            current.testButton.setText(R.string.ai_provider_test_cancel)
            current.status.setText(R.string.ai_provider_test_starting)
            current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            testClient.start()
        }
    }

    private fun persist(onSaved: (() -> Unit)? = null) {
        val current = binding ?: return
        applyKnownPreset()
        val profile = currentProfile()
        if (!showProfileErrors(profile)) return

        val enteredKey = current.apiKey.text.toString()
        val scope = credentialScope(profile)
        val canPreserve = hasSavedCredential && loadedCredentialScope == scope
        val key = when {
            enteredKey.isEmpty() && !selectedPreset().isCustom && !canPreserve -> {
                current.status.setText(
                    if (hasSavedCredential) {
                        R.string.ai_provider_key_provider_changed
                    } else {
                        R.string.ai_provider_key_required
                    },
                )
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                return
            }

            enteredKey.isEmpty() && !canPreserve ->
                if (hasSavedCredential) CharArray(0) else null

            enteredKey.isEmpty() -> null
            runCatching { ProviderCredential.Bearer(enteredKey) }.isFailure -> {
                current.status.setText(R.string.ai_provider_key_invalid)
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                return
            }

            else -> enteredKey.toCharArray()
        }
        current.status.setText(R.string.ai_provider_not_configured)
        current.status.setTextColor(activity.getColor(R.color.sense_secondary))
        controller.save(profile, key) { result ->
            result
                .onSuccess { snapshot ->
                    hasSavedCredential = snapshot.hasCredential
                    loadedCredentialScope = scope
                    current.apiKey.text.clear()
                    updateKeyHint()
                    current.status.setText(R.string.ai_provider_saved)
                    current.status.setTextColor(activity.getColor(R.color.sense_success))
                    onSaved?.invoke()
                }
                .onFailure {
                    current.status.setText(R.string.ai_provider_save_failed)
                    current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
        }
    }

    private fun clearCredential() {
        val current = binding ?: return
        controller.clearCredential { result ->
            result
                .onSuccess { snapshot ->
                    hasSavedCredential = false
                    loadedCredentialScope = snapshot.profile?.let(::credentialScope)
                    current.apiKey.text.clear()
                    updateKeyHint()
                    current.status.setText(
                        if (snapshot.profile == null) {
                            R.string.ai_provider_not_configured
                        } else {
                            R.string.ai_provider_key_cleared
                        },
                    )
                    current.status.setTextColor(
                        activity.getColor(
                            if (snapshot.profile == null) {
                                R.color.sense_secondary
                            } else {
                                R.color.sense_success
                            },
                        ),
                    )
                }
                .onFailure {
                    current.status.setText(R.string.ai_provider_save_failed)
                    current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
        }
    }

    private fun validateSaved() {
        val current = binding ?: return
        controller.validate { result ->
            result
                .onSuccess { configured ->
                    current.status.setText(
                        if (configured) {
                            R.string.ai_provider_local_valid
                        } else {
                            R.string.ai_provider_not_configured
                        },
                    )
                    current.status.setTextColor(
                        activity.getColor(
                            if (configured) R.color.sense_success else R.color.sense_secondary,
                        ),
                    )
                }
                .onFailure {
                    current.status.setText(R.string.ai_provider_invalid)
                    current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
        }
    }

    private fun currentProfile(): ProviderProfile {
        val current = requireNotNull(binding)
        val baseUrl = current.baseUrl.text.toString().trim()
        val enteredModel = current.model.text.toString().trim()
        val model = ProviderCompatibility.activeModelForSavedProfile(baseUrl, enteredModel)
        if (model != enteredModel) current.model.setText(model)
        val profile = ProviderProfile(
            id = "primary",
            displayName = current.name.text.toString().trim(),
            apiStyle = if (current.apiStyle.selectedItemPosition == 0) {
                ProviderApiStyle.OPENAI_RESPONSES
            } else {
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS
            },
            baseUrl = baseUrl,
            model = model,
            streaming = current.streaming.isChecked,
            structuredOutput = when (current.structuredOutput.selectedItemPosition) {
                0 -> StructuredOutputMode.JSON_SCHEMA
                1 -> StructuredOutputMode.JSON_OBJECT
                else -> StructuredOutputMode.PROMPT_ONLY
            },
        )
        return selectedReasoningStrength().applyTo(profile)
    }

    private fun selectedReasoningStrength(): ProviderReasoningStrength {
        val current = requireNotNull(binding)
        return ProviderReasoningStrength.entries[
            current.thinkingMode.selectedItemPosition.coerceIn(
                0,
                ProviderReasoningStrength.entries.lastIndex,
            )
        ]
    }

    private fun selectedPreset(): ProviderPreset {
        val current = requireNotNull(binding)
        return ProviderPresetCatalog.presets[
            current.preset.selectedItemPosition.coerceIn(
                0,
                ProviderPresetCatalog.presets.lastIndex,
            )
        ]
    }

    private fun credentialScope(profile: ProviderProfile): String =
        CredentialEndpointScope.normalize(profile.baseUrl)

    private fun updateKeyHint() {
        val current = binding ?: return
        val profile = currentProfile()
        val canPreserve =
            hasSavedCredential && loadedCredentialScope == credentialScope(profile)
        current.apiKey.hint = activity.getString(
            when {
                canPreserve -> R.string.ai_provider_key_saved
                selectedPreset().isCustom -> R.string.ai_provider_key_optional
                else -> R.string.ai_provider_key_required
            },
        )
    }

    private fun applyPresetFields(preset: ProviderPreset) {
        val current = binding ?: return
        current.name.setText(preset.providerName)
        current.baseUrl.setText(preset.baseUrl)
        current.model.setText(preset.model)
        current.apiStyle.setSelection(
            if (preset.apiStyle == ProviderApiStyle.OPENAI_RESPONSES) 0 else 1,
        )
        current.structuredOutput.setSelection(
            when (preset.structuredOutput) {
                StructuredOutputMode.JSON_SCHEMA -> 0
                StructuredOutputMode.JSON_OBJECT -> 1
                StructuredOutputMode.PROMPT_ONLY -> 2
            },
        )
        current.streaming.isChecked = true
    }

    private fun updateAdvancedVisibility() {
        val current = binding ?: return
        val custom = selectedPreset().id == ProviderPresetId.CUSTOM
        if (custom && !current.advanced.isChecked) {
            current.advanced.isChecked = true
            return
        }
        current.advancedFields.visibility =
            if (current.advanced.isChecked || custom) View.VISIBLE else View.GONE
        current.advanced.isEnabled = !custom
    }

    private fun applyKnownPreset() {
        val current = binding ?: return
        if (!ProviderCompatibility.isOfficialDeepSeek(current.baseUrl.text.toString().trim())) return
        current.apiStyle.setSelection(1)
        current.structuredOutput.setSelection(1)
        val currentName = current.name.text.toString().trim()
        if (currentName.isEmpty() || currentName.equals("OpenAI", ignoreCase = true)) {
            current.name.setText("DeepSeek")
        }
    }

    private fun showProfileErrors(profile: ProviderProfile): Boolean {
        val current = binding ?: return false
        val validation = profile.validate()
        if (!validation.isValid) {
            current.status.text = validation.errors.joinToString("\n") { it.message }
            current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            return false
        }
        val issues = ProviderCompatibility.issues(profile)
        if (issues.isNotEmpty()) {
            current.status.text = issues.joinToString("\n") { issue ->
                activity.getString(
                    when (issue) {
                        ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_CHAT_COMPLETIONS ->
                            R.string.ai_provider_deepseek_chat_required
                        ProviderCompatibilityIssue.DEEPSEEK_REASONING_CONFIGURATION_UNSUPPORTED ->
                            R.string.ai_provider_deepseek_reasoning_unsupported
                    },
                )
            }
            current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            return false
        }
        return true
    }

    private fun onConnectionTestEvent(event: ProviderConnectionTestEvent) {
        val current = binding ?: return
        when (event) {
            ProviderConnectionTestEvent.Starting -> {
                current.status.setText(R.string.ai_provider_test_starting)
                current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            }

            is ProviderConnectionTestEvent.Progress -> {
                current.status.setText(
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
                current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            }

            is ProviderConnectionTestEvent.Succeeded -> {
                finishTest()
                current.status.text =
                    if (event.inputTokens != null && event.outputTokens != null) {
                        activity.getString(
                            R.string.ai_provider_test_succeeded_with_usage,
                            event.elapsedMs / 1_000.0,
                            event.inputTokens,
                            event.outputTokens,
                        )
                    } else {
                        activity.getString(
                            R.string.ai_provider_test_succeeded,
                            event.elapsedMs / 1_000.0,
                        )
                    }
                current.status.setTextColor(activity.getColor(R.color.sense_success))
            }

            is ProviderConnectionTestEvent.Failed -> {
                finishTest()
                current.status.setText(testFailureMessage(event.failure))
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            }

            ProviderConnectionTestEvent.Cancelled -> {
                finishTest()
                current.status.setText(R.string.ai_provider_test_cancelled)
                current.status.setTextColor(activity.getColor(R.color.sense_secondary))
            }
        }
    }

    private fun finishTest() {
        val current = binding ?: return
        testRunning = false
        current.testButton.isEnabled = true
        current.testButton.setText(R.string.ai_provider_test)
    }

    private fun testFailureMessage(failure: ProviderConnectionTestFailure): Int =
        when (failure) {
            ProviderConnectionTestFailure.NOT_CONFIGURED ->
                R.string.ai_provider_test_not_configured
            ProviderConnectionTestFailure.AUTHENTICATION ->
                R.string.ai_provider_test_authentication
            ProviderConnectionTestFailure.QUOTA -> R.string.ai_provider_test_quota
            ProviderConnectionTestFailure.CONFIGURATION ->
                R.string.ai_provider_test_configuration
            ProviderConnectionTestFailure.RATE_LIMIT -> R.string.ai_provider_test_rate_limit
            ProviderConnectionTestFailure.UNAVAILABLE -> R.string.ai_provider_test_unavailable
            ProviderConnectionTestFailure.NETWORK -> R.string.ai_provider_test_network
            ProviderConnectionTestFailure.TIMEOUT -> R.string.ai_provider_test_timeout
            ProviderConnectionTestFailure.PROTOCOL -> R.string.ai_provider_test_protocol
            ProviderConnectionTestFailure.INTERNAL -> R.string.ai_provider_test_internal
        }

    private companion object {
        const val DEFAULT_PROVIDER_MODEL = "gpt-4.1-mini"
    }
}
