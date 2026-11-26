package com.abrah.nightmare.desktop.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import com.abrah.nightmare.desktop.gui.ui.AppButton
import com.abrah.nightmare.desktop.gui.ui.AppCard
import com.abrah.nightmare.desktop.gui.ui.AppText
import com.abrah.nightmare.desktop.gui.ui.AppTheme

/**
 * 对话模式 shell (prototype variant B): a single chat column is the body, the
 * one workflow is a breadcrumb across the top, and the existing result/log
 * panels stay on the right. Ask bubbles are clickable here: a tap releases the
 * in-flight turn through [ChatSession.answer], and typing instead releases that
 * same Ask with CANCEL before [ChatSession.send] starts a new turn.
 */
@Composable
fun ChatModeScreen(
    state: ChatSession = remember { ChatSession() },
    onSwitchToDefault: () -> Unit = {},
    onSwitchToAdvanced: () -> Unit = {},
) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) {
        val unsubscribe = state.addChangeListener { revision++ }
        onDispose { unsubscribe() }
    }
    val items = remember(state, revision) { state.snapshot() }
    val workflow = remember(state, revision) { state.workflow }
    val runState = remember(state, revision) { state.runSnapshot() }
    var draft by remember { mutableStateOf("") }
    var openNodeId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val bubbleScroll = rememberScrollState()
    // Follow the newest chat item; earlier bubbles stay readable above.
    LaunchedEffect(items.size) {
        bubbleScroll.animateScrollTo(bubbleScroll.maxValue)
    }
    // The same process-wide settings object the production ChatModel reads.
    val settings = ChatProviderStore.settings
    DisposableEffect(settings) {
        val unsubscribe = settings.addChangeListener { revision++ }
        onDispose { unsubscribe() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FB))) {
        ChatBreadcrumb(
            nodes = workflow.nodes,
            onOpenNode = { openNodeId = it },
            onSwitchToDefault = onSwitchToDefault,
            onSwitchToAdvanced = onSwitchToAdvanced,
            configured = settings.statusLabel(),
            onOpenSettings = { settingsOpen = true },
        )

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().weight(0.62f)) {
                Column(
                    modifier = Modifier.fillMaxSize().weight(1f)
                        .verticalScroll(bubbleScroll)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    items.forEach { bubble ->
                        ChatBubble(
                            bubble,
                            onAnswer = { id, option -> state.answer(id, option) },
                            onOpenSettings = { settingsOpen = true },
                        )
                    }
                }
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    running = state.running,
                    awaitingAsk = state.awaitingAsk,
                    onSend = {
                        val text = draft.trim()
                        // A refused send (a turn is live) keeps the typed text.
                        if (text.isNotBlank() && state.send(text)) draft = ""
                    },
                    onCancel = { state.cancelTurn() },
                )
            }

            // The same listener/snapshot path as default mode: no second result
            // panel and no new event bus. 引擎日志 stays in its own panel.
            Column(modifier = Modifier.fillMaxSize().weight(0.38f)) {
                ResultPreview(
                    preview = PreviewInfo("对话出图", "输入一句描述，AI 会改工作流并运行。"),
                    results = runState.results,
                    status = runState.status,
                    modifier = Modifier.fillMaxWidth().weight(0.62f),
                )
                RunLogPanel(runState = runState, modifier = Modifier.fillMaxWidth().weight(0.38f))
            }
        }
    }

    val openNode = openNodeId?.let { id -> workflow.nodes.firstOrNull { it.id == id } }
    if (openNode != null) {
        ReadOnlyNodeDrawer(
            node = openNode,
            onClose = { openNodeId = null },
        )
    }
    if (settingsOpen) {
        ProviderSettingsDialog(settings, onClose = { settingsOpen = false })
    }
}

@Composable
private fun ChatBreadcrumb(
    nodes: List<Node>,
    onOpenNode: (String) -> Unit,
    onSwitchToDefault: () -> Unit,
    onSwitchToAdvanced: () -> Unit,
    configured: String,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onSwitchToDefault) { Text("默认模式") }
        Spacer(modifier = Modifier.width(6.dp))
        Button(onClick = onSwitchToAdvanced) { Text("高级模式") }
        Spacer(modifier = Modifier.width(12.dp))
        Text("对话 ·", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(10.dp))
        // Gear entry: only the masked status is ever shown, never the key.
        Button(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFF8FAFD),
                contentColor = Color(0xFF243049),
            ),
        ) {
            Text("设置（$configured）", fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        nodes.forEachIndexed { index, node ->
            if (index > 0) {
                Text("→", color = Color(0xFF94A0B5), modifier = Modifier.padding(horizontal = 6.dp))
            }
            val title = DesktopNodeTypes.registry[node.type]?.title ?: node.type
            Button(
                onClick = { onOpenNode(node.id) },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFF8FAFD),
                    contentColor = Color(0xFF243049),
                ),
            ) {
                Text(title, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChatBubble(
    item: ChatItem,
    onAnswer: (id: Int, option: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val mine = item is ChatItem.User
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.dims.spaceSm),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        val background = when (item) {
            is ChatItem.User -> AppTheme.colors.primary
            is ChatItem.Error -> AppTheme.colors.dangerSurface
            is ChatItem.Tool -> AppTheme.colors.toolSurface
            is ChatItem.Ask -> AppTheme.colors.askSurface
            is ChatItem.Picture -> AppTheme.colors.successSurface
            else -> AppTheme.colors.surface
        }
        val ink = if (mine) AppTheme.colors.onPrimary else AppTheme.colors.ink
        AppCard(
            modifier = Modifier.fillMaxWidth(0.84f),
            color = background,
            padding = AppTheme.dims.spaceMd,
        ) {
            when (item) {
                is ChatItem.User -> {
                    AppText("我", color = ink, fontWeight = FontWeight.Bold, fontSize = AppTheme.typography.caption)
                    AppText(item.text, color = ink)
                }

                is ChatItem.Assistant -> {
                    AppText(
                        "助手",
                        color = AppTheme.colors.muted,
                        fontWeight = FontWeight.Bold,
                        fontSize = AppTheme.typography.caption,
                    )
                    AppText(item.text, color = ink)
                }

                is ChatItem.Tool -> {
                    AppText("工具 · ${item.name}", color = AppTheme.colors.muted, fontSize = AppTheme.typography.caption)
                    AppText(item.summary, color = ink)
                    AppText(
                        if (item.ok) "已接受" else "被拒绝",
                        color = if (item.ok) AppTheme.colors.successInk else AppTheme.colors.danger,
                        fontSize = AppTheme.typography.caption,
                    )
                }

                is ChatItem.Picture -> {
                    AppText(
                        "生成图片",
                        color = AppTheme.colors.successInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = AppTheme.typography.caption,
                    )
                    AppText(item.path, color = ink, fontSize = AppTheme.typography.bodySmall)
                }

                is ChatItem.Error -> {
                    val issue = providerIssueOf(item.text)
                    AppText(
                        "错误",
                        color = AppTheme.colors.danger,
                        fontWeight = FontWeight.Bold,
                        fontSize = AppTheme.typography.caption,
                    )
                    // Two provider classes get fixed, key-free text and a settings exit.
                    // No retry is triggered here and the workflow is untouched.
                    AppText(
                        if (issue == ProviderIssue.OTHER) item.text else providerIssueText(item.text),
                        color = AppTheme.colors.dangerInk,
                    )
                    Spacer(modifier = Modifier.height(AppTheme.dims.spaceSm))
                    AppButton("打开设置", onClick = onOpenSettings)
                }

                is ChatItem.Ask -> {
                    AppText(
                        "追问",
                        color = AppTheme.colors.askInk,
                        fontSize = AppTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                    AppText(item.question, color = ink)
                    if (item.answer == AskGate.CANCEL) {
                        AppText("已取消", color = AppTheme.colors.askInk, fontSize = AppTheme.typography.bodySmall)
                    } else {
                        item.options.forEach { option ->
                            val chosen = item.answer == option
                            AppButton(
                                text = if (chosen) "✓ $option" else option,
                                onClick = { onAnswer(item.id, option) },
                                enabled = item.answer == null,
                                modifier = Modifier.fillMaxWidth().padding(top = AppTheme.dims.spaceXs),
                                backgroundColor = if (chosen) AppTheme.colors.askSelected else AppTheme.colors.surface,
                                contentColor = AppTheme.colors.ink,
                            )
                        }
                        item.answer?.let {
                            AppText("已选择：$it", color = ink, fontSize = AppTheme.typography.caption)
                        }
                    }
                }

                is ChatItem.Log -> Unit // never a bubble: 引擎日志 lives in RunLogPanel.
            }
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    awaitingAsk: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = {
                Text(
                    if (awaitingAsk) "追问中 · 输入新指令会先放行这次追问"
                    else "说一句，让 AI 改工作流并出图",
                )
            },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (running && !awaitingAsk) {
            Text("运行中", color = Color(0xFF66738C), fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        // An Ask wait is not busy work: typing here is the documented bypass.
        Button(onClick = onSend, enabled = (!running || awaitingAsk) && draft.isNotBlank()) {
            Text(if (running && !awaitingAsk) "运行中" else if (awaitingAsk) "发送新指令" else "发送")
        }
        if (running) {
            Spacer(modifier = Modifier.width(8.dp))
            // The one user-facing cancel entry: ChatSession cancels the turn
            // cooperatively (and releases any waiting Ask) through one path.
            Button(onClick = onCancel) { Text("取消", fontSize = 12.sp) }
        }
    }
}

/** Read-only view of what AI changed in one workflow node; editing stays in 默认/高级. */
@Composable
private fun ReadOnlyNodeDrawer(node: Node, onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().width(340.dp)
                .background(Color.White)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("只读 · 工作流节点", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            Text(DesktopNodeTypes.registry[node.type]?.title ?: node.type, fontWeight = FontWeight.Bold)
            Text("节点 id：${node.id}", color = Color(0xFF66738C), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            node.params.forEach { (key, value) ->
                Text("$key：$value", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onClose) { Text("关闭") }
        }
    }
}
