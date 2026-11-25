package com.abrah.nightmare.desktop.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
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

/**
 * 默认模式 shell: prototype C's chain rail, large result preview, parameter
 * drawer and one log panel for progress events plus raw engine output. It
 * renders only the shared workflow/run state; no second chain is kept.
 */
@Composable
fun DefaultModeScreen(state: DefaultModeState = remember { DefaultModeState() }) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) {
        val unsubscribe = state.addChangeListener { revision++ }
        onDispose {
            unsubscribe()
            // Window close must not leave a background CLI behind (ADR-0009).
            WorkflowRunner.cancelActiveProcess(state.runHandle())
        }
    }
    val snapshot = remember(state, revision) { state.snapshot() }
    val runState = remember(state, revision) { state.runSnapshot() }
    var timeoutMinutes by remember {
        mutableStateOf((GuiRunSettings.timeoutMillis / 60_000L).toString())
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FB)),
    ) {
        ChainRail(
            snapshot = snapshot,
            runState = runState,
            onSelect = { nodeId -> state.selectNode(nodeId) },
            onRun = {
                // run() blocks on the child process; keep the shell responsive.
                Thread { state.triggerRun() }.apply { isDaemon = true }.start()
            },
            onCancel = { WorkflowRunner.cancelActiveProcess(state.runHandle()) },
            timeoutMinutes = timeoutMinutes,
            onTimeoutChange = { text ->
                timeoutMinutes = text
                text.toLongOrNull()?.takeIf { it > 0 }?.let {
                    GuiRunSettings.timeoutMillis = it * 60_000L
                }
            },
        )

        if (runState.status == RunStatus.FAILURE || runState.status == RunStatus.CANCELLED) {
            RunStatusBanner(runState, modifier = Modifier.fillMaxWidth())
        }

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            ResultPreview(
                preview = snapshot.preview,
                results = runState.results,
                status = runState.status,
                modifier = Modifier.weight(0.58f),
            )
            ParameterDrawer(
                snapshot = snapshot,
                onEdit = { key, value -> state.setSelectedParameter(key, value) },
                modifier = Modifier.weight(0.42f),
            )
        }

        RunLogPanel(runState = runState, modifier = Modifier.fillMaxWidth().height(160.dp))
    }
}

@Composable
private fun ChainRail(
    snapshot: DefaultModeSnapshot,
    runState: RunSnapshot,
    onSelect: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    timeoutMinutes: String,
    onTimeoutChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color.White)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshot.chips.forEachIndexed { index, chip ->
            if (index > 0) {
                Text("→", color = Color(0xFF94A0B5), modifier = Modifier.padding(horizontal = 8.dp))
            }
            val selected = chip.nodeId == snapshot.selectedNodeId
            Button(
                onClick = { onSelect(chip.nodeId) },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (selected) Color(0xFF3F6FC7) else Color(0xFFF8FAFD),
                    contentColor = if (selected) Color.White else Color(0xFF243049),
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(chip.title, fontWeight = FontWeight.Bold)
                    Text(
                        chip.summary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        val running = runState.status == RunStatus.RUNNING
        val total = runState.progress.lastOrNull()?.total ?: 0
        Text(
            when {
                running -> "生成中 ${runState.progress.size}/$total"
                runState.status == RunStatus.SUCCESS -> "完成 · ${runState.results.size} 张"
                runState.error != null -> runState.error.orEmpty()
                else -> "就绪"
            },
            color = if (runState.error != null) Color(0xFFB23B3B) else Color(0xFF66738C),
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        OutlinedTextField(
            value = timeoutMinutes,
            onValueChange = onTimeoutChange,
            label = { Text("超时(分)") },
            modifier = Modifier.width(104.dp),
            singleLine = true,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onRun, enabled = !running) {
            Text(if (running) "运行中" else "运行")
        }
        if (running) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB23B3B)),
            ) {
                Text("取消")
            }
        }
    }
}

/** The single readable error line: exit code + first reason; details stay in the log. */
@Composable
private fun RunStatusBanner(runState: RunSnapshot, modifier: Modifier = Modifier) {
    val cancelled = runState.status == RunStatus.CANCELLED
    val message = runState.error ?: if (cancelled) "运行已取消" else "运行失败（详见日志）"
    Row(
        modifier = modifier
            .background(if (cancelled) Color(0xFF6B7280) else Color(0xFFFDECEA))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = if (cancelled) Color.White else Color(0xFF9B2C2C), fontSize = 12.sp)
    }
}

@Composable
private fun ResultPreview(
    preview: PreviewInfo,
    results: List<ResultArtifact>,
    status: RunStatus,
    modifier: Modifier = Modifier,
) {
    val latest = results.lastOrNull()
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF1E2431)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .aspectRatio(1f)
                .background(Color(0xFF2E3A52), RoundedCornerShape(12.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (latest != null) {
                Text("生成成功", color = Color(0xFF9BE0A6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(latest.path, color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(paramsSummary(latest.params), color = Color(0xFFCFD8EA), fontSize = 12.sp)
            } else {
                Text(preview.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(preview.detail, color = Color(0xFFCFD8EA))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    when (status) {
                        RunStatus.RUNNING -> "引擎正在生成，逐张进度见下方日志面板。"
                        RunStatus.FAILURE -> "本次运行失败，临时产物已清理；可修改参数后重跑。"
                        RunStatus.CANCELLED -> "本次运行已取消，临时产物已清理；可修改参数后重跑。"
                        else -> "内置工作流已加载；点击「运行」生成 PNG。"
                    },
                    color = Color(0xFF9FADC7),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The settings shown next to a successful PNG, so one image maps to one run. */
private fun paramsSummary(params: Map<String, String>): String = listOfNotNull(
    params["prompt"]?.takeIf { it.isNotBlank() },
    params["steps"]?.let { "steps $it" },
    params["seed"]?.let { "seed $it" },
    params["width"]?.let { width -> params["height"]?.let { height -> "${width}×${height}" } },
).joinToString(" · ")

/** One panel for both structured progress events and unmodified engine output. */
@Composable
private fun RunLogPanel(runState: RunSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(Color(0xFF141A26)).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            "运行日志 · 进度事件 ${runState.progress.size} 条 · 引擎输出 ${runState.log.size} 行",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            runState.log.takeLast(80).forEach { line ->
                Text(line, color = Color(0xFFB8C6DE), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ParameterDrawer(
    snapshot: DefaultModeSnapshot,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("参数抽屉", style = MaterialTheme.typography.h5)
        Text(
            "选中顶部链芯片，立即编辑该类参数",
            color = Color(0xFF66738C),
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))

        val selectedTitle = snapshot.chips.firstOrNull { it.nodeId == snapshot.selectedNodeId }?.title
        Text(selectedTitle.orEmpty(), fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        snapshot.fields.forEach { field ->
            ParameterFieldRow(field = field, onEdit = onEdit)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ParameterFieldRow(field: ParameterField, onEdit: (String, String) -> Unit) {
    when {
        field.kind == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = field.value == "true",
                onCheckedChange = { checked -> onEdit(field.key, checked.toString()) },
            )
            Text(field.label)
        }

        field.options.isNotEmpty() -> ChoiceField(field = field, onEdit = onEdit)

        field.kind == "text" -> OutlinedTextField(
            value = field.value,
            onValueChange = { onEdit(field.key, it) },
            label = { Text(field.label) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        else -> OutlinedTextField(
            value = field.value,
            onValueChange = { onEdit(field.key, it) },
            label = { Text(field.label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun ChoiceField(field: ParameterField, onEdit: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(field.label, fontSize = 12.sp, color = Color(0xFF66738C))
        Box {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFF8FAFD),
                    contentColor = Color(0xFF243049),
                ),
            ) {
                Text(field.value)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        onClick = {
                            onEdit(field.key, option)
                            expanded = false
                        },
                    ) {
                        Text(option)
                    }
                }
            }
        }
    }
}
