package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * API 36 structural isolation gate for the active-Skill Aurora.
 *
 * GitHub's Android emulator uses a shared host and software GPU, so its absolute
 * frame time is not evidence for a physical phone. This gate still stays hard:
 * every window must prove that the parent keyboard does not redraw; at least
 * two of three must also prove callback ownership, Aurora draw and FrameMetrics
 * advancement in every five-second watchdog slice, bounded allocation and
 * bounded report loss; deactivation must always leave no recurring work.
 *
 * Absolute p95 and frame-rate acceptance is a separate, explicitly opted-in
 * test. It rejects emulator builds and binds the run to exact fingerprints,
 * installed APK hashes, refresh rate and a declared thermal ceiling.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class SkillAuroraDevicePerformanceTest {
    @get:Rule
    val testName = TestName()

    private lateinit var instrumentation: Instrumentation
    private lateinit var instrumentationArguments: Bundle
    private lateinit var activity: SkillKeyboardTestActivity
    private lateinit var keyboard: SenseKeyboardView
    private lateinit var overlay: ActiveSkillAuroraOverlayView
    private val originalAnimationScales = linkedMapOf<String, String>()

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentationArguments = InstrumentationRegistry.getArguments()
        if (testName.methodName == FIXED_PHYSICAL_TEST_METHOD) {
            assumeTrue(
                "Fixed-device Aurora SLA is opt-in; shared emulator CI proves " +
                    "structural isolation/liveness only",
                instrumentationArguments.getString(ARG_PHYSICAL_GATE) == "true",
            )
        }
        ANIMATION_SCALE_SETTINGS.forEach { setting ->
            originalAnimationScales[setting] =
                shell("settings get global $setting").trim().also { original ->
                    require(
                        original == "null" ||
                            original.matches(Regex("""[0-9]+(?:\.[0-9]+)?""")),
                    ) { "Unexpected $setting value: $original" }
                }
        }
        shell("settings put global window_animation_scale 0")
        shell("settings put global transition_animation_scale 0")
        shell("settings put global animator_duration_scale 1")
        assertTrue(
            "Device gate could not enable property animations",
            waitUntil(3_000L) { ValueAnimator.areAnimatorsEnabled() },
        )
        val intent = Intent().apply {
            setClassName(
                instrumentation.targetContext.packageName,
                SkillKeyboardTestActivity::class.java.name,
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        activity = instrumentation.startActivitySync(intent) as SkillKeyboardTestActivity
        instrumentation.waitForIdleSync()
        keyboard = onMain { activity.keyboardView }
        overlay = onMain { activity.skillAuroraOverlay }
        assertTrue(
            "Keyboard test host did not become visible",
            waitUntil(2_000L) {
                onMain {
                    keyboard.isShown &&
                        keyboard.width > 0 &&
                        keyboard.height > 0 &&
                        overlay.isShown
                }
            },
        )
    }

    @After
    fun tearDown() {
        try {
            if (::activity.isInitialized) {
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (::instrumentation.isInitialized) {
                originalAnimationScales.forEach { (setting, original) ->
                    if (original == "null") {
                        shell("settings delete global $setting")
                    } else {
                        shell("settings put global $setting $original")
                    }
                }
            }
        }
    }

    @Test(timeout = DEVICE_TEST_TIMEOUT_MILLIS)
    fun sharedEmulatorAuroraProvesIsolationLivenessOwnershipAllocationAndStops() {
        runStructuralGate(fixedAttestation = null)
    }

    @Test(timeout = DEVICE_TEST_TIMEOUT_MILLIS)
    fun fixedPhysicalAuroraMeetsAbsoluteP95AndFrameRateGate() {
        val attestation = fixedPhysicalAttestation()
        Log.i(LOG_TAG, attestation.diagnostic("before"))

        val samples = runStructuralGate(attestation)
        val absoluteFailures = samples.flatMap { sample ->
            buildList {
                if (sample.failures.isNotEmpty()) {
                    add(
                        "sample ${sample.round} failed structural invariants: " +
                            sample.failures.joinToString(),
                    )
                }
                val totalP95 = sample.totalP95Nanos
                if (totalP95 == null || totalP95 > MAX_FIXED_DEVICE_TOTAL_P95_NANOS) {
                    add(
                        "sample ${sample.round} TOTAL_DURATION p95=" +
                            "${totalP95?.div(NANOS_PER_MILLI) ?: "n/a"}ms exceeds " +
                            "${MAX_FIXED_DEVICE_TOTAL_P95_NANOS / NANOS_PER_MILLI}ms",
                    )
                }
                if (!sample.meetsFixedDeviceFrameRate()) {
                    add(
                        "sample ${sample.round} did not sustain at least " +
                            "$MIN_FIXED_DEVICE_TARGET_PERCENT% of the " +
                            "${1_000.0 / ActiveSkillAuroraLoopPolicy.FRAME_INTERVAL_MILLIS}fps " +
                            "Aurora target: elapsed=${sample.elapsedMillis}ms, " +
                            "overlay=${sample.overlay.animatedDrawCalls}, " +
                            "windowReports=${sample.windowFrames + sample.droppedReports}",
                    )
                }
            }
        }
        assertTrue(
            "Fixed physical-device Aurora SLA failed for ${attestation.diagnostic("before")}.\n" +
                absoluteFailures.joinToString(separator = "\n") +
                "\n" +
                samples.joinToString(separator = "\n") { it.diagnostic() },
            absoluteFailures.isEmpty(),
        )
        assertFixedPhysicalEnvironmentStillMatches(attestation)
    }

    private fun runStructuralGate(
        fixedAttestation: FixedPhysicalAttestation?,
    ): List<SampleResult> {
        assertTrue(
            "Aurora performance cannot be measured while reduced motion is enabled",
            ValueAnimator.areAnimatorsEnabled(),
        )
        val binding = KeyboardSkillBinding(
            keyCode = 'g'.code,
            direction = KeyboardSkillDirection.UP,
            skillId = "aurora-device-gate",
            label = "极光门禁",
            description = "设备隔离、帧推进和分配门禁",
        )
        val active = ActiveKeyboardSkill(
            skillId = binding.skillId,
            sourceKeyCode = binding.keyCode,
            direction = binding.direction,
        )
        val metricsThread = HandlerThread("sense-aurora-frame-metrics").apply { start() }
        val metricsHandler = Handler(metricsThread.looper)
        val collector = FrameCollector()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            collector.record(
                totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                syncNanos = metrics.getMetric(FrameMetrics.SYNC_DURATION),
                commandNanos = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                swapNanos = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                gpuNanos = metrics.getMetric(FrameMetrics.GPU_DURATION),
                deadlineNanos = metrics.getMetric(FrameMetrics.DEADLINE),
                droppedReports = dropped,
            )
        }
        return try {
            onMain {
                activity.window.addOnFrameMetricsAvailableListener(
                    listener,
                    metricsHandler,
                )
                keyboard.updateKeyboardSkills(listOf(binding), active)
            }
            assertTrue(
                "Aurora child layer did not converge to the exact physical owner envelope",
                waitUntil(2_000L) { overlayMatchesActiveOwner() },
            )

            SystemClock.sleep(WARMUP_MILLIS)
            val samples = (1..SAMPLE_COUNT).map { round ->
                measureSample(
                    round,
                    collector,
                    metricsHandler,
                    fixedAttestation,
                ).also { sample ->
                    Log.i(LOG_TAG, sample.diagnostic())
                }
            }

            assertTrue(
                "Parent keyboard redraw isolation is an unconditional gate.\n" +
                    samples.joinToString(separator = "\n") { it.diagnostic() },
                samples.all { it.parentDraws in 0L..MAX_PARENT_DRAWS_PER_SAMPLE },
            )
            val passingSamples = samples.count { it.failures.isEmpty() }
            assertTrue(
                "Aurora requires at least $REQUIRED_PASSING_SAMPLES/$SAMPLE_COUNT " +
                    "complete passing samples; observed $passingSamples.\n" +
                    samples.joinToString(separator = "\n") { it.diagnostic() },
                passingSamples >= REQUIRED_PASSING_SAMPLES,
            )

            onMain {
                // Reset while the production loop is still active. The following
                // clear must own and record cancellation; the test never repairs it.
                overlay.resetInstrumentation()
                keyboard.updateActiveKeyboardSkill(null)
            }
            instrumentation.waitForIdleSync()
            awaitHandlerBarrier(metricsHandler)
            collector.reset()
            val hiddenParentBefore = onMain { keyboard.renderPassCountForTesting() }
            val hiddenCpuBeforeMillis = android.os.Process.getElapsedCpuTime()
            SystemClock.sleep(HIDDEN_SETTLE_MILLIS)
            awaitHandlerBarrier(metricsHandler)
            val hiddenCpuDeltaMillis =
                android.os.Process.getElapsedCpuTime() - hiddenCpuBeforeMillis
            val hiddenParentDraws =
                onMain { keyboard.renderPassCountForTesting() } - hiddenParentBefore
            val hiddenOverlay = onMain { overlay.instrumentationSnapshot() }
            val hiddenSnapshot = collector.snapshot()
            val hiddenReportedFrames =
                hiddenSnapshot.totalNanos.size + hiddenSnapshot.droppedReports
            val hiddenDiagnostic =
                "Aurora hidden gate: windowRecorded=${hiddenSnapshot.totalNanos.size}, " +
                    "dropped=${hiddenSnapshot.droppedReports}, " +
                    "reported=$hiddenReportedFrames, parentDraws=$hiddenParentDraws, " +
                    "overlay=$hiddenOverlay, cpu=${hiddenCpuDeltaMillis}ms"
            Log.i(LOG_TAG, hiddenDiagnostic)
            assertTrue(
                "Aurora retained an active/scheduled overlay after deactivation. $hiddenDiagnostic",
                !hiddenOverlay.active &&
                    !hiddenOverlay.frameCallbackPosted &&
                    hiddenOverlay.executedFrameCallbacks == 0L &&
                    hiddenOverlay.animatedDrawCalls == 0L &&
                    hiddenOverlay.cancelledFrameCallbacks >= 1L,
            )
            assertTrue(
                "Aurora kept producing window frames after deactivation. $hiddenDiagnostic",
                hiddenReportedFrames <= MAX_HIDDEN_FRAMES,
            )
            assertTrue(
                "Aurora redrew the parent keyboard after deactivation settled. $hiddenDiagnostic",
                hiddenParentDraws in 0L..MAX_HIDDEN_PARENT_DRAWS,
            )
            assertTrue(
                "Hidden Aurora consumed too much process CPU. $hiddenDiagnostic",
                hiddenCpuDeltaMillis in 0..MAX_HIDDEN_CPU_MILLIS,
            )
            samples
        } finally {
            onMain {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                keyboard.updateActiveKeyboardSkill(null)
            }
            metricsThread.quitSafely()
            metricsThread.join(2_000L)
        }
    }

    private fun measureSample(
        round: Int,
        collector: FrameCollector,
        metricsHandler: Handler,
        fixedAttestation: FixedPhysicalAttestation?,
    ): SampleResult {
        SystemClock.sleep(SAMPLE_BOUNDARY_SETTLE_MILLIS)
        awaitHandlerBarrier(metricsHandler)
        collector.reset()
        val initialOverlay = onMain {
            overlay.resetInstrumentation()
            overlay.instrumentationSnapshot()
        }
        var previousOverlay = initialOverlay
        var previousReports = collector.reportCount()
        val livenessSlices = ArrayList<LivenessSlice>(LIVENESS_CHECKPOINT_COUNT)

        val parentBefore = onMain { keyboard.renderPassCountForTesting() }
        val allocatedBefore = allocatedBytes()
        val cpuBeforeMillis = android.os.Process.getElapsedCpuTime()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        repeat(LIVENESS_CHECKPOINT_COUNT) { index ->
            SystemClock.sleep(LIVENESS_CHECKPOINT_MILLIS)
            awaitHandlerBarrier(metricsHandler)
            val currentOverlay = onMain { overlay.instrumentationSnapshot() }
            val currentReports = collector.reportCount()
            livenessSlices += LivenessSlice(
                index = index + 1,
                executedCallbacks =
                    currentOverlay.executedFrameCallbacks -
                        previousOverlay.executedFrameCallbacks,
                animatedDraws =
                    currentOverlay.animatedDrawCalls - previousOverlay.animatedDrawCalls,
                windowReports = currentReports - previousReports,
                active = currentOverlay.active,
                frameCallbackPosted = currentOverlay.frameCallbackPosted,
                outstandingCallbacks =
                    currentOverlay.postedFrameCallbacks -
                        currentOverlay.executedFrameCallbacks,
                cancelledCallbacks = currentOverlay.cancelledFrameCallbacks,
                fixedEnvironmentFailure =
                    fixedAttestation?.let(::fixedEnvironmentFailure),
            )
            previousOverlay = currentOverlay
            previousReports = currentReports
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
        awaitHandlerBarrier(metricsHandler)
        val cpuAfterMillis = android.os.Process.getElapsedCpuTime()
        val allocatedAfter = allocatedBytes()
        val parentDraws = onMain { keyboard.renderPassCountForTesting() } - parentBefore
        val overlaySnapshot = onMain { overlay.instrumentationSnapshot() }
        val frameSnapshot = collector.snapshot()
        val failures = mutableListOf<String>()

        if (elapsedNanos < MEASURE_MILLIS * NANOS_PER_MILLI) {
            failures += "measurement ended before ${MEASURE_MILLIS}ms"
        }
        if (parentDraws !in 0L..MAX_PARENT_DRAWS_PER_SAMPLE) {
            failures +=
                "parent keyboard drew $parentDraws times; maximum is " +
                MAX_PARENT_DRAWS_PER_SAMPLE
        }
        if (
            overlaySnapshot.animatedDrawCalls <= 0L ||
            overlaySnapshot.drawCalls != overlaySnapshot.animatedDrawCalls
        ) {
            failures +=
                "overlay draws animated=${overlaySnapshot.animatedDrawCalls}, " +
                "total=${overlaySnapshot.drawCalls}; active samples require only animated draws"
        }
        if (!overlaySnapshot.active || !overlaySnapshot.frameCallbackPosted) {
            failures += "active overlay did not retain exactly one pending animation callback"
        }
        val outstandingCallbacks =
            overlaySnapshot.postedFrameCallbacks - overlaySnapshot.executedFrameCallbacks
        if (
            outstandingCallbacks != 1L ||
            overlaySnapshot.cancelledFrameCallbacks != 0L
        ) {
            failures +=
                "callback ownership posted=${overlaySnapshot.postedFrameCallbacks}, " +
                "executed=${overlaySnapshot.executedFrameCallbacks}, " +
                "cancelled=${overlaySnapshot.cancelledFrameCallbacks}"
        }
        livenessSlices.forEach { slice ->
            failures += slice.failures()
        }

        val reportCount =
            frameSnapshot.totalNanos.size.toLong() + frameSnapshot.droppedReports.toLong()
        if (
            reportCount <= 0L ||
            frameSnapshot.droppedReports.toLong() * 100L >
            reportCount * MAX_DROPPED_REPORT_PERCENT
        ) {
            failures +=
                "dropped report ratio ${frameSnapshot.droppedReports}/$reportCount exceeds " +
                "$MAX_DROPPED_REPORT_PERCENT%"
        }

        val allocatedDelta =
            if (allocatedBefore != null && allocatedAfter != null) {
                allocatedAfter - allocatedBefore
            } else {
                null
            }
        val allocatedPerOverlayDraw =
            allocatedDelta?.div(overlaySnapshot.animatedDrawCalls.coerceAtLeast(1L))
        when {
            allocatedDelta == null -> {
                failures +=
                    "ART runtime stat $ART_ALLOCATED_BYTES unavailable: " +
                    "before=$allocatedBefore after=$allocatedAfter"
            }

            allocatedDelta < 0L -> {
                failures +=
                    "ART allocation counter moved backwards: " +
                    "$allocatedBefore->$allocatedAfter"
            }

            allocatedDelta > MAX_ALLOCATED_BYTES_PER_SAMPLE ||
                requireNotNull(allocatedPerOverlayDraw) >
                MAX_ALLOCATED_BYTES_PER_OVERLAY_DRAW -> {
                failures +=
                    "allocation $allocatedDelta bytes " +
                    "($allocatedPerOverlayDraw/overlay draw) exceeds " +
                    "$MAX_ALLOCATED_BYTES_PER_SAMPLE total or " +
                    "$MAX_ALLOCATED_BYTES_PER_OVERLAY_DRAW/draw"
            }
        }

        return SampleResult(
            round = round,
            elapsedMillis = elapsedNanos / NANOS_PER_MILLI,
            windowFrames = frameSnapshot.totalNanos.size,
            droppedReports = frameSnapshot.droppedReports,
            totalP95Nanos = p95(frameSnapshot.totalNanos),
            drawP95Nanos = p95(frameSnapshot.drawNanos),
            syncP95Nanos = p95(frameSnapshot.syncNanos),
            commandP95Nanos = p95(frameSnapshot.commandNanos),
            swapP95Nanos = p95(frameSnapshot.swapNanos),
            gpuP95Nanos = p95(frameSnapshot.gpuNanos),
            deadlineP95Nanos = p95(frameSnapshot.deadlineNanos),
            parentDraws = parentDraws,
            overlay = overlaySnapshot,
            allocatedBytes = allocatedDelta,
            allocatedBytesPerOverlayDraw = allocatedPerOverlayDraw,
            cpuMillis = cpuAfterMillis - cpuBeforeMillis,
            livenessSlices = livenessSlices,
            failures = failures,
        )
    }

    private fun allocatedBytes(): Long? =
        Debug.getRuntimeStat(ART_ALLOCATED_BYTES)?.toLongOrNull()

    private fun p95(values: LongArray): Long? {
        if (values.isEmpty()) return null
        val sorted = values.copyOf()
        sorted.sort()
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)
        return sorted[index]
    }

    private fun shell(command: String): String {
        val descriptor = requireNotNull(instrumentation.uiAutomation) {
            "UiAutomation could not connect"
        }.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (condition()) return true
            SystemClock.sleep(20L)
        } while (SystemClock.uptimeMillis() < deadline)
        return condition()
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun awaitHandlerBarrier(handler: Handler) {
        val barrier = FutureTask<Unit> {}
        check(handler.post(barrier)) { "FrameMetrics handler rejected its boundary barrier" }
        barrier.get(METRICS_BARRIER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun overlayMatchesActiveOwner(): Boolean = onMain {
        val owner = keyboard.activeSkillSourceBoundsForTesting() ?: return@onMain false
        val expectedLeft = floor(owner.left.toDouble()).toInt()
        val expectedTop = floor(owner.top.toDouble()).toInt()
        val expectedRight = ceil(owner.right.toDouble()).toInt()
        val expectedBottom = ceil(owner.bottom.toDouble()).toInt()
        overlay.left == expectedLeft &&
            overlay.top == expectedTop &&
            overlay.width == expectedRight - expectedLeft &&
            overlay.height == expectedBottom - expectedTop &&
            overlay.width < keyboard.width &&
            overlay.height < keyboard.height
    }

    private fun fixedPhysicalAttestation(): FixedPhysicalAttestation {
        assertTrue(
            "Fixed-device Aurora SLA refuses emulator/generic builds: " +
                "fingerprint=${Build.FINGERPRINT}, hardware=${Build.HARDWARE}, " +
                "model=${Build.MODEL}, product=${Build.PRODUCT}",
            !isProbablyEmulator(),
        )
        val expectedFingerprint = requiredPhysicalArgument(ARG_EXPECTED_FINGERPRINT)
        assertTrue(
            "Physical-device fingerprint differs from the attested runner",
            Build.FINGERPRINT == expectedFingerprint,
        )

        val expectedTargetApkSha256 =
            requiredSha256Argument(ARG_EXPECTED_TARGET_APK_SHA256)
        val expectedTestApkSha256 =
            requiredSha256Argument(ARG_EXPECTED_TEST_APK_SHA256)
        val targetApkSha256 =
            sha256(File(instrumentation.targetContext.applicationInfo.sourceDir))
        val testApkSha256 =
            sha256(File(instrumentation.context.applicationInfo.sourceDir))
        assertTrue(
            "Installed target APK differs from the attested artifact: " +
                "expected=$expectedTargetApkSha256 actual=$targetApkSha256",
            targetApkSha256 == expectedTargetApkSha256,
        )
        assertTrue(
            "Installed instrumentation APK differs from the attested artifact: " +
                "expected=$expectedTestApkSha256 actual=$testApkSha256",
            testApkSha256 == expectedTestApkSha256,
        )

        val expectedRefreshRate = requiredPhysicalArgument(ARG_EXPECTED_REFRESH_RATE)
            .toFloatOrNull()
        assertTrue(
            "$ARG_EXPECTED_REFRESH_RATE must be a finite positive decimal",
            expectedRefreshRate != null &&
                expectedRefreshRate.isFinite() &&
                expectedRefreshRate > 0f,
        )
        val attestedRefreshRate = requireNotNull(expectedRefreshRate)
        val maximumThermalStatus =
            requiredPhysicalArgument(ARG_MAXIMUM_THERMAL_STATUS).toIntOrNull()
        assertTrue(
            "$ARG_MAXIMUM_THERMAL_STATUS must be an Android thermal status in 0..6",
            maximumThermalStatus != null && maximumThermalStatus in 0..6,
        )
        val attestedMaximumThermalStatus = requireNotNull(maximumThermalStatus)
        val actualRefreshRate = currentRefreshRate()
        assertTrue(
            "Display refresh rate differs from the attested fixed mode: " +
                "expected=$expectedRefreshRate actual=$actualRefreshRate",
            abs(actualRefreshRate - attestedRefreshRate) <=
                REFRESH_RATE_TOLERANCE_HZ,
        )
        val thermalStatus = currentThermalStatus()
        assertTrue(
            "Physical runner is already above its declared thermal ceiling: " +
                "actual=$thermalStatus maximum=$maximumThermalStatus",
            thermalStatus <= attestedMaximumThermalStatus,
        )
        return FixedPhysicalAttestation(
            fingerprint = expectedFingerprint,
            targetApkSha256 = expectedTargetApkSha256,
            testApkSha256 = expectedTestApkSha256,
            refreshRateHz = attestedRefreshRate,
            maximumThermalStatus = attestedMaximumThermalStatus,
            initialThermalStatus = thermalStatus,
        )
    }

    private fun assertFixedPhysicalEnvironmentStillMatches(
        attestation: FixedPhysicalAttestation,
    ) {
        val currentTargetApkSha256 =
            sha256(File(instrumentation.targetContext.applicationInfo.sourceDir))
        val currentTestApkSha256 =
            sha256(File(instrumentation.context.applicationInfo.sourceDir))
        val finalThermalStatus = currentThermalStatus()
        val environmentFailure = fixedEnvironmentFailure(attestation)
        val finalDiagnostic =
            attestation.copy(initialThermalStatus = finalThermalStatus).diagnostic("after") +
                ", actualTargetApkSha256=$currentTargetApkSha256, " +
                "actualTestApkSha256=$currentTestApkSha256, " +
                "environmentFailure=${environmentFailure ?: "none"}"
        Log.i(LOG_TAG, finalDiagnostic)
        assertTrue(
            "Fixed physical-device attestation changed during measurement. $finalDiagnostic",
            Build.FINGERPRINT == attestation.fingerprint &&
                currentTargetApkSha256 == attestation.targetApkSha256 &&
                currentTestApkSha256 == attestation.testApkSha256 &&
                environmentFailure == null,
        )
    }

    private fun fixedEnvironmentFailure(
        attestation: FixedPhysicalAttestation,
    ): String? {
        if (Build.FINGERPRINT != attestation.fingerprint) {
            return "fingerprint changed during fixed-device measurement"
        }
        val refreshRate = currentRefreshRate()
        if (
            abs(refreshRate - attestation.refreshRateHz) >
            REFRESH_RATE_TOLERANCE_HZ
        ) {
            return "refresh rate changed: expected=${attestation.refreshRateHz} " +
                "actual=$refreshRate"
        }
        val thermalStatus = currentThermalStatus()
        if (thermalStatus > attestation.maximumThermalStatus) {
            return "thermal ceiling exceeded: maximum=" +
                "${attestation.maximumThermalStatus} actual=$thermalStatus"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun currentRefreshRate(): Float =
        activity.windowManager.defaultDisplay.refreshRate

    private fun currentThermalStatus(): Int =
        requireNotNull(
            instrumentation.targetContext.getSystemService(PowerManager::class.java),
        ) {
            "Physical gate cannot resolve PowerManager"
        }.currentThermalStatus

    private fun requiredPhysicalArgument(name: String): String =
        requireNotNull(instrumentationArguments.getString(name)?.takeIf { it.isNotBlank() }) {
            "Fixed-device Aurora SLA requires instrumentation argument $name"
        }

    private fun requiredSha256Argument(name: String): String =
        requiredPhysicalArgument(name).also { value ->
            require(value.matches(Regex("[0-9a-f]{64}"))) {
                "$name must be exactly 64 lowercase hexadecimal characters"
            }
        }

    private fun sha256(file: File): String {
        require(file.isFile) { "Cannot hash installed APK ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            product.contains("sdk_gphone") ||
            product.contains("emulator")
    }

    private data class FixedPhysicalAttestation(
        val fingerprint: String,
        val targetApkSha256: String,
        val testApkSha256: String,
        val refreshRateHz: Float,
        val maximumThermalStatus: Int,
        val initialThermalStatus: Int,
    ) {
        fun diagnostic(stage: String): String =
            "Aurora fixed-device attestation $stage: fingerprint=$fingerprint, " +
                "targetApkSha256=$targetApkSha256, testApkSha256=$testApkSha256, " +
                "refreshRate=${refreshRateHz}Hz, thermal=$initialThermalStatus/" +
                maximumThermalStatus
    }

    private data class SampleResult(
        val round: Int,
        val elapsedMillis: Long,
        val windowFrames: Int,
        val droppedReports: Int,
        val totalP95Nanos: Long?,
        val drawP95Nanos: Long?,
        val syncP95Nanos: Long?,
        val commandP95Nanos: Long?,
        val swapP95Nanos: Long?,
        val gpuP95Nanos: Long?,
        val deadlineP95Nanos: Long?,
        val parentDraws: Long,
        val overlay: ActiveSkillAuroraInstrumentationSnapshot,
        val allocatedBytes: Long?,
        val allocatedBytesPerOverlayDraw: Long?,
        val cpuMillis: Long,
        val livenessSlices: List<LivenessSlice>,
        val failures: List<String>,
    ) {
        fun meetsFixedDeviceFrameRate(): Boolean {
            val requiredProgress =
                elapsedMillis * MIN_FIXED_DEVICE_TARGET_PERCENT
            val overlayProgress =
                overlay.animatedDrawCalls *
                    ActiveSkillAuroraLoopPolicy.FRAME_INTERVAL_MILLIS *
                    100L
            val windowProgress =
                (windowFrames.toLong() + droppedReports.toLong()) *
                    ActiveSkillAuroraLoopPolicy.FRAME_INTERVAL_MILLIS *
                    100L
            return overlayProgress >= requiredProgress &&
                windowProgress >= requiredProgress
        }

        fun diagnostic(): String {
            val reportCount = windowFrames.toLong() + droppedReports
            return "Aurora isolation sample $round: " +
                "${if (failures.isEmpty()) "PASS" else "FAIL"}; " +
                "elapsed=${elapsedMillis}ms; windowFrames=$windowFrames; " +
                "dropped=$droppedReports/$reportCount; parentDraws=$parentDraws; " +
                "overlay=$overlay; p95(total/draw/sync/command/swap/gpu/deadline)=" +
                listOf(
                    totalP95Nanos,
                    drawP95Nanos,
                    syncP95Nanos,
                    commandP95Nanos,
                    swapP95Nanos,
                    gpuP95Nanos,
                    deadlineP95Nanos,
                ).joinToString("/") { nanos ->
                    nanos?.let { "${it / NANOS_PER_MILLI}ms" } ?: "n/a"
                } +
                "; allocation=${allocatedBytes ?: "unavailable"} bytes " +
                "(${allocatedBytesPerOverlayDraw ?: "unavailable"}/overlay draw); " +
                "cpu=${cpuMillis}ms; liveness=" +
                livenessSlices.joinToString(
                    prefix = "[",
                    postfix = "]",
                    separator = ", ",
                ) { it.diagnostic() } +
                "; failures=" +
                if (failures.isEmpty()) "none" else failures.joinToString()
        }
    }

    private data class LivenessSlice(
        val index: Int,
        val executedCallbacks: Long,
        val animatedDraws: Long,
        val windowReports: Long,
        val active: Boolean,
        val frameCallbackPosted: Boolean,
        val outstandingCallbacks: Long,
        val cancelledCallbacks: Long,
        val fixedEnvironmentFailure: String?,
    ) {
        fun failures(): List<String> = buildList {
            if (executedCallbacks <= 0L) {
                add("liveness slice $index executed no frame callback")
            }
            if (animatedDraws <= 0L) {
                add("liveness slice $index drew no animated Aurora frame")
            }
            if (windowReports <= 0L) {
                add("liveness slice $index delivered no FrameMetrics report")
            }
            // These are pipeline-depth bounds, not throughput thresholds:
            // exactly one Aurora callback may be pending between callback and
            // traversal, while FrameMetrics crosses one additional Handler
            // handoff. A busy shared host can advance slowly, but an internal
            // callback-only or draw-only zombie cannot accumulate unbounded skew.
            val callbackDrawSkew = abs(executedCallbacks - animatedDraws)
            if (callbackDrawSkew > MAX_CALLBACK_DRAW_SKEW_PER_SLICE) {
                add(
                    "liveness slice $index callback/draw skew=$callbackDrawSkew " +
                        "exceeds $MAX_CALLBACK_DRAW_SKEW_PER_SLICE",
                )
            }
            val drawReportSkew = abs(animatedDraws - windowReports)
            if (drawReportSkew > MAX_DRAW_REPORT_SKEW_PER_SLICE) {
                add(
                    "liveness slice $index draw/report skew=$drawReportSkew " +
                        "exceeds $MAX_DRAW_REPORT_SKEW_PER_SLICE",
                )
            }
            if (!active || !frameCallbackPosted) {
                add(
                    "liveness slice $index lost active/pending state: " +
                        "active=$active pending=$frameCallbackPosted",
                )
            }
            if (outstandingCallbacks != 1L || cancelledCallbacks != 0L) {
                add(
                    "liveness slice $index lost callback ownership: " +
                        "outstanding=$outstandingCallbacks cancelled=$cancelledCallbacks",
                )
            }
            fixedEnvironmentFailure?.let { failure ->
                add("liveness slice $index fixed environment changed: $failure")
            }
        }

        fun diagnostic(): String =
            "$index:callbacks=$executedCallbacks/draws=$animatedDraws/" +
                "reports=$windowReports/outstanding=$outstandingCallbacks/" +
                "cancelled=$cancelledCallbacks"
    }

    private class FrameCollector {
        private val total = LongArray(MAX_CAPTURED_FRAMES)
        private val draw = LongArray(MAX_CAPTURED_FRAMES)
        private val sync = LongArray(MAX_CAPTURED_FRAMES)
        private val command = LongArray(MAX_CAPTURED_FRAMES)
        private val swap = LongArray(MAX_CAPTURED_FRAMES)
        private val gpu = LongArray(MAX_CAPTURED_FRAMES)
        private val deadline = LongArray(MAX_CAPTURED_FRAMES)
        private var count = 0
        private var dropped = 0

        @Synchronized
        fun record(
            totalNanos: Long,
            drawNanos: Long,
            syncNanos: Long,
            commandNanos: Long,
            swapNanos: Long,
            gpuNanos: Long,
            deadlineNanos: Long,
            droppedReports: Int,
        ) {
            dropped += droppedReports.coerceAtLeast(0)
            if (totalNanos <= 0L) return
            if (count >= total.size) {
                dropped += 1
                return
            }
            total[count] = totalNanos
            draw[count] = drawNanos
            sync[count] = syncNanos
            command[count] = commandNanos
            swap[count] = swapNanos
            gpu[count] = gpuNanos
            deadline[count] = deadlineNanos
            count += 1
        }

        @Synchronized
        fun reset() {
            count = 0
            dropped = 0
        }

        @Synchronized
        fun reportCount(): Long = count.toLong() + dropped.toLong()

        @Synchronized
        fun snapshot(): FrameSnapshot =
            FrameSnapshot(
                totalNanos = total.copyOf(count),
                drawNanos = draw.copyOf(count),
                syncNanos = sync.copyOf(count),
                commandNanos = command.copyOf(count),
                swapNanos = swap.copyOf(count),
                gpuNanos = gpu.copyOf(count),
                deadlineNanos = deadline.copyOf(count),
                droppedReports = dropped,
            )
    }

    private data class FrameSnapshot(
        val totalNanos: LongArray,
        val drawNanos: LongArray,
        val syncNanos: LongArray,
        val commandNanos: LongArray,
        val swapNanos: LongArray,
        val gpuNanos: LongArray,
        val deadlineNanos: LongArray,
        val droppedReports: Int,
    )

    private companion object {
        val ANIMATION_SCALE_SETTINGS = listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        )
        const val LOG_TAG = "SenseAuroraDeviceGate"
        const val DEVICE_TEST_TIMEOUT_MILLIS = 45L * 60L * 1_000L
        const val WARMUP_MILLIS = 3_000L
        const val SAMPLE_BOUNDARY_SETTLE_MILLIS = 250L
        const val LIVENESS_CHECKPOINT_MILLIS = 5_000L
        const val LIVENESS_CHECKPOINT_COUNT = 6
        const val MAX_CALLBACK_DRAW_SKEW_PER_SLICE = 1L
        const val MAX_DRAW_REPORT_SKEW_PER_SLICE = 2L
        const val MEASURE_MILLIS =
            LIVENESS_CHECKPOINT_MILLIS * LIVENESS_CHECKPOINT_COUNT
        const val SAMPLE_COUNT = 3
        const val REQUIRED_PASSING_SAMPLES = 2
        const val MAX_PARENT_DRAWS_PER_SAMPLE = 5L
        const val MAX_CAPTURED_FRAMES = 8_192
        const val MAX_DROPPED_REPORT_PERCENT = 2L
        const val MAX_ALLOCATED_BYTES_PER_SAMPLE = 4L * 1024L * 1024L
        const val MAX_ALLOCATED_BYTES_PER_OVERLAY_DRAW = 1_024L
        const val HIDDEN_SETTLE_MILLIS = 1_000L
        const val MAX_HIDDEN_FRAMES = 12
        const val MAX_HIDDEN_PARENT_DRAWS = 1L
        const val MAX_HIDDEN_CPU_MILLIS = 500L
        const val METRICS_BARRIER_TIMEOUT_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val ART_ALLOCATED_BYTES = "art.gc.bytes-allocated"
        const val FIXED_PHYSICAL_TEST_METHOD =
            "fixedPhysicalAuroraMeetsAbsoluteP95AndFrameRateGate"
        const val ARG_PHYSICAL_GATE = "senseAuroraPhysicalGate"
        const val ARG_EXPECTED_FINGERPRINT = "senseAuroraExpectedFingerprint"
        const val ARG_EXPECTED_TARGET_APK_SHA256 =
            "senseAuroraExpectedTargetApkSha256"
        const val ARG_EXPECTED_TEST_APK_SHA256 =
            "senseAuroraExpectedTestApkSha256"
        const val ARG_EXPECTED_REFRESH_RATE = "senseAuroraExpectedRefreshRateHz"
        const val ARG_MAXIMUM_THERMAL_STATUS =
            "senseAuroraMaximumThermalStatus"
        const val MAX_FIXED_DEVICE_TOTAL_P95_NANOS = 32L * NANOS_PER_MILLI
        const val MIN_FIXED_DEVICE_TARGET_PERCENT = 80L
        const val REFRESH_RATE_TOLERANCE_HZ = 0.05f
    }
}
