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

/**
 * 对话模式 shell (prototype variant B): a single chat column is the body, the
 * one workflow is a breadcrumb across the top, and the existing result/log
 * panels stay on the right. Ask bubbles are a read-only placeholder here —
 * their option interaction belongs to ticket 11.
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

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FB))) {
        ChatBreadcrumb(
            nodes = workflow.nodes,
            onOpenNode = { openNodeId = it },
            onSwitchToDefault = onSwitchToDefault,
            onSwitchToAdvanced = onSwitchToAdvanced,
        )

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().weight(0.62f)) {
                Column(
                    modifier = Modifier.fillMaxSize().weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    items.forEach { bubble -> ChatBubble(bubble) }
                }
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    running = state.running,
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotBlank() && state.send(text)) draft = ""
                    },
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
}

@Composable
private fun ChatBreadcrumb(
    nodes: List<Node>,
    onOpenNode: (String) -> Unit,
    onSwitchToDefault: () -> Unit,
    onSwitchToAdvanced: () -> Unit,
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
private fun ChatBubble(item: ChatItem) {
    val mine = item is ChatItem.User
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        val background = when (item) {
            is ChatItem.User -> Color(0xFF3F6FC7)
            is ChatItem.Error -> Color(0xFFFDECEA)
            is ChatItem.Tool -> Color(0xFFEDF2FA)
            is ChatItem.Ask -> Color(0xFFFFF4DE)
            is ChatItem.Picture -> Color(0xFFE7F5EA)
            else -> Color.White
        }
        val ink = if (mine) Color.White else Color(0xFF243049)
        Column(
            modifier = Modifier.fillMaxWidth(0.84f)
                .background(background, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            when (item) {
                is ChatItem.User -> {
                    Text("我", color = ink, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(item.text, color = ink)
                }

                is ChatItem.Assistant -> {
                    Text("助手", color = Color(0xFF66738C), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(item.text, color = ink)
                }

                is ChatItem.Tool -> {
                    Text("工具 · ${item.name}", color = Color(0xFF66738C), fontSize = 11.sp)
                    Text(item.summary, color = ink)
                    Text(
                        if (item.ok) "已接受" else "被拒绝",
                        color = if (item.ok) Color(0xFF2E7D4F) else Color(0xFFB23B3B),
                        fontSize = 11.sp,
                    )
                }

                is ChatItem.Picture -> {
                    Text("生成图片", color = Color(0xFF2E7D4F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(item.path, color = ink, fontSize = 12.sp)
                }

                is ChatItem.Error -> {
                    Text("错误", color = Color(0xFFB23B3B), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(item.text, color = Color(0xFF9B2C2C))
                }

                is ChatItem.Ask -> {
                    // Placeholder only: option buttons are ticket 11's work.
                    Text("追问 · 选项交互待开放", color = Color(0xFF8A6A22), fontSize = 11.sp)
                    Text(item.question, color = ink)
                    item.options.forEach { option -> Text("· $option", color = ink, fontSize = 12.sp) }
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
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("说一句，让 AI 改工作流并出图") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (running) {
            Text("运行中", color = Color(0xFF66738C), fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Button(onClick = onSend, enabled = !running && draft.isNotBlank()) {
            Text(if (running) "运行中" else "发送")
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
