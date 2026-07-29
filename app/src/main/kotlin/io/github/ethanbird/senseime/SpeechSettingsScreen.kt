package io.github.ethanbird.senseime

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.CredentialEndpointScope
import io.github.ethanbird.senseime.speech.SpeechProviderCredentialPolicy
import io.github.ethanbird.senseime.speech.SpeechProviderCredentialRequirement
import io.github.ethanbird.senseime.speech.SpeechProviderPreset
import io.github.ethanbird.senseime.speech.SpeechProviderPresetCatalog
import io.github.ethanbird.senseime.speech.SpeechProviderProfile
import io.github.ethanbird.senseime.speech.SpeechProviderRuntimeCapability
import io.github.ethanbird.senseime.speech.SpeechProviderSettingsStore
import java.util.concurrent.Executor

internal data class SpeechSettingsSnapshot(
    val profile: SpeechProviderProfile?,
    val hasCredential: Boolean,
)

internal interface SpeechSettingsRepository {
    fun load(): Result<SpeechSettingsSnapshot>
    fun save(
        profile: SpeechProviderProfile,
        credential: CharArray?,
    ): Result<SpeechSettingsSnapshot>
    fun clearCredential(): Result<SpeechSettingsSnapshot>
}

internal class RuntimeSpeechSettingsRepository(
    activity: Activity,
) : SpeechSettingsRepository {
    private val applicationContext = activity.applicationContext
    private val store by lazy { SpeechProviderSettingsStore(applicationContext) }

    override fun load(): Result<SpeechSettingsSnapshot> =
        store.loadProfile().map { profile ->
            SpeechSettingsSnapshot(profile, store.hasCredential())
        }

    override fun save(
        profile: SpeechProviderProfile,
        credential: CharArray?,
    ): Result<SpeechSettingsSnapshot> =
        store.save(profile, credential).map {
            SpeechSettingsSnapshot(profile, store.hasCredential())
        }

    override fun clearCredential(): Result<SpeechSettingsSnapshot> =
        store.loadProfile().fold(
            onSuccess = { profile ->
                if (profile == null) {
                    Result.success(SpeechSettingsSnapshot(null, false))
                } else {
                    store.save(profile, CharArray(0)).map {
                        SpeechSettingsSnapshot(profile, false)
                    }
                }
            },
            onFailure = { Result.failure(it) },
        )
}

internal class SpeechSettingsController(
    private val repository: SpeechSettingsRepository,
    private val tasks: SettingsTaskRunner,
) : AutoCloseable {
    private val generations = mutableMapOf<String, Long>()
    private var closed = false

    fun load(deliver: (Result<SpeechSettingsSnapshot>) -> Unit): Boolean =
        refresh(LOAD_CHANNEL, repository::load, deliver)

    fun save(
        profile: SpeechProviderProfile,
        credential: CharArray?,
        deliver: (Result<SpeechSettingsSnapshot>) -> Unit,
    ): Boolean = execute(
        operation = SAVE_OPERATION,
        task = { repository.save(profile, credential).getOrThrow() },
        deliver = deliver,
    )

    fun clearCredential(deliver: (Result<SpeechSettingsSnapshot>) -> Unit): Boolean =
        execute(
            operation = CLEAR_CREDENTIAL_OPERATION,
            task = { repository.clearCredential().getOrThrow() },
            deliver = deliver,
        )

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
            deliver(Result.failure(IllegalStateException("Speech settings lane is closed")))
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
            deliver(Result.failure(IllegalStateException("Speech settings lane is closed")))
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
        const val LOAD_CHANNEL = "speech-load"
        const val SAVE_OPERATION = "speech-save"
        const val CLEAR_CREDENTIAL_OPERATION = "speech-clear-credential"
    }
}

internal class SpeechSettingsViewBinding(
    val root: LinearLayout,
    val preset: Spinner,
    val language: Spinner,
    val apiKey: EditText,
    val endpoint: EditText,
    val model: EditText,
    val advanced: Switch,
    val advancedFields: LinearLayout,
    val permissionButton: Button,
    val status: TextView,
)

internal class SpeechSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val emitEffect: (SettingsEffect) -> Unit,
    repository: SpeechSettingsRepository =
        RuntimeSpeechSettingsRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-SpeechSettings",
        uiExecutor = Executor { command -> activity.runOnUiThread(command) },
    ),
) : AutoCloseable {
    private var binding: SpeechSettingsViewBinding? = null
    private var uiLoaded = false
    private var selectedPresetPosition = 0
    private var loadedCredentialScope: String? = null
    private var hasSavedCredential = false
    private var permissionRequestInFlight = false
    private var permissionDeniedOnce = false
    private val controller = SpeechSettingsController(repository, tasks)

    fun createView(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(views.text(R.string.speech_provider_body, 13f, R.color.sense_secondary))

        val preset = views.accessibleSpinner(R.string.speech_provider_preset).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                SpeechProviderPresetCatalog.all.map(SpeechProviderPreset::displayName),
            )
        }
        val language = views.accessibleSpinner(R.string.speech_provider_language).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                SPEECH_LANGUAGES.map { it.first },
            )
        }
        val apiKey =
            views.secretField(R.string.speech_provider_key, "可选；系统模式无需 Key")
        val endpoint = views.editField(R.string.speech_provider_endpoint, "https://…")
        val model = views.editField(R.string.speech_provider_model, "model")
        val advanced = views.switch(R.string.speech_provider_advanced)
        val advancedFields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(views.labeledField(R.string.speech_provider_endpoint, endpoint))
            addView(
                views.labeledField(R.string.speech_provider_model, model)
                    .withTop(views.dp(10)),
            )
        }

        root.addView(
            views.labeledField(R.string.speech_provider_preset, preset)
                .withTop(views.dp(14)),
        )
        root.addView(
            views.labeledField(R.string.speech_provider_language, language)
                .withTop(views.dp(10)),
        )
        root.addView(
            views.labeledField(R.string.speech_provider_key, apiKey)
                .withTop(views.dp(10)),
        )
        root.addView(advanced.withTop(views.dp(10)))
        root.addView(advancedFields.withTop(views.dp(10)))
        root.addView(
            views.secondaryButton(R.string.speech_provider_save, ::save)
                .withTop(views.dp(12)),
        )
        root.addView(
            views.secondaryButton(R.string.speech_provider_clear_key, ::clearCredential)
                .withTop(views.dp(8)),
        )
        val permissionButton =
            views.primaryButton(R.string.speech_permission_grant, ::requestPermission)
        root.addView(permissionButton.withTop(views.dp(8)))
        val status =
            views.text(
                R.string.speech_provider_not_configured,
                12f,
                R.color.sense_secondary,
            ).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        root.addView(status.withTop(views.dp(10)))

        binding = SpeechSettingsViewBinding(
            root = root,
            preset = preset,
            language = language,
            apiKey = apiKey,
            endpoint = endpoint,
            model = model,
            advanced = advanced,
            advancedFields = advancedFields,
            permissionButton = permissionButton,
            status = status,
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
                if (uiLoaded && changed) {
                    apiKey.text.clear()
                    applyPresetFields(selectedPreset())
                    advanced.isChecked = false
                }
                updateAdvancedVisibility()
                updateKeyHint()
                updateCapabilityStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateAdvancedVisibility()
        updatePermissionButton()
        load()
        return root
    }

    fun onResume() {
        updatePermissionButton()
    }

    fun onPermissionResult(granted: Boolean) {
        permissionRequestInFlight = false
        permissionDeniedOnce = !granted
        updatePermissionButton()
        if (!granted) {
            binding?.status?.setText(R.string.speech_permission_denied)
            binding?.status?.setTextColor(activity.getColor(android.R.color.holo_red_dark))
        }
    }

    override fun close() {
        controller.close()
        binding = null
    }

    private fun load() {
        controller.load { result ->
            result
                .onSuccess(::applySnapshot)
                .onFailure {
                    applyDefault()
                    binding?.status?.setText(R.string.speech_provider_invalid)
                    binding?.status?.setTextColor(
                        activity.getColor(android.R.color.holo_red_dark),
                    )
                }
        }
    }

    private fun applySnapshot(snapshot: SpeechSettingsSnapshot) {
        val current = binding ?: return
        hasSavedCredential = snapshot.hasCredential
        val profile = snapshot.profile
        val preset = profile?.let { SpeechProviderPresetCatalog.find(it.presetId) }
            ?: SpeechProviderPresetCatalog.require(SpeechProviderPresetCatalog.SYSTEM)
        selectedPresetPosition = SpeechProviderPresetCatalog.all.indexOf(preset).coerceAtLeast(0)
        current.preset.setSelection(selectedPresetPosition)
        applyPresetFields(preset)
        if (profile != null) {
            current.endpoint.setText(profile.endpointUrl.orEmpty())
            current.model.setText(profile.model.orEmpty())
            val languageIndex =
                SPEECH_LANGUAGES.indexOfFirst { it.second == profile.languageTag }
            current.language.setSelection(languageIndex.coerceAtLeast(0))
        }
        loadedCredentialScope = profile?.let { credentialScope(preset, it.endpointUrl) }
        current.advanced.isChecked = false
        uiLoaded = true
        updateAdvancedVisibility()
        updateKeyHint()
        updateCapabilityStatus()
    }

    private fun applyDefault() {
        val current = binding ?: return
        hasSavedCredential = false
        loadedCredentialScope = null
        val preset =
            SpeechProviderPresetCatalog.require(SpeechProviderPresetCatalog.SYSTEM)
        selectedPresetPosition = SpeechProviderPresetCatalog.all.indexOf(preset).coerceAtLeast(0)
        current.preset.setSelection(selectedPresetPosition)
        applyPresetFields(preset)
        current.advanced.isChecked = false
        uiLoaded = true
        updateAdvancedVisibility()
        updateKeyHint()
    }

    private fun save() {
        val current = binding ?: return
        val preset = selectedPreset()
        val profile = preset.defaultProfile(selectedLanguageTag()).copy(
            endpointUrl = if (preset.defaultEndpointUrl == null) {
                null
            } else {
                current.endpoint.text.toString().trim()
            },
            model = if (preset.defaultModel == null) {
                null
            } else {
                current.model.text.toString().trim()
            },
        )
        val validation = profile.validate()
        if (!validation.isValid) {
            current.status.text = validation.errors.joinToString("\n") {
                "${it.path}: ${it.message}"
            }
            current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
            return
        }

        val enteredKey = current.apiKey.text.toString()
        val scope = credentialScope(preset, profile.endpointUrl)
        val canPreserve =
            hasSavedCredential && loadedCredentialScope == scope
        val apiKey = when {
            preset.credentialRequirement == SpeechProviderCredentialRequirement.NONE ->
                CharArray(0)
            enteredKey.isEmpty() && !canPreserve -> {
                current.status.setText(
                    if (hasSavedCredential) {
                        R.string.speech_provider_key_provider_changed
                    } else {
                        R.string.speech_provider_key_required_to_save
                    },
                )
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                return
            }
            enteredKey.isEmpty() -> null
            !SpeechProviderCredentialPolicy.isValid(enteredKey) -> {
                current.status.setText(R.string.speech_provider_key_invalid)
                current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                return
            }
            else -> enteredKey.toCharArray()
        }
        controller.save(profile, apiKey) { result ->
            result
                .onSuccess { snapshot ->
                    hasSavedCredential = snapshot.hasCredential
                    loadedCredentialScope = scope
                    current.apiKey.text.clear()
                    updateKeyHint()
                    current.status.setText(
                        if (
                            preset.runtimeCapability ==
                            SpeechProviderRuntimeCapability.AVAILABLE
                        ) {
                            R.string.speech_provider_saved
                        } else {
                            R.string.speech_provider_saved_configuration_only
                        },
                    )
                    current.status.setTextColor(
                        activity.getColor(
                            if (
                                preset.runtimeCapability ==
                                SpeechProviderRuntimeCapability.AVAILABLE
                            ) {
                                R.color.sense_success
                            } else {
                                R.color.sense_secondary
                            },
                        ),
                    )
                }
                .onFailure {
                    current.status.setText(R.string.speech_provider_save_failed)
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
                    loadedCredentialScope = snapshot.profile?.let { profile ->
                        SpeechProviderPresetCatalog.find(profile.presetId)?.let { preset ->
                            credentialScope(preset, profile.endpointUrl)
                        }
                    }
                    current.apiKey.text.clear()
                    updateKeyHint()
                    current.status.setText(
                        if (snapshot.profile == null) {
                            R.string.speech_provider_not_configured
                        } else {
                            R.string.speech_provider_key_cleared
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
                    current.status.setText(R.string.speech_provider_save_failed)
                    current.status.setTextColor(activity.getColor(android.R.color.holo_red_dark))
                }
        }
    }

    private fun selectedPreset(): SpeechProviderPreset {
        val current = requireNotNull(binding)
        return SpeechProviderPresetCatalog.all[
            current.preset.selectedItemPosition.coerceIn(
                0,
                SpeechProviderPresetCatalog.all.lastIndex,
            )
        ]
    }

    private fun selectedLanguageTag(): String {
        val current = requireNotNull(binding)
        return SPEECH_LANGUAGES[
            current.language.selectedItemPosition.coerceIn(0, SPEECH_LANGUAGES.lastIndex)
        ].second
    }

    private fun applyPresetFields(preset: SpeechProviderPreset) {
        val current = binding ?: return
        current.endpoint.setText(preset.defaultEndpointUrl.orEmpty())
        current.model.setText(preset.defaultModel.orEmpty())
    }

    private fun credentialScope(
        preset: SpeechProviderPreset,
        endpointUrl: String?,
    ): String =
        "${preset.id}:${CredentialEndpointScope.normalize(endpointUrl.orEmpty())}"

    private fun updateAdvancedVisibility() {
        val current = binding ?: return
        val cloud = selectedPreset().defaultEndpointUrl != null
        current.advanced.isEnabled = cloud
        if (!cloud && current.advanced.isChecked) {
            current.advanced.isChecked = false
            return
        }
        current.advancedFields.visibility =
            if (cloud && current.advanced.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateKeyHint() {
        val current = binding ?: return
        val preset = selectedPreset()
        if (preset.credentialRequirement == SpeechProviderCredentialRequirement.NONE) {
            current.apiKey.text.clear()
            current.apiKey.isEnabled = false
            current.apiKey.hint = activity.getString(R.string.speech_provider_key_not_required)
            return
        }
        current.apiKey.isEnabled = true
        val scope = credentialScope(preset, current.endpoint.text.toString())
        current.apiKey.hint = activity.getString(
            if (hasSavedCredential && loadedCredentialScope == scope) {
                R.string.speech_provider_key_saved
            } else {
                R.string.speech_provider_key_required
            },
        )
    }

    private fun updateCapabilityStatus() {
        val current = binding ?: return
        val preset = selectedPreset()
        if (preset.runtimeCapability == SpeechProviderRuntimeCapability.AVAILABLE) {
            current.status.setText(
                when {
                    !hasPermission() -> R.string.speech_provider_permission_needed
                    preset.id == SpeechProviderPresetCatalog.SYSTEM ->
                        R.string.speech_provider_system_ready
                    else -> R.string.speech_provider_cloud_ready
                },
            )
            current.status.setTextColor(
                activity.getColor(
                    if (hasPermission()) R.color.sense_success else R.color.sense_secondary,
                ),
            )
        } else {
            current.status.text = preset.capabilityNotice
                ?: activity.getString(R.string.speech_provider_configuration_only)
            current.status.setTextColor(activity.getColor(R.color.sense_secondary))
        }
    }

    private fun requestPermission() {
        if (hasPermission()) {
            updatePermissionButton()
            return
        }
        if (
            permissionDeniedOnce &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            emitEffect(SettingsEffect.OpenApplicationDetails)
            return
        }
        permissionRequestInFlight = true
        updatePermissionButton()
        emitEffect(SettingsEffect.RequestRecordAudio)
    }

    private fun updatePermissionButton() {
        val current = binding ?: return
        val granted = hasPermission()
        current.permissionButton.setText(
            when {
                granted -> R.string.speech_permission_granted
                permissionDeniedOnce &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.RECORD_AUDIO,
                    ) ->
                    R.string.speech_permission_open_settings
                else -> R.string.speech_permission_grant
            },
        )
        current.permissionButton.isEnabled = !granted && !permissionRequestInFlight
        updateCapabilityStatus()
    }

    private fun hasPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        val SPEECH_LANGUAGES = listOf(
            "普通话（中国大陆）" to "zh-CN",
            "粤语（香港）" to "zh-HK",
            "中文（台湾）" to "zh-TW",
            "English (US)" to "en-US",
        )
    }
}
