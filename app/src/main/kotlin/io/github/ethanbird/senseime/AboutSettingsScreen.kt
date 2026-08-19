package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.Executor

internal fun interface AboutNoticeRepository {
    fun load(): Result<String>
}

internal val ABOUT_NOTICE_ASSETS = listOf(
    "NOTICE" to "NOTICE.txt",
    "Sense GPL-3.0" to "LICENSE.txt",
    "Rime Frost NOTICE" to "RIME-FROST-NOTICE.txt",
    "Rime Frost GPL-3.0" to "RIME-FROST-GPL-3.0.txt",
    "Rime Wubi NOTICE" to "RIME-WUBI-NOTICE.txt",
    "Rime Wubi LGPL-3.0" to "RIME-WUBI-LGPL-3.0.txt",
    "OkHttp Apache-2.0" to "OKHTTP-APACHE-2.0.txt",
    "Lark OpenAPI Apache-2.0" to "LARK-OAPI-APACHE-2.0.txt",
    "Concentus BSD-3-Clause" to "CONCENTUS-BSD-3-CLAUSE.txt",
)

internal class AssetAboutNoticeRepository(
    activity: Activity,
) : AboutNoticeRepository {
    private val applicationContext = activity.applicationContext

    override fun load(): Result<String> = runCatching {
        ABOUT_NOTICE_ASSETS.joinToString("\n\n") { (heading, fileName) ->
            "$heading\n${"=".repeat(heading.length)}\n${readAsset(fileName).trimEnd()}"
        }
    }

    private fun readAsset(fileName: String): String =
        applicationContext.assets.open(fileName)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}

/**
 * Owns the one-shot About asset read. The repository always runs on the screen I/O lane and a
 * detached destination revokes delivery without interrupting already accepted work.
 */
internal class AboutNoticeController(
    private val repository: AboutNoticeRepository,
    private val tasks: SettingsTaskRunner,
) : AutoCloseable {
    private var closed = false
    private var loading = false

    fun load(deliver: (Result<String>) -> Unit): Boolean {
        if (closed || loading) return false
        loading = true
        val accepted = tasks.refresh(
            channel = LOAD_CHANNEL,
            operation = { repository.load().getOrThrow() },
        ) { result ->
            if (closed) return@refresh
            loading = false
            deliver(result)
        }
        if (!accepted && !closed) {
            loading = false
            deliver(Result.failure(IllegalStateException("About settings lane is closed")))
        }
        return accepted
    }

    override fun close() {
        closed = true
        loading = false
        tasks.close()
    }

    private companion object {
        const val LOAD_CHANNEL = "about-notice"
    }
}

internal class AboutSettingsScreen(
    private val activity: Activity,
    private val views: SettingsViewFactory,
    private val emitEffect: (SettingsEffect) -> Unit,
    repository: AboutNoticeRepository = AssetAboutNoticeRepository(activity),
    tasks: SettingsTaskRunner = SettingsAsyncLane(
        threadName = "Sense-AboutSettings",
        uiExecutor = Executor { command -> activity.runOnUiThread(command) },
    ),
) : AutoCloseable {
    private var noticeCard: View? = null
    private val controller = AboutNoticeController(repository, tasks)

    fun createView(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            views.card(
                R.string.m0_title,
                views.text(R.string.m0_body, 15f, R.color.sense_secondary),
            ),
        )
        addView(
            views.card(
                R.string.dictionary_notice_title,
                views.text(
                    R.string.dictionary_notice_body,
                    13f,
                    R.color.sense_secondary,
                ),
            ).apply {
                isClickable = true
                isFocusable = true
                foreground = views.selectableItemBackground()
                contentDescription = activity.getString(R.string.dictionary_notice_title)
                tag = NOTICE_IDLE_TAG
                setOnClickListener { loadNotice() }
                noticeCard = this
            }.withTop(views.dp(12)),
        )
        addView(
            views.text(
                R.string.version_label,
                12f,
                R.color.sense_secondary,
                Typeface.NORMAL,
            ).withTop(views.dp(24)),
        )
    }

    override fun close() {
        noticeCard = null
        controller.close()
    }

    private fun loadNotice() {
        val card = noticeCard ?: return
        card.isEnabled = false
        card.tag = NOTICE_LOADING_TAG
        val accepted = controller.load { result ->
            val current = noticeCard ?: return@load
            current.isEnabled = true
            current.tag = NOTICE_LOADED_TAG
            emitEffect(
                SettingsEffect.ShowDictionaryNotice(
                    result.getOrElse {
                        activity.getString(R.string.dictionary_notice_load_error)
                    },
                ),
            )
        }
        if (!accepted) {
            card.isEnabled = true
            card.tag = NOTICE_IDLE_TAG
        }
    }

    companion object {
        internal const val NOTICE_IDLE_TAG = "about-notice-idle"
        internal const val NOTICE_LOADING_TAG = "about-notice-loading"
        internal const val NOTICE_LOADED_TAG = "about-notice-loaded"
    }
}
