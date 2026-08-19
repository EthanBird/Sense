package io.github.ethanbird.senseime.brain.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class AgentHubHistoryRequest(
    val requestId: String,
    val conversationId: String,
    val expectedConversationRevision: Long,
    val beforeIndexExclusive: Int,
)

enum class AgentHubHistoryStatus {
    OK,
    STALE_REVISION,
    NOT_FOUND,
    INVALID_REQUEST,
}

data class AgentHubHistoryPage(
    val requestId: String,
    val conversationId: String,
    val conversationRevision: Long,
    val status: AgentHubHistoryStatus,
    val totalMessageCount: Int,
    val startIndex: Int,
    val messages: List<AgentHubMessage>,
    val nextBeforeIndexExclusive: Int?,
    val detail: String = "",
)

data class AgentHubConversationPageRequest(
    val requestId: String,
    val expectedConversationRevision: Long,
    val startIndex: Int,
)

data class AgentHubConversationPage(
    val requestId: String,
    val conversationRevision: Long,
    val status: AgentHubHistoryStatus,
    val totalConversationCount: Int,
    val startIndex: Int,
    val conversations: List<AgentHubConversationSummary>,
    val nextStartIndex: Int?,
    val detail: String = "",
)

/** Builds full-text pages without applying the compact projection's display truncation. */
internal object AgentHubHistoryPager {
    const val MAX_PAGE_MESSAGES = 4
    const val MAX_FULL_MESSAGE_BYTES = 64 * 1024
    const val PAGE_TEXT_BUDGET_BYTES = 192 * 1024

    fun page(
        request: AgentHubHistoryRequest,
        conversationRevision: Long,
        messages: List<AgentHubMessage>?,
    ): AgentHubHistoryPage {
        if (request.expectedConversationRevision != conversationRevision) {
            return result(
                request,
                conversationRevision,
                AgentHubHistoryStatus.STALE_REVISION,
                messages?.size ?: 0,
                detail = "Conversation revision changed",
            )
        }
        val source = messages ?: return result(
            request,
            conversationRevision,
            AgentHubHistoryStatus.NOT_FOUND,
            0,
            detail = "Conversation was not found",
        )
        if (request.beforeIndexExclusive !in 0..source.size) {
            return result(
                request,
                conversationRevision,
                AgentHubHistoryStatus.INVALID_REQUEST,
                source.size,
                detail = "History cursor is outside the retained conversation",
            )
        }
        var start = request.beforeIndexExclusive
        var selected = 0
        var textBytes = 0
        while (start > 0 && selected < MAX_PAGE_MESSAGES) {
            val candidate = source[start - 1]
            val candidateBytes = candidate.text.toByteArray(StandardCharsets.UTF_8).size
            if (candidateBytes > MAX_FULL_MESSAGE_BYTES) {
                return result(
                    request,
                    conversationRevision,
                    AgentHubHistoryStatus.INVALID_REQUEST,
                    source.size,
                    detail = "A retained message exceeds the full-message IPC budget",
                )
            }
            if (selected > 0 && textBytes + candidateBytes > PAGE_TEXT_BUDGET_BYTES) break
            textBytes += candidateBytes
            selected += 1
            start -= 1
        }
        val pageMessages = source.subList(start, request.beforeIndexExclusive).map {
            it.copy(wireTruncated = false)
        }
        return AgentHubHistoryPage(
            requestId = request.requestId,
            conversationId = request.conversationId,
            conversationRevision = conversationRevision,
            status = AgentHubHistoryStatus.OK,
            totalMessageCount = source.size,
            startIndex = start,
            messages = pageMessages,
            nextBeforeIndexExclusive = start.takeIf { it > 0 },
        )
    }

    private fun result(
        request: AgentHubHistoryRequest,
        conversationRevision: Long,
        status: AgentHubHistoryStatus,
        total: Int,
        detail: String,
    ) = AgentHubHistoryPage(
        requestId = request.requestId,
        conversationId = request.conversationId,
        conversationRevision = conversationRevision,
        status = status,
        totalMessageCount = total,
        startIndex = 0,
        messages = emptyList(),
        nextBeforeIndexExclusive = null,
        detail = detail,
    )
}

/** Pages every retained archive summary so the compact projection is not the history index. */
internal object AgentHubConversationPager {
    const val MAX_PAGE_CONVERSATIONS = 12

    fun page(
        request: AgentHubConversationPageRequest,
        conversationRevision: Long,
        conversations: List<AgentHubConversationSummary>,
    ): AgentHubConversationPage {
        if (request.expectedConversationRevision != conversationRevision) {
            return result(
                request = request,
                conversationRevision = conversationRevision,
                status = AgentHubHistoryStatus.STALE_REVISION,
                total = conversations.size,
                detail = "Conversation catalog revision changed",
            )
        }
        if (request.startIndex !in 0..conversations.size) {
            return result(
                request = request,
                conversationRevision = conversationRevision,
                status = AgentHubHistoryStatus.INVALID_REQUEST,
                total = conversations.size,
                detail = "Conversation catalog cursor is outside retention",
            )
        }
        val end = (request.startIndex + MAX_PAGE_CONVERSATIONS)
            .coerceAtMost(conversations.size)
        return AgentHubConversationPage(
            requestId = request.requestId,
            conversationRevision = conversationRevision,
            status = AgentHubHistoryStatus.OK,
            totalConversationCount = conversations.size,
            startIndex = request.startIndex,
            conversations = conversations.subList(request.startIndex, end),
            nextStartIndex = end.takeIf { it < conversations.size },
        )
    }

    private fun result(
        request: AgentHubConversationPageRequest,
        conversationRevision: Long,
        status: AgentHubHistoryStatus,
        total: Int,
        detail: String,
    ) = AgentHubConversationPage(
        requestId = request.requestId,
        conversationRevision = conversationRevision,
        status = status,
        totalConversationCount = total,
        startIndex = 0,
        conversations = emptyList(),
        nextStartIndex = null,
        detail = detail,
    )
}

internal object AgentHubHistoryCodec {
    const val MAX_REQUEST_BYTES = 4 * 1024
    const val MAX_PAGE_BYTES = 240 * 1024
    const val MAX_CONVERSATION_REQUEST_BYTES = 4 * 1024
    const val MAX_CONVERSATION_PAGE_BYTES = 96 * 1024

    private const val REQUEST_MAGIC = 0x53484851 // SHHQ
    private const val PAGE_MAGIC = 0x53484850 // SHHP
    private const val CONVERSATION_REQUEST_MAGIC = 0x53484351 // SHCQ
    private const val CONVERSATION_PAGE_MAGIC = 0x53484350 // SHCP
    private const val VERSION = 1
    private const val MAX_ID_BYTES = 256
    private const val MAX_DETAIL_BYTES = 512
    private const val MAX_TITLE_BYTES = 384
    private const val MAX_PREVIEW_BYTES = 768

    fun encodeRequest(request: AgentHubHistoryRequest): ByteArray = encode(MAX_REQUEST_BYTES) {
        it.writeInt(REQUEST_MAGIC)
        it.writeInt(VERSION)
        it.writeUtf8(request.requestId, MAX_ID_BYTES)
        it.writeUtf8(request.conversationId, MAX_ID_BYTES)
        it.writeLong(request.expectedConversationRevision)
        it.writeInt(request.beforeIndexExclusive)
    }

    fun decodeRequest(bytes: ByteArray): AgentHubHistoryRequest = decode(bytes, MAX_REQUEST_BYTES) {
        require(it.readInt() == REQUEST_MAGIC) { "Invalid history request magic" }
        require(it.readInt() == VERSION) { "Unsupported history request version" }
        AgentHubHistoryRequest(
            requestId = it.readUtf8(MAX_ID_BYTES).also { value -> require(value.isNotBlank()) },
            conversationId = it.readUtf8(MAX_ID_BYTES).also { value ->
                require(value.isNotBlank())
            },
            expectedConversationRevision = it.readLong(),
            beforeIndexExclusive = it.readInt().also { cursor -> require(cursor >= 0) },
        )
    }

    fun encodePage(page: AgentHubHistoryPage): ByteArray = encode(MAX_PAGE_BYTES) {
        it.writeInt(PAGE_MAGIC)
        it.writeInt(VERSION)
        it.writeUtf8(page.requestId, MAX_ID_BYTES)
        it.writeUtf8(page.conversationId, MAX_ID_BYTES)
        it.writeLong(page.conversationRevision)
        it.writeInt(page.status.ordinal)
        it.writeInt(page.totalMessageCount)
        it.writeInt(page.startIndex)
        it.writeInt(page.messages.size)
        require(page.messages.size <= AgentHubHistoryPager.MAX_PAGE_MESSAGES)
        page.messages.forEach { message ->
            it.writeInt(message.role.ordinal)
            it.writeUtf8(message.text, AgentHubHistoryPager.MAX_FULL_MESSAGE_BYTES)
            it.writeLong(message.createdAtEpochMs)
        }
        it.writeBoolean(page.nextBeforeIndexExclusive != null)
        page.nextBeforeIndexExclusive?.let(it::writeInt)
        it.writeUtf8(page.detail, MAX_DETAIL_BYTES)
    }

    fun decodePage(bytes: ByteArray): AgentHubHistoryPage = decode(bytes, MAX_PAGE_BYTES) { input ->
        require(input.readInt() == PAGE_MAGIC) { "Invalid history page magic" }
        require(input.readInt() == VERSION) { "Unsupported history page version" }
        val requestId = input.readUtf8(MAX_ID_BYTES)
        val conversationId = input.readUtf8(MAX_ID_BYTES)
        val revision = input.readLong()
        val status = input.readEnum<AgentHubHistoryStatus>()
        val total = input.readInt()
        val start = input.readInt()
        val count = input.readInt()
        require(total >= 0 && start in 0..total)
        require(count in 0..AgentHubHistoryPager.MAX_PAGE_MESSAGES)
        require(start + count <= total)
        val messages = List(count) {
            AgentHubMessage(
                role = input.readEnum(),
                text = input.readUtf8(AgentHubHistoryPager.MAX_FULL_MESSAGE_BYTES),
                createdAtEpochMs = input.readLong(),
                wireTruncated = false,
            )
        }
        val next = if (input.readBoolean()) input.readInt() else null
        require(next == null || (next == start && next > 0))
        AgentHubHistoryPage(
            requestId = requestId,
            conversationId = conversationId,
            conversationRevision = revision,
            status = status,
            totalMessageCount = total,
            startIndex = start,
            messages = messages,
            nextBeforeIndexExclusive = next,
            detail = input.readUtf8(MAX_DETAIL_BYTES),
        )
    }

    fun encodeConversationRequest(request: AgentHubConversationPageRequest): ByteArray =
        encode(MAX_CONVERSATION_REQUEST_BYTES) {
            it.writeInt(CONVERSATION_REQUEST_MAGIC)
            it.writeInt(VERSION)
            it.writeUtf8(request.requestId, MAX_ID_BYTES)
            it.writeLong(request.expectedConversationRevision)
            it.writeInt(request.startIndex)
        }

    fun decodeConversationRequest(bytes: ByteArray): AgentHubConversationPageRequest =
        decode(bytes, MAX_CONVERSATION_REQUEST_BYTES) {
            require(it.readInt() == CONVERSATION_REQUEST_MAGIC) {
                "Invalid conversation page request magic"
            }
            require(it.readInt() == VERSION) {
                "Unsupported conversation page request version"
            }
            AgentHubConversationPageRequest(
                requestId = it.readUtf8(MAX_ID_BYTES).also { value ->
                    require(value.isNotBlank())
                },
                expectedConversationRevision = it.readLong(),
                startIndex = it.readInt().also { cursor -> require(cursor >= 0) },
            )
        }

    fun encodeConversationPage(page: AgentHubConversationPage): ByteArray =
        encode(MAX_CONVERSATION_PAGE_BYTES) {
            it.writeInt(CONVERSATION_PAGE_MAGIC)
            it.writeInt(VERSION)
            it.writeUtf8(page.requestId, MAX_ID_BYTES)
            it.writeLong(page.conversationRevision)
            it.writeInt(page.status.ordinal)
            it.writeInt(page.totalConversationCount)
            it.writeInt(page.startIndex)
            require(page.conversations.size <= AgentHubConversationPager.MAX_PAGE_CONVERSATIONS)
            it.writeInt(page.conversations.size)
            page.conversations.forEach { conversation ->
                it.writeUtf8(conversation.id, MAX_ID_BYTES)
                it.writeUtf8(conversation.title, MAX_TITLE_BYTES)
                it.writeUtf8(conversation.preview, MAX_PREVIEW_BYTES)
                it.writeLong(conversation.updatedAtEpochMs)
                it.writeInt(conversation.messageCount)
                it.writeBoolean(conversation.current)
            }
            it.writeBoolean(page.nextStartIndex != null)
            page.nextStartIndex?.let(it::writeInt)
            it.writeUtf8(page.detail, MAX_DETAIL_BYTES)
        }

    fun decodeConversationPage(bytes: ByteArray): AgentHubConversationPage =
        decode(bytes, MAX_CONVERSATION_PAGE_BYTES) { input ->
            require(input.readInt() == CONVERSATION_PAGE_MAGIC) {
                "Invalid conversation page magic"
            }
            require(input.readInt() == VERSION) {
                "Unsupported conversation page version"
            }
            val requestId = input.readUtf8(MAX_ID_BYTES)
            val revision = input.readLong()
            val status = input.readEnum<AgentHubHistoryStatus>()
            val total = input.readInt()
            val start = input.readInt()
            val count = input.readInt()
            require(total >= 0 && start in 0..total)
            require(count in 0..AgentHubConversationPager.MAX_PAGE_CONVERSATIONS)
            require(start + count <= total)
            val conversations = List(count) {
                AgentHubConversationSummary(
                    id = input.readUtf8(MAX_ID_BYTES),
                    title = input.readUtf8(MAX_TITLE_BYTES),
                    preview = input.readUtf8(MAX_PREVIEW_BYTES),
                    updatedAtEpochMs = input.readLong(),
                    messageCount = input.readInt().also { require(it >= 0) },
                    current = input.readBoolean(),
                )
            }
            val next = if (input.readBoolean()) input.readInt() else null
            require(next == null || next in (start + count)..total)
            AgentHubConversationPage(
                requestId = requestId,
                conversationRevision = revision,
                status = status,
                totalConversationCount = total,
                startIndex = start,
                conversations = conversations,
                nextStartIndex = next,
                detail = input.readUtf8(MAX_DETAIL_BYTES),
            )
        }

    private inline fun encode(max: Int, block: (DataOutputStream) -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use(block)
        return sink.toByteArray().also { require(it.size <= max) }
    }

    private inline fun <T> decode(
        bytes: ByteArray,
        max: Int,
        block: (DataInputStream) -> T,
    ): T {
        require(bytes.size <= max)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val result = block(input)
        require(input.available() == 0) { "Trailing history IPC bytes" }
        return result
    }

    private fun DataOutputStream.writeUtf8(value: String, max: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= max)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(max: Int): String {
        val size = readInt()
        require(size in 0..max && size <= available())
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
        enumValues<T>().getOrNull(readInt()) ?: error("Invalid history enum ordinal")

}
