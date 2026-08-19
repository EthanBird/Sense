package io.github.ethanbird.senseime.brain.runtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import java.util.concurrent.Executors

/** Private `:brain` bridge exposing the single Hub owner to the IME process. */
class SenseAgentHubBridgeService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = linkedMapOf<IBinder, CallbackRecord>()
    private val projectionEncoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sense-agent-hub-ipc-projection").apply { isDaemon = true }
    }
    private val projectionLock = Any()
    private val wireDeliveryLock = Any()
    private var pendingProjection: AgentHubProjection? = null
    private var projectionDrainScheduled = false
    private var pendingWireProjection: WireProjection? = null
    private var wireDeliveryScheduled = false
    private val commandAckLedger = AgentHubCommandAckLedger()
    private lateinit var port: AgentHubPort
    private var subscription: AutoCloseable? = null
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(AgentHubIpcProtocol.DESCRIPTOR)
                return true
            }
            return when (code) {
                AgentHubIpcProtocol.TRANSACTION_REGISTER -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    mainHandler.post {
                        if (!registerCallback(callback)) return@post
                        scheduleProjection(port.currentProjection())
                    }
                    true
                }
                AgentHubIpcProtocol.TRANSACTION_UNREGISTER -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    mainHandler.post { unregisterCallback(callback) }
                    true
                }
                AgentHubIpcProtocol.TRANSACTION_COMMAND -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    val bytes = requireNotNull(data.createByteArray())
                    val decoded = runCatching { AgentHubIpcProtocol.decodeCommand(bytes) }
                    mainHandler.post {
                        if (!registerCallback(callback)) return@post
                        val ack = decoded.fold(
                            onSuccess = { command -> executeIdempotently(command) },
                            onFailure = {
                                val current = port.currentProjection()
                                AgentHubIpcAck(
                                    clientCommandId =
                                        AgentHubIpcProtocol.peekClientCommandId(bytes) ?: "invalid",
                                    code = AgentHubIpcAckCode.PROTOCOL_ERROR,
                                    serverRevision = current.revision,
                                    requestId = current.requestId,
                                    generation = current.generation,
                                )
                            },
                        )
                        sendCommandAck(callback, ack)
                    }
                    true
                }
                AgentHubIpcProtocol.TRANSACTION_PROJECTION_ACK -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    val revision = data.readLong()
                    mainHandler.post { acknowledgeProjection(callback, revision) }
                    true
                }
                AgentHubIpcProtocol.TRANSACTION_HISTORY_FETCH -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    val bytes = requireNotNull(data.createByteArray())
                    val request = runCatching { AgentHubHistoryCodec.decodeRequest(bytes) }
                        .getOrNull()
                    if (request != null) {
                        mainHandler.post {
                            if (!registerCallback(callback)) return@post
                            sendHistoryPage(callback, port.fetchHistoryPage(request))
                        }
                    }
                    true
                }
                AgentHubIpcProtocol.TRANSACTION_CONVERSATION_FETCH -> {
                    data.enforceInterface(AgentHubIpcProtocol.DESCRIPTOR)
                    val callback = requireNotNull(data.readStrongBinder())
                    val bytes = requireNotNull(data.createByteArray())
                    val request = runCatching {
                        AgentHubHistoryCodec.decodeConversationRequest(bytes)
                    }.getOrNull()
                    if (request != null) {
                        mainHandler.post {
                            if (!registerCallback(callback)) return@post
                            sendConversationPage(callback, port.fetchConversationPage(request))
                        }
                    }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        port = SenseAgentHubRuntime.get(applicationContext)
        subscription = port.observe(AgentHubObserver(::scheduleProjection))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        subscription?.close()
        subscription = null
        callbacks.toMap().forEach { (callback, record) ->
            runCatching { callback.unlinkToDeath(record.deathRecipient, 0) }
        }
        callbacks.clear()
        projectionEncoder.shutdownNow()
        super.onDestroy()
    }

    private fun executeIdempotently(command: AgentHubIpcCommand): AgentHubIpcAck {
        return commandAckLedger.executeOnce(command) {
            val accepted = runCatching {
                when (command) {
                    is AgentHubIpcCommand.SendPrepared -> port.sendPrepared(command.prepared)
                    is AgentHubIpcCommand.Stop -> port.stop()
                    is AgentHubIpcCommand.ClearConversation -> port.clearConversation()
                    is AgentHubIpcCommand.OpenConversation ->
                        port.openConversation(command.conversationId)
                    is AgentHubIpcCommand.RunGoldQuote -> port.runGoldQuote()
                    is AgentHubIpcCommand.CancelAction -> port.cancelAction()
                    is AgentHubIpcCommand.DismissAction -> port.dismissAction()
                }
            }
            val projection = port.currentProjection()
            AgentHubIpcAck(
                clientCommandId = command.clientCommandId,
                code = when {
                    accepted.isFailure -> AgentHubIpcAckCode.INTERNAL_ERROR
                    accepted.getOrDefault(false) -> AgentHubIpcAckCode.ACCEPTED
                    else -> AgentHubIpcAckCode.REJECTED
                },
                serverRevision = projection.revision,
                requestId = projection.requestId,
                generation = projection.generation,
            )
        }
    }

    private fun sendCommandAck(callback: IBinder, ack: AgentHubIpcAck) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(AgentHubIpcProtocol.DESCRIPTOR)
            data.writeByteArray(AgentHubIpcProtocol.encodeAck(ack))
            if (!callback.transact(
                    AgentHubIpcProtocol.TRANSACTION_COMMAND_ACK,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY,
                )
            ) {
                unregisterCallback(callback)
            }
        } catch (_: RemoteException) {
            unregisterCallback(callback)
        } finally {
            data.recycle()
        }
    }

    private fun sendHistoryPage(callback: IBinder, page: AgentHubHistoryPage) {
        projectionEncoder.execute {
            val bytes = runCatching { AgentHubHistoryCodec.encodePage(page) }.getOrNull()
                ?: return@execute
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(AgentHubIpcProtocol.DESCRIPTOR)
                data.writeByteArray(bytes)
                if (!callback.transact(
                        AgentHubIpcProtocol.TRANSACTION_HISTORY_PAGE,
                        data,
                        null,
                        IBinder.FLAG_ONEWAY,
                    )
                ) {
                    mainHandler.post { unregisterCallback(callback) }
                }
            } catch (_: RemoteException) {
                mainHandler.post { unregisterCallback(callback) }
            } finally {
                data.recycle()
            }
        }
    }

    private fun sendConversationPage(callback: IBinder, page: AgentHubConversationPage) {
        projectionEncoder.execute {
            val bytes = runCatching {
                AgentHubHistoryCodec.encodeConversationPage(page)
            }.getOrNull() ?: return@execute
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(AgentHubIpcProtocol.DESCRIPTOR)
                data.writeByteArray(bytes)
                if (!callback.transact(
                        AgentHubIpcProtocol.TRANSACTION_CONVERSATION_PAGE,
                        data,
                        null,
                        IBinder.FLAG_ONEWAY,
                    )
                ) {
                    mainHandler.post { unregisterCallback(callback) }
                }
            } catch (_: RemoteException) {
                mainHandler.post { unregisterCallback(callback) }
            } finally {
                data.recycle()
            }
        }
    }

    private fun registerCallback(callback: IBinder): Boolean {
        if (callbacks.containsKey(callback)) return true
        val recipient = IBinder.DeathRecipient {
            mainHandler.post { unregisterCallback(callback) }
        }
        if (!tryLinkAgentHubCallback { callback.linkToDeath(recipient, 0) }) return false
        callbacks[callback] = CallbackRecord(recipient)
        return true
    }

    private fun unregisterCallback(callback: IBinder) {
        val record = callbacks.remove(callback) ?: return
        record.delivery.reset()
        runCatching { callback.unlinkToDeath(record.deathRecipient, 0) }
    }

    private fun scheduleProjection(projection: AgentHubProjection) {
        synchronized(projectionLock) {
            pendingProjection = projection
            if (projectionDrainScheduled) return
            projectionDrainScheduled = true
        }
        projectionEncoder.execute {
            while (!Thread.currentThread().isInterrupted) {
                val next = synchronized(projectionLock) {
                    pendingProjection.also {
                        pendingProjection = null
                        if (it == null) projectionDrainScheduled = false
                    }
                } ?: return@execute
                val wire = runCatching { AgentHubProjectionCodec.encode(next) }.getOrNull()
                    ?: continue
                scheduleWireDelivery(WireProjection(next.revision, wire))
            }
        }
    }

    private fun scheduleWireDelivery(wire: WireProjection) {
        val schedule = synchronized(wireDeliveryLock) {
            pendingWireProjection = wire
            if (wireDeliveryScheduled) {
                false
            } else {
                wireDeliveryScheduled = true
                true
            }
        }
        if (schedule) mainHandler.post(::drainWireDelivery)
    }

    private fun drainWireDelivery() {
        val wire = synchronized(wireDeliveryLock) {
            pendingWireProjection.also { pendingWireProjection = null }
        }
        if (wire != null) publishWireProjection(wire)
        val reschedule = synchronized(wireDeliveryLock) {
            if (pendingWireProjection == null) {
                wireDeliveryScheduled = false
                false
            } else {
                true
            }
        }
        if (reschedule) mainHandler.post(::drainWireDelivery)
    }

    private fun publishWireProjection(wire: WireProjection) {
        callbacks.toMap().forEach { (callback, record) ->
            record.delivery.offer(wire.revision, wire)?.let { sendProjection(callback, it) }
        }
    }

    private fun acknowledgeProjection(callback: IBinder, revision: Long) {
        val record = callbacks[callback] ?: return
        record.delivery.acknowledge(revision)?.let { sendProjection(callback, it) }
    }

    private fun sendProjection(callback: IBinder, wire: WireProjection) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(AgentHubIpcProtocol.DESCRIPTOR)
            data.writeByteArray(wire.bytes)
            val delivered = callback.transact(
                AgentHubIpcProtocol.TRANSACTION_PROJECTION,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )
            if (!delivered) unregisterCallback(callback)
        } catch (_: RemoteException) {
            unregisterCallback(callback)
        } finally {
            data.recycle()
        }
    }

    private data class CallbackRecord(
        val deathRecipient: IBinder.DeathRecipient,
        val delivery: AgentHubProjectionDeliveryWindow<WireProjection> =
            AgentHubProjectionDeliveryWindow(),
    )

    private data class WireProjection(
        val revision: Long,
        val bytes: ByteArray,
    )
}
