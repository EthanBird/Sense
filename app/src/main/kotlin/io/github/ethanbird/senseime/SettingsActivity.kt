package io.github.ethanbird.senseime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.ProviderCompatibilityIssue
import io.github.ethanbird.senseime.brain.api.ProviderApiStyle
import io.github.ethanbird.senseime.brain.api.ProviderCredential
import io.github.ethanbird.senseime.brain.api.ProviderProfile
import io.github.ethanbird.senseime.brain.api.StructuredOutputMode
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestEvent
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestFailure
import io.github.ethanbird.senseime.brain.runtime.ProviderConnectionTestPhase
import io.github.ethanbird.senseime.brain.runtime.ProviderSettingsStore
import io.github.ethanbird.senseime.brain.runtime.SenseAiProviderTestClient

class SettingsActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var providerName: EditText
    private lateinit var providerBaseUrl: EditText
    private lateinit var providerModel: EditText
    private lateinit var providerApiKey: EditText
    private lateinit var providerApiStyle: Spinner
    private lateinit var providerStructuredOutput: Spinner
    private lateinit var providerStreaming: Switch
    private lateinit var providerStatus: TextView
    private lateinit var providerTestButton: Button
    private val providerStore by lazy { ProviderSettingsStore(this) }
    private lateinit var providerTestClient: SenseAiProviderTestClient
    private var providerTestRunning = false

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
        loadProviderSettings()
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
        providerStreaming = Switch(this@SettingsActivity).apply {
            setText(R.string.ai_provider_stream)
            isChecked = true
            setTextColor(getColor(R.color.sense_primary))
        }

        addView(labeledField(R.string.ai_provider_name, providerName).withTop(dp(14)))
        addView(labeledField(R.string.ai_provider_base_url, providerBaseUrl).withTop(dp(10)))
        addView(labeledField(R.string.ai_provider_model, providerModel).withTop(dp(10)))
        addView(labeledField(R.string.ai_provider_style, providerApiStyle).withTop(dp(10)))
        addView(
            labeledField(
                R.string.ai_provider_structured_output,
                providerStructuredOutput,
            ).withTop(dp(10)),
        )
        addView(labeledField(R.string.ai_provider_key, providerApiKey).withTop(dp(10)))
        addView(providerStreaming.withTop(dp(10)))

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
    }

    private fun saveProviderSettings() {
        if (providerTestRunning) providerTestClient.cancel()
        persistProviderSettings()
    }

    private fun saveAndTestProviderConnection() {
        if (providerTestRunning) return
        persistProviderSettings {
            providerTestRunning = true
            providerTestButton.isEnabled = false
            providerStatus.setText(R.string.ai_provider_test_starting)
            providerStatus.setTextColor(getColor(R.color.sense_secondary))
            providerTestClient.start()
        }
    }

    private fun persistProviderSettings(onSaved: (() -> Unit)? = null) {
        val profile = currentProviderProfile()
        if (!showProfileErrors(profile)) return

        val enteredKey = providerApiKey.text.toString()
        val key = if (enteredKey.isEmpty()) {
            null
        } else {
            val valid = runCatching { ProviderCredential.Bearer(enteredKey) }.isSuccess
            if (!valid) {
                providerStatus.setText(R.string.ai_provider_key_invalid)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                return
            }
            enteredKey.toCharArray()
        }
        providerStore.save(profile, key)
            .onSuccess {
                providerApiKey.text.clear()
                providerApiKey.hint = getString(R.string.ai_provider_key_saved)
                providerStatus.setText(R.string.ai_provider_saved)
                providerStatus.setTextColor(getColor(R.color.sense_success))
                onSaved?.invoke()
            }
            .onFailure {
                providerStatus.setText(R.string.ai_provider_save_failed)
                providerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun currentProviderProfile(): ProviderProfile =
        ProviderProfile(
            id = "primary",
            displayName = providerName.text.toString().trim(),
            apiStyle = if (providerApiStyle.selectedItemPosition == 0) {
                ProviderApiStyle.OPENAI_RESPONSES
            } else {
                ProviderApiStyle.OPENAI_COMPATIBLE_CHAT_COMPLETIONS
            },
            baseUrl = providerBaseUrl.text.toString().trim(),
            model = providerModel.text.toString().trim(),
            streaming = providerStreaming.isChecked,
            structuredOutput = when (providerStructuredOutput.selectedItemPosition) {
                0 -> StructuredOutputMode.JSON_SCHEMA
                1 -> StructuredOutputMode.JSON_OBJECT
                else -> StructuredOutputMode.PROMPT_ONLY
            },
        )

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
                        ProviderCompatibilityIssue.DEEPSEEK_REQUIRES_JSON_OBJECT ->
                            R.string.ai_provider_deepseek_json_required
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
                        inputTokens,
                        outputTokens,
                    )
                } else {
                    getString(R.string.ai_provider_test_succeeded)
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
                        providerApiKey.hint = getString(R.string.ai_provider_key_optional)
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
                    providerName.setText("OpenAI")
                    providerBaseUrl.setText(ProviderProfile.DEFAULT_OPENAI_BASE_URL)
                    providerModel.setText(DEFAULT_PROVIDER_MODEL)
                    providerApiStyle.setSelection(0)
                    providerStructuredOutput.setSelection(0)
                    providerStreaming.isChecked = true
                    providerApiKey.hint = getString(R.string.ai_provider_key_optional)
                    providerStatus.setText(R.string.ai_provider_not_configured)
                    return@onSuccess
                }
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
                providerStreaming.isChecked = profile.streaming
                providerApiKey.hint = getString(
                    if (providerStore.hasCredential()) R.string.ai_provider_key_saved
                    else R.string.ai_provider_key_optional,
                )
                providerStatus.setText(R.string.ai_provider_saved)
                providerStatus.setTextColor(getColor(R.color.sense_success))
            }
            .onFailure {
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
    }
}
