package io.github.ethanbird.senseime

import android.Manifest
import android.app.Activity
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

class SettingsActivity : Activity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.sense_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(buildContent())
        providerTestClient = SenseAiProviderTestClient(this, ::onProviderConnectionTestEvent)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (!providerUiLoaded) loadProviderSettings()
        if (!speechUiLoaded) loadSpeechSettings()
        updateSpeechPermissionButton()
    }

    override fun onStop() {
        if (::providerTestClient.isInitialized) providerTestClient.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (::providerTestClient.isInitialized) providerTestClient.close()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
        }

        content.addView(text(R.string.brand_english, 12f, R.color.sense_accent, Typeface.BOLD).apply {
            letterSpacing = 0.24f
        })
        content.addView(text(R.string.brand_chinese, 34f, R.color.sense_primary, Typeface.BOLD).withTop(dp(5)))
        content.addView(text(R.string.brand_tagline, 15f, R.color.sense_secondary).withTop(dp(8)))
        content.addView(badge().withTop(dp(18)))

        statusText = text(R.string.ime_disabled, 16f, R.color.sense_primary, Typeface.BOLD)
        content.addView(card(R.string.ime_status_title, statusText).withTop(dp(26)))

        content.addView(primaryButton(R.string.enable_ime) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.withTop(dp(14)))
        content.addView(secondaryButton(R.string.switch_ime) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }.withTop(dp(10)))

        content.addView(card(R.string.ai_provider_title, providerForm()).withTop(dp(24)))
        content.addView(card(R.string.speech_provider_title, speechProviderForm()).withTop(dp(12)))
        content.addView(
            card(
                R.string.m0_title,
                text(R.string.m0_body, 15f, R.color.sense_secondary),
            ).withTop(dp(12)),
        )
        content.addView(
            card(
                R.string.offline_title,
                text(R.string.offline_body, 15f, R.color.sense_secondary),
            ).withTop(dp(12)),
        )
        content.addView(
            card(
                R.string.dictionary_notice_title,
                text(R.string.dictionary_notice_body, 13f, R.color.sense_secondary),
            ).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { showDictionaryNotice() }
            }.withTop(dp(12)),
        )
        content.addView(text(R.string.version_label, 12f, R.color.sense_secondary).withTop(dp(24)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.sense_background))
            isFillViewport = true
            addView(content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    content.setPadding(dp(22), dp(30) + bars.top, dp(22), dp(30) + bars.bottom)
                    insets
                }
            }
        }
        return scroll
    }

    private fun updateStatus() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        statusText.setText(if (enabled) R.string.ime_enabled else R.string.ime_disabled)
        statusText.setTextColor(getColor(if (enabled) R.color.sense_success else R.color.sense_primary))
    }

    private fun providerForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(text(R.string.ai_provider_body, 13f, R.color.sense_secondary))
        providerPreset = Spinner(this@SettingsActivity).apply {
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
        providerApiStyle = Spinner(this@SettingsActivity).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenAI Responses", "OpenAI-compatible Chat Completions"),
            )
        }
        providerStructuredOutput = Spinner(this@SettingsActivity).apply {
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
        providerThinkingMode = Spinner(this@SettingsActivity).apply {
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
        }
        providerAdvanced = Switch(this@SettingsActivity).apply {
            setText(R.string.ai_provider_advanced)
            setTextColor(getColor(R.color.sense_primary))
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
        providerStatus = text(R.string.ai_provider_not_configured, 12f, R.color.sense_secondary)
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

    private fun speechProviderForm(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(R.string.speech_provider_body, 13f, R.color.sense_secondary))

        speechPreset = Spinner(this@SettingsActivity).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SpeechProviderPresetCatalog.all.map(SpeechProviderPreset::displayName),
            )
        }
        speechLanguage = Spinner(this@SettingsActivity).apply {
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
        speechStatus = text(R.string.speech_provider_not_configured, 12f, R.color.sense_secondary)
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
        permissions: Array<out String>,
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
        textSize = 14f
        setSingleLine(true)
        setTextColor(getColor(R.color.sense_primary))
        setHintTextColor(getColor(R.color.sense_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(getColor(R.color.sense_background), dp(10).toFloat())
    }

    private fun labeledField(labelRes: Int, field: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(labelRes, 12f, R.color.sense_secondary, Typeface.BOLD))
        addView(field.withTop(dp(5)))
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

    companion object {
        private const val DEFAULT_PROVIDER_MODEL = "gpt-4.1-mini"
        private const val REQUEST_RECORD_AUDIO = 40
        private val SPEECH_LANGUAGES = listOf(
            "普通话（中国大陆）" to "zh-CN",
            "粤语（香港）" to "zh-HK",
            "中文（台湾）" to "zh-TW",
            "English (US)" to "en-US",
        )
    }
}
