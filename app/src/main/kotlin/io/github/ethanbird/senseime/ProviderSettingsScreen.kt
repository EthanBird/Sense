package io.github.ethanbird.senseime

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.CredentialEndpointScope
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderAuthMode
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderPreset
import io.github.ethanbird.senseime.brain.api.ProviderPresetCatalog
import io.github.ethanbird.senseime.brain.api.ProviderPresetId
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.ProviderReasoningStrength
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.runtime.CodexOAuthClient
import io.github.ethanbird.senseime.brain.runtime.CodexSubscriptionAuthStore
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestEvent
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestFailure
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestPhase
import io.github.ethanbird.senseime.brain.runtime.ProviderSettingsStore
import io.github.ethanbird.senseime.brain.runtime.SenseAiProviderTestClient
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

internal data class ProviderSettingsSnapshot(
    val profile: ProviderProfile?,
    val hasCredential: Boolean,
)

internal interface ProviderSettingsRepository {
    fun load(): Result<ProviderSettingsSnapshot>
    fun save(profile: ProviderProfile, credential: CharArray?): Result<ProviderSettingsSnapshot>
    fun clearCredential(): Result<ProviderSettingsSnapshot>
    fun hasValidConfiguration(): Result<Boolean>
}

internal class RuntimeProviderSettingsRepository(activity: Activity) : ProviderSettingsRepository {
    private val store by lazy { ProviderSettingsStore(activity.applicationContext) }

    override fun load(): Result<ProviderSettingsSnapshot> = store.loadProfile().map {
        ProviderSettingsSnapshot(it, store.hasCredential())
    }

    override fun save(profile: ProviderProfile, credential: CharArray?) =
        store.save(profile, credential).map { ProviderSettingsSnapshot(profile, store.hasCredential()) }

    override fun clearCredential(): Result<ProviderSettingsSnapshot> = store.loadProfile().fold(
        onSuccess = { profile ->
            if (profile == null) Result.success(ProviderSettingsSnapshot(null, false))
            else store.save(profile, CharArray(0)).map { ProviderSettingsSnapshot(profile, false) }
        },
        onFailure = { Result.failure(it) },
    )

    override fun hasValidConfiguration(): Result<Boolean> = store.load().map { config ->
        config != null && when (config.profile.authMode) {
            ProviderAuthMode.NONE -> true
            ProviderAuthMode.API_KEY -> config.credential is ProviderCredential.Bearer
            ProviderAuthMode.CODEX_SUBSCRIPTION -> config.credential is ProviderCredential.ChatGpt
        }
    }
}

internal class ProviderSettingsController(
    private val repository: ProviderSettingsRepository,
    private val tasks: SettingsTaskRunner,
) : AutoCloseable {
    private val generations = mutableMapOf<String, Long>()
    private var closed = false

    fun load(deliver: (Result<ProviderSettingsSnapshot>) -> Unit) =
        refresh("provider-load", repository::load, deliver)
    fun save(profile: ProviderProfile, credential: CharArray?, deliver: (Result<ProviderSettingsSnapshot>) -> Unit) =
        execute("provider-save", { repository.save(profile, credential).getOrThrow() }, deliver)
    fun clearCredential(deliver: (Result<ProviderSettingsSnapshot>) -> Unit) =
        execute("provider-clear", { repository.clearCredential().getOrThrow() }, deliver)
    fun validate(deliver: (Result<Boolean>) -> Unit) =
        refresh("provider-validate", repository::hasValidConfiguration, deliver)

    override fun close() {
        closed = true
        generations.clear()
        tasks.close()
    }

    private fun <T> refresh(key: String, operation: () -> Result<T>, deliver: (Result<T>) -> Unit): Boolean {
        val generation = next(key)
        val accepted = tasks.refresh(key, { operation().getOrThrow() }) { if (accepts(key, generation)) deliver(it) }
        if (!accepted && accepts(key, generation)) deliver(Result.failure(IllegalStateException("Provider settings lane is closed")))
        return accepted
    }

    private fun <T> execute(key: String, operation: () -> T, deliver: (Result<T>) -> Unit): Boolean {
        val generation = next(key)
        val accepted = tasks.execute(operation) { if (accepts(key, generation)) deliver(it) }
        if (!accepted && accepts(key, generation)) deliver(Result.failure(IllegalStateException("Provider settings lane is closed")))
        return accepted
    }

    private fun next(key: String): Long = ((generations[key] ?: 0L) + 1L).also { generations[key] = it }
    private fun accepts(key: String, generation: Long) = !closed && generations[key] == generation
}

/** Scrollable directory page: every jcode-compatible provider is a stable, explicit destination. */
internal class ProviderCatalogScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val activePreset: ProviderPresetId?,
    private val onOpen: (ProviderPresetId) -> Unit,
) {
    fun createView(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(views.text(R.string.ai_provider_catalog_body, 13f, R.color.sense_secondary))
        ProviderPresetCatalog.presets.forEach { preset ->
            addView(providerRow(preset).withTop(views.dp(10)))
        }
    }

    private fun providerRow(preset: ProviderPreset): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(views.dp(16), views.dp(13), views.dp(16), views.dp(13))
        background = views.selectableItemBackground()
        isClickable = true
        isFocusable = true
        contentDescription = "${preset.displayName}，${preset.model}"
        addView(views.text(preset.displayName, 15f, R.color.sense_primary, Typeface.BOLD))
        addView(views.text(
            when (preset.authMode) {
                ProviderAuthMode.CODEX_SUBSCRIPTION -> "ChatGPT 浏览器登录 · ${preset.model}"
                ProviderAuthMode.NONE -> "无需密钥 · ${preset.model}"
                ProviderAuthMode.API_KEY -> "API Key · ${preset.model}"
            } + if (preset.id == activePreset) " · 当前" else "",
            12f,
            if (preset.id == activePreset) R.color.sense_success else R.color.sense_secondary,
        ).withTop(views.dp(4)))
        setOnClickListener { onOpen(preset.id) }
    }
}

private data class ProviderSettingsBinding(
    val root: LinearLayout,
    val presetTitle: TextView,
    val name: EditText,
    val baseUrl: EditText,
    val model: EditText,
    val apiKey: EditText,
    val apiKeyContainer: View,
    val apiStyleChoices: ChoiceButtons,
    val outputChoices: ChoiceButtons,
    val reasoningChoices: ChoiceButtons,
    val streaming: Switch,
    val advanced: Switch,
    val advancedFields: LinearLayout,
    val codexArea: LinearLayout,
    val codexLoginButton: Button,
    val status: TextView,
    val testButton: Button,
)

private class ChoiceButtons(
    private val buttons: List<Button>,
    initial: Int = 0,
) {
    var selected: Int = initial
        set(value) {
            field = value.coerceIn(0, buttons.lastIndex)
            buttons.forEachIndexed { index, button ->
                button.isSelected = index == field
                button.alpha = if (index == field) 1f else 0.64f
                button.text = (if (index == field) "✓  " else "○  ") + button.tag.toString()
            }
        }
    var onChange: (() -> Unit)? = null
    init {
        buttons.forEachIndexed { index, button -> button.setOnClickListener { selected = index; onChange?.invoke() } }
        selected = initial
    }
}

internal class ProviderSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val initialPresetId: ProviderPresetId? = null,
    private val onChooseProvider: (() -> Unit)? = null,
    repository: ProviderSettingsRepository = RuntimeProviderSettingsRepository(activity),
    private val tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-ProviderSettings",
        uiExecutor = Executor { activity.runOnUiThread(it) },
    ),
) : AutoCloseable {
    private val controller = ProviderSettingsController(repository, tasks)
    private val testClient = SenseAiProviderTestClient(activity.applicationContext, ::onConnectionTestEvent)
    private val codexClient = CodexOAuthClient()
    private val codexStore = CodexSubscriptionAuthStore(activity.applicationContext, codexClient)
    private val codexLoginCommitGate = CodexLoginCommitGate()
    private var binding: ProviderSettingsBinding? = null
    private var selectedPresetId = initialPresetId ?: ProviderPresetCatalog.default.id
    private var uiLoaded = false
    private var suppress = false
    private var hasSavedCredential = false
    private var loadedCredentialScope: String? = null
    private var testRunning = false
    private var codexLoginInProgress = false
    @Volatile private var codexLoginGeneration = 0L
    @Volatile private var closed = false
    private val persistRunnable = Runnable(::autoPersist)

    fun createView(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(views.text(R.string.ai_provider_body, 13f, R.color.sense_secondary))
        val presetTitle = views.text("", 18f, R.color.sense_primary, Typeface.BOLD)
        root.addView(presetTitle.withTop(views.dp(16)))
        onChooseProvider?.let { root.addView(views.secondaryButton(R.string.ai_provider_choose, it).withTop(views.dp(8))) }

        val name = views.editField(R.string.ai_provider_name, "OpenAI")
        val baseUrl = views.editField(R.string.ai_provider_base_url, ProviderProfile.DEFAULT_OPENAI_BASE_URL)
        val model = views.editField(R.string.ai_provider_model, "gpt-5.6-sol")
        val apiKey = views.secretField(R.string.ai_provider_key, "sk-…")
        val apiKeyContainer = views.labeledField(R.string.ai_provider_key, apiKey)
        root.addView(apiKeyContainer.withTop(views.dp(12)))

        val reasoning = choiceGroup(root, R.string.ai_provider_reasoning_strength, listOf("快速 · 最低延迟", "均衡 · 自动判断", "深度 · 更强推理"))
        val advanced = views.switch(R.string.ai_provider_advanced)
        root.addView(advanced.withTop(views.dp(12)))
        val advancedFields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        advancedFields.addView(views.labeledField(R.string.ai_provider_name, name))
        advancedFields.addView(views.labeledField(R.string.ai_provider_base_url, baseUrl).withTop(views.dp(10)))
        advancedFields.addView(views.labeledField(R.string.ai_provider_model, model).withTop(views.dp(10)))
        val apiStyle = choiceGroup(advancedFields, R.string.ai_provider_style, listOf("OpenAI Responses", "OpenAI-compatible Chat Completions"))
        val output = choiceGroup(advancedFields, R.string.ai_provider_structured_output, listOf("严格 JSON Schema", "JSON Object", "仅提示词约束"))
        val streaming = views.switch(R.string.ai_provider_stream, true)
        advancedFields.addView(streaming.withTop(views.dp(10)))
        root.addView(advancedFields.withTop(views.dp(10)))

        val codexLoginButton = views.primaryButton(R.string.codex_login_start, ::startCodexLogin)
        val codexArea = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(views.text(R.string.codex_login_body, 13f, R.color.sense_secondary))
            addView(codexLoginButton.withTop(views.dp(10)))
            addView(views.secondaryButton(R.string.codex_login_logout, ::logoutCodex).withTop(views.dp(8)))
        }
        root.addView(codexArea.withTop(views.dp(12)))
        val testButton = views.primaryButton(R.string.ai_provider_test, ::testConnection)
        root.addView(testButton.withTop(views.dp(14)))
        root.addView(views.secondaryButton(R.string.ai_provider_validate, ::validateSaved).withTop(views.dp(8)))
        root.addView(views.secondaryButton(R.string.ai_provider_clear_key, ::clearCredential).withTop(views.dp(8)))
        val status = views.text(R.string.ai_provider_not_configured, 12f, R.color.sense_secondary).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status.withTop(views.dp(10)))
        binding = ProviderSettingsBinding(root, presetTitle, name, baseUrl, model, apiKey, apiKeyContainer, apiStyle, output, reasoning, streaming, advanced, advancedFields, codexArea, codexLoginButton, status, testButton)

        advanced.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        listOf(apiStyle, output, reasoning).forEach { it.onChange = ::autoPersist }
        streaming.setOnCheckedChangeListener { _, _ -> autoPersist() }
        listOf(name, baseUrl, model, apiKey).forEach { it.persistText() }
        load()
        return root
    }

    fun onStop() { testClient.cancel(); flush() }

    override fun close() {
        codexLoginCommitGate.close()
        closed = true
        codexLoginGeneration += 1
        codexLoginInProgress = false
        codexClient.cancelLogin()
        testClient.cancel()
        testClient.close()
        flush()
        controller.close()
        binding = null
    }

    private fun choiceGroup(parent: LinearLayout, label: Int, labels: List<String>): ChoiceButtons {
        parent.addView(views.text(label, 12f, R.color.sense_secondary, Typeface.BOLD).withTop(views.dp(10)))
        val buttons = labels.map { labelText ->
            Button(activity).apply {
                tag = labelText
                text = labelText
                isAllCaps = false
                textSize = 13f
                setTextColor(activity.getColor(R.color.sense_primary))
            }.also { parent.addView(it.withTop(views.dp(5))) }
        }
        return ChoiceButtons(buttons)
    }

    private fun load() {
        controller.load { result ->
            result.onSuccess { snapshot ->
                val requested = initialPresetId?.let(ProviderPresetCatalog::requirePreset)
                if (requested != null && ProviderPresetCatalog.detect(snapshot.profile ?: ProviderPresetCatalog.default.profile()).id != requested.id) {
                    applyProfile(requested.profile(), false)
                    autoPersist()
                } else applyProfile(snapshot.profile ?: ProviderPresetCatalog.default.profile(), snapshot.hasCredential)
            }.onFailure { applyProfile(ProviderPresetCatalog.default.profile(), false); show(R.string.ai_provider_invalid, true) }
        }
    }

    private fun applyProfile(profile: ProviderProfile, credential: Boolean) {
        val current = binding ?: return
        suppress = true
        uiLoaded = false
        selectedPresetId = initialPresetId ?: ProviderPresetCatalog.detect(profile).id
        hasSavedCredential = credential
        current.presetTitle.text = ProviderPresetCatalog.requirePreset(selectedPresetId).displayName
        current.name.setText(profile.displayName)
        current.baseUrl.setText(profile.baseUrl)
        current.model.setText(profile.model)
        current.apiStyleChoices.selected = if (profile.apiStyle == ProviderApiStyle.OPENAI_RESPONSES) 0 else 1
        current.outputChoices.selected = profile.structuredOutput.ordinal
        current.reasoningChoices.selected = ProviderReasoningStrength.from(profile).ordinal
        current.streaming.isChecked = profile.streaming
        current.advanced.isChecked = ProviderPresetCatalog.requirePreset(selectedPresetId).isCustom
        loadedCredentialScope = credentialScope(profile)
        suppress = false
        uiLoaded = true
        updateVisibility()
        updateKeyHint()
        show(if (credential || profile.authMode == ProviderAuthMode.NONE) R.string.ai_provider_saved else R.string.ai_provider_not_configured, false)
    }

    private fun currentProfile(): ProviderProfile {
        val current = checkNotNull(binding)
        val preset = ProviderPresetCatalog.requirePreset(selectedPresetId)
        val base = preset.profile().copy(
            displayName = current.name.text.toString().trim(),
            baseUrl = current.baseUrl.text.toString().trim(),
            model = current.model.text.toString().trim(),
            apiStyle = if (current.apiStyleChoices.selected == 0) ProviderApiStyle.OPENAI_RESPONSES else ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
            structuredOutput = StructuredOutputMode.entries[current.outputChoices.selected],
            streaming = current.streaming.isChecked,
        )
        return ProviderReasoningStrength.entries[current.reasoningChoices.selected].applyTo(base)
    }

    private fun persist(onSaved: (() -> Unit)? = null) {
        val current = binding ?: return
        val profile = currentProfile()
        if (!profile.validate().isValid) { show(R.string.ai_provider_save_failed, true); return }
        val entered = current.apiKey.text.toString()
        val scope = credentialScope(profile)
        val preserve = hasSavedCredential && loadedCredentialScope == scope
        val credential = when (profile.authMode) {
            ProviderAuthMode.CODEX_SUBSCRIPTION, ProviderAuthMode.NONE -> CharArray(0)
            ProviderAuthMode.API_KEY -> when {
                entered.isNotEmpty() && runCatching { ProviderCredential.Bearer(entered) }.isFailure -> { show(R.string.ai_provider_key_invalid, true); return }
                entered.isNotEmpty() -> entered.toCharArray()
                preserve -> null
                profile.baseUrl.contains("localhost") || profile.baseUrl.contains("127.0.0.1") -> CharArray(0)
                else -> { show(R.string.ai_provider_key_required, true); return }
            }
        }
        controller.save(profile, credential) { result ->
            result.onSuccess {
                hasSavedCredential = it.hasCredential
                loadedCredentialScope = scope
                current.apiKey.text.clear()
                updateKeyHint()
                show(if (profile.authMode == ProviderAuthMode.CODEX_SUBSCRIPTION && !it.hasCredential) R.string.codex_login_needed else R.string.ai_provider_saved, false)
                onSaved?.invoke()
            }.onFailure { show(R.string.ai_provider_save_failed, true) }
        }
    }

    private fun autoPersist() {
        if (!uiLoaded || suppress) return
        binding?.root?.removeCallbacks(persistRunnable)
        binding?.root?.postDelayed(persistRunnable, 500)
    }

    private fun flush() {
        val root = binding?.root ?: return
        root.removeCallbacks(persistRunnable)
        if (uiLoaded && !suppress) persist()
    }

    private fun startCodexLogin() {
        binding ?: return
        if (codexLoginInProgress) {
            cancelCodexLogin(showStatus = true)
            return
        }
        codexLoginInProgress = true
        val generation = ++codexLoginGeneration
        codexLoginCommitGate.begin(generation)
        updateCodexLoginButton()
        show(R.string.codex_login_requesting, false)
        tasks.execute({
            codexClient.beginLogin().also { session ->
                if (!codexLoginCommitGate.isActive(generation)) codexClient.cancelLogin(session)
            }
        }) { started ->
            if (!codexLoginCommitGate.isActive(generation)) {
                started.getOrNull()?.let(codexClient::cancelLogin)
                return@execute
            }
            started.onSuccess { session ->
                val opened = runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizationUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                    )
                }
                if (opened.isFailure) {
                    codexClient.cancelLogin()
                    finishCodexLogin(generation, Result.failure<Unit>(opened.exceptionOrNull()!!))
                    return@onSuccess
                }
                show(R.string.codex_login_waiting, false)
                tasks.execute({
                    val tokens = codexClient.completeLogin(session) {
                        !codexLoginCommitGate.isActive(generation)
                    }
                    when (val commit = codexLoginCommitGate.commitIfActive(generation) {
                        codexStore.save(tokens).getOrThrow()
                    }) {
                        is CodexLoginCommitResult.Accepted -> commit.result.getOrThrow()
                        CodexLoginCommitResult.Rejected -> throw CancellationException(
                            "Codex login attempt ended before credential commit",
                        )
                    }
                }) { completed ->
                    finishCodexLogin(generation, completed)
                }
            }.onFailure { finishCodexLogin(generation, started) }
        }
    }

    private fun finishCodexLogin(generation: Long, result: Result<*>) {
        if (generation != codexLoginGeneration || closed) return
        codexLoginCommitGate.finish(generation)
        codexLoginInProgress = false
        updateCodexLoginButton()
        result.onSuccess {
            hasSavedCredential = true
            show(R.string.codex_login_succeeded, false)
            persist()
        }.onFailure {
            show(R.string.codex_login_failed, true)
        }
    }

    private fun cancelCodexLogin(showStatus: Boolean) {
        if (!codexLoginInProgress) return
        if (!codexLoginCommitGate.cancel(codexLoginGeneration)) return
        codexLoginGeneration += 1
        codexLoginInProgress = false
        codexClient.cancelLogin()
        updateCodexLoginButton()
        if (showStatus) show(R.string.codex_login_cancelled, false)
    }

    private fun updateCodexLoginButton() {
        binding?.codexLoginButton?.setText(
            if (codexLoginInProgress) R.string.codex_login_cancel else R.string.codex_login_start,
        )
    }

    private fun logoutCodex() {
        cancelCodexLogin(showStatus = false)
        tasks.execute({ codexStore.clear().getOrThrow() }) { result ->
            result.onSuccess { hasSavedCredential = false; show(R.string.codex_login_logged_out, false) }
                .onFailure { show(R.string.ai_provider_save_failed, true) }
        }
    }

    private fun clearCredential() {
        if (currentProfile().authMode == ProviderAuthMode.CODEX_SUBSCRIPTION) { logoutCodex(); return }
        controller.clearCredential { result -> result.onSuccess { hasSavedCredential = false; updateKeyHint(); show(R.string.ai_provider_key_cleared, false) }.onFailure { show(R.string.ai_provider_save_failed, true) } }
    }

    private fun validateSaved() = controller.validate { result -> result.onSuccess { show(if (it) R.string.ai_provider_local_valid else R.string.ai_provider_not_configured, !it) }.onFailure { show(R.string.ai_provider_invalid, true) } }

    private fun testConnection() {
        val current = binding ?: return
        if (testRunning) { testClient.cancel(); return }
        persist {
            testRunning = true
            current.testButton.setText(R.string.ai_provider_test_cancel)
            show(R.string.ai_provider_test_starting, false)
            testClient.start()
        }
    }

    private fun onConnectionTestEvent(event: ProviderConnectionTestEvent) {
        val current = binding ?: return
        when (event) {
            ProviderConnectionTestEvent.Starting -> show(R.string.ai_provider_test_starting, false)
            is ProviderConnectionTestEvent.Progress -> show(when (event.phase) {
                ProviderConnectionTestPhase.CONNECTING -> R.string.ai_provider_test_connecting
                ProviderConnectionTestPhase.UNDERSTANDING -> R.string.ai_provider_test_understanding
                ProviderConnectionTestPhase.GENERATING -> R.string.ai_provider_test_generating
                ProviderConnectionTestPhase.VALIDATING -> R.string.ai_provider_test_validating
            }, false)
            is ProviderConnectionTestEvent.Succeeded -> {
                testRunning = false; current.testButton.setText(R.string.ai_provider_test)
                current.status.text = if (event.inputTokens != null && event.outputTokens != null) activity.getString(R.string.ai_provider_test_succeeded_with_usage, event.elapsedMs / 1000.0, event.inputTokens, event.outputTokens) else activity.getString(R.string.ai_provider_test_succeeded, event.elapsedMs / 1000.0)
                current.status.setTextColor(activity.getColor(R.color.sense_success))
            }
            is ProviderConnectionTestEvent.Failed -> {
                testRunning = false; current.testButton.setText(R.string.ai_provider_test)
                show(when (event.failure) {
                    ProviderConnectionTestFailure.NOT_CONFIGURED -> R.string.ai_provider_test_not_configured
                    ProviderConnectionTestFailure.AUTHENTICATION -> R.string.ai_provider_test_authentication
                    ProviderConnectionTestFailure.QUOTA -> R.string.ai_provider_test_quota
                    ProviderConnectionTestFailure.CONFIGURATION -> R.string.ai_provider_test_configuration
                    ProviderConnectionTestFailure.RATE_LIMIT -> R.string.ai_provider_test_rate_limit
                    ProviderConnectionTestFailure.UNAVAILABLE -> R.string.ai_provider_test_unavailable
                    ProviderConnectionTestFailure.NETWORK -> R.string.ai_provider_test_network
                    ProviderConnectionTestFailure.TIMEOUT -> R.string.ai_provider_test_timeout
                    ProviderConnectionTestFailure.PROTOCOL -> R.string.ai_provider_test_protocol
                    ProviderConnectionTestFailure.INTERNAL -> R.string.ai_provider_test_internal
                }, true)
            }
            ProviderConnectionTestEvent.Cancelled -> { testRunning = false; current.testButton.setText(R.string.ai_provider_test); show(R.string.ai_provider_test_cancelled, false) }
        }
    }

    private fun updateVisibility() {
        val current = binding ?: return
        val auth = ProviderPresetCatalog.requirePreset(selectedPresetId).authMode
        current.apiKeyContainer.visibility = if (auth == ProviderAuthMode.API_KEY) View.VISIBLE else View.GONE
        current.codexArea.visibility = if (auth == ProviderAuthMode.CODEX_SUBSCRIPTION) View.VISIBLE else View.GONE
        current.advancedFields.visibility = if (current.advanced.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateKeyHint() { binding?.apiKey?.hint = if (hasSavedCredential) activity.getString(R.string.ai_provider_key_saved) else "sk-…" }
    private fun credentialScope(profile: ProviderProfile) = CredentialEndpointScope.normalize(profile.baseUrl)
    private fun show(resource: Int, error: Boolean) { binding?.status?.setText(resource); binding?.status?.setTextColor(activity.getColor(if (error) android.R.color.holo_red_dark else R.color.sense_success)) }

    private fun EditText.persistText() {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = autoPersist()
        })
    }
}
