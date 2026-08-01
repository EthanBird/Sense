package io.github.ethanbird.senseime

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.github.ethanbird.senseime.brain.api.AgentSkillPolicy
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gates for the programmatic Settings hierarchy and the user-owned Skill draft editor.
 *
 * Pure JVM tests cover the exact Bundle/file codec and crash interleavings. These checks exercise
 * the real Activity widgets, Android saved-state recreation, accessibility names, and InputFilter
 * behavior so an Android-only regression cannot silently truncate a draft.
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
class SettingsSkillsDeviceTest {
    @Test
    fun hierarchyAndCurrentDraftSurviveRealActivityRecreation() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(
                    findCategory(activity, R.string.settings_provider_title),
                )
                assertNotNull(
                    findCategory(activity, R.string.settings_soul_title),
                )
                assertNotNull(
                    findCategory(activity, R.string.settings_tools_title),
                )
                requireNotNull(
                    findCategory(activity, R.string.settings_skills_title),
                ).performClick()
            }
            assertTrue(
                "Skills editor did not hydrate",
                waitUntil(scenario, 5_000L) { activity ->
                    findEditText(activity, activity.getString(R.string.skills_content))
                        ?.isEnabled == true
                },
            )

            val exactDraft = "设备恢复草稿\\u0000🙂\\n保留每一个字符"
                .replace("\\u0000", "\u0000")
                .replace("\\n", "\n")
            scenario.onActivity { activity ->
                val label = activity.getString(R.string.skills_content)
                val editor = requireNotNull(findEditText(activity, label))
                editor.setText(exactDraft)
                assertEquals(label, editor.contentDescription.toString())
            }

            scenario.recreate()

            assertTrue(
                "recreated Settings Activity did not restore the exact current Skill draft",
                waitUntil(scenario, 5_000L) { activity ->
                    findEditText(activity, activity.getString(R.string.skills_content))
                        ?.text?.toString() == exactDraft
                },
            )
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertNotNull(
                    findCategory(activity, R.string.settings_skills_title),
                )
            }
        }
    }

    @Test
    fun overLimitReplacementIsRejectedAtomicallyWithoutTruncatingAcceptedText() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                requireNotNull(
                    findCategory(activity, R.string.settings_skills_title),
                ).performClick()
            }
            assertTrue(
                "Skills editor did not hydrate",
                waitUntil(scenario, 5_000L) { activity ->
                    findEditText(activity, activity.getString(R.string.skills_content))
                        ?.isEnabled == true
                },
            )
            val accepted = buildString(AgentSkillPolicy.MAX_CONTENT_CHARS) {
                repeat(AgentSkillPolicy.MAX_CONTENT_CHARS) { index ->
                    append(('a'.code + index % 26).toChar())
                }
            }
            val rejected = "$accepted!"

            scenario.onActivity { activity ->
                val editor = requireNotNull(
                    findEditText(activity, activity.getString(R.string.skills_content)),
                )
                editor.setText(accepted)
                assertEquals(accepted, editor.text.toString())
                editor.text.replace(0, editor.length(), rejected)
                assertEquals(
                    "over-limit replacement must preserve the complete prior draft",
                    accepted,
                    editor.text.toString(),
                )
            }
            assertTrue(
                "atomic rejection was not exposed in the live accessibility status",
                waitUntil(scenario, 2_000L) { activity ->
                    findView(activity) {
                        it is android.widget.TextView &&
                            it.text.toString().contains("本次输入未写入")
                    } != null
                },
            )
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<SettingsActivity>,
        timeoutMillis: Long,
        predicate: (SettingsActivity) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            val matched = AtomicBoolean(false)
            scenario.onActivity { activity ->
                matched.set(predicate(activity))
            }
            if (matched.get()) return true
            SystemClock.sleep(20L)
        } while (SystemClock.uptimeMillis() < deadline)
        val matched = AtomicBoolean(false)
        scenario.onActivity { activity ->
            matched.set(predicate(activity))
        }
        return matched.get()
    }

    private fun findEditText(activity: Activity, contentDescription: String): EditText? =
        findView(activity) {
            it is EditText && it.contentDescription?.toString() == contentDescription
        } as? EditText

    private fun findCategory(activity: Activity, titleRes: Int): View? {
        val prefix = "${activity.getString(titleRes)}，"
        return findView(activity) {
            it.contentDescription?.toString()?.startsWith(prefix) == true
        }
    }

    private fun findView(
        activity: Activity,
        predicate: (View) -> Boolean,
    ): View? = findView(activity.window.decorView, predicate)

    private fun findView(
        view: View,
        predicate: (View) -> Boolean,
    ): View? {
        if (predicate(view)) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findView(view.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }
}
