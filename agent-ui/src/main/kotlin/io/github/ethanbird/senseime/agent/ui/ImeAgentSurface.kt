package io.github.ethanbird.senseime.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ImeAgentSurface(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    SenseAgentTheme {
        val palette = LocalAgentPalette.current
        if (state.historyVisible) {
            AgentHistoryScreen(state = state, actions = actions, modifier = modifier)
            return@SenseAgentTheme
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background),
        ) {
            Column(Modifier.fillMaxSize()) {
                AgentHeader(state, actions)
                AgentConversation(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
                if (state.tools.isNotEmpty()) {
                    ToolLiveDock(
                        tools = state.tools,
                        selectedIndex = state.selectedToolIndex,
                        actions = actions,
                    )
                }
                AgentComposer(state, actions)
            }
            if (state.menuVisible) {
                val dismissInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = dismissInteraction,
                            indication = null,
                            onClick = actions.onDismissMenu,
                        ),
                )
            }
            state.openToolId
                ?.let { id -> state.tools.firstOrNull { it.id == id } }
                ?.let { tool ->
                    ToolDetailSurface(
                        tool = tool,
                        running = state.running,
                        actions = actions,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            AnimatedVisibility(
                visible = state.menuVisible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 54.dp),
            ) {
                AgentOverflowMenu(state, actions)
            }
        }
    }
}

@Composable
private fun ToolDetailSurface(
    tool: AgentToolUi,
    running: Boolean,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    Column(
        modifier
            .background(palette.background)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleTextButton(
                text = "‹",
                contentDescription = "收起工具详情",
                onClick = actions.onToolClose,
                textSize = 30f,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (tool.kind) {
                        AgentToolKind.TERMINAL -> "Terminal"
                        AgentToolKind.BROWSER -> "Browser"
                        AgentToolKind.GENERIC -> "Agent Tool"
                    },
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tool.title,
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ToolStateIcon(tool.state)
            Text(
                text = toolStateLabel(tool.state),
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, end = 7.dp),
            )
        }
        when (tool.kind) {
            AgentToolKind.TERMINAL -> TerminalDetailBody(tool, Modifier.weight(1f))
            AgentToolKind.BROWSER -> BrowserDetailBody(tool, Modifier.weight(1f))
            AgentToolKind.GENERIC -> GenericToolDetailBody(tool, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (running && tool.state == AgentToolState.RUNNING) {
                ToolDetailAction("停止", palette.danger, actions.onStop)
                Spacer(Modifier.width(8.dp))
            }
            ToolDetailAction("收起", palette.textPrimary, actions.onToolClose)
        }
    }
}

@Composable
private fun TerminalDetailBody(
    tool: AgentToolUi,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    val output = tool.detail
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .ifBlank { "工具正在运行，输出到达后会显示在这里。" }
    Column(
        modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBackground)
            .padding(13.dp),
    ) {
        Text(
            text = "$ ${tool.title}",
            color = palette.codeText,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = output,
            color = palette.codeText.copy(alpha = 0.88f),
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun BrowserDetailBody(
    tool: AgentToolUi,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(palette.secondaryBackground, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹  ›  ↻", color = palette.textSecondary, fontSize = 16.sp)
            Text(
                text = tool.detail.ifBlank { "Browser session" },
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8F1EE))
                .border(0.5.dp, palette.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▣", color = Color(0xFF275B47), fontSize = 38.sp)
                Text(
                    text = if (tool.state == AgentToolState.RUNNING) {
                        "浏览器正在执行页面操作"
                    } else {
                        "浏览器操作已记录"
                    },
                    color = Color(0xFF275B47),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GenericToolDetailBody(
    tool: AgentToolUi,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    Column(
        modifier
            .padding(horizontal = 14.dp)
            .background(palette.secondaryBackground, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = tool.title,
            color = palette.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = tool.detail.ifBlank { toolStateLabel(tool.state) },
            color = palette.textSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ToolDetailAction(
    label: String,
    color: Color,
    action: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(38.dp)
            .clickable(onClick = action),
        shape = RoundedCornerShape(19.dp),
        color = color.copy(alpha = 0.10f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AgentHeader(
    state: AgentUiState,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .padding(horizontal = 12.dp),
    ) {
        CircleTextButton(
            text = "⌄",
            contentDescription = "收起 Agent",
            onClick = actions.onClose,
            modifier = Modifier.align(Alignment.CenterStart),
            textSize = 25f,
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = actions.onHistory),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            if (state.loaded) palette.accent else palette.textTertiary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = state.modelGroup,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                )
                Text(
                    text = "⌄",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
            Text(
                text = state.modelLabel,
                color = palette.textTertiary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircleTextButton(
            text = "⋮",
            contentDescription = "更多",
            onClick = actions.onMore,
            modifier = Modifier.align(Alignment.CenterEnd),
            textSize = 24f,
        )
    }
}

@Composable
private fun AgentOverflowMenu(
    state: AgentUiState,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    Surface(
        modifier = Modifier
            .width(184.dp)
            .shadow(12.dp, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = palette.toolSurface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
    ) {
        Column(Modifier.padding(vertical = 5.dp)) {
            AgentMenuRow(
                label = if (state.running) "停止当前任务" else "新会话",
                color = if (state.running) palette.danger else palette.textPrimary,
            ) {
                if (state.running) actions.onStop() else actions.onNewChat()
                actions.onDismissMenu()
            }
            AgentMenuRow("会话历史", palette.textPrimary) {
                actions.onHistory()
                actions.onDismissMenu()
            }
            AgentMenuRow("收起到键盘", palette.textPrimary) {
                actions.onClose()
                actions.onDismissMenu()
            }
        }
    }
}

@Composable
private fun AgentMenuRow(
    label: String,
    color: Color,
    action: () -> Unit,
) {
    Text(
        text = label,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = action)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

@Composable
private fun AgentHistoryScreen(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    Column(
        modifier
            .fillMaxSize()
            .background(palette.secondaryBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleTextButton(
                text = "‹",
                contentDescription = "返回会话",
                onClick = actions.onHistory,
                textSize = 30f,
            )
            Text(
                text = "Sense Agent",
                color = palette.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.running) "◉" else "✦",
                color = if (state.running) palette.accent else palette.textPrimary,
                fontSize = 23.sp,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "返回当前会话" }
                    .clickable(onClick = actions.onHistory)
                    .padding(top = 7.dp),
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = "完整记录",
            color = palette.textSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp),
        )
        if (state.conversations.isEmpty()) {
            Text(
                text = "每次新会话后，当前对话会完整归档在这里",
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.conversations, key = AgentConversationUi::id) { conversation ->
                    AgentHistoryRow(
                        conversation = conversation,
                        running = state.running && conversation.current,
                        onClick = { actions.onOpenConversation(conversation.id) },
                    )
                }
            }
        }
        if (state.conversations.isEmpty()) Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryFab(
                label = "＋",
                background = Color(0xFFB7AF96),
                foreground = Color.White,
                onClick = actions.onNewChat,
            )
            Spacer(Modifier.weight(1f))
            HistoryFab(
                label = "↩",
                background = palette.toolSurface,
                foreground = palette.textPrimary,
                onClick = actions.onHistory,
            )
        }
    }
}

@Composable
private fun AgentHistoryRow(
    conversation: AgentConversationUi,
    running: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalAgentPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (conversation.current) palette.accentSoft else palette.toolSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (conversation.current) "▣" else "□",
                        color = if (conversation.current) palette.accent else palette.textSecondary,
                        fontSize = 20.sp,
                    )
                }
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(50.dp),
                    color = palette.accent,
                    strokeWidth = 2.dp,
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(start = 15.dp)
                .weight(1f),
        ) {
            Text(
                text = conversation.title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.preview,
                color = palette.textSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            text = if (running) "运行中" else "${conversation.messageCount} 条",
            color = if (running) palette.accent else palette.textTertiary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun HistoryFab(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = foreground, fontSize = 27.sp)
        }
    }
}

@Composable
private fun AgentConversation(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val totalItems = state.messages.size + state.tools.size + when {
        state.streamingText.isNotBlank() -> 1
        state.running && state.tools.isEmpty() -> 1
        state.messages.isEmpty() -> 1
        else -> 0
    }
    var previousMessageCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.messages.size, state.streamingText.length, state.tools.size) {
        val receivedNewMessage = state.messages.size > previousMessageCount
        previousMessageCount = state.messages.size
        if (totalItems > 0 && (receivedNewMessage || !listState.canScrollForward)) {
            listState.scrollToItem(totalItems - 1)
        }
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 5.dp,
                bottom = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (state.messages.isEmpty() && state.streamingText.isBlank()) {
                item(key = "empty") {
                    EmptyConversation(state.loaded)
                }
            }
            items(state.messages, key = AgentMessageUi::id) { message ->
                when (message.role) {
                    AgentMessageRole.USER -> UserMessage(message)
                    AgentMessageRole.ASSISTANT -> AssistantMessage(message, actions)
                }
            }
            items(state.tools, key = { "tool-pill-${it.id}" }) { tool ->
                ToolCallPill(tool = tool, onClick = { actions.onToolOpen(tool) })
            }
            if (state.streamingText.isNotBlank()) {
                item(key = "streaming") {
                    StreamingAssistantMessage(state.streamingText)
                }
            }
            if (state.running && state.streamingText.isBlank() && state.tools.isEmpty()) {
                item(key = "running-status") {
                    ThinkingRow(state.status)
                }
            }
        }
        AnimatedVisibility(
            visible = listState.canScrollForward,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .shadow(7.dp, CircleShape)
                    .clickable {
                        if (totalItems > 0) {
                            scope.launch { listState.animateScrollToItem(totalItems - 1) }
                        }
                    },
                shape = CircleShape,
                color = palette.toolSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↓", color = palette.textPrimary, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(loaded: Boolean) {
    val palette = LocalAgentPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✦", color = palette.accent, fontSize = 28.sp)
        Text(
            text = if (loaded) "今天想一起完成什么？" else "正在恢复最近会话…",
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "可以直接对话，也可以让 Sense 使用终端或浏览器。",
            color = palette.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun UserMessage(message: AgentMessageUi) {
    val palette = LocalAgentPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 310.dp),
            shape = RoundedCornerShape(18.dp),
            color = palette.userBubble,
        ) {
            Text(
                text = message.text,
                color = palette.textPrimary,
                fontSize = 16.5.sp,
                lineHeight = 23.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: AgentMessageUi,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        AssistantIdentity()
        MarkdownContent(
            markdown = message.text,
            modifier = Modifier.padding(top = 7.dp),
        )
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                text = "写入当前输入框",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { actions.onInsertMessage(message) }
                    .padding(vertical = 5.dp, horizontal = 2.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "复制",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { actions.onCopyMessage(message) }
                    .padding(vertical = 5.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun StreamingAssistantMessage(text: String) {
    val palette = LocalAgentPalette.current
    Column(Modifier.fillMaxWidth()) {
        AssistantIdentity()
        MarkdownContent(
            markdown = text,
            modifier = Modifier.padding(top = 7.dp),
            streaming = true,
        )
        Box(
            Modifier
                .padding(top = 5.dp)
                .width(7.dp)
                .height(16.dp)
                .background(palette.accent, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun AssistantIdentity() {
    val palette = LocalAgentPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✦", color = palette.accent, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Sense",
            color = palette.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ThinkingRow(status: String) {
    val palette = LocalAgentPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = palette.accent,
            strokeWidth = 2.dp,
        )
        Text(
            text = status,
            color = palette.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

@Composable
private fun ToolCallPill(
    tool: AgentToolUi,
    onClick: () -> Unit,
) {
    val palette = LocalAgentPalette.current
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clickable(onClick = onClick),
        color = palette.toolCapsule,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            ToolStateIcon(tool.state)
            Text(
                text = tool.title,
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
            Text(
                text = toolStateLabel(tool.state),
                color = palette.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ToolLiveDock(
    tools: List<AgentToolUi>,
    selectedIndex: Int,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    val index = selectedIndex.coerceIn(0, tools.lastIndex)
    val tool = tools[index]
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .align(Alignment.BottomStart)
                .shadow(8.dp, RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = palette.toolSurface,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
        ) {
            Row(
                modifier = Modifier.padding(start = 118.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolStateIcon(tool.state)
                Text(
                    text = tool.title,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .weight(1f),
                )
                Text(
                    text = "${index + 1}/${tools.size}",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                DockArrow("‹", index > 0, actions.onToolPrevious)
                DockArrow("›", index < tools.lastIndex, actions.onToolNext)
            }
        }
        ToolPreviewThumbnail(
            tool = tool,
            modifier = Modifier
                .offset(x = 10.dp)
                .size(width = 100.dp, height = 65.dp)
                .align(Alignment.TopStart)
                .shadow(10.dp, RoundedCornerShape(8.dp))
                .clickable { actions.onToolOpen(tool) },
        )
    }
}

@Composable
private fun ToolPreviewThumbnail(
    tool: AgentToolUi,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAgentPalette.current
    val terminal = tool.kind == AgentToolKind.TERMINAL
    val background = if (terminal) Color(0xFF131418) else Color(0xFFE8F1EE)
    val foreground = if (terminal) Color(0xFF8CF38C) else Color(0xFF275B47)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(0.5.dp, palette.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Text(
            text = when (tool.kind) {
                AgentToolKind.TERMINAL -> "$ ${tool.title}"
                AgentToolKind.BROWSER -> "▣ ${tool.title}"
                AgentToolKind.GENERIC -> "✦ ${tool.title}"
            },
            color = foreground,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = tool.detail.ifBlank { toolStateLabel(tool.state) },
            color = foreground.copy(alpha = 0.84f),
            fontSize = 5.8.sp,
            lineHeight = 7.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ToolStateIcon(state: AgentToolState) {
    val palette = LocalAgentPalette.current
    if (state == AgentToolState.RUNNING || state == AgentToolState.QUEUED) {
        CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = palette.accent,
            strokeWidth = 2.dp,
        )
    } else {
        Text(
            text = if (state == AgentToolState.SUCCEEDED) "✓" else "!",
            color = if (state == AgentToolState.SUCCEEDED) palette.accent else palette.danger,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun toolStateLabel(state: AgentToolState): String = when (state) {
    AgentToolState.QUEUED -> "等待"
    AgentToolState.RUNNING -> "运行中"
    AgentToolState.SUCCEEDED -> "完成"
    AgentToolState.FAILED -> "失败"
}

@Composable
private fun DockArrow(
    text: String,
    enabled: Boolean,
    action: () -> Unit,
) {
    val palette = LocalAgentPalette.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.textPrimary else palette.textTertiary,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun AgentComposer(
    state: AgentUiState,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = palette.composerBackground,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
    ) {
        if (state.composing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp, max = 108.dp)
                    .clickable(onClick = actions.onComposerTap)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerText(state, Modifier.weight(1f))
                SmallComposerButton("⌄", "收起输入框", actions.onCloseComposer)
                Spacer(Modifier.width(8.dp))
                SendOrStopButton(state, actions)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onComposerTap)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                ComposerText(state, Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallComposerButton("＋", "添加", actions.onAdd)
                    Spacer(Modifier.width(8.dp))
                    SmallComposerButton("／", "快捷指令", actions.onSlash)
                    Spacer(Modifier.weight(1f))
                    SmallComposerButton("♩", "语音", actions.onVoice)
                    Spacer(Modifier.width(8.dp))
                    SendOrStopButton(state, actions)
                }
            }
        }
    }
}

@Composable
private fun ComposerText(
    state: AgentUiState,
    modifier: Modifier,
) {
    val palette = LocalAgentPalette.current
    val visibleDraft = if (state.composing) {
        val cursor = state.draftCursor.coerceIn(0, state.draft.length)
        state.draft.substring(0, cursor) + "▏" + state.draft.substring(cursor)
    } else {
        state.draft
    }
    Text(
        text = visibleDraft.ifEmpty { "发消息给 Sense（@ 提及文件）" },
        color = if (state.draft.isEmpty() && !state.composing) {
            palette.textTertiary
        } else {
            palette.textPrimary
        },
        fontSize = 16.5.sp,
        lineHeight = 22.sp,
        maxLines = if (state.composing) 4 else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun SendOrStopButton(
    state: AgentUiState,
    actions: AgentUiActions,
) {
    val palette = LocalAgentPalette.current
    val stop = state.running
    val enabled = stop || (state.loaded && state.draft.isNotBlank())
    Surface(
        modifier = Modifier
            .size(38.dp)
            .clickable(enabled = enabled) {
                if (stop) actions.onStop() else actions.onSend()
            },
        shape = CircleShape,
        color = when {
            stop -> palette.danger
            enabled -> palette.textPrimary
            else -> palette.secondaryBackground
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (stop) "■" else "↑",
                color = when {
                    stop -> Color.White
                    enabled -> palette.background
                    else -> palette.textTertiary
                },
                fontSize = if (stop) 12.sp else 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SmallComposerButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val palette = LocalAgentPalette.current
    Surface(
        modifier = Modifier
            .size(38.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = palette.secondaryBackground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = palette.textPrimary,
                fontSize = 21.sp,
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun CircleTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: Float = 20f,
) {
    val palette = LocalAgentPalette.current
    Box(
        modifier = modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = palette.textPrimary, fontSize = textSize.sp)
    }
}
