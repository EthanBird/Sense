package io.github.ethanbird.senseime.agent.ui

enum class AgentMessageRole {
    USER,
    ASSISTANT,
}

data class AgentMessageUi(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
)

enum class AgentToolKind {
    TERMINAL,
    BROWSER,
    GENERIC,
}

enum class AgentToolState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class AgentToolUi(
    val id: String,
    val kind: AgentToolKind,
    val title: String,
    val detail: String = "",
    val state: AgentToolState = AgentToolState.RUNNING,
)

data class AgentConversationUi(
    val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val current: Boolean,
)

enum class AgentActionState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class AgentActionUi(
    val requestId: String,
    val skillId: String,
    val title: String,
    val primaryValue: String = "",
    val secondaryValue: String = "",
    val insertText: String = "",
    val sourceLabel: String = "",
    val state: AgentActionState,
    val detail: String = "",
)

data class AgentUiState(
    val revision: Long = 0L,
    val title: String = "问候与求助",
    val modelGroup: String = "Default Models",
    val modelLabel: String = "Sense · Agent",
    val loaded: Boolean = false,
    val running: Boolean = false,
    val status: String = "正在读取会话…",
    val messages: List<AgentMessageUi> = emptyList(),
    val streamingText: String = "",
    val tools: List<AgentToolUi> = emptyList(),
    val selectedToolIndex: Int = 0,
    val draft: String = "",
    val draftCursor: Int = 0,
    val composing: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val historyVisible: Boolean = false,
    val menuVisible: Boolean = false,
    val openToolId: String? = null,
    val conversations: List<AgentConversationUi> = emptyList(),
    val action: AgentActionUi? = null,
)

data class AgentUiActions(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onCloseComposer: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onDismissMenu: () -> Unit = {},
    val onComposerTap: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onSlash: () -> Unit = {},
    val onVoice: () -> Unit = {},
    val onToolPrevious: () -> Unit = {},
    val onToolNext: () -> Unit = {},
    val onToolOpen: (AgentToolUi) -> Unit = {},
    val onToolClose: () -> Unit = {},
    val onCopyMessage: (AgentMessageUi) -> Unit = {},
    val onInsertMessage: (AgentMessageUi) -> Unit = {},
    val onOpenConversation: (String) -> Unit = {},
    val onGoldQuote: () -> Unit = {},
    val onCancelAction: () -> Unit = {},
    val onDismissAction: () -> Unit = {},
    val onInsertAction: (AgentActionUi) -> Unit = {},
    val onAnalyzeAction: (AgentActionUi) -> Unit = {},
)
