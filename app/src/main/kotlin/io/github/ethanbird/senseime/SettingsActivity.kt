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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.sense_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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

        content.addView(
            card(
                R.string.m0_title,
                text(R.string.m0_body, 15f, R.color.sense_secondary),
            ).withTop(dp(24)),
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
}
