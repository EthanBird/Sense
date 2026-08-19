package io.github.ethanbird.senseime.brain.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Non-blocking IME-side port for the single Agent Hub owner hosted in `:brain`. */
class RemoteSenseAgentHubClient private constructor(context: Context) : AgentHubPort {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<AgentHubObserver>()
    private val pendingCommands = linkedMapOf<String, PendingCommand>()
    private val ipcWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-hub-ipc-writer").apply { isDaemon = true }
    }
    private var projection = AgentHubProjection(status = STATUS_CONNECTING)
    private var serverProjection = projection
    private var hydration: HistoryHydration? = null
    private var conversationHydration: ConversationHydration? = null
    private var remote: IBinder? = null
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    private var callback: IBinder? = null
    private var binding = false
    private var boundRequested = false
    private var retryScheduled = false
    private var retryDelayMs = INITIAL_RETRY_MS
    private val projectionFence = AgentHubProjectionFence()
    private var clientRevision = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            checkMainThread()
            binding = false
            retryDelayMs = INITIAL_RETRY_MS
            val generation = projectionFence.beginConnection()
            val callbackBinder = ProjectionCallback(generation)
            val deathRecipient = IBinder.DeathRecipient {
                mainHandler.post { handleConnectionLost(service) }
            }
            try {
                service.linkToDeath(deathRecipient, 0)
                remote = service
                remoteDeathRecipient = deathRecipient
                callback = callbackBinder
                sendRegister(service, callbackBinder)
            } catch (_: Exception) {
                handleConnectionLost(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleConnectionLost(remote)

        override fun onBindingDied(name: ComponentName?) = handleConnectionLost(remote)

        override fun onNullBinding(name: ComponentName?) = handleConnectionLost(remote)
    }

    override fun observe(observer: AgentHubObserver): AutoCloseable {
        checkMainThread()
        observers += observer
        observer.onProjection(projection)
        ensureBound()
        return AutoCloseable {
            mainHandler.post {
                observers -= observer
                if (observers.isEmpty()) {
                    hydration?.activeRequestId = null
                    if (!hasBindingDemand()) unbind()
                }
            }
        }
    }

    override fun currentProjection(): AgentHubProjection {
        checkMainThread()
        return projection
    }

    override fun fetchHistoryPage(request: AgentHubHistoryRequest): AgentHubHistoryPage =
        AgentHubHistoryPage(
            requestId = request.requestId,
            conversationId = request.conversationId,
            conversationRevision = serverProjection.conversationRevision,
            status = AgentHubHistoryStatus.INVALID_REQUEST,
            totalMessageCount = serverProjection.messageTotalCount,
            startIndex = 0,
            messages = emptyList(),
            nextBeforeIndexExclusive = null,
            detail = "Remote history is delivered asynchronously into the IME projection",
        )

    override fun fetchConversationPage(
        request: AgentHubConversationPageRequest,
    ): AgentHubConversationPage = AgentHubConversationPage(
        requestId = request.requestId,
        conversationRevision = serverProjection.conversationRevision,
        status = AgentHubHistoryStatus.INVALID_REQUEST,
        totalConversationCount = serverProjection.conversationTotalCount,
        startIndex = 0,
        conversations = emptyList(),
        nextStartIndex = null,
        detail = "Remote conversation pages are merged asynchronously into the IME projection",
    )

    override fun prepareRun(message: String): AgentHubPreparedRun? {
        checkMainThread()
        val text = message.trim()
        if (remote == null || !projection.loaded || projection.running || text.isEmpty()) return null
        if (text.length > SenseAgentHubRuntime.MAX_USER_MESSAGE_CHARS) return null
        return AgentHubPreparedRun(
            requestId = UUID.randomUUID().toString(),
            generation = nextGeneration(projection.generation),
            userMessage = text,
            userCreatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    // Cross-process callers use the asynchronous methods below. Boolean methods deliberately never
    // claim acceptance before the :brain ACK arrives.
    override fun sendPrepared(prepared: AgentHubPreparedRun): Boolean = false
    override fun stop(): Boolean = false
    override fun clearConversation(): Boolean = false
    override fun openConversation(id: String): Boolean = false
    override fun runGoldQuote(): Boolean = false
    override fun cancelAction(): Boolean = false
    override fun dismissAction(): Boolean = false

    override fun sendAsync(
        message: String,
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle {
        checkMainThread()
        val text = message.trim()
        val prepared = if (
            remote != null &&
            projection.loaded &&
            !projection.running &&
            text.isNotEmpty() &&
            text.length <= SenseAgentHubRuntime.MAX_USER_MESSAGE_CHARS
        ) {
            AgentHubPreparedRun(
                requestId = UUID.randomUUID().toString(),
                generation = nextGeneration(projection.generation),
                userMessage = text,
                userCreatedAtEpochMs = System.currentTimeMillis(),
            )
        } else {
            null
        }
        return if (prepared != null) {
            sendPreparedAsync(prepared, callback)
        } else {
            rejectAsynchronously(callback)
        }
    }

    override fun sendPreparedAsync(
        prepared: AgentHubPreparedRun,
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.SendPrepared(UUID.randomUUID().toString(), prepared),
        callback,
    )

    override fun stopAsync(callback: AgentHubCommandCallback): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.Stop(UUID.randomUUID().toString()),
        callback,
    )

    override fun clearConversationAsync(
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.ClearConversation(UUID.randomUUID().toString()),
        callback,
    )

    override fun openConversationAsync(
        id: String,
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.OpenConversation(UUID.randomUUID().toString(), id),
        callback,
    )

    override fun runGoldQuoteAsync(
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.RunGoldQuote(UUID.randomUUID().toString()),
        callback,
    )

    override fun cancelActionAsync(
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.CancelAction(UUID.randomUUID().toString()),
        callback,
    )

    override fun dismissActionAsync(
        callback: AgentHubCommandCallback,
    ): AgentHubCommandHandle = submit(
        AgentHubIpcCommand.DismissAction(UUID.randomUUID().toString()),
        callback,
    )

    private fun submit(
        command: AgentHubIpcCommand,
        completion: AgentHubCommandCallback,
    ): AgentHubCommandHandle {
        checkMainThread()
        val pending = PendingCommand(
            command = command,
            completion = completion,
            projectionProof = AgentHubCommandProjectionProof(
                submittedServerRevision = serverProjection.revision,
                priorRunning = serverProjection.running,
                priorMessageTotalCount = serverProjection.messageTotalCount,
                priorActionRequestId = serverProjection.action?.requestId,
                priorActionState = serverProjection.action?.state,
                openConversationSummary =
                    (command as? AgentHubIpcCommand.OpenConversation)?.let {
                        projection.conversations.firstOrNull { summary ->
                            summary.id == it.conversationId
                        }
                    },
            ),
        )
        pendingCommands[command.clientCommandId] = pending
        ensureBound()
        sendPendingCommand(pending)
        return object : AgentHubCommandHandle {
            override val clientCommandId: String = command.clientCommandId
            override fun close() {
                mainHandler.post { cancelPendingCommand(clientCommandId) }
            }
        }
    }

    private fun rejectAsynchronously(
        completion: AgentHubCommandCallback,
    ): AgentHubCommandHandle {
        val id = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean(false)
        mainHandler.post {
            if (!cancelled.get()) {
                completion.onComplete(
                    AgentHubCommandOutcome(
                        clientCommandId = id,
                        code = AgentHubCommandOutcomeCode.REJECTED,
                        generation = projection.generation,
                    ),
                )
            }
        }
        return object : AgentHubCommandHandle {
            override val clientCommandId: String = id
            override fun close() {
                cancelled.set(true)
            }
        }
    }

    private fun sendPendingCommand(pending: PendingCommand) {
        checkMainThread()
        val service = remote ?: return
        val callbackBinder = callback ?: return
        val connection = projectionFence.connectionGeneration
        if (
            !serverProjection.loaded ||
            pending.sentConnection == connection ||
            pending.scheduledConnection == connection
        ) {
            return
        }
        pending.scheduledConnection = connection
        // From this point the writer may already be inside Binder; replay always uses the same id.
        pending.everSent = true
        val bytes = AgentHubIpcProtocol.encodeCommand(pending.command)
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_COMMAND,
            populate = { data ->
            data.writeStrongBinder(callbackBinder)
                data.writeByteArray(bytes)
            },
        ) { delivered ->
            if (pendingCommands[pending.command.clientCommandId] !== pending) return@transactOneway
            if (pending.scheduledConnection != connection) return@transactOneway
            pending.scheduledConnection = 0
            if (remote !== service || projectionFence.connectionGeneration != connection) {
                return@transactOneway
            }
            if (!delivered) {
                handleConnectionLost(service)
            } else {
                pending.sentConnection = connection
                mainHandler.removeCallbacksAndMessages(pending.timeoutToken)
                mainHandler.postDelayed(
                    {
                        if (pendingCommands[pending.command.clientCommandId] === pending) {
                        handleConnectionLost(remote)
                        }
                    },
                    pending.timeoutToken,
                    COMMAND_ACK_TIMEOUT_MS,
                )
            }
        }
    }

    private fun handleCommandAck(ack: AgentHubIpcAck) {
        checkMainThread()
        val pending = pendingCommands[ack.clientCommandId] ?: return
        mainHandler.removeCallbacksAndMessages(pending.timeoutToken)
        settleCommand(
            pending,
            accepted = ack.confirms(pending.command),
            requestId = ack.requestId,
            generation = ack.generation,
        )
    }

    private fun confirmCommandsFromProjection(next: AgentHubProjection) {
        pendingCommands.values.toList().forEach { pending ->
            val confirmed = pending.everSent &&
                pending.projectionProof.confirms(pending.command, next)
            if (confirmed) {
                settleCommand(
                    pending,
                    accepted = true,
                    requestId = (pending.command as? AgentHubIpcCommand.SendPrepared)?.prepared?.requestId,
                    generation = next.generation,
                )
            }
        }
    }

    private fun settleCommand(
        pending: PendingCommand,
        accepted: Boolean,
        requestId: String?,
        generation: Long,
    ) {
        if (pendingCommands.remove(pending.command.clientCommandId) !== pending) return
        mainHandler.removeCallbacksAndMessages(pending.timeoutToken)
        pending.completion?.onComplete(
            AgentHubCommandOutcome(
                clientCommandId = pending.command.clientCommandId,
                code = if (accepted) {
                    AgentHubCommandOutcomeCode.ACCEPTED
                } else {
                    AgentHubCommandOutcomeCode.REJECTED
                },
                requestId = requestId,
                generation = generation,
            ),
        )
        if (!hasBindingDemand()) unbind()
    }

    private fun cancelPendingCommand(clientCommandId: String) {
        val pending = pendingCommands[clientCommandId] ?: return
        if (pending.everSent) {
            // The one-way transaction may already be executing. Detach the UI callback and retain
            // the command-id replay state until an ACK/projection resolves it.
            pending.completion = null
            return
        }
        pendingCommands.remove(clientCommandId)
        mainHandler.removeCallbacksAndMessages(pending.timeoutToken)
        pending.completion?.onComplete(
            AgentHubCommandOutcome(
                clientCommandId = clientCommandId,
                code = AgentHubCommandOutcomeCode.CANCELLED,
                generation = projection.generation,
            ),
        )
        if (!hasBindingDemand()) unbind()
    }

    private fun receiveProjection(connection: Long, next: AgentHubProjection) {
        checkMainThread()
        if (remote == null || !projectionFence.accepts(connection, next.revision)) return
        serverProjection = next
        confirmCommandsFromProjection(next)
        updateHydration(next)
        updateConversationHydration(next)
        pendingCommands.values.forEach(::sendPendingCommand)
    }

    private fun updateHydration(next: AgentHubProjection) {
        val state = hydration?.takeIf {
            it.conversationRevision == next.conversationRevision &&
                it.totalMessageCount == next.messageTotalCount
        } ?: HistoryHydration(
            conversationRevision = next.conversationRevision,
            totalMessageCount = next.messageTotalCount,
        ).also { hydration = it }
        next.messages.forEachIndexed { offset, message ->
            val index = next.messageWindowStart + offset
            val existing = state.messages[index]
            if (existing == null || !message.wireTruncated || existing.wireTruncated) {
                state.messages[index] = message
            }
        }
        if (state.nextBeforeIndexExclusive == null && state.messages.isNotEmpty()) {
            if (next.messageWindowStart > 0 || next.messages.any(AgentHubMessage::wireTruncated)) {
                state.nextBeforeIndexExclusive = next.messageTotalCount
            }
        }
        publishHydrated()
        requestNextHistoryPage()
    }

    private fun requestNextHistoryPage() {
        checkMainThread()
        if (observers.isEmpty()) return
        val state = hydration ?: return
        if (state.activeRequestId != null) return
        val before = state.nextBeforeIndexExclusive ?: return
        val service = remote ?: return
        val callbackBinder = callback ?: return
        val request = AgentHubHistoryRequest(
            requestId = UUID.randomUUID().toString(),
            conversationId = SenseAgentHubRuntime.CURRENT_CONVERSATION_ID,
            expectedConversationRevision = state.conversationRevision,
            beforeIndexExclusive = before,
        )
        state.activeRequestId = request.requestId
        val bytes = AgentHubHistoryCodec.encodeRequest(request)
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_HISTORY_FETCH,
            populate = { data ->
                data.writeStrongBinder(callbackBinder)
                data.writeByteArray(bytes)
            },
        ) { delivered ->
            if (state.activeRequestId != request.requestId) return@transactOneway
            if (!delivered) {
                state.activeRequestId = null
                handleConnectionLost(service)
            }
        }
    }

    private fun handleHistoryPage(page: AgentHubHistoryPage) {
        checkMainThread()
        val state = hydration ?: return
        if (
            state.activeRequestId != page.requestId ||
            state.conversationRevision != page.conversationRevision
        ) {
            return
        }
        state.activeRequestId = null
        if (
            page.status != AgentHubHistoryStatus.OK ||
            page.totalMessageCount != state.totalMessageCount
        ) {
            state.nextBeforeIndexExclusive = null
            return
        }
        page.messages.forEachIndexed { offset, message ->
            state.messages[page.startIndex + offset] = message.copy(wireTruncated = false)
        }
        state.nextBeforeIndexExclusive = page.nextBeforeIndexExclusive
        publishHydrated()
        requestNextHistoryPage()
    }

    private fun updateConversationHydration(next: AgentHubProjection) {
        val state = conversationHydration?.takeIf {
            it.conversationRevision == next.conversationRevision &&
                it.totalConversationCount == next.conversationTotalCount
        } ?: ConversationHydration(
            conversationRevision = next.conversationRevision,
            totalConversationCount = next.conversationTotalCount,
        ).also { conversationHydration = it }
        next.conversations.forEachIndexed { offset, conversation ->
            state.conversations[next.conversationWindowStart + offset] = conversation
        }
        val wireEnd = next.conversationWindowStart + next.conversations.size
        if (state.nextStartIndex == null && wireEnd < next.conversationTotalCount) {
            state.nextStartIndex = wireEnd
        }
        publishHydrated()
        requestNextConversationPage()
    }

    private fun requestNextConversationPage() {
        checkMainThread()
        if (observers.isEmpty()) return
        val state = conversationHydration ?: return
        if (state.activeRequestId != null) return
        val start = state.nextStartIndex ?: return
        val service = remote ?: return
        val callbackBinder = callback ?: return
        val request = AgentHubConversationPageRequest(
            requestId = UUID.randomUUID().toString(),
            expectedConversationRevision = state.conversationRevision,
            startIndex = start,
        )
        state.activeRequestId = request.requestId
        val bytes = AgentHubHistoryCodec.encodeConversationRequest(request)
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_CONVERSATION_FETCH,
            populate = { data ->
                data.writeStrongBinder(callbackBinder)
                data.writeByteArray(bytes)
            },
        ) { delivered ->
            if (state.activeRequestId != request.requestId) return@transactOneway
            if (!delivered) {
                state.activeRequestId = null
                handleConnectionLost(service)
            }
        }
    }

    private fun handleConversationPage(page: AgentHubConversationPage) {
        checkMainThread()
        val state = conversationHydration ?: return
        if (
            state.activeRequestId != page.requestId ||
            state.conversationRevision != page.conversationRevision
        ) {
            return
        }
        state.activeRequestId = null
        if (
            page.status != AgentHubHistoryStatus.OK ||
            page.totalConversationCount != state.totalConversationCount
        ) {
            state.nextStartIndex = null
            return
        }
        page.conversations.forEachIndexed { offset, conversation ->
            state.conversations[page.startIndex + offset] = conversation
        }
        state.nextStartIndex = page.nextStartIndex
        publishHydrated()
        requestNextConversationPage()
    }

    private fun publishHydrated() {
        val base = serverProjection
        val messageState = hydration
        val hydratedMessages = if (
            messageState != null && messageState.conversationRevision == base.conversationRevision
        ) {
            messageState.messages.toSortedMap().values.toList()
        } else {
            base.messages
        }
        val conversationState = conversationHydration
        val hydratedConversations = if (
            conversationState != null &&
            conversationState.conversationRevision == base.conversationRevision
        ) {
            conversationState.conversations.toSortedMap().values.toList()
        } else {
            base.conversations
        }
        clientRevision = nextGeneration(clientRevision)
        projection = base.copy(
            revision = clientRevision,
            messages = hydratedMessages,
            messageWindowStart = messageState?.messages?.keys?.minOrNull()
                ?: base.messageWindowStart,
            messageTotalCount = base.messageTotalCount,
            conversations = hydratedConversations,
            conversationWindowStart = conversationState?.conversations?.keys?.minOrNull()
                ?: base.conversationWindowStart,
            conversationTotalCount = base.conversationTotalCount,
        )
        publish()
    }

    private fun sendRegister(service: IBinder, callbackBinder: IBinder) {
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_REGISTER,
            populate = { it.writeStrongBinder(callbackBinder) },
        ) { delivered ->
            if (!delivered && remote === service) {
                handleConnectionLost(service)
            }
        }
    }

    private inner class ProjectionCallback(private val connection: Long) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(AgentHubIpcProtocol.DESCRIPTOR)
                return true
            }
            data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
            val wire = requireNotNull(data.createByteArray())
            when (code) {
                AgentHubIpcProtocol.TRANSACTION_PROJECTION -> {
                    val next = runCatching { AgentHubProjectionCodec.decode(wire) }.getOrNull()
                    if (next == null) {
                        mainHandler.post { handleConnectionLost(remote) }
                    } else {
                        mainHandler.post {
                            receiveProjection(connection, next)
                            acknowledgeProjection(this, next.revision)
                        }
                    }
                    return true
                }
                AgentHubIpcProtocol.TRANSACTION_COMMAND_ACK -> {
                    val ack = runCatching { AgentHubIpcProtocol.decodeAck(wire) }.getOrNull()
                    if (ack != null) mainHandler.post { handleCommandAck(ack) }
                    return true
                }
                AgentHubIpcProtocol.TRANSACTION_HISTORY_PAGE -> {
                    val page = runCatching { AgentHubHistoryCodec.decodePage(wire) }.getOrNull()
                    if (page != null) mainHandler.post { handleHistoryPage(page) }
                    return true
                }
                AgentHubIpcProtocol.TRANSACTION_CONVERSATION_PAGE -> {
                    val page = runCatching {
                        AgentHubHistoryCodec.decodeConversationPage(wire)
                    }.getOrNull()
                    if (page != null) mainHandler.post { handleConversationPage(page) }
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun acknowledgeProjection(callbackBinder: IBinder, serverRevision: Long) {
        val service = remote ?: return
        if (callback !== callbackBinder) return
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_PROJECTION_ACK,
            populate = { data ->
                data.writeStrongBinder(callbackBinder)
                data.writeLong(serverRevision)
            },
        ) { delivered ->
            if (!delivered && remote === service && callback === callbackBinder) {
                handleConnectionLost(service)
            }
        }
    }

    private fun ensureBound() {
        checkMainThread()
        if (remote != null || binding || boundRequested || !hasBindingDemand()) return
        binding = true
        boundRequested = runCatching {
            applicationContext.bindService(
                Intent(applicationContext, SenseAgentHubBridgeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        binding = false
        if (!boundRequested) scheduleRebind()
    }

    private fun handleConnectionLost(expected: IBinder?) {
        checkMainThread()
        if (expected != null && remote != null && remote !== expected) return
        val disconnectedService = remote
        val disconnectedCallback = callback
        if (disconnectedService != null && disconnectedCallback != null) {
            unregisterRemoteCallback(disconnectedService, disconnectedCallback)
        }
        remoteDeathRecipient?.let { recipient ->
            runCatching { remote?.unlinkToDeath(recipient, 0) }
        }
        remote = null
        remoteDeathRecipient = null
        callback = null
        projectionFence.invalidate()
        hydration?.activeRequestId = null
        conversationHydration?.activeRequestId = null
        pendingCommands.values.forEach { pending ->
            pending.sentConnection = 0
            pending.scheduledConnection = 0
            mainHandler.removeCallbacksAndMessages(pending.timeoutToken)
        }
        if (boundRequested) {
            runCatching { applicationContext.unbindService(connection) }
            boundRequested = false
        }
        binding = false
        clientRevision = nextGeneration(clientRevision)
        projection = projection.copy(
            revision = clientRevision,
            loaded = false,
            status = STATUS_RECONNECTING,
        )
        publish()
        scheduleRebind()
    }

    private fun scheduleRebind() {
        if (retryScheduled || !hasBindingDemand()) return
        retryScheduled = true
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        mainHandler.postDelayed({
            retryScheduled = false
            ensureBound()
        }, REBIND_TOKEN, delay)
    }

    private fun unbind() {
        retryScheduled = false
        mainHandler.removeCallbacksAndMessages(REBIND_TOKEN)
        val service = remote
        val callbackBinder = callback
        if (service != null && callbackBinder != null) {
            unregisterRemoteCallback(service, callbackBinder)
        }
        remoteDeathRecipient?.let { recipient ->
            runCatching { service?.unlinkToDeath(recipient, 0) }
        }
        remote = null
        remoteDeathRecipient = null
        callback = null
        projectionFence.invalidate()
        hydration?.activeRequestId = null
        conversationHydration?.activeRequestId = null
        if (boundRequested) {
            runCatching { applicationContext.unbindService(connection) }
            boundRequested = false
        }
        binding = false
        clientRevision = nextGeneration(clientRevision)
        projection = projection.copy(
            revision = clientRevision,
            loaded = false,
            status = STATUS_CONNECTING,
        )
    }

    private fun unregisterRemoteCallback(service: IBinder, callbackBinder: IBinder) {
        transactOneway(
            service = service,
            code = AgentHubIpcProtocol.TRANSACTION_UNREGISTER,
            populate = { it.writeStrongBinder(callbackBinder) },
        )
    }

    /** The IME main thread only schedules writes; every Binder transact runs on this writer lane. */
    private fun transactOneway(
        service: IBinder,
        code: Int,
        populate: (Parcel) -> Unit,
        completion: (Boolean) -> Unit = {},
    ) {
        ipcWriter.execute {
            val data = Parcel.obtain()
            val delivered = try {
                data.writeInterfaceToken(AgentHubIpcProtocol.DESCRIPTOR)
                populate(data)
                service.transact(code, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
                false
            } finally {
                data.recycle()
            }
            mainHandler.post { completion(delivered) }
        }
    }

    private fun hasBindingDemand(): Boolean = observers.isNotEmpty() || pendingCommands.isNotEmpty()

    private fun publish() {
        observers.toList().forEach { it.onProjection(projection) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    private data class PendingCommand(
        val command: AgentHubIpcCommand,
        var completion: AgentHubCommandCallback?,
        val projectionProof: AgentHubCommandProjectionProof,
        var sentConnection: Long = 0,
        var scheduledConnection: Long = 0,
        var everSent: Boolean = false,
        val timeoutToken: Any = Any(),
    )

    private data class HistoryHydration(
        val conversationRevision: Long,
        val totalMessageCount: Int,
        val messages: MutableMap<Int, AgentHubMessage> = linkedMapOf(),
        var nextBeforeIndexExclusive: Int? = null,
        var activeRequestId: String? = null,
    )

    private data class ConversationHydration(
        val conversationRevision: Long,
        val totalConversationCount: Int,
        val conversations: MutableMap<Int, AgentHubConversationSummary> = linkedMapOf(),
        var nextStartIndex: Int? = null,
        var activeRequestId: String? = null,
    )

    companion object {
        private const val INITIAL_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 8_000L
        private const val COMMAND_ACK_TIMEOUT_MS = 5_000L
        private const val STATUS_CONNECTING = "正在连接 Agent…"
        private const val STATUS_RECONNECTING = "正在重新连接 Agent…"
        private val REBIND_TOKEN = Any()

        @Volatile
        private var instance: RemoteSenseAgentHubClient? = null

        fun get(context: Context): RemoteSenseAgentHubClient = instance ?: synchronized(this) {
            instance ?: RemoteSenseAgentHubClient(context).also { instance = it }
        }

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
