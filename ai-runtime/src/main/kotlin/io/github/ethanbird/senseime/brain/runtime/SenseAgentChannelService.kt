package io.github.ethanbird.senseime.brain.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.content.IntentFilter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SenseAgentChannelService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bootstrap: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-channel-bootstrap").apply { isDaemon = true }
    }
    private var coordinator: AgentChannelCoordinator? = null
    private var bootstrapGeneration = 0L
    private var lastConfigRevision = 0L
    private val statusQueryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATUS_QUERY) return
            coordinator?.reportStatus() ?: SenseAgentChannelRuntime.publish(
                applicationContext,
                SenseAgentChannelStatus(configRevision = lastConfigRevision),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接远程 Agent 信道…"))
        SenseAgentChannelRuntime.publish(applicationContext,
            SenseAgentChannelStatus(phase = SenseAgentChannelPhase.STARTING),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                statusQueryReceiver,
                IntentFilter(ACTION_STATUS_QUERY),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusQueryReceiver, IntentFilter(ACTION_STATUS_QUERY))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopChannels()
            ACTION_PAUSE -> pauseChannels()
            ACTION_START, ACTION_RELOAD -> loadChannels()
            ACTION_STATUS_QUERY -> coordinator?.reportStatus() ?: SenseAgentChannelRuntime.publish(
                applicationContext,
                SenseAgentChannelStatus(configRevision = lastConfigRevision),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bootstrapGeneration++
        coordinator?.let { lastConfigRevision = it.configRevision() }
        coordinator?.close()
        coordinator = null
        bootstrap.shutdownNow()
        runCatching { unregisterReceiver(statusQueryReceiver) }
        SenseAgentChannelRuntime.publish(
            applicationContext,
            SenseAgentChannelStatus(configRevision = lastConfigRevision),
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadChannels() {
        val generation = ++bootstrapGeneration
        coordinator?.close()
        coordinator = null
        SenseAgentChannelRuntime.publish(applicationContext,
            SenseAgentChannelStatus(phase = SenseAgentChannelPhase.STARTING),
        )
        bootstrap.execute {
            val result = AgentChannelSettingsStore(applicationContext).loadRuntimeConfig()
            mainHandler.post {
                if (generation != bootstrapGeneration) return@post
                result.fold(
                    onSuccess = { config ->
                        lastConfigRevision = config.settings.revision
                        if (!config.settings.shouldRun) {
                            stopChannels(config.settings.revision)
                            return@fold
                        }
                        var candidate: AgentChannelCoordinator? = null
                        runCatching {
                            AgentChannelCoordinator(
                                context = applicationContext,
                                config = config,
                                notificationChanged = ::updateNotification,
                            ).also {
                                candidate = it
                                it.start()
                            }
                        }.onSuccess { started ->
                            coordinator = started
                        }.onFailure { failure ->
                            candidate?.close()
                            publishStartupError(failure)
                        }
                    },
                    onFailure = { failure ->
                        publishStartupError(failure)
                    },
                )
            }
        }
    }

    private fun publishStartupError(failure: Throwable) {
        val detail = failure.message?.take(180).orEmpty().ifBlank {
            failure::class.java.simpleName
        }
        SenseAgentChannelRuntime.publish(
            applicationContext,
            SenseAgentChannelStatus(
                phase = SenseAgentChannelPhase.ERROR,
                detail = detail,
            ),
        )
        updateNotification("远程 Agent 信道启动失败：$detail")
    }

    private fun stopChannels(configRevision: Long = 0L) {
        if (configRevision > 0L) lastConfigRevision = configRevision
        coordinator?.let { lastConfigRevision = it.configRevision() }
        bootstrapGeneration++
        coordinator?.close()
        coordinator = null
        SenseAgentChannelRuntime.publish(
            applicationContext,
            SenseAgentChannelStatus(configRevision = lastConfigRevision),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseChannels() {
        val generation = ++bootstrapGeneration
        bootstrap.execute {
            val result = AgentChannelSettingsStore(applicationContext).setPaused(true)
            mainHandler.post {
                if (generation != bootstrapGeneration) return@post
                result.fold(
                    onSuccess = { next -> stopChannels(next.revision) },
                    onFailure = ::publishStartupError,
                )
            }
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sense Agent 远程信道",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持 Telegram 与飞书长连接，并管理后台 Agent 任务"
                setShowBadge(false)
            },
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent().setClassName(packageName, SETTINGS_ACTIVITY)
            .putExtra(SETTINGS_SECTION_EXTRA, "CHANNELS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val stopIntent = Intent(this, SenseAgentChannelService::class.java).setAction(ACTION_PAUSE)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Sense Agent 信道")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "暂停信道",
                    PendingIntent.getService(
                        this,
                        1,
                        stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ethanbird.senseime.action.START_AGENT_CHANNELS"
        const val ACTION_RELOAD = "io.github.ethanbird.senseime.action.RELOAD_AGENT_CHANNELS"
        const val ACTION_STOP = "io.github.ethanbird.senseime.action.STOP_AGENT_CHANNELS"
        const val ACTION_PAUSE = "io.github.ethanbird.senseime.action.PAUSE_AGENT_CHANNELS"
        const val ACTION_STATUS_QUERY =
            "io.github.ethanbird.senseime.action.QUERY_AGENT_CHANNEL_STATUS"
        private const val NOTIFICATION_CHANNEL_ID = "sense-agent-channels"
        private const val NOTIFICATION_ID = 2411
        private const val SETTINGS_ACTIVITY = "io.github.ethanbird.senseime.SettingsActivity"
        private const val SETTINGS_SECTION_EXTRA = "io.github.ethanbird.senseime.extra.INITIAL_SECTION"
    }
}

internal class AgentChannelCoordinator(
    private val context: Context,
    config: AgentChannelRuntimeConfig,
    private val notificationChanged: (String) -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsStore = AgentChannelSettingsStore(context)
    private val journal = AgentChannelJournal(context)
    private val runtime = SenseAgentHubRuntime.get(context)
    private val registry = AgentControlTargetRegistry(listOf(LocalSenseAgentTarget))
    private val adapters = linkedMapOf<AgentChannelType, AgentChannelAdapter>()
    private val connections = linkedMapOf<AgentChannelType, AgentChannelConnectionState>()
    private val configuredChannels: Set<AgentChannelType>
    private var deliveryAvailableChannels: Set<AgentChannelType> = emptySet()
    private val terminalErrorChannels = mutableSetOf<AgentChannelType>()
    private var settings = config.settings
    private var subscription: AutoCloseable? = null
    private var active: ActiveDelivery? = null
    private var startingPromptKey: String? = null
    private val controlDeliveries = linkedMapOf<String, ControlDelivery>()
    private val retryPolicy = AgentChannelRetryPolicy()
    private var closed = false
    private var settingsRefreshRetryScheduled = false

    init {
        val state = journal.snapshot()
        config.telegramBotToken?.takeIf { config.settings.telegram.enabled }?.let { token ->
            adapters[AgentChannelType.TELEGRAM] = TelegramAgentChannelAdapter(
                token = token,
                initialOffset = state.telegramOffset,
                advanceOffset = journal::advanceTelegramOffset,
            )
        }
        config.feishuAppSecret?.takeIf { config.settings.feishu.enabled }?.let { secret ->
            adapters[AgentChannelType.FEISHU] = FeishuAgentChannelAdapter(
                appId = config.settings.feishu.appId,
                appSecret = secret,
                domain = config.settings.feishu.domain,
            )
        }
        configuredChannels = adapters.keys.toSet()
        deliveryAvailableChannels = configuredChannels
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        subscription = runtime.observe(AgentHubObserver(::onProjection))
        adapters.values.toList().forEach { adapter ->
            runCatching {
                adapter.start(
                    inbound = AgentChannelInboundListener { inbound ->
                        val admission = runCatching { journal.admit(inbound) }
                            .getOrDefault(AgentChannelAdmission.RETRY_LATER)
                        if (admission == AgentChannelAdmission.ADMITTED) {
                            mainHandler.post {
                                drainInterrupts()
                                drainQueue()
                            }
                        }
                        admission
                    },
                    stateChanged = { state -> mainHandler.post { onConnectionState(state) } },
                )
            }.onFailure { failure ->
                runCatching { adapter.close() }
                onConnectionState(
                    AgentChannelConnectionState(
                        adapter.type,
                        AgentChannelConnectionPhase.ERROR,
                        failure.message?.take(180).orEmpty(),
                    ),
                )
            }
        }
        journal.consumeRecoveryNotice()?.let(notificationChanged)
        recoverAndDrain()
        publishStatus()
    }

    private fun dispatchInbound(inbound: AgentChannelInbound) {
        if (closed) return
        if (!refreshSettingsForAccess(inbound.source.channel)) return
        if (!AgentChannelRuntimeAccessPolicy.isDispatchEnabled(settings, inbound.source.channel)) {
            terminallyFail(
                inbound,
                "Channel was disabled or paused before dispatch",
            )
            mainHandler.post(::drainQueue)
            return
        }
        val command = AgentChannelCommandParser.parse(inbound.text)
        if (command !is AgentChannelCommand.Prompt && inbound.source.eventKey in controlDeliveries) {
            return
        }
        when (access(inbound.source, command)) {
            AgentChannelAccessDecision.REJECT -> {
                journal.markDone(inbound.source.eventKey)
                mainHandler.post(::drainQueue)
                return
            }
            AgentChannelAccessDecision.ACCEPT_PAIRING -> {
                val pair = command as AgentChannelCommand.Pair
                settingsStore.bind(
                    source = inbound.source,
                    expectedPairingCode = pair.code,
                    expectedPairingGeneration = pairingGeneration(inbound.source.channel),
                )
                    .onSuccess { next ->
                        settings = next
                        publishStatus()
                        sendAndComplete(inbound, "配对完成。现在可以直接向 Sense Agent 发送消息。")
                    }
                    .onFailure {
                        settingsStore.load().getOrNull()?.settings?.let { latest -> settings = latest }
                        sendAndComplete(inbound, "配对码已经更新，请使用设置页显示的新配对码。")
                    }
                return
            }
            AgentChannelAccessDecision.ACCEPT -> Unit
        }
        when (command) {
            AgentChannelCommand.Stop -> {
                val stopped = runtime.stop()
                sendAndComplete(inbound, if (stopped) "已停止当前 Agent 任务。" else "当前没有运行中的 Agent 任务。")
            }
            AgentChannelCommand.NewConversation -> {
                val created = runtime.clearConversation()
                sendAndComplete(inbound, if (created) "已开始新会话。" else "请先 /stop，再开始新会话。")
            }
            AgentChannelCommand.Status -> sendAndComplete(inbound, statusText(runtime.currentProjection()))
            AgentChannelCommand.Help -> sendAndComplete(inbound, helpText())
            AgentChannelCommand.ListTargets -> sendAndComplete(inbound, targetListText(inbound.source))
            is AgentChannelCommand.SelectTarget -> selectTarget(inbound, command.targetId)
            is AgentChannelCommand.Pair -> sendAndComplete(inbound, "当前信道已经配对。")
            is AgentChannelCommand.Prompt -> startPrompt(inbound)
        }
    }

    private fun refreshSettingsForAccess(type: AgentChannelType): Boolean {
        val latest = settingsStore.loadSettings().getOrElse {
            if (!settingsRefreshRetryScheduled) {
                settingsRefreshRetryScheduled = true
                mainHandler.postDelayed(
                    {
                        settingsRefreshRetryScheduled = false
                        if (!closed) {
                            drainInterrupts()
                            drainQueue()
                        }
                    },
                    retryPolicy.delayMs(1),
                )
            }
            return false
        }
        settingsRefreshRetryScheduled = false
        if (!AgentChannelPairingGeneration.isCurrent(settings, latest, type)) {
            notificationChanged("远程信道配对状态已更新")
        }
        // The settings lock read is the access-decision linearization point. Updating the complete
        // snapshot also observes a same-generation bind performed by this coordinator.
        settings = latest
        return true
    }

    private fun access(
        source: AgentChannelSource,
        command: AgentChannelCommand,
    ): AgentChannelAccessDecision {
        val endpoint = when (source.channel) {
            AgentChannelType.TELEGRAM -> settings.telegram
            AgentChannelType.FEISHU -> settings.feishu
        }
        return when (endpoint) {
            is TelegramAgentChannelSettings -> AgentChannelAccessPolicy.decide(
                source,
                command,
                endpoint.pairingCode,
                endpoint.boundPeerId,
                endpoint.boundChatId,
            )
            is FeishuAgentChannelSettings -> AgentChannelAccessPolicy.decide(
                source,
                command,
                endpoint.pairingCode,
                endpoint.boundPeerId,
                endpoint.boundChatId,
            )
            else -> AgentChannelAccessDecision.REJECT
        }
    }

    private fun pairingGeneration(type: AgentChannelType): Long = when (type) {
        AgentChannelType.TELEGRAM -> settings.telegram.pairingGeneration
        AgentChannelType.FEISHU -> settings.feishu.pairingGeneration
    }

    private fun selectTarget(inbound: AgentChannelInbound, targetId: String) {
        if (registry.find(targetId) == null) {
            sendAndComplete(inbound, "未找到 Agent：$targetId\n${targetListText(inbound.source)}")
            return
        }
        journal.selectTarget(inbound.source.sessionKey, targetId)
        sendAndComplete(inbound, "当前会话已切换到 Agent：$targetId")
    }

    private fun recoverAndDrain() {
        if (startingPromptKey != null) return
        drainUnavailableBlockers()
        val projection = runtime.currentProjection()
        if (!projection.loaded) return
        val state = journal.snapshot()
        val activeKey = state.activeEventKey
        val activeInbound = activeKey?.let { key -> state.pending.firstOrNull { it.source.eventKey == key } }
        val identity = state.activeRunIdentity
        val current = active
        when {
            current != null && AgentChannelRunMatcher.isRunning(current.identity, projection) -> {
                ensureDraft(current, projection)
                drainInterrupts()
            }
            current != null && !projection.running -> finishActive(projection)
            activeInbound != null && identity != null -> when (
                AgentChannelRunRecovery.decide(
                    identity,
                    state.activeRunPhase ?: AgentChannelRunPhase.ACTIVE,
                    projection,
                )
            ) {
                AgentChannelRunRecoveryAction.ATTACH_RUNNING,
                AgentChannelRunRecoveryAction.ATTACH_TERMINAL,
                -> attachRecovered(activeInbound, identity, state, projection)
                AgentChannelRunRecoveryAction.RESUME_PREPARED ->
                    resumePrepared(activeInbound, identity)
                AgentChannelRunRecoveryAction.COMPLETE_WITHOUT_REPLAY -> {
                    // ACTIVE means sendPrepared crossed the durable start boundary. Missing Hub
                    // data is completed explicitly; replaying could execute the prompt twice.
                    sendAndComplete(
                        activeInbound,
                        "上次远程任务已经启动，但当前会话未保留对应结果；该请求没有重复执行。",
                    )
                }
                AgentChannelRunRecoveryAction.WAIT_FOR_OTHER_RUN -> drainInterrupts()
            }
            activeInbound != null && identity == null -> {
                // v1 journal recovery: complete with an explicit state result instead of attaching
                // an unrelated Hub run that may have been started by another frontend.
                sendAndComplete(activeInbound, "上次远程任务缺少运行标识，请重新发送该请求。")
            }
            current == null && !projection.running -> drainQueue()
            else -> drainInterrupts()
        }
    }

    private fun attachRecovered(
        inbound: AgentChannelInbound,
        identity: AgentChannelRunIdentity,
        state: AgentChannelJournalState,
        projection: AgentHubProjection,
    ) {
        runCatching { journal.markActive(identity) }
        val delivery = ActiveDelivery(
            inbound = inbound,
            identity = identity,
            remoteMessage = state.activeRemoteMessageId
                ?.let { CompletableFuture.completedFuture(it) },
            finalChunkRemoteIds = state.activeFinalChunkRemoteMessageIds.toMutableList(),
        )
        active = delivery
        if (projection.running) {
            ensureDraft(delivery, projection)
            drainInterrupts()
        } else {
            finishActive(projection)
        }
    }

    private fun resumePrepared(
        inbound: AgentChannelInbound,
        identity: AgentChannelRunIdentity,
    ) {
        startingPromptKey = inbound.source.eventKey
        val prepared = AgentHubPreparedRun(
            requestId = identity.requestId,
            generation = identity.generation,
            userMessage = inbound.text.trim(),
            userCreatedAtEpochMs = identity.userCreatedAtEpochMs,
        )
        val started = runtime.sendPrepared(prepared)
        if (!started) {
            startingPromptKey = null
            sendAndComplete(
                inbound,
                "远程任务准备记录已被更新的本机会话取代；该请求没有重复执行。",
            )
            return
        }
        val projection = runtime.currentProjection()
        runCatching { journal.markActive(identity) }
        active = ActiveDelivery(inbound, identity)
        startingPromptKey = null
        ensureDraft(checkNotNull(active), projection)
        publishStatus()
    }

    private fun drainQueue() {
        if (closed || startingPromptKey != null) return
        drainUnavailableBlockers()
        val projection = runtime.currentProjection()
        if (!projection.loaded) return
        val state = journal.snapshot()
        if (active != null || state.activeEventKey != null) {
            drainInterrupts()
            return
        }
        val next = AgentChannelDispatchQueue.head(state) ?: return
        val command = AgentChannelCommandParser.parse(next.text)
        if (projection.running && command is AgentChannelCommand.Prompt) return
        dispatchInbound(next)
    }

    private fun drainInterrupts() {
        if (closed) return
        val projection = runtime.currentProjection()
        if (!AgentChannelInterruptPolicy.canScan(active != null, projection.running)) return
        val next = AgentChannelDispatchQueue.firstInterrupt(journal.snapshot()) ?: return
        dispatchInbound(next)
    }

    private fun startPrompt(next: AgentChannelInbound) {
        if (closed || active != null || startingPromptKey != null) return
        val projectionBefore = runtime.currentProjection()
        if (!projectionBefore.loaded || projectionBefore.running) return
        val state = journal.snapshot()
        if (AgentChannelDispatchQueue.head(state)?.source?.eventKey != next.source.eventKey) {
            return
        }
        val targetId = state.targetBySession[next.source.sessionKey] ?: LocalSenseAgentTarget.id
        if (targetId != LocalSenseAgentTarget.id) {
            sendAndComplete(next, "当前版本实际连接的 Agent 目标只有 sense；当前选择为 $targetId。")
            return
        }
        startingPromptKey = next.source.eventKey
        val prepared = runtime.prepareRun(next.text)
        if (prepared == null) {
            startingPromptKey = null
            return
        }
        val identity = AgentChannelRunIdentity(
            eventKey = next.source.eventKey,
            requestId = prepared.requestId,
            generation = prepared.generation,
            userCreatedAtEpochMs = prepared.userCreatedAtEpochMs,
        )
        val preparedPersisted = runCatching { journal.markPrepared(identity) }
        if (preparedPersisted.isFailure) {
            sendAndComplete(next, "远程任务准备记录保存失败，请重新发送该请求。")
            startingPromptKey = null
            return
        }
        if (!runtime.sendPrepared(prepared)) {
            startingPromptKey = null
            recoverAndDrain()
            return
        }
        val projection = runtime.currentProjection()
        runCatching { journal.markActive(identity) }
        active = ActiveDelivery(next, identity)
        startingPromptKey = null
        ensureDraft(checkNotNull(active), projection)
        publishStatus()
    }

    private fun onProjection(projection: AgentHubProjection) {
        if (closed || !projection.loaded) return
        if (active == null) {
            recoverAndDrain()
            return
        }
        val current = checkNotNull(active)
        if (projection.running && !AgentChannelRunMatcher.isRunning(current.identity, projection)) {
            drainInterrupts()
            publishStatus()
            return
        }
        if (!projection.running) {
            finishActive(projection)
            publishStatus()
            return
        }
        ensureDraft(current, projection)
        val text = projectionText(projection, current.identity, terminal = false)
        if (current.gate.shouldSend(text, System.currentTimeMillis(), terminal = false)) {
            editDraft(current, text)
        }
        publishStatus()
    }

    private fun ensureDraft(delivery: ActiveDelivery, projection: AgentHubProjection) {
        if (delivery.remoteMessage != null) return
        val adapter = adapters[delivery.inbound.source.channel] ?: return
        val draftText = projectionText(projection, delivery.identity, terminal = false)
        val future = agentChannelFuture {
            adapter.sendText(
                delivery.inbound.source,
                draftText,
            )
        }.thenApply { remoteId ->
            require(remoteId.isNotBlank())
            delivery.lastAppliedText = draftText
            remoteId
        }
        delivery.remoteMessage = future
        future.whenComplete { remoteId, failure ->
            mainHandler.post {
                if (active !== delivery) return@post
                if (!AgentChannelDeliveryOutcome.succeeded(remoteId, failure)) {
                    if (publishFatalDeliveryError(delivery.inbound.source.channel, failure)) {
                        return@post
                    }
                    delivery.remoteMessage = null
                    delivery.draftFailures++
                    mainHandler.postDelayed(
                        { if (active === delivery && !delivery.finalizing) ensureDraft(delivery, runtime.currentProjection()) },
                        retryPolicy.delayMs(delivery.draftFailures),
                    )
                    return@post
                }
                delivery.draftFailures = 0
                journal.recordActiveRemoteMessage(delivery.inbound.source.eventKey, remoteId)
                if (!delivery.finalizing) pumpDraftEdit(delivery)
            }
        }
    }

    private fun editDraft(delivery: ActiveDelivery, text: String) {
        if (delivery.finalizing) return
        if (!AgentChannelRemoteEditDecision.requiresEdit(delivery.lastAppliedText, text)) return
        delivery.latestText.offer(text)
        pumpDraftEdit(delivery)
    }

    private fun pumpDraftEdit(delivery: ActiveDelivery) {
        if (delivery.finalizing || delivery.editInFlight) return
        val adapter = adapters[delivery.inbound.source.channel] ?: return
        val future = delivery.remoteMessage
        if (future == null || !future.isDone) {
            return
        }
        val remoteId = runCatching { future.getNow(null) }.getOrNull()
        if (remoteId.isNullOrBlank()) {
            return
        } else {
            val text = delivery.latestText.take() ?: return
            if (!AgentChannelRemoteEditDecision.requiresEdit(delivery.lastAppliedText, text)) {
                pumpDraftEdit(delivery)
                return
            }
            delivery.editInFlight = true
            val edit = agentChannelFuture {
                adapter.editText(delivery.inbound.source, remoteId, text)
            }.thenApply {
                delivery.lastAppliedText = text
                Unit
            }
            delivery.editFuture = edit
            edit.whenComplete { _, failure ->
                mainHandler.post {
                    if (active !== delivery) return@post
                    delivery.editInFlight = false
                    if (failure != null) {
                        if (publishFatalDeliveryError(delivery.inbound.source.channel, failure)) {
                            return@post
                        }
                        delivery.latestText.restoreIfEmpty(text)
                        delivery.editFailures++
                        mainHandler.postDelayed(
                            {
                                if (active === delivery && !delivery.finalizing) {
                                    pumpDraftEdit(delivery)
                                }
                            },
                            retryPolicy.delayMs(delivery.editFailures),
                        )
                        return@post
                    }
                    delivery.editFailures = 0
                    pumpDraftEdit(delivery)
                }
            }
        }
    }

    private fun finishActive(projection: AgentHubProjection) {
        val delivery = active ?: return
        if (delivery.finalizing) return
        delivery.finalizing = true
        delivery.finalText = projectionText(projection, delivery.identity, terminal = true)
        delivery.finalChunks = AgentChannelTextChunks.split(
            delivery.inbound.source.channel,
            delivery.finalText,
        )
        attemptFinalDelivery(delivery)
    }

    private fun attemptFinalDelivery(delivery: ActiveDelivery) {
        if (closed || active !== delivery) return
        val adapter = adapters[delivery.inbound.source.channel] ?: run {
            terminallyFail(delivery.inbound, "Channel became unavailable before final delivery")
            mainHandler.post(::drainQueue)
            return
        }
        val chunks = delivery.finalChunks
        val chunkIndex = AgentChannelFinalChunkProgress.nextIndex(
            delivery.finalChunkRemoteIds,
            chunks.size,
        )
        if (chunkIndex == chunks.size) {
            journal.markDone(delivery.inbound.source.eventKey)
            active = null
            publishStatus()
            mainHandler.post(::drainQueue)
            return
        }
        val chunkText = chunks[chunkIndex]
        val existing = delivery.remoteMessage
        val finalFuture: CompletableFuture<String> = if (chunkIndex > 0) {
            agentChannelFuture {
                adapter.sendText(delivery.inbound.source, chunkText)
            }.thenApply { remoteId ->
                require(remoteId.isNotBlank())
                remoteId
            }
        } else if (existing == null) {
            agentChannelFuture {
                adapter.sendText(delivery.inbound.source, chunkText)
            }.thenApply { remoteId ->
                require(remoteId.isNotBlank())
                delivery.lastAppliedText = chunkText
                remoteId
            }
        } else {
            existing.thenCompose { remoteId ->
                require(remoteId.isNotBlank())
                val previousEdit = delivery.editFuture
                    ?.handle { _, _ -> Unit }
                    ?: CompletableFuture.completedFuture(Unit)
                previousEdit.thenCompose {
                    if (!AgentChannelRemoteEditDecision.requiresEdit(
                            delivery.lastAppliedText,
                            chunkText,
                        )
                    ) {
                        CompletableFuture.completedFuture(Unit)
                    } else {
                        agentChannelFuture {
                            adapter.editText(delivery.inbound.source, remoteId, chunkText)
                        }.thenApply {
                            delivery.lastAppliedText = chunkText
                            Unit
                        }
                    }
                }.thenApply { remoteId }
            }
        }
        finalFuture.whenComplete { remoteId, failure ->
            mainHandler.post {
                if (closed || active !== delivery) return@post
                if (AgentChannelDeliveryOutcome.succeeded(remoteId, failure)) {
                    if (chunkIndex == 0) {
                        journal.recordActiveRemoteMessage(
                            delivery.inbound.source.eventKey,
                            remoteId,
                        )
                    }
                    journal.recordFinalChunk(
                        eventKey = delivery.inbound.source.eventKey,
                        chunkIndex = chunkIndex,
                        remoteMessageId = remoteId,
                    )
                    if (delivery.finalChunkRemoteIds.size == chunkIndex) {
                        delivery.finalChunkRemoteIds += remoteId
                    }
                    delivery.finalFailures = 0
                    attemptFinalDelivery(delivery)
                } else {
                    if (publishFatalDeliveryError(delivery.inbound.source.channel, failure)) {
                        return@post
                    }
                    val existingId = existing?.takeIf { chunkIndex == 0 && it.isDone }
                        ?.let { runCatching { it.getNow(null) }.getOrNull() }
                    if (
                        chunkIndex == 0 &&
                        (existing == null || existing.isCompletedExceptionally || existingId.isNullOrBlank())
                    ) {
                        delivery.remoteMessage = null
                    }
                    delivery.finalFailures++
                    mainHandler.postDelayed(
                        { attemptFinalDelivery(delivery) },
                        retryPolicy.delayMs(delivery.finalFailures),
                    )
                }
            }
        }
    }

    private fun projectionText(
        projection: AgentHubProjection,
        identity: AgentChannelRunIdentity,
        terminal: Boolean,
    ): String {
        if (terminal) {
            AgentChannelRunMatcher.finalAssistant(identity, projection)?.let {
                return AgentChannelUnicode.truncate(it, 18_000)
            }
            if (!AgentChannelRunMatcher.hasUserMessage(identity, projection)) {
                return "Agent 任务已结束，当前会话中没有对应运行结果。"
            }
        }
        val preview = projection.preview.trim()
        val activeTool = projection.tools.lastOrNull { it.state == AgentHubToolState.RUNNING }
        return buildString {
            if (preview.isNotEmpty()) append(preview)
            if (activeTool != null) {
                if (isNotEmpty()) append("\n\n")
                append("工具：").append(activeTool.title)
            }
            if (isEmpty() || preview.isEmpty()) {
                if (isNotEmpty()) append('\n')
                append(projection.status.ifBlank { "Sense Agent 正在处理…" })
            }
        }.let { AgentChannelUnicode.truncate(it, 18_000) }
    }

    private fun statusText(projection: AgentHubProjection): String = buildString {
        append(if (projection.running) "Sense Agent 正在运行" else "Sense Agent 当前空闲")
        append("\n状态：").append(projection.status)
        if (projection.tools.isNotEmpty()) append("\n工具步骤：").append(projection.tools.size)
        append("\n排队消息：").append(journal.snapshot().pending.size - if (active == null) 0 else 1)
    }

    private fun targetListText(source: AgentChannelSource): String {
        val state = journal.snapshot()
        val selected = state.targetBySession[source.sessionKey] ?: LocalSenseAgentTarget.id
        return buildString {
            appendLine("可用 Agent：")
            registry.list().forEach { target ->
                append(if (target.id == selected) "• ✓ " else "• ")
                append(target.id).append(" — ").appendLine(target.displayName)
            }
            append("使用 /agent use <id> 切换。")
        }
    }

    private fun helpText(): String = """
        直接发送消息即可远程调用 Sense Agent。
        /status 查看运行状态
        /stop 立即停止当前任务
        /new 开始新会话
        /agents 查看 Agent 目标
        /agent use sense 切换目标
    """.trimIndent()

    private fun sendAndComplete(inbound: AgentChannelInbound, text: String) {
        val key = inbound.source.eventKey
        if (controlDeliveries.putIfAbsent(key, ControlDelivery(inbound, text)) != null) return
        attemptControlDelivery(checkNotNull(controlDeliveries[key]))
    }

    private fun attemptControlDelivery(delivery: ControlDelivery) {
        if (closed || controlDeliveries[delivery.inbound.source.eventKey] !== delivery) return
        val adapter = adapters[delivery.inbound.source.channel]
        if (adapter == null) {
            terminallyFail(delivery.inbound, "Channel became unavailable before control reply")
            mainHandler.post(::drainQueue)
            return
        }
        val future = adapter.let {
            agentChannelFuture { it.sendText(delivery.inbound.source, delivery.text) }
        }
        future.whenComplete { remoteId, failure ->
            mainHandler.post {
                val key = delivery.inbound.source.eventKey
                if (closed || controlDeliveries[key] !== delivery) return@post
                if (AgentChannelDeliveryOutcome.succeeded(remoteId, failure)) {
                    controlDeliveries.remove(key)
                    journal.markDone(key)
                    publishStatus()
                    mainHandler.post(::drainQueue)
                } else {
                    if (publishFatalDeliveryError(delivery.inbound.source.channel, failure)) {
                        return@post
                    }
                    delivery.failures++
                    mainHandler.postDelayed(
                        { attemptControlDelivery(delivery) },
                        retryPolicy.delayMs(delivery.failures),
                    )
                }
            }
        }
    }

    private fun onConnectionState(state: AgentChannelConnectionState) {
        if (closed) return
        if (!AgentChannelTerminalConnectionFence.accepts(
                state.channel in terminalErrorChannels,
                state.phase,
            )
        ) {
            return
        }
        if (state.phase == AgentChannelConnectionPhase.ERROR) {
            terminalErrorChannels += state.channel
        }
        connections[state.channel] = state
        val nextAvailable = AgentChannelDeliveryAvailability.afterConnectionState(
            deliveryAvailableChannels,
            state,
        )
        if (nextAvailable != deliveryAvailableChannels) {
            deliveryAvailableChannels = nextAvailable
            adapters.remove(state.channel)?.let { adapter -> runCatching { adapter.close() } }
            drainUnavailableBlockers()
            if (!runtime.currentProjection().running) mainHandler.post(::drainQueue)
        }
        val connected = connections.values.count { it.phase == AgentChannelConnectionPhase.CONNECTED }
        val aggregate = AgentChannelStatusAggregation.resolve(
            connections.values,
            configuredChannels.size,
        )
        notificationChanged(
            when (aggregate.phase) {
                SenseAgentChannelPhase.RUNNING -> "已连接 $connected/${configuredChannels.size} 个信道"
                SenseAgentChannelPhase.ERROR -> "远程信道认证或连接失败：${aggregate.detail}"
                SenseAgentChannelPhase.DEGRADED ->
                    "远程信道部分可用（$connected/${configuredChannels.size}）：${aggregate.detail}"
                SenseAgentChannelPhase.STARTING ->
                    "远程信道正在连接（$connected/${configuredChannels.size}）：${aggregate.detail}"
                SenseAgentChannelPhase.OFF -> "远程信道已关闭"
            },
        )
        publishStatus()
    }

    private fun drainUnavailableBlockers() {
        while (!closed) {
            val blocked = AgentChannelUnavailableDeliveryPolicy.blockingEvent(
                journal.snapshot(),
                deliveryAvailableChannels,
            ) ?: return
            terminallyFail(
                blocked,
                "Channel is disabled or unavailable in the active configuration",
            )
        }
    }

    private fun terminallyFail(inbound: AgentChannelInbound, reason: String) {
        val key = inbound.source.eventKey
        journal.markFailed(key, reason)
        if (active?.inbound?.source?.eventKey == key) active = null
        controlDeliveries.remove(key)
        notificationChanged("远程信道消息已终止：${inbound.source.channel.name.lowercase()}")
        publishStatus()
    }

    private fun publishFatalDeliveryError(
        channel: AgentChannelType,
        failure: Throwable?,
    ): Boolean {
        val value = failure ?: return false
        if (!AgentChannelFailureClassifier.isFatalAuthentication(channel, value)) return false
        val detail = generateSequence(value) { it.cause }
            .mapNotNull(Throwable::message)
            .firstOrNull(String::isNotBlank)
            ?.take(180)
            .orEmpty()
        onConnectionState(
            AgentChannelConnectionState(
                channel = channel,
                phase = AgentChannelConnectionPhase.ERROR,
                detail = detail,
            ),
        )
        return true
    }

    fun reportStatus() = publishStatus()

    fun configRevision(): Long = settings.revision

    private fun publishStatus() {
        if (closed) return
        val pending = journal.snapshot().pending.size
        val aggregate = AgentChannelStatusAggregation.resolve(
            connections.values,
            configuredChannels.size,
        )
        SenseAgentChannelRuntime.publish(context,
            SenseAgentChannelStatus(
                phase = aggregate.phase,
                connections = connections.toMap(),
                queuedMessages = pending,
                activeSessionKey = active?.inbound?.source?.sessionKey,
                detail = aggregate.detail,
                configRevision = settings.revision,
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        subscription = null
        adapters.values.forEach { adapter -> runCatching { adapter.close() } }
        adapters.clear()
        controlDeliveries.clear()
    }

    private data class ActiveDelivery(
        val inbound: AgentChannelInbound,
        val identity: AgentChannelRunIdentity,
        val gate: AgentChannelStreamUpdateGate = AgentChannelStreamUpdateGate(),
        val latestText: AgentChannelLatestTextBuffer = AgentChannelLatestTextBuffer(),
        var remoteMessage: CompletableFuture<String>? = null,
        var editInFlight: Boolean = false,
        var editFuture: CompletableFuture<Unit>? = null,
        var editFailures: Int = 0,
        var draftFailures: Int = 0,
        var finalizing: Boolean = false,
        var finalText: String = "",
        var finalFailures: Int = 0,
        @Volatile var lastAppliedText: String? = null,
        var finalChunks: List<String> = emptyList(),
        val finalChunkRemoteIds: MutableList<String> = mutableListOf(),
    )

    private data class ControlDelivery(
        val inbound: AgentChannelInbound,
        val text: String,
        var failures: Int = 0,
    )

    private data object LocalSenseAgentTarget : AgentControlTarget {
        override val id = "sense"
        override val displayName = "本机 Sense Agent"
    }
}

class AgentChannelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        BOOTSTRAP.execute {
            try {
                val enabled = AgentChannelSettingsStore(context).load().getOrNull()
                    ?.settings
                    ?.shouldRun == true
                if (enabled) runCatching { SenseAgentChannelRuntime.start(context) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val BOOTSTRAP = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sense-agent-channel-boot").apply { isDaemon = true }
        }
    }
}
