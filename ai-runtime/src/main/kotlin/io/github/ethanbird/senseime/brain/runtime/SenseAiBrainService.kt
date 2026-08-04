package io.github.ethanbird.senseime.brain.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import io.github.ethanbird.senseime.ai.protocol.AiEvent
import io.github.ethanbird.senseime.ai.protocol.HarnessCancelReason
import io.github.ethanbird.senseime.ai.protocol.HarnessErrorCode
import io.github.ethanbird.senseime.ai.protocol.isTerminal
import io.github.ethanbird.senseime.brain.AiBrainEngine
import io.github.ethanbird.senseime.brain.BrainRequestMode
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDefinition
import io.github.ethanbird.senseime.brain.api.AgentSkillMutation
import io.github.ethanbird.senseime.brain.api.BrainTraceEvent
import io.github.ethanbird.senseime.brain.api.BrainRunSpec
import io.github.ethanbird.senseime.brain.api.ProviderCompatibility
import io.github.ethanbird.senseime.brain.api.toSummary
import io.github.ethanbird.senseime.brain.memory.AgentEventJournal
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchAccess
import io.github.ethanbird.senseime.brain.memory.AgentMemorySearchBounds
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Non-exported process host for Provider networking and model response parsing.
 *
 * It receives only immutable protocol values. InputConnection, keyboard View, editor Binder and
 * application package metadata never enter :brain.
 */
class SenseAiBrainService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(IncomingHandler())
    private val activeLock = Any()
    private lateinit var transport: HttpUrlConnectionProviderTransport
    private lateinit var engine: AiBrainEngine
    private lateinit var settings: ProviderSettingsStore
    private lateinit var toolSettings: AgentToolSettingsStore
    private lateinit var skillStore: AgentSkillStore
    /*
     * Shared across Service instances in this :brain process. Android may construct a replacement
     * Service immediately after onDestroy() returns, while the old instance is still draining a
     * blocking admission. A process-wide FIFO makes the old journal/transport close happen before
     * the replacement attempts to acquire the journal writer lock.
     */
    private val admissionExecutor = PROCESS_ADMISSION_EXECUTOR
    private lateinit var admissionLane: BrainAdmissionSerialLane<StartAdmission>
    @Volatile
    private var journal: AgentEventJournal? = null
    @Volatile
    private var journalOpenFailure: Throwable? = null
    private var active: ActiveRun? = null
    private var durableRunPromoted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val outboundDeliveries =
        BrainIpcSerialDeliveryQueue<OutboundDelivery>()
    private val deliverOutboundEvents = Runnable {
        while (true) {
            val delivery = outboundDeliveries.poll() ?: return@Runnable
            sendEventsNow(delivery)
        }
    }

    override fun onCreate() {
        super.onCreate()
        transport = HttpUrlConnectionProviderTransport()
        val application = applicationContext
        admissionExecutor.execute {
            settings = ProviderSettingsStore(application)
            toolSettings = AgentToolSettingsStore(application)
            skillStore = AgentSkillStore(application)
            runCatching {
                AgentEventJournal.open(File(application.filesDir, AGENT_HISTORY_DIRECTORY))
            }.onSuccess {
                journal = it
            }.onFailure {
                journalOpenFailure = it
            }
        }
        val memorySource = AgentMemorySearchSource { query, maxResults, excludeId, excludeGeneration ->
            val source = journal ?: return@AgentMemorySearchSource emptyList()
            source.search(
                query = query,
                access = AgentMemorySearchAccess.ENABLED,
                bounds = AgentMemorySearchBounds(maxResults = maxResults),
                excludeRequestId = excludeId,
                excludeRunGeneration = excludeGeneration,
            ).hits.map { hit ->
                AgentMemorySearchHit(
                    id = hit.sequence.toString(),
                    text = hit.excerpt,
                    source = "${hit.kind.name}:${hit.requestId}",
                )
            }
        }
        val skillSource = object : AgentSkillToolSource {
            override fun read(skillId: String, revision: Long): AgentSkillDefinition? =
                skillStore.readRevision(skillId, revision).getOrThrow()

            override fun apply(mutation: AgentSkillMutation): AgentSkillCatalog =
                skillStore.apply(mutation).getOrThrow()
        }
        engine = AiBrainEngine(
            transport = transport,
            toolExecutor = DefaultAgentToolExecutor(
                memorySource = memorySource,
                skillSource = skillSource,
                terminalSource = AgentTerminalToolSource(
                    AgentRuntimeComponents.terminal(application)::executeForTool,
                ),
                browserSource = AgentBrowserToolSource(
                    AgentRuntimeComponents.browser(application)::executeForTool,
                ),
            ),
        )
        admissionLane = BrainAdmissionSerialLane(
            executor = admissionExecutor,
            admit = ::admitStart,
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROMOTE_DURABLE_RUN -> promoteDurableRun()
            ACTION_STOP_DURABLE_RUN -> stopDurableRunFromNotification()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val previous = synchronized(activeLock) {
            active.also {
                active = null
                it?.let { current ->
                    current.admissionCancellation = HarnessCancelReason.BRAIN_DIED
                    cancelTickerLocked(current)
                    cancelVisibleFlushLocked(current)
                }
            }
        }
        mainHandler.removeCallbacks(deliverOutboundEvents)
        outboundDeliveries.clear()
        previous?.retentionGate?.currentHandle()?.cancel(HarnessCancelReason.BRAIN_DIED)
        releaseWakeLock()
        if (::admissionLane.isInitialized) {
            admissionLane.closeAfterDraining {
                /*
                 * A START that was already inside a blocking config/catalog read observes the
                 * revoked ActiveRun before engine.start. Keeping transport/journal cleanup behind
                 * that admission also prevents either dependency from closing underneath it.
                 */
                transport.close()
                val closing = journal
                runCatching { closing?.flush() }
                runCatching { closing?.close() }
                journal = null
            }
        } else {
            transport.close()
        }
        super.onDestroy()
    }

    private fun handleStart(message: Message) {
        val request = runCatching { BrainMessageCodec.decodeRequest(message.data) }.getOrNull()
        val reply = message.replyTo
        if (request == null || reply == null) return
        val identity = request.requestId to request.runGeneration
        val previous = synchronized(activeLock) {
            active?.also {
                it.admissionCancellation = HarnessCancelReason.CALLER_REQUESTED
            }
        }
        // Cancellation emits the previous terminal event synchronously. Keep its ActiveRun
        // installed until that callback is captured for its original Messenger; replacing it
        // first would make emit() drop the cancellation as stale and strand the old client's UI.
        previous?.retentionGate?.currentHandle()?.cancel(HarnessCancelReason.CALLER_REQUESTED)
        val current = ActiveRun(identity = identity, reply = reply)
        synchronized(activeLock) {
            // A previous run with no installed handle cannot emit its own
            // terminal event. Its pending fragments still must not leak into
            // this run, and a stale frame callback must not target the new one.
            active?.let { remaining ->
                cancelTickerLocked(remaining)
                cancelVisibleFlushLocked(remaining)
                remaining.ipcEvents.clear()
            }
            active = current
        }

        val enqueued = admissionLane.submit(StartAdmission(current, request))
        if (!enqueued) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
        }
    }

    private fun admitStart(admission: StartAdmission) {
        val current = admission.run
        val request = admission.request
        val activeJournal = journal
        if (activeJournal == null || journalOpenFailure != null) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        val recorder = runCatching {
            AgentRunRecorder.begin(activeJournal, request)
        }.getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        synchronized(activeLock) {
            current.recorder = recorder
        }
        if (finishCancelledAdmission(current)) return

        val configResult = settings.load()
        if (finishCancelledAdmission(current)) return
        if (configResult.isFailure) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        val config = configResult.getOrNull()
        if (config == null) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.PROVIDER_NOT_CONFIGURED,
                    retryable = false,
                ),
            )
            return
        }
        if (ProviderCompatibility.issues(config.profile).isNotEmpty()) {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.PROVIDER_CONFIGURATION,
                    retryable = false,
                ),
            )
            return
        }

        val admittedToolSettings = toolSettings.load().getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        val enabledTools = AgentToolRunAdmission.freeze(admittedToolSettings)
        if (finishCancelledAdmission(current)) return

        val requestedSkillGeneration = request.skillCatalogGeneration
        val skillCatalogResult = if (requestedSkillGeneration == null) {
            skillStore.loadCatalog()
        } else {
            skillStore.readCatalogGeneration(requestedSkillGeneration).mapCatching { catalog ->
                requireNotNull(catalog) {
                    "Frozen Skill catalog generation is unavailable"
                }
            }
        }
        val skillCatalog = skillCatalogResult.getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        if (finishCancelledAdmission(current)) return
        runCatching {
            AgentSkillRunAdmission.requireConsistent(request, skillCatalog)
        }.getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
            return
        }
        if (finishCancelledAdmission(current)) return

        val handle = runCatching {
            val requestMode = if (ProviderConnectionTestProtocol.isProbe(request)) {
                BrainRequestMode.CONNECTIVITY_TEST
            } else {
                BrainRequestMode.NORMAL
            }
            engine.start(
                BrainRunSpec(
                    harnessRequest = request,
                    provider = config.profile,
                    credential = config.credential,
                    skillCatalog = skillCatalog.definitions.map { it.toSummary() },
                    skillCatalogGeneration = skillCatalog.generation,
                    enabledTools = enabledTools,
                    traceSink = { trace -> recordTrace(current, trace) },
                ),
                sink = { event -> emit(current, event) },
                requestMode = requestMode,
            )
        }.getOrElse {
            emit(
                current,
                AiEvent.Failed(
                    request.requestId,
                    request.runGeneration,
                    HarnessErrorCode.INTERNAL_FAILURE,
                ),
            )
            return
        }
        val keep = synchronized(activeLock) {
            active === current && current.retentionGate.install(handle)
        }
        if (keep && !handle.isTerminal) {
            scheduleTicker(current)
        } else {
            handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
        }
    }

    private fun handleCancel(message: Message) {
        val (requestId, generation, reason) = runCatching {
            BrainMessageCodec.decodeCancel(message.data)
        }.getOrNull() ?: return
        val identity = requestId to generation
        val current = synchronized(activeLock) {
            active?.takeIf { it.identity == identity }?.also {
                active = null
                it.admissionCancellation = reason
                cancelTickerLocked(it)
                cancelVisibleFlushLocked(it)
                it.ipcEvents.clear()
            }
        } ?: return
        current.retentionGate.currentHandle()?.cancel(reason)
        finishDurableRun(null)
    }

    /**
     * Returns true when this admission lost authority before a Brain handle was installed.
     *
     * The request was already durably begun, so the cancellation is retained exactly once even
     * though it is no longer eligible for a Binder callback.
     */
    private fun finishCancelledAdmission(current: ActiveRun): Boolean {
        val reason = synchronized(activeLock) {
            val lostAuthority = active !== current || current.admissionCancellation != null
            if (!lostAuthority || current.admissionTerminalRecorded) {
                null
            } else {
                current.admissionTerminalRecorded = true
                current.admissionCancellation ?: HarnessCancelReason.CALLER_REQUESTED
            }
        } ?: return false
        runCatching {
            current.recorder?.record(
                AiEvent.Cancelled(
                    current.identity.first,
                    current.identity.second,
                    reason,
                ),
            )
        }
        return true
    }

    private fun emit(expectedRun: ActiveRun, event: AiEvent) {
        if ((event.requestId to event.runGeneration) != expectedRun.identity) return
        val retained = expectedRun.recorder?.let { recorder ->
            runCatching { recorder.record(event) }.isSuccess
        } ?: false
        if (!retained) {
            expectedRun.retentionGate.markFailed()?.let { handle ->
                mainHandler.post { handle.cancel(HarnessCancelReason.BRAIN_DIED) }
            }
        }
        val terminal = event.isTerminal

        if (event is AiEvent.PreviewDelta || event is AiEvent.DescriptionDelta) {
            synchronized(activeLock) {
                if (active !== expectedRun) return@synchronized
                val thresholdReached = expectedRun.ipcEvents.append(event)
                when {
                    thresholdReached && !expectedRun.ipcFlushUrgent -> {
                        cancelVisibleFlushLocked(expectedRun)
                        scheduleVisibleFlushLocked(expectedRun, delayMillis = 0L)
                    }
                    !expectedRun.ipcFlushScheduled -> {
                        scheduleVisibleFlushLocked(
                            expectedRun,
                            delayMillis = IPC_FRAME_INTERVAL_MS,
                        )
                    }
                }
            }
            return
        }

        val scheduleDrain = synchronized(activeLock) {
            if (active !== expectedRun) return@synchronized false
            cancelVisibleFlushLocked(expectedRun)
            val outbound = expectedRun.ipcEvents.drainBefore(event)
            // A terminal event revokes the run before any cross-process callback.
            // Synchronous engine completion and late provider callbacks therefore
            // cannot race a newly-started request back into authority.
            if (terminal) {
                active = null
                cancelTickerLocked(expectedRun)
            }
            enqueueDeliveryLocked(expectedRun, outbound)
        }
        scheduleOutboundDrain(scheduleDrain)
        if (terminal) mainHandler.post { finishDurableRun(event) }
    }

    private fun promoteDurableRun() {
        durableRunPromoted = true
        ensureNotificationChannel()
        val notification = runningNotification("Agent 正在准备任务…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RUNNING_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } else {
            startForeground(RUNNING_NOTIFICATION_ID, notification)
        }
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:sense-agent-run",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun stopDurableRunFromNotification() {
        val current = synchronized(activeLock) {
            active?.also {
                it.admissionCancellation = HarnessCancelReason.CALLER_REQUESTED
            }
        }
        if (current == null) {
            finishDurableRun(null)
            return
        }
        val handle = current.retentionGate.currentHandle()
        if (handle != null) {
            handle.cancel(HarnessCancelReason.CALLER_REQUESTED)
        } else {
            synchronized(activeLock) {
                current.admissionTerminalRecorded = true
            }
            emit(
                current,
                AiEvent.Cancelled(
                    current.identity.first,
                    current.identity.second,
                    HarnessCancelReason.CALLER_REQUESTED,
                ),
            )
        }
    }

    private fun finishDurableRun(event: AiEvent?) {
        if (!durableRunPromoted) return
        durableRunPromoted = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val completedText = when (event) {
            is AiEvent.FinalAnswer -> event.text.lineSequence().firstOrNull().orEmpty().take(120)
            is AiEvent.FinalPatch -> "编辑提案已经准备好"
            is AiEvent.Failed -> "任务结束，可打开 Agent 查看详情"
            is AiEvent.Cancelled, null -> "任务已停止"
            else -> "任务结束"
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ensureNotificationChannel()
            getSystemService(NotificationManager::class.java).notify(
                COMPLETED_NOTIFICATION_ID,
                completedNotification(completedText),
            )
        }
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sense Agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Agent 后台任务、终端和浏览器运行状态"
            },
        )
    }

    private fun runningNotification(status: String): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Sense Agent 正在运行")
            .setContentText(status)
            .setContentIntent(openHubPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止",
                    PendingIntent.getService(
                        this,
                        2,
                        Intent(this, SenseAiBrainService::class.java)
                            .setAction(ACTION_STOP_DURABLE_RUN),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()

    private fun completedNotification(status: String): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Sense Agent")
            .setContentText(status)
            .setContentIntent(openHubPendingIntent())
            .setAutoCancel(true)
            .build()

    private fun openHubPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent().setClassName(packageName, AGENT_HUB_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun recordTrace(expectedRun: ActiveRun, trace: BrainTraceEvent) {
        require((trace.requestId to trace.runGeneration) == expectedRun.identity)
        val recorder = checkNotNull(expectedRun.recorder) {
            "Agent trace arrived before its complete-history recorder"
        }
        when (trace) {
            is BrainTraceEvent.ProviderInput -> recorder.recordProviderInput(
                rawBytes = trace.body.toByteArray(StandardCharsets.UTF_8),
                attributes = mapOf(
                    "attempt" to trace.attempt.toString(),
                    "endpoint" to trace.endpoint,
                ),
            )
            is BrainTraceEvent.ProviderOutput -> recorder.recordProviderOutput(
                rawBytes = trace.bytes,
                contentType = "application/octet-stream",
                attributes = mapOf("attempt" to trace.attempt.toString()),
            )
            is BrainTraceEvent.ProviderOpened -> recorder.recordPrivateEvent(
                eventType = "provider_opened",
                rawBytes = buildString {
                    append("attempt=").append(trace.attempt)
                    append('\n').append("status_code=").append(trace.statusCode)
                    append('\n').append("content_type=").append(trace.contentType.orEmpty())
                }.toByteArray(StandardCharsets.UTF_8),
                attributes = mapOf("attempt" to trace.attempt.toString()),
            )
            is BrainTraceEvent.ProviderCompleted -> {
                recorder.recordPrivateEvent(
                    eventType = "provider_completed",
                    rawBytes = "attempt=${trace.attempt}".toByteArray(StandardCharsets.UTF_8),
                    attributes = mapOf("attempt" to trace.attempt.toString()),
                )
                recorder.flush()
            }
            is BrainTraceEvent.ProviderFailed -> {
                recorder.recordPrivateEvent(
                    eventType = "provider_failed",
                    rawBytes = buildString {
                        append("attempt=").append(trace.attempt)
                        append('\n').append("kind=").append(trace.kind.name)
                        append('\n').append("status_code=").append(trace.statusCode)
                        append('\n').append("message=").append(trace.message)
                    }.toByteArray(StandardCharsets.UTF_8),
                    attributes = mapOf("attempt" to trace.attempt.toString()),
                )
                recorder.flush()
            }
            is BrainTraceEvent.ToolCall -> {
                recorder.recordPrivateEvent(
                    eventType = "tool_assistant_context",
                    rawBytes = buildString {
                        append(trace.privateReasoning)
                        append("\n--- assistant content ---\n")
                        append(trace.assistantContent)
                    }.toByteArray(StandardCharsets.UTF_8),
                    attributes = mapOf(
                        "tool_call_id" to trace.callId,
                        "tool_name" to trace.toolName,
                    ),
                )
                recorder.recordToolCall(
                    toolCallId = trace.callId,
                    toolName = trace.toolName,
                    arguments = trace.arguments,
                )
            }
            is BrainTraceEvent.ToolResult -> recorder.recordToolResult(
                toolCallId = trace.callId,
                toolName = trace.toolName,
                result = trace.content,
                isError = trace.isError,
            )
        }
    }

    /**
     * Runs only on [mainHandler]. Every provider thread first enters the
     * serial delivery queue, so a terminal event can never overtake a frame
     * flush that already drained visible text.
     */
    private fun sendEventsNow(delivery: OutboundDelivery) {
        val current = delivery.run
        try {
            delivery.events.forEach { event ->
                val chunks = if (event is AiEvent.PreviewDelta) {
                    BrainIpcTextChunker.chunk(event.text).map { chunk -> event.copy(text = chunk) }
                } else {
                    listOf(event)
                }
                chunks.forEach { outboundEvent ->
                    current.reply.send(
                        Message.obtain(null, BrainMessageProtocol.EVENT).apply {
                            data = BrainMessageCodec.encodeEvent(outboundEvent)
                        },
                    )
                }
            }
        } catch (_: RemoteException) {
            synchronized(activeLock) {
                cancelTickerLocked(current)
                if (active === current) {
                    active = null
                    cancelVisibleFlushLocked(current)
                }
            }
            outboundDeliveries.removeAll { it.run === current }
            current.ipcEvents.clear()
            current.retentionGate.currentHandle()?.cancel(HarnessCancelReason.BRAIN_DIED)
        }
    }

    /**
     * Installs a timer owned by one exact [ActiveRun].
     *
     * Provider callbacks may terminate an old run on a worker thread while the
     * main thread is starting a new run. Keeping the Runnable on the run object
     * prevents an old terminal path from removing or rescheduling the new
     * run's timer.
     */
    private fun scheduleTicker(current: ActiveRun) {
        lateinit var callback: Runnable
        callback = Runnable {
            val handle = synchronized(activeLock) {
                if (
                    active !== current ||
                    !current.ticker.owns(callback)
                ) {
                    return@Runnable
                }
                current.retentionGate.currentHandle()
            }
            handle?.tick()
            val reschedule = synchronized(activeLock) {
                active === current &&
                    current.ticker.owns(callback) &&
                    current.retentionGate.currentHandle()?.isTerminal != true
            }
            if (reschedule) {
                mainHandler.postDelayed(callback, TICK_INTERVAL_MS)
            } else {
                synchronized(activeLock) {
                    current.ticker.clearIfOwned(callback)
                }
            }
        }
        val installed = synchronized(activeLock) {
            if (active !== current) {
                false
            } else {
                current.ticker.install(callback)
            }
        }
        if (installed) {
            mainHandler.postDelayed(callback, TICK_INTERVAL_MS)
        }
    }

    /**
     * Must be called while [activeLock] is held.
     */
    private fun cancelTickerLocked(current: ActiveRun) {
        current.ticker.clear()?.let(mainHandler::removeCallbacks)
    }

    private fun scheduleVisibleFlushLocked(
        current: ActiveRun,
        delayMillis: Long,
    ) {
        check(!current.ipcFlushScheduled)
        lateinit var callback: Runnable
        callback = Runnable { flushVisibleEvents(current, callback) }
        current.ipcFlushScheduled = true
        current.ipcFlushUrgent = delayMillis == 0L
        current.ipcFlushRunnable = callback
        if (delayMillis == 0L) {
            mainHandler.post(callback)
        } else {
            mainHandler.postDelayed(callback, delayMillis)
        }
    }

    private fun cancelVisibleFlushLocked(current: ActiveRun) {
        current.ipcFlushRunnable?.let(mainHandler::removeCallbacks)
        current.ipcFlushRunnable = null
        current.ipcFlushScheduled = false
        current.ipcFlushUrgent = false
    }

    private fun flushVisibleEvents(
        expectedRun: ActiveRun,
        expectedCallback: Runnable,
    ) {
        val scheduleDrain = synchronized(activeLock) {
            if (
                active !== expectedRun ||
                expectedRun.ipcFlushRunnable !== expectedCallback
            ) {
                return@synchronized false
            }
            expectedRun.ipcFlushRunnable = null
            expectedRun.ipcFlushScheduled = false
            expectedRun.ipcFlushUrgent = false
            enqueueDeliveryLocked(expectedRun, expectedRun.ipcEvents.drain())
        }
        scheduleOutboundDrain(scheduleDrain)
    }

    /**
     * Must be called while [activeLock] is held. This is the ordering boundary
     * shared by the timer and terminal-event paths.
     */
    private fun enqueueDeliveryLocked(
        current: ActiveRun,
        events: List<AiEvent>,
    ): Boolean {
        if (events.isEmpty()) return false
        return outboundDeliveries.enqueue(OutboundDelivery(current, events))
    }

    private fun scheduleOutboundDrain(required: Boolean) {
        if (required) mainHandler.post(deliverOutboundEvents)
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                BrainMessageProtocol.START -> handleStart(message)
                BrainMessageProtocol.CANCEL -> handleCancel(message)
                else -> super.handleMessage(message)
            }
        }
    }

    private class ActiveRun(
        val identity: Pair<String, Long>,
        val reply: Messenger,
        @Volatile var recorder: AgentRunRecorder? = null,
        val retentionGate: BrainRetentionFailureGate = BrainRetentionFailureGate(),
        val ticker: BrainRunTickerSlot = BrainRunTickerSlot(),
        val ipcEvents: BrainIpcEventBatcher = BrainIpcEventBatcher(),
        var ipcFlushScheduled: Boolean = false,
        var ipcFlushUrgent: Boolean = false,
        var ipcFlushRunnable: Runnable? = null,
        var admissionCancellation: HarnessCancelReason? = null,
        var admissionTerminalRecorded: Boolean = false,
    )

    private data class StartAdmission(
        val run: ActiveRun,
        val request: io.github.ethanbird.senseime.ai.protocol.HarnessRequestV1,
    )

    private data class OutboundDelivery(
        val run: ActiveRun,
        val events: List<AiEvent>,
    )

    companion object {
        const val ACTION_PROMOTE_DURABLE_RUN =
            "io.github.ethanbird.senseime.action.PROMOTE_AGENT_RUN"
        const val ACTION_STOP_DURABLE_RUN =
            "io.github.ethanbird.senseime.action.STOP_AGENT_RUN"
        private const val AGENT_HISTORY_DIRECTORY = "agent-history"
        private const val AGENT_HUB_ACTIVITY =
            "io.github.ethanbird.senseime.AgentHubActivity"
        private const val NOTIFICATION_CHANNEL_ID = "sense-agent-runtime"
        private const val RUNNING_NOTIFICATION_ID = 2407
        private const val COMPLETED_NOTIFICATION_ID = 2408
        private const val MAX_WAKE_LOCK_MS = 10 * 60 * 1_000L
        private const val TICK_INTERVAL_MS = 100L
        private const val IPC_FRAME_INTERVAL_MS = 16L
        private val PROCESS_ADMISSION_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sense-brain-admission").apply { isDaemon = true }
        }
    }
}
