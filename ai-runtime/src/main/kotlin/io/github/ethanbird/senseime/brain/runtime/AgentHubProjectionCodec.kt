package io.github.ethanbird.senseime.brain.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Bounded cross-process projection codec.
 *
 * The durable store remains authoritative and retains its full configured history. Only the wire
 * projection is reduced to the newest messages and bounded display fields so one Binder update can
 * never approach the platform transaction limit.
 */
internal object AgentHubProjectionCodec {
    const val MAX_WIRE_BYTES = 248 * 1024
    const val MAX_WIRE_MESSAGES = 28
    const val MAX_WIRE_TOOLS = 12
    const val MAX_WIRE_CONVERSATIONS = 16

    private const val MAGIC = 0x53485052 // SHPR
    private const val VERSION = 3
    private const val MAX_LATEST_MESSAGE_TEXT_BYTES = 40 * 1024
    private const val MAX_OLDER_MESSAGE_TEXT_BYTES = 3 * 1024
    private const val MAX_PREVIEW_BYTES = 32 * 1024
    private const val MAX_STATUS_BYTES = 512
    private const val MAX_ID_BYTES = 256
    private const val MAX_TOOL_NAME_BYTES = 128
    private const val MAX_TITLE_BYTES = 384
    private const val MAX_DETAIL_BYTES = 1024
    private const val MAX_CONVERSATION_PREVIEW_BYTES = 768
    private const val MAX_ACTION_VALUE_BYTES = 4 * 1024
    private const val MAX_ACTION_INSERT_BYTES = 12 * 1024
    private const val MAX_ACTION_DETAIL_BYTES = 2 * 1024

    fun encode(source: AgentHubProjection): ByteArray {
        val value = source.forWire()
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(value.revision)
            output.writeLong(value.conversationRevision)
            output.writeBoolean(value.loaded)
            output.writeBoolean(value.running)
            output.writeUtf8(value.preview, MAX_PREVIEW_BYTES)
            output.writeUtf8(value.status, MAX_STATUS_BYTES)
            output.writeNullableUtf8(value.requestId, MAX_ID_BYTES)
            output.writeLong(value.generation)
            output.writeInt(value.inputTokens)
            output.writeInt(value.outputTokens)
            output.writeBoolean(value.actionSkillsEnabled)
            output.writeInt(value.messageWindowStart)
            output.writeInt(value.messageTotalCount)
            output.writeInt(value.conversationWindowStart)
            output.writeInt(value.conversationTotalCount)

            output.writeInt(value.messages.size)
            value.messages.forEachIndexed { index, message ->
                output.writeInt(message.role.ordinal)
                output.writeUtf8(
                    message.text,
                    if (index == value.messages.lastIndex) {
                        MAX_LATEST_MESSAGE_TEXT_BYTES
                    } else {
                        MAX_OLDER_MESSAGE_TEXT_BYTES
                    },
                )
                output.writeLong(message.createdAtEpochMs)
                output.writeBoolean(message.wireTruncated)
            }

            output.writeInt(value.tools.size)
            value.tools.forEach { tool ->
                output.writeUtf8(tool.id, MAX_ID_BYTES)
                output.writeNullableUtf8(tool.toolCallId, MAX_ID_BYTES)
                output.writeNullableUtf8(tool.toolName, MAX_TOOL_NAME_BYTES)
                output.writeUtf8(tool.title, MAX_TITLE_BYTES)
                output.writeUtf8(tool.detail, MAX_DETAIL_BYTES)
                output.writeInt(tool.state.ordinal)
            }

            output.writeInt(value.conversations.size)
            value.conversations.forEach { conversation ->
                output.writeUtf8(conversation.id, MAX_ID_BYTES)
                output.writeUtf8(conversation.title, MAX_TITLE_BYTES)
                output.writeUtf8(conversation.preview, MAX_CONVERSATION_PREVIEW_BYTES)
                output.writeLong(conversation.updatedAtEpochMs)
                output.writeInt(conversation.messageCount)
                output.writeBoolean(conversation.current)
            }

            output.writeBoolean(value.action != null)
            value.action?.let { action ->
                output.writeUtf8(action.requestId, MAX_ID_BYTES)
                output.writeUtf8(action.skillId, MAX_ID_BYTES)
                output.writeUtf8(action.title, MAX_TITLE_BYTES)
                output.writeUtf8(action.primaryValue, MAX_ACTION_VALUE_BYTES)
                output.writeUtf8(action.secondaryValue, MAX_ACTION_VALUE_BYTES)
                output.writeUtf8(action.insertText, MAX_ACTION_INSERT_BYTES)
                output.writeUtf8(action.sourceLabel, MAX_TITLE_BYTES)
                output.writeInt(action.state.ordinal)
                output.writeUtf8(action.detail, MAX_ACTION_DETAIL_BYTES)
            }
        }
        return sink.toByteArray().also { bytes ->
            require(bytes.size <= MAX_WIRE_BYTES) {
                "Agent Hub projection exceeds $MAX_WIRE_BYTES bytes"
            }
        }
    }

    fun decode(bytes: ByteArray): AgentHubProjection {
        require(bytes.size <= MAX_WIRE_BYTES) {
            "Agent Hub projection exceeds $MAX_WIRE_BYTES bytes"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid Agent Hub projection magic" }
        require(input.readInt() == VERSION) { "Unsupported Agent Hub projection version" }
        val revision = input.readLong()
        val conversationRevision = input.readLong()
        val loaded = input.readBoolean()
        val running = input.readBoolean()
        val preview = input.readUtf8(MAX_PREVIEW_BYTES)
        val status = input.readUtf8(MAX_STATUS_BYTES)
        val requestId = input.readNullableUtf8(MAX_ID_BYTES)
        val generation = input.readLong()
        val inputTokens = input.readInt()
        val outputTokens = input.readInt()
        val actionSkillsEnabled = input.readBoolean()
        val messageWindowStart = input.readInt()
        val messageTotalCount = input.readInt()
        require(messageWindowStart >= 0 && messageTotalCount >= messageWindowStart)
        val conversationWindowStart = input.readInt()
        val conversationTotalCount = input.readInt()
        require(
            conversationWindowStart >= 0 &&
                conversationTotalCount >= conversationWindowStart,
        )

        val messageCount = input.readBoundedCount(MAX_WIRE_MESSAGES)
        val messages = List(messageCount) { index ->
            AgentHubMessage(
                role = input.readEnum(),
                text = input.readUtf8(
                    if (index == messageCount - 1) {
                        MAX_LATEST_MESSAGE_TEXT_BYTES
                    } else {
                        MAX_OLDER_MESSAGE_TEXT_BYTES
                    },
                ),
                createdAtEpochMs = input.readLong(),
                wireTruncated = input.readBoolean(),
            )
        }
        require(messageWindowStart + messageCount <= messageTotalCount)
        val tools = input.readCount(MAX_WIRE_TOOLS) {
            AgentHubToolStep(
                id = input.readUtf8(MAX_ID_BYTES),
                toolCallId = input.readNullableUtf8(MAX_ID_BYTES),
                toolName = input.readNullableUtf8(MAX_TOOL_NAME_BYTES),
                title = input.readUtf8(MAX_TITLE_BYTES),
                detail = input.readUtf8(MAX_DETAIL_BYTES),
                state = input.readEnum(),
            )
        }
        val conversations = input.readCount(MAX_WIRE_CONVERSATIONS) {
            AgentHubConversationSummary(
                id = input.readUtf8(MAX_ID_BYTES),
                title = input.readUtf8(MAX_TITLE_BYTES),
                preview = input.readUtf8(MAX_CONVERSATION_PREVIEW_BYTES),
                updatedAtEpochMs = input.readLong(),
                messageCount = input.readInt(),
                current = input.readBoolean(),
            )
        }
        require(conversationWindowStart + conversations.size <= conversationTotalCount)
        val action = if (input.readBoolean()) {
            AgentHubActionCard(
                requestId = input.readUtf8(MAX_ID_BYTES),
                skillId = input.readUtf8(MAX_ID_BYTES),
                title = input.readUtf8(MAX_TITLE_BYTES),
                primaryValue = input.readUtf8(MAX_ACTION_VALUE_BYTES),
                secondaryValue = input.readUtf8(MAX_ACTION_VALUE_BYTES),
                insertText = input.readUtf8(MAX_ACTION_INSERT_BYTES),
                sourceLabel = input.readUtf8(MAX_TITLE_BYTES),
                state = input.readEnum(),
                detail = input.readUtf8(MAX_ACTION_DETAIL_BYTES),
            )
        } else {
            null
        }
        require(input.available() == 0) { "Trailing Agent Hub projection bytes" }
        return AgentHubProjection(
            revision = revision,
            conversationRevision = conversationRevision,
            loaded = loaded,
            messages = messages,
            tools = tools,
            preview = preview,
            status = status,
            running = running,
            requestId = requestId,
            generation = generation,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            conversations = conversations,
            action = action,
            actionSkillsEnabled = actionSkillsEnabled,
            messageWindowStart = messageWindowStart,
            messageTotalCount = messageTotalCount,
            conversationWindowStart = conversationWindowStart,
            conversationTotalCount = conversationTotalCount,
        )
    }

    private fun AgentHubProjection.forWire(): AgentHubProjection {
        val retainedMessages = messages.takeLast(MAX_WIRE_MESSAGES)
        val windowStart = (messages.size - retainedMessages.size).coerceAtLeast(0)
        val retainedConversations = conversations.take(MAX_WIRE_CONVERSATIONS)
        return copy(
            messages = retainedMessages.mapIndexed { index, message ->
                val bounded = message.text.boundedUtf8(
                    maxBytes = if (index == retainedMessages.lastIndex) {
                        MAX_LATEST_MESSAGE_TEXT_BYTES
                    } else {
                        MAX_OLDER_MESSAGE_TEXT_BYTES
                    },
                    markTruncated = true,
                )
                message.copy(
                    text = bounded,
                    wireTruncated = message.wireTruncated || bounded != message.text,
                )
            },
            messageWindowStart = windowStart,
            messageTotalCount = messages.size,
            tools = tools.takeLast(MAX_WIRE_TOOLS).map { tool ->
                tool.copy(
                    id = tool.id.boundedUtf8(MAX_ID_BYTES),
                    toolCallId = tool.toolCallId?.boundedUtf8(MAX_ID_BYTES),
                    toolName = tool.toolName?.boundedUtf8(MAX_TOOL_NAME_BYTES),
                    title = tool.title.boundedUtf8(MAX_TITLE_BYTES, markTruncated = true),
                    detail = tool.detail.boundedUtf8(MAX_DETAIL_BYTES, markTruncated = true),
                )
            },
            preview = preview.boundedUtf8(MAX_PREVIEW_BYTES, markTruncated = true),
            status = status.boundedUtf8(MAX_STATUS_BYTES, markTruncated = true),
            requestId = requestId?.boundedUtf8(MAX_ID_BYTES),
            conversations = retainedConversations.map { conversation ->
                conversation.copy(
                    id = conversation.id.boundedUtf8(MAX_ID_BYTES),
                    title = conversation.title.boundedUtf8(
                        MAX_TITLE_BYTES,
                        markTruncated = true,
                    ),
                    preview = conversation.preview.boundedUtf8(
                        MAX_CONVERSATION_PREVIEW_BYTES,
                        markTruncated = true,
                    ),
                )
            },
            conversationWindowStart = 0,
            conversationTotalCount = conversations.size,
            action = action?.let { action ->
                action.copy(
                    requestId = action.requestId.boundedUtf8(MAX_ID_BYTES),
                    skillId = action.skillId.boundedUtf8(MAX_ID_BYTES),
                    title = action.title.boundedUtf8(MAX_TITLE_BYTES, markTruncated = true),
                    primaryValue = action.primaryValue.boundedUtf8(
                        MAX_ACTION_VALUE_BYTES,
                        markTruncated = true,
                    ),
                    secondaryValue = action.secondaryValue.boundedUtf8(
                        MAX_ACTION_VALUE_BYTES,
                        markTruncated = true,
                    ),
                    insertText = action.insertText.boundedUtf8(
                        MAX_ACTION_INSERT_BYTES,
                        markTruncated = true,
                    ),
                    sourceLabel = action.sourceLabel.boundedUtf8(
                        MAX_TITLE_BYTES,
                        markTruncated = true,
                    ),
                    detail = action.detail.boundedUtf8(
                        MAX_ACTION_DETAIL_BYTES,
                        markTruncated = true,
                    ),
                )
            },
        )
    }

    private fun DataOutputStream.writeUtf8(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= maxBytes)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?, maxBytes: Int) {
        writeBoolean(value != null)
        if (value != null) writeUtf8(value, maxBytes)
    }

    private fun DataInputStream.readUtf8(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes && size <= available()) { "Invalid UTF-8 field size" }
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableUtf8(maxBytes: Int): String? =
        if (readBoolean()) readUtf8(maxBytes) else null

    private inline fun <T> DataInputStream.readCount(max: Int, read: () -> T): List<T> {
        val count = readBoundedCount(max)
        return List(count) { read() }
    }

    private fun DataInputStream.readBoundedCount(max: Int): Int = readInt().also { count ->
        require(count in 0..max) { "Invalid projection item count" }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val ordinal = readInt()
        return enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("Invalid enum ordinal")
    }

    private fun String.boundedUtf8(maxBytes: Int, markTruncated: Boolean = false): String {
        if (toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return this
        val suffix = if (markTruncated) "…" else ""
        val contentBudget = (maxBytes - suffix.toByteArray(StandardCharsets.UTF_8).size)
            .coerceAtLeast(0)
        val result = StringBuilder(length.coerceAtMost(maxBytes))
        var index = 0
        var used = 0
        while (index < length) {
            val first = this[index]
            val validPair = Character.isHighSurrogate(first) &&
                index + 1 < length && Character.isLowSurrogate(this[index + 1])
            val codePoint = when {
                validPair -> Character.toCodePoint(first, this[index + 1])
                Character.isSurrogate(first) -> 0xFFFD
                else -> first.code
            }
            val byteCount = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (used + byteCount > contentBudget) break
            result.appendCodePoint(codePoint)
            used += byteCount
            index += if (validPair) 2 else 1
        }
        return result.append(suffix).toString()
    }
}
