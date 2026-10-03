package io.github.ethanbird.senseime.inputqualityfixture

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Ordinary external EditTexts: no Sense dependency, reflection, or direct service calls. */
class ExternalEditorActivity : Activity() {
    lateinit var first: EditText
    lateinit var second: EditText
    lateinit var password: EditText
    private var initialImeRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) {
            // This external-editor fixture measures IME touches, not launch animations.
            // A retained starting surface occludes the IME and Android drops its touches.
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (36 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        root.addView(TextView(this).apply { text = "Sense · external editor acceptance"; textSize = 19f })
        fun field(label: String, type: Int) = EditText(this).apply {
            hint = label
            contentDescription = label
            inputType = type
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                if (intent.getBooleanExtra("noLearning", false)) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0
            minLines = 2
            root.addView(this, LinearLayout.LayoutParams(-1, (82 * dp).toInt()))
        }
        first = field("First external editor", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        second = field("Second external editor", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        password = field("Private external editor", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        setContentView(root)
        first.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::first.isInitialized || initialImeRequested) return
        // A timer can fire before the editor is served. Request after window focus,
        // once per Activity, without reopening the IME after an explicit dismissal.
        first.requestFocus()
        first.post {
            if (first.hasWindowFocus() && first.isFocused && !initialImeRequested) {
                initialImeRequested = true
                getSystemService(InputMethodManager::class.java).showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
