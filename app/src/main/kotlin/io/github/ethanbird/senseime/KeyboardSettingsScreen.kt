package io.github.ethanbird.senseime

import android.app.Activity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.config.ImePreferencesStore
import io.github.ethanbird.senseime.config.ImePreferencesV1
import io.github.ethanbird.senseime.config.WubiAutoCommitMode
import java.util.concurrent.Executor

internal interface KeyboardSettingsRepository {
    fun load(): Result<ImePreferencesV1>
    fun save(value: ImePreferencesV1): Result<Unit>
}

internal class RuntimeKeyboardSettingsRepository(activity: Activity) : KeyboardSettingsRepository {
    private val store by lazy { ImePreferencesStore(activity.applicationContext) }

    override fun load(): Result<ImePreferencesV1> = store.load()

    override fun save(value: ImePreferencesV1): Result<Unit> = store.save(value)
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

    fun save(value: ImePreferencesV1, deliver: (Result<ImePreferencesV1>) -> Unit): Boolean {
        val requestGeneration = nextGeneration()
        return tasks.execute(
            operation = {
                repository.save(value).getOrThrow()
                value
            },
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
        val save = views.secondaryButton(R.string.keyboard_save, ::save).apply { isEnabled = false }
        val status = views.text(R.string.keyboard_loading, 12f, R.color.sense_secondary)

        root.addView(
            views.labeledField(R.string.keyboard_scheme_label, scheme).withTop(views.dp(14)),
        )
        root.addView(
            views.labeledField(R.string.keyboard_wubi_auto_commit_label, autoCommit)
                .withTop(views.dp(10)),
        )
        root.addView(save.withTop(views.dp(12)))
        root.addView(status.withTop(views.dp(10)))
        binding = Binding(root, scheme, autoCommit, save, status)

        controller.load(::renderLoad)
        return root
    }

    private fun renderLoad(result: Result<ImePreferencesV1>) {
        val current = binding ?: return
        result.fold(
            onSuccess = { value ->
                current.scheme.setSelection(SCHEMES.indexOfFirst { it.value == value.chineseInputScheme })
                current.autoCommit.setSelection(
                    AUTO_COMMITS.indexOfFirst { it.value == value.wubiAutoCommitMode },
                )
                current.scheme.isEnabled = true
                current.autoCommit.isEnabled = true
                current.save.isEnabled = true
                current.status.text = activity.getString(
                    R.string.keyboard_ready,
                    activity.getString(SCHEMES[current.scheme.selectedItemPosition].labelRes),
                )
            },
            onFailure = { error ->
                current.status.text = activity.getString(
                    R.string.keyboard_load_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            },
        )
    }

    private fun save() {
        val current = binding ?: return
        if (!current.save.isEnabled) return
        current.save.isEnabled = false
        val value = ImePreferencesV1(
            chineseInputScheme = SCHEMES[current.scheme.selectedItemPosition].value,
            wubiAutoCommitMode = AUTO_COMMITS[current.autoCommit.selectedItemPosition].value,
        )
        controller.save(value) { result ->
            binding?.let { active ->
                active.save.isEnabled = true
                active.status.text = result.fold(
                    onSuccess = { activity.getString(R.string.keyboard_saved) },
                    onFailure = { error ->
                        activity.getString(
                            R.string.keyboard_save_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
            }
        }
    }

    override fun close() {
        binding = null
        controller.close()
    }

    private data class Binding(
        val root: LinearLayout,
        val scheme: Spinner,
        val autoCommit: Spinner,
        val save: Button,
        val status: TextView,
    )

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
    }
}
