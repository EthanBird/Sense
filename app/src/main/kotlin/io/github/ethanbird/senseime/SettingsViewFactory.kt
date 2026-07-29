package io.github.ethanbird.senseime

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * The single styling and accessibility authority for the programmatic Settings hierarchy.
 *
 * Screen modules own behavior and state. This factory owns Android widget construction, minimum
 * touch sizes, labels, colors, spacing, and reusable drawable policy.
 */
internal class SettingsViewFactory(
    private val activity: Activity,
) {
    fun editField(labelRes: Int, hintText: String): EditText = EditText(activity).apply {
        hint = hintText
        contentDescription = activity.getString(labelRes)
        id = View.generateViewId()
        minimumHeight = dp(48)
        textSize = 14f
        setSingleLine(true)
        setTextColor(activity.getColor(R.color.sense_primary))
        setHintTextColor(activity.getColor(R.color.sense_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(activity.getColor(R.color.sense_background), dp(10).toFloat())
    }

    fun secretField(labelRes: Int, hintText: String): EditText =
        editField(labelRes, hintText).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
        }

    fun multiLineEditField(labelRes: Int, hintText: String): EditText =
        editField(labelRes, hintText).apply {
            setSingleLine(false)
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
        }

    fun labeledField(labelRes: Int, field: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (field.id == View.NO_ID) field.id = View.generateViewId()
            addView(
                text(labelRes, 12f, R.color.sense_secondary, Typeface.BOLD).apply {
                    labelFor = field.id
                },
            )
            addView(field.withTop(dp(5)))
        }

    fun accessibleSpinner(labelRes: Int): Spinner = Spinner(activity).apply {
        id = View.generateViewId()
        minimumHeight = dp(48)
        contentDescription = activity.getString(labelRes)
    }

    fun switch(labelRes: Int, checked: Boolean = false): Switch = Switch(activity).apply {
        setText(labelRes)
        setTextColor(activity.getColor(R.color.sense_primary))
        isChecked = checked
        minimumHeight = dp(48)
    }

    fun badge(): TextView =
        text(R.string.stage_badge, 12f, R.color.sense_accent, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(
                activity.getColor(R.color.sense_surface),
                dp(18).toFloat(),
                activity.getColor(R.color.sense_accent),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    fun card(titleRes: Int, body: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(18))
        background = rounded(activity.getColor(R.color.sense_surface), dp(18).toFloat())
        addView(text(titleRes, 13f, R.color.sense_secondary, Typeface.BOLD))
        addView(body.withTop(dp(8)))
    }

    fun primaryButton(textRes: Int, action: () -> Unit): Button = Button(activity).apply {
        setText(textRes)
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(activity.getColor(R.color.sense_accent), dp(14).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
    }

    fun secondaryButton(textRes: Int, action: () -> Unit): Button = Button(activity).apply {
        setText(textRes)
        isAllCaps = false
        textSize = 15f
        setTextColor(activity.getColor(R.color.sense_primary))
        background = rounded(activity.getColor(R.color.sense_surface), dp(14).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
    }

    fun text(
        textRes: Int,
        size: Float,
        colorRes: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(activity).apply {
        setText(textRes)
        textSize = size
        setTextColor(activity.getColor(colorRes))
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setLineSpacing(0f, 1.16f)
    }

    fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    fun selectableItemBackground(): Drawable? {
        val typedArray =
            activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try {
            typedArray.getDrawable(0)
        } finally {
            typedArray.recycle()
        }
    }

    fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}

internal fun <T : View> T.withTop(margin: Int): T = apply {
    val current = layoutParams as? LinearLayout.LayoutParams
        ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    current.topMargin = margin
    layoutParams = current
}
