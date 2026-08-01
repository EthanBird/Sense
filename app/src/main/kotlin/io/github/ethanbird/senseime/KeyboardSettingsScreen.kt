package io.github.ethanbird.senseime

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesStore
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.KeyboardHeightPolicy
import io.github.ethanbird.senseime.config.T9SideSymbolPolicy
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import java.util.concurrent.Executor

internal interface KeyboardSettingsRepository {
    fun load(): Result<ImePreferencesV1>
    fun update(mutation: KeyboardSettingsMutation): Result<ImePreferencesV1>
}

internal class RuntimeKeyboardSettingsRepository(activity: Activity) : KeyboardSettingsRepository {
    private val store by lazy { ImePreferencesStore(activity.applicationContext) }

    override fun load(): Result<ImePreferencesV1> = store.load()

    override fun update(mutation: KeyboardSettingsMutation): Result<ImePreferencesV1> =
        store.update(mutation::applyTo)
}

internal class KeyboardSettingsController(
    private val repository: KeyboardSettingsRepository,
    private val tasks: SettingsTaskRunner,
) : AutoCloseable {
    private var generation = 0L
    private var closed = false

    fun load(deliver: (Result<ImePreferencesV1>) -> Unit): Boolean {
        val requestGeneration = nextGeneration()
        return tasks.refresh(LOAD_CHANNEL, { repository.load().getOrThrow() }) { result ->
            if (!closed && generation == requestGeneration) deliver(result)
        }
    }

    fun update(
        mutation: KeyboardSettingsMutation,
        deliver: (Result<ImePreferencesV1>) -> Unit,
    ): Boolean {
        val requestGeneration = nextGeneration()
        return tasks.execute(
            operation = { repository.update(mutation).getOrThrow() },
            deliver = { result ->
                if (!closed && generation == requestGeneration) deliver(result)
            },
        )
    }

    override fun close() {
        closed = true
        generation = nextValue(generation)
        tasks.close()
    }

    private fun nextGeneration(): Long = nextValue(generation).also { generation = it }

    private fun nextValue(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

    private companion object {
        const val LOAD_CHANNEL = "keyboard-load"
    }
}

/** Keyboard settings persist field-by-field as soon as the user changes each control. */
internal class KeyboardSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    repository: KeyboardSettingsRepository = RuntimeKeyboardSettingsRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-KeyboardSettings",
        uiExecutor = Executor { command -> activity.runOnUiThread(command) },
    ),
) : AutoCloseable {
    private val controller = KeyboardSettingsController(repository, tasks)
    private var binding: Binding? = null
    private val persistSymbolsRunnable = Runnable(::persistSymbolsFromEditor)

    fun createView(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(views.text(R.string.keyboard_scheme_body, 13f, R.color.sense_secondary))

        val scheme = views.accessibleSpinner(R.string.keyboard_scheme_label).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                SCHEMES.map { activity.getString(it.labelRes) },
            )
            isEnabled = false
        }
        val autoCommit = views.accessibleSpinner(R.string.keyboard_wubi_auto_commit_label).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                AUTO_COMMITS.map { activity.getString(it.labelRes) },
            )
            isEnabled = false
        }
        val preview = KeyboardHeightPreviewView(activity).apply { isEnabled = false }
        val portraitValue = views.text(
            R.string.keyboard_height_default_value,
            12f,
            R.color.sense_secondary,
        )
        val portraitHeight = heightSlider(KeyboardHeightPolicy.DEFAULT_PORTRAIT_HEIGHT_DP)
        val landscapeValue = views.text(
            R.string.keyboard_height_default_value,
            12f,
            R.color.sense_secondary,
        )
        val landscapeHeight = heightSlider(KeyboardHeightPolicy.DEFAULT_LANDSCAPE_HEIGHT_DP)
        val sideSymbols = views.editField(
            R.string.keyboard_t9_side_symbols_label,
            activity.getString(R.string.keyboard_t9_side_symbols_hint),
        ).apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isEnabled = false
        }
        val status = views.text(R.string.keyboard_loading, 12f, R.color.sense_secondary)

        root.addView(views.labeledField(R.string.keyboard_scheme_label, scheme).withTop(views.dp(14)))
        root.addView(
            views.labeledField(R.string.keyboard_wubi_auto_commit_label, autoCommit)
                .withTop(views.dp(10)),
        )
        root.addView(
            views.text(R.string.keyboard_height_body, 13f, R.color.sense_secondary)
                .withTop(views.dp(18)),
        )
        root.addView(preview.withTop(views.dp(10)))
        root.addView(
            heightField(
                labelRes = R.string.keyboard_portrait_height_label,
                value = portraitValue,
                slider = portraitHeight,
            ).withTop(views.dp(12)),
        )
        root.addView(
            heightField(
                labelRes = R.string.keyboard_landscape_height_label,
                value = landscapeValue,
                slider = landscapeHeight,
            ).withTop(views.dp(8)),
        )
        root.addView(
            views.labeledField(R.string.keyboard_t9_side_symbols_label, sideSymbols)
                .withTop(views.dp(14)),
        )
        root.addView(
            views.text(R.string.keyboard_t9_side_symbols_body, 12f, R.color.sense_secondary)
                .withTop(views.dp(5)),
        )
        root.addView(status.withTop(views.dp(10)))

        val created = Binding(
            root = root,
            scheme = scheme,
            autoCommit = autoCommit,
            preview = preview,
            portraitHeight = portraitHeight,
            portraitValue = portraitValue,
            landscapeHeight = landscapeHeight,
            landscapeValue = landscapeValue,
            sideSymbols = sideSymbols,
            status = status,
        )
        binding = created
        attachListeners(created)
        controller.load(::renderLoad)
        return root
    }

    private fun renderLoad(result: Result<ImePreferencesV1>) {
        val current = binding ?: return
        result.fold(
            onSuccess = { value ->
                renderSnapshot(current, value)
                current.status.setText(R.string.keyboard_auto_save_ready)
            },
            onFailure = { error ->
                current.status.text = activity.getString(
                    R.string.keyboard_load_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            },
        )
    }

    private fun attachListeners(current: Binding) {
        current.scheme.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(position: Int) {
                if (current.programmatic || current.snapshot == null) return
                persist(KeyboardSettingsMutation.InputScheme(SCHEMES[position].value))
            }
        }
        current.autoCommit.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(position: Int) {
                if (current.programmatic || current.snapshot == null) return
                persist(KeyboardSettingsMutation.WubiAutoCommit(AUTO_COMMITS[position].value))
            }
        }
        current.portraitHeight.setOnSeekBarChangeListener(
            heightListener(current, landscape = false, current.portraitValue),
        )
        current.landscapeHeight.setOnSeekBarChangeListener(
            heightListener(current, landscape = true, current.landscapeValue),
        )
        current.sideSymbols.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                if (current.programmatic || current.snapshot == null) return
                current.sideSymbols.removeCallbacks(persistSymbolsRunnable)
                current.sideSymbols.postDelayed(persistSymbolsRunnable, SYMBOL_SAVE_DEBOUNCE_MS)
            }
        })
        current.sideSymbols.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                current.sideSymbols.removeCallbacks(persistSymbolsRunnable)
                persistSymbolsFromEditor()
                canonicalizeSymbolEditor(current)
            }
        }
    }

    private fun persist(mutation: KeyboardSettingsMutation) {
        val current = binding ?: return
        val before = current.snapshot ?: return
        val optimistic = mutation.applyTo(before)
        if (optimistic == before) return
        current.snapshot = optimistic
        current.status.setText(R.string.keyboard_auto_saving)
        val accepted = controller.update(mutation) { result ->
            binding?.let { active ->
                active.status.text = result.fold(
                    onSuccess = { persisted ->
                        renderSnapshot(active, persisted)
                        activity.getString(R.string.keyboard_auto_saved)
                    },
                    onFailure = { error ->
                        recoverSnapshot()
                        activity.getString(
                            R.string.keyboard_save_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
            }
        }
        if (!accepted) {
            current.snapshot = before
            renderSnapshot(current, before)
        }
    }

    private fun recoverSnapshot() {
        controller.load { result ->
            val current = binding ?: return@load
            result.onSuccess { renderSnapshot(current, it) }
        }
    }

    private fun persistSymbolsFromEditor() {
        val current = binding ?: return
        if (current.snapshot == null) return
        persist(
            KeyboardSettingsMutation.T9SideSymbols(
                T9SideSymbolPolicy.fromEditorText(current.sideSymbols.text.toString()),
            ),
        )
    }

    private fun renderSnapshot(current: Binding, value: ImePreferencesV1) {
        current.snapshot = value
        current.programmatic = true
        try {
            current.scheme.setSelection(
                SCHEMES.indexOfFirst { it.value == value.chineseInputScheme }.coerceAtLeast(0),
            )
            current.autoCommit.setSelection(
                AUTO_COMMITS.indexOfFirst { it.value == value.wubiAutoCommitMode }.coerceAtLeast(0),
            )
            if (!current.portraitTracking) current.portraitHeight.progress = value.portraitKeyboardHeightDp
            if (!current.landscapeTracking) current.landscapeHeight.progress = value.landscapeKeyboardHeightDp
            if (!current.sideSymbols.hasFocus()) canonicalizeSymbolEditor(current)
            setHeightValue(current.portraitValue, current.portraitHeight.progress)
            setHeightValue(current.landscapeValue, current.landscapeHeight.progress)
            current.preview.setKeyboardHeightDp(
                heightDp = if (current.previewLandscape) {
                    current.landscapeHeight.progress
                } else {
                    current.portraitHeight.progress
                },
                landscape = current.previewLandscape,
            )
        } finally {
            current.programmatic = false
        }
        current.scheme.isEnabled = true
        current.autoCommit.isEnabled = true
        current.portraitHeight.isEnabled = true
        current.landscapeHeight.isEnabled = true
        current.sideSymbols.isEnabled = true
        current.preview.isEnabled = true
    }

    private fun canonicalizeSymbolEditor(current: Binding) {
        val snapshot = current.snapshot ?: return
        val expected = T9SideSymbolPolicy.toEditorText(snapshot.t9SideSymbols)
        if (current.sideSymbols.text.toString() == expected) return
        val wasProgrammatic = current.programmatic
        current.programmatic = true
        current.sideSymbols.setText(expected)
        current.sideSymbols.setSelection(expected.length)
        current.programmatic = wasProgrammatic
    }

    private fun heightListener(
        current: Binding,
        landscape: Boolean,
        valueView: TextView,
    ): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            setHeightValue(valueView, progress)
            val preview = KeyboardHeightDragPolicy.previewState(progress, landscape, fromUser)
                ?: return
            current.previewLandscape = preview.landscape
            current.preview.setKeyboardHeightDp(preview.heightDp, preview.landscape)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            if (landscape) current.landscapeTracking = true else current.portraitTracking = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            if (landscape) current.landscapeTracking = false else current.portraitTracking = false
            persist(KeyboardHeightDragPolicy.persistenceMutation(seekBar.progress, landscape))
        }
    }

    private fun setHeightValue(view: TextView, heightDp: Int) {
        view.text = activity.getString(R.string.keyboard_height_value, heightDp)
    }

    private fun heightSlider(defaultDp: Int): SeekBar = SeekBar(activity).apply {
        id = View.generateViewId()
        min = KeyboardHeightPolicy.MIN_HEIGHT_DP
        max = KeyboardHeightPolicy.MAX_HEIGHT_DP
        progress = defaultDp
        isEnabled = false
    }

    private fun heightField(
        labelRes: Int,
        value: TextView,
        slider: SeekBar,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    views.text(labelRes, 12f, R.color.sense_secondary).apply {
                        labelFor = slider.id
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    },
                )
                addView(value)
            },
        )
        addView(slider)
    }

    fun onStop() {
        flushVisibleValues()
    }

    override fun close() {
        flushVisibleValues()
        binding = null
        controller.close()
    }

    private fun flushVisibleValues() {
        binding?.let { current ->
            current.sideSymbols.removeCallbacks(persistSymbolsRunnable)
            if (current.snapshot != null) {
                persist(KeyboardSettingsMutation.PortraitHeight(current.portraitHeight.progress))
                persist(KeyboardSettingsMutation.LandscapeHeight(current.landscapeHeight.progress))
                persistSymbolsFromEditor()
            }
        }
    }

    private data class Binding(
        val root: LinearLayout,
        val scheme: Spinner,
        val autoCommit: Spinner,
        val preview: KeyboardHeightPreviewView,
        val portraitHeight: SeekBar,
        val portraitValue: TextView,
        val landscapeHeight: SeekBar,
        val landscapeValue: TextView,
        val sideSymbols: EditText,
        val status: TextView,
        var snapshot: ImePreferencesV1? = null,
        var programmatic: Boolean = false,
        var portraitTracking: Boolean = false,
        var landscapeTracking: Boolean = false,
        var previewLandscape: Boolean = false,
    )

    private abstract class SimpleItemSelectedListener : AdapterView.OnItemSelectedListener {
        final override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onItemSelected(position)
        }

        final override fun onNothingSelected(parent: AdapterView<*>?) = Unit

        abstract fun onItemSelected(position: Int)
    }

    private data class SchemeChoice(val value: ChineseInputScheme, val labelRes: Int)
    private data class AutoCommitChoice(val value: WubiAutoCommitMode, val labelRes: Int)

    private companion object {
        val SCHEMES = listOf(
            SchemeChoice(ChineseInputScheme.PINYIN_QWERTY, R.string.keyboard_scheme_pinyin_qwerty),
            SchemeChoice(ChineseInputScheme.PINYIN_T9, R.string.keyboard_scheme_pinyin_t9),
            SchemeChoice(ChineseInputScheme.WUBI_86, R.string.keyboard_scheme_wubi_86),
        )
        val AUTO_COMMITS = listOf(
            AutoCommitChoice(WubiAutoCommitMode.RIME_STYLE, R.string.keyboard_wubi_rime_style),
            AutoCommitChoice(WubiAutoCommitMode.UNIQUE_AT_4, R.string.keyboard_wubi_unique_four),
            AutoCommitChoice(WubiAutoCommitMode.OFF, R.string.keyboard_wubi_off),
        )
        const val SYMBOL_SAVE_DEBOUNCE_MS = 350L
        const val WRAP_CONTENT = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
