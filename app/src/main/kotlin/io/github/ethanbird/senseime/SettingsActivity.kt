package io.github.ethanbird.senseime

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import io.github.ethanbird.senseime.config.ImeSettingsRoute
import io.github.ethanbird.senseime.brain.api.ProviderPresetId

class SettingsActivity : ComponentActivity() {
    private val navigation = SettingsNavigationState()
    private val sectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateBack()
        }
    }
    private lateinit var screenScroll: ScrollView
    private lateinit var screenContent: LinearLayout
    private lateinit var bottomNavigation: LinearLayout
    private lateinit var statusText: TextView
    private val settingsViews by lazy { SettingsViewFactory(this) }
    private var activeSectionScreen: AutoCloseable? = null
    private var activeSpeechScreen: SpeechSettingsScreen? = null
    private var activeMicScreen: MicSettingsScreen? = null
    private lateinit var skillsScreen: SkillsSettingsScreen
    private var selectedProviderPreset: ProviderPresetId? = null

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
        navigation.restore(
            savedInstanceState?.getString(STATE_SECTION)
                ?: intent.getStringExtra(ImeSettingsRoute.EXTRA_INITIAL_SECTION),
        )
        skillsScreen = SkillsSettingsScreen(
            activity = this,
            views = settingsViews,
            bundledState = savedInstanceState?.getByteArray(STATE_SKILL_DRAFTS),
            initiallyEditing = savedInstanceState?.getBoolean(STATE_SKILL_EDITOR) ?: false,
            openActions = { showSection(SettingsSection.ACTION_SKILLS) },
            onPaneChanged = { screenScroll.post { screenScroll.scrollTo(0, 0) } },
        )
        setContentView(buildContent())
        renderCurrentSection()
    }

    override fun onResume() {
        super.onResume()
        when (navigation.current) {
            SettingsSection.HOME -> updateStatus()
            SettingsSection.KEYBOARD -> Unit
            SettingsSection.SKILLS -> skillsScreen.onResume()
            SettingsSection.VOICE -> activeSpeechScreen?.onResume()
            SettingsSection.MIC -> activeMicScreen?.onResume()
            else -> Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigation.restore(intent.getStringExtra(ImeSettingsRoute.EXTRA_INITIAL_SECTION))
        renderCurrentSection()
    }

    override fun onStop() {
        // Always enqueue a terminal snapshot after every previously accepted debounced write.
        // Equality with the last completed snapshot cannot prove that an older write is not still
        // queued (for example A -> queued B -> user reverts to A -> stop).
        if (navigation.current == SettingsSection.SKILLS) {
            skillsScreen.onStop()
        }
        (activeSectionScreen as? KeyboardSettingsScreen)?.onStop()
        (activeSectionScreen as? ProviderSettingsScreen)?.onStop()
        activeSpeechScreen?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        releaseActiveSection(persistSkillDraft = false)
        // Android dispatches onStop before onDestroy; that single barrier owns the final snapshot.
        // Do not enqueue a duplicate 3.5-second wait if the original durability work timed out.
        skillsScreen.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SECTION, navigation.serialize())
        outState.putBoolean(STATE_SKILL_EDITOR, skillsScreen.isEditorOpen)
        skillsScreen.snapshotForSavedState()
            ?.let { outState.putByteArray(STATE_SKILL_DRAFTS, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            activeSpeechScreen?.onPermissionResult(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED,
            )
        } else if (requestCode == REQUEST_SENSE_MIC_PERMISSIONS) {
            val recordAudioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val audioGranted = if (recordAudioIndex >= 0) {
                grantResults.getOrNull(recordAudioIndex) == PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }
            activeMicScreen?.onPermissionResult(audioGranted)
        }
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
        }
        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(getColor(R.color.sense_surface))
            elevation = dp(3).toFloat()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.sense_background))
            addView(screenScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomNavigation, LinearLayout.LayoutParams(-1, -2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                    bottomNavigation.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
                    insets
                }
            }
        }
    }

    private fun renderCurrentSection() {
        if (!::screenContent.isInitialized) return
        releaseActiveSection()
        sectionBackCallback.isEnabled = navigation.current != SettingsSection.HOME
        screenContent.removeAllViews()
        renderBottomNavigation()
        when (navigation.current) {
            SettingsSection.HOME -> renderHome()
            SettingsSection.AGENT -> {
                renderTopHeader(R.string.settings_tab_agent, R.string.settings_agent_intro)
                val screen = AgentSettingsScreen(this, settingsViews, ::showSection)
                activeSectionScreen = screen
                screenContent.addView(screen.createView().withTop(dp(24)))
            }
            SettingsSection.MORE -> renderMore()
            SettingsSection.KEYBOARD -> {
                renderDetailHeader(
                    R.string.settings_keyboard_title,
                    R.string.settings_keyboard_summary,
                )
                val screen = KeyboardSettingsScreen(this, settingsViews)
                activeSectionScreen = screen
                screenContent.addView(
                    card(R.string.keyboard_scheme_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.PROVIDER -> {
                renderDetailHeader(R.string.settings_provider_title, R.string.settings_provider_summary)
                val screen = ProviderSettingsScreen(
                    this,
                    settingsViews,
                    initialPresetId = selectedProviderPreset,
                    onChooseProvider = {
                        navigation.openChild(SettingsSection.PROVIDER_CATALOG, SettingsSection.PROVIDER)
                        renderCurrentSection()
                    },
                )
                activeSectionScreen = screen
                screenContent.addView(
                    card(R.string.ai_provider_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.PROVIDER_CATALOG -> {
                renderDetailHeader(R.string.ai_provider_catalog_title, R.string.ai_provider_catalog_summary)
                screenContent.addView(
                    ProviderCatalogScreen(this, settingsViews, selectedProviderPreset) { preset ->
                        selectedProviderPreset = preset
                        navigation.openChild(SettingsSection.PROVIDER, SettingsSection.PROVIDER_CATALOG)
                        renderCurrentSection()
                    }.createView().withTop(dp(20)),
                )
            }
            SettingsSection.SOUL -> renderSoul()
            SettingsSection.TOOLS -> {
                renderDetailHeader(R.string.settings_tools_title, R.string.settings_tools_summary)
                val screen = AgentToolsSettingsScreen(this, settingsViews)
                activeSectionScreen = screen
                screenContent.addView(
                    card(R.string.agent_tools_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.CHANNELS -> {
                renderDetailHeader(
                    R.string.settings_channels_title,
                    R.string.settings_channels_summary,
                )
                val screen = AgentChannelsSettingsScreen(this, settingsViews)
                activeSectionScreen = screen
                screenContent.addView(
                    card(R.string.agent_channels_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.ACTION_SKILLS -> {
                renderDetailHeader(R.string.settings_action_skills_title, R.string.settings_action_skills_summary)
                val screen = ActionSkillsSettingsScreen(this, settingsViews)
                activeSectionScreen = screen
                screenContent.addView(screen.createView().withTop(dp(20)))
            }
            SettingsSection.SKILLS -> {
                renderTopHeader(
                    R.string.settings_tab_skills,
                    R.string.skills_library_intro,
                )
                screenContent.addView(skillsScreen.createView().withTop(dp(20)))
            }
            SettingsSection.VOICE -> {
                renderDetailHeader(R.string.settings_voice_title, R.string.settings_voice_summary)
                val screen = SpeechSettingsScreen(
                    activity = this,
                    views = settingsViews,
                    emitEffect = ::handleEffect,
                )
                activeSectionScreen = screen
                activeSpeechScreen = screen
                screenContent.addView(
                    card(R.string.speech_provider_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.MIC -> {
                renderDetailHeader(R.string.settings_mic_title, R.string.settings_mic_summary)
                val screen = MicSettingsScreen(
                    activity = this,
                    views = settingsViews,
                    emitEffect = ::handleEffect,
                )
                activeSectionScreen = screen
                activeMicScreen = screen
                screenContent.addView(
                    card(R.string.sense_mic_service_title, screen.createView()).withTop(dp(20)),
                )
            }
            SettingsSection.ABOUT -> {
                renderDetailHeader(R.string.settings_about_title, R.string.settings_about_summary)
                val screen = AboutSettingsScreen(
                    activity = this,
                    views = settingsViews,
                    emitEffect = ::handleEffect,
                )
                activeSectionScreen = screen
                screenContent.addView(screen.createView().withTop(dp(20)))
            }
        }
        screenScroll.post { screenScroll.scrollTo(0, 0) }
    }

    private fun releaseActiveSection(persistSkillDraft: Boolean = true) {
        skillsScreen.detach(persistSkillDraft)
        activeSectionScreen?.close()
        activeSectionScreen = null
        activeSpeechScreen = null
        activeMicScreen = null
    }

    private fun handleEffect(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.OpenInputMethodSettings ->
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            SettingsEffect.ShowInputMethodPicker ->
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            SettingsEffect.RequestRecordAudio ->
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO,
                )
            SettingsEffect.RequestSenseMicPermissions -> {
                val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions += Manifest.permission.POST_NOTIFICATIONS
                }
                requestPermissions(
                    permissions.distinct().toTypedArray(),
                    REQUEST_SENSE_MIC_PERMISSIONS,
                )
            }
            SettingsEffect.OpenApplicationDetails ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            is SettingsEffect.ShowDictionaryNotice ->
                showDictionaryNotice(effect.text)
        }
    }

    private fun renderHome() {
        addBrandHeader(showTagline = true)
        statusText = text(R.string.ime_disabled, 16f, R.color.sense_primary, Typeface.BOLD)
        screenContent.addView(card(R.string.ime_status_title, statusText).withTop(dp(26)))
        screenContent.addView(primaryButton(R.string.enable_ime) {
            handleEffect(SettingsEffect.OpenInputMethodSettings)
        }.withTop(dp(14)))
        screenContent.addView(secondaryButton(R.string.switch_ime) {
            handleEffect(SettingsEffect.ShowInputMethodPicker)
        }.withTop(dp(10)))
        screenContent.addView(
            text(R.string.settings_home_shortcuts, 13f, R.color.sense_secondary, Typeface.BOLD)
                .withTop(dp(28)),
        )
        addCategory(
            SettingsSection.KEYBOARD,
            R.string.settings_keyboard_title,
            R.string.settings_keyboard_summary,
        )
        addCategory(
            SettingsSection.AGENT,
            R.string.settings_tab_agent,
            R.string.settings_agent_intro,
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
        updateStatus()
    }

    private fun renderTopHeader(titleRes: Int, summaryRes: Int) {
        screenContent.addView(text(titleRes, 32f, R.color.sense_primary, Typeface.BOLD))
        screenContent.addView(text(summaryRes, 14f, R.color.sense_secondary).withTop(dp(8)))
    }

    private fun renderMore() {
        renderTopHeader(R.string.settings_tab_more, R.string.settings_more_intro)
        addCategory(SettingsSection.VOICE, R.string.settings_voice_title, R.string.settings_voice_summary)
        addCategory(SettingsSection.MIC, R.string.settings_mic_title, R.string.settings_mic_summary)
        addCategory(SettingsSection.ABOUT, R.string.settings_about_title, R.string.settings_about_summary)
        screenContent.addView(text(R.string.version_label, 12f, R.color.sense_secondary).withTop(dp(24)))
    }

    private fun renderBottomNavigation() {
        bottomNavigation.removeAllViews()
        listOf(
            Triple(SettingsSection.HOME, R.string.settings_tab_home, "⌂"),
            Triple(SettingsSection.AGENT, R.string.settings_tab_agent, "◎"),
            Triple(SettingsSection.SKILLS, R.string.settings_tab_skills, "✦"),
            Triple(SettingsSection.MORE, R.string.settings_tab_more, "···"),
        ).forEach { (section, label, icon) ->
            val selected = navigation.topLevel == section
            val tab = TextView(this).apply {
                text = "$icon\n${getString(label)}"
                textSize = 13f
                gravity = Gravity.CENTER
                minimumHeight = dp(56)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(getColor(if (selected) R.color.sense_accent else R.color.sense_secondary))
                background = settingsViews.rounded(
                    getColor(if (selected) R.color.sense_background else R.color.sense_surface), dp(16).toFloat(),
                )
                foreground = selectableItemBackground()
                isSelected = selected
                isFocusable = true
                contentDescription = getString(label)
                setOnClickListener {
                    if (navigation.current == section) {
                        if (section == SettingsSection.SKILLS) skillsScreen.showLibrary()
                    } else {
                        navigation.open(section)
                        renderCurrentSection()
                    }
                }
            }
            bottomNavigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
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
        navigation.openChild(section, navigation.current)
        renderCurrentSection()
    }

    private fun navigateBack() {
        if (navigation.current == SettingsSection.SKILLS && skillsScreen.showLibrary()) return
        when (navigation.back()) {
            SettingsBackResult.CONSUMED -> renderCurrentSection()
            SettingsBackResult.EXIT_ACTIVITY -> {
                sectionBackCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
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

    private fun updateStatus() {
        if (!::statusText.isInitialized || navigation.current != SettingsSection.HOME) return
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)?.packageName == packageName
        statusText.setText(when {
            current -> R.string.settings_ime_active
            enabled -> R.string.ime_enabled
            else -> R.string.ime_disabled
        })
        statusText.setTextColor(getColor(if (enabled) R.color.sense_success else R.color.sense_primary))
    }

    private fun showDictionaryNotice(notice: String) {
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

    private fun badge(): TextView = settingsViews.badge()

    private fun card(titleRes: Int, body: View): LinearLayout =
        settingsViews.card(titleRes, body)

    private fun primaryButton(textRes: Int, action: () -> Unit): Button =
        settingsViews.primaryButton(textRes, action)

    private fun secondaryButton(textRes: Int, action: () -> Unit): Button =
        settingsViews.secondaryButton(textRes, action)

    private fun text(
        textRes: Int,
        size: Float,
        colorRes: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = settingsViews.text(textRes, size, colorRes, style)

    private fun selectableItemBackground() =
        settingsViews.selectableItemBackground()

    private fun dp(value: Int): Int = settingsViews.dp(value)

    companion object {
        private const val STATE_SECTION = "settings-section"
        private const val STATE_SKILL_DRAFTS = "settings-skill-drafts"
        private const val STATE_SKILL_EDITOR = "settings-skill-editor"
        private const val REQUEST_RECORD_AUDIO = 40
        private const val REQUEST_SENSE_MIC_PERMISSIONS = 41
    }
}
