package io.github.ethanbird.senseime

import android.app.Activity
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.os.strictmode.DiskReadViolation
import android.os.strictmode.DiskWriteViolation
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for Settings destination hydration.
 *
 * The disk policy is installed only on the Activity main thread. Repository
 * reads performed by the Settings async lanes therefore remain outside this
 * gate, while an accidental UI-thread file/SharedPreferences/Keystore access is
 * reported with its original StrictMode stack.
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
class SettingsMainThreadIoDeviceTest {
    @Test
    fun toolsHydrationPerformsNoMainThreadDiskIo() {
        val masterLabel = targetString(R.string.agent_tools_master)
        assertDestinationHydratesWithoutMainThreadDiskIo(
            destinationTitle = R.string.settings_tools_title,
        ) { activity ->
            findView(activity) {
                it is Switch &&
                    it.text.toString() == masterLabel &&
                    it.isEnabled
            } != null
        }
    }

    @Test
    fun providerHydrationPerformsNoMainThreadDiskIo() {
        val fieldLabel = targetString(R.string.ai_provider_key)
        val hydratedHints = setOf(
            targetString(R.string.ai_provider_key_saved),
            targetString(R.string.ai_provider_key_optional),
            targetString(R.string.ai_provider_key_required),
        )
        assertDestinationHydratesWithoutMainThreadDiskIo(
            destinationTitle = R.string.settings_provider_title,
        ) { activity ->
            hasHydratedSecretField(
                activity = activity,
                fieldLabel = fieldLabel,
                hydratedHints = hydratedHints,
            )
        }
    }

    @Test
    fun speechHydrationPerformsNoMainThreadDiskIo() {
        val fieldLabel = targetString(R.string.speech_provider_key)
        val hydratedHints = setOf(
            targetString(R.string.speech_provider_key_saved),
            targetString(R.string.speech_provider_key_required),
            targetString(R.string.speech_provider_key_not_required),
        )
        assertDestinationHydratesWithoutMainThreadDiskIo(
            destinationTitle = R.string.settings_voice_title,
        ) { activity ->
            hasHydratedSecretField(
                activity = activity,
                fieldLabel = fieldLabel,
                hydratedHints = hydratedHints,
            )
        }
    }

    @Test
    fun aboutNoticeAssetReadPerformsNoMainThreadDiskIo() {
        val noticeTitle = targetString(R.string.dictionary_notice_title)
        var clicked = false
        assertDestinationHydratesWithoutMainThreadDiskIo(
            destinationTitle = R.string.settings_about_title,
        ) { activity ->
            val card = findView(activity) {
                it.contentDescription?.toString() == noticeTitle
            } ?: return@assertDestinationHydratesWithoutMainThreadDiskIo false
            if (!clicked) {
                clicked = true
                card.performClick()
            }
            card.tag == AboutSettingsScreen.NOTICE_LOADED_TAG
        }
    }

    private fun assertDestinationHydratesWithoutMainThreadDiskIo(
        @StringRes destinationTitle: Int,
        isHydrated: (SettingsActivity) -> Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val violations = ConcurrentLinkedQueue<MainThreadDiskViolation>()
        val destinationPrefix = "${targetString(destinationTitle)}，"

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            assertTrue(
                "Settings home did not expose destination ${resourceName(destinationTitle)}",
                waitUntil(scenario, UI_TIMEOUT_MILLIS) { activity ->
                    findCategory(activity, destinationPrefix) != null
                },
            )
            instrumentation.waitForIdleSync()

            lateinit var previousPolicy: StrictMode.ThreadPolicy
            scenario.onActivity { activity ->
                previousPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyListener(DIRECT_EXECUTOR) { violation ->
                            if (
                                Looper.myLooper() == Looper.getMainLooper() &&
                                (violation is DiskReadViolation ||
                                    violation is DiskWriteViolation)
                            ) {
                                violations += MainThreadDiskViolation(
                                    type = violation.javaClass.simpleName,
                                    threadName = Thread.currentThread().name,
                                    stack = Log.getStackTraceString(violation),
                                )
                            }
                        }
                        .build(),
                )
                requireNotNull(findCategory(activity, destinationPrefix)).performClick()
            }

            val hydrated = try {
                val ready = waitUntil(scenario, UI_TIMEOUT_MILLIS, isHydrated)
                instrumentation.waitForIdleSync()
                ready
            } finally {
                scenario.onActivity {
                    StrictMode.setThreadPolicy(previousPolicy)
                }
            }

            assertTrue(
                "Settings destination ${resourceName(destinationTitle)} did not reach its first hydrated state",
                hydrated,
            )
        }

        assertTrue(
            buildString {
                append("Main-thread Settings disk I/O detected:\n")
                violations.forEach { violation ->
                    append(violation.type)
                    append(" on ")
                    append(violation.threadName)
                    append('\n')
                    append(violation.stack)
                    append('\n')
                }
            },
            violations.isEmpty(),
        )
    }

    private fun hasHydratedSecretField(
        activity: Activity,
        fieldLabel: String,
        hydratedHints: Set<String>,
    ): Boolean {
        val field = findView(activity) {
            it is EditText &&
                it.contentDescription?.toString() == fieldLabel
        } as? EditText ?: return false
        return field.hint?.toString() in hydratedHints
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
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        val matched = AtomicBoolean(false)
        scenario.onActivity { activity ->
            matched.set(predicate(activity))
        }
        return matched.get()
    }

    private fun findCategory(
        activity: Activity,
        descriptionPrefix: String,
    ): View? {
        return findView(activity) {
            it.contentDescription?.toString()?.startsWith(descriptionPrefix) == true
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

    private fun resourceName(@StringRes value: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getResourceEntryName(value)

    private fun targetString(@StringRes value: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(value)

    private data class MainThreadDiskViolation(
        val type: String,
        val threadName: String,
        val stack: String,
    )

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        const val UI_TIMEOUT_MILLIS = 8_000L
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
