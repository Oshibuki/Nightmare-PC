package com.abrah.nightmare.desktop.gui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.desktop.canvas.Pt
import com.abrah.nightmare.desktop.canvas.Viewport
import com.abrah.nightmare.desktop.canvas.wirePath
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes

/**
 * 高级模式 shell: prototype A's node palette, free-form wire canvas, property
 * panel and result bar. It renders only the shared workflow/run state; a mode
 * switch changes this shell, not the 工作流 (ADR-0008).
 */
@Composable
fun AdvancedModeScreen(
    state: AdvancedModeState = remember { AdvancedModeState() },
    onSwitchToDefault: () -> Unit = {},
    onSwitchToChat: () -> Unit = {},
) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) {
        val unsubscribe = state.addChangeListener { revision++ }
        onDispose { unsubscribe() }
    }
    val snapshot = remember(state, revision) { state.snapshot() }
    val runState = remember(state, revision) { state.runSnapshot() }
    val density = LocalDensity.current
    var zoomCenter by remember { mutableStateOf(Pt(640f, 400f)) }
    var workflowPath by remember { mutableStateOf("") }
    var fileFeedback by remember { mutableStateOf<PersistenceFeedback?>(null) }
    val deviceViewport = snapshot.viewport.forDevice(density.density)

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFEEF1F6))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF2B3242)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("高级模式 · 自由节点画布", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onSwitchToDefault) { Text("切到默认模式") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSwitchToChat) { Text("切到对话模式") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { state.zoomAt(zoomCenter, 1.2f) }) { Text("放大") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { state.zoomAt(zoomCenter, 1f / 1.2f) }) { Text("缩小") }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${snapshot.nodes.size} 节点 · ${snapshot.wires.size} 连线 · ${zoomLabel(snapshot.viewport.scale)}",
                color = Color(0xFFCBD5E8),
                fontSize = 12.sp,
            )
        }

        WorkflowFileBar(
            path = workflowPath,
            onPathChange = { workflowPath = it },
            onSave = { fileFeedback = state.saveWorkflow(workflowPath) },
            onLoad = { fileFeedback = state.loadWorkflow(workflowPath) },
            feedback = fileFeedback,
            modifier = Modifier.fillMaxWidth(),
        )

        if (snapshot.message != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFDECEA)).padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(snapshot.message.orEmpty(), color = Color(0xFF9B2C2C), fontSize = 12.sp)
            }
        }

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            NodePalette(
                onAdd = { type -> state.addNode(type) },
                modifier = Modifier.width(168.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(Color(0xFFF7F9FC))
                    .onSizeChanged { size -> zoomCenter = Pt(size.width / 2f, size.height / 2f) }
                    .pointerInput(state) {
                        fun toWorld(at: Offset): Pt = state.currentViewport()
                            .forDevice(density.density)
                            .toWorld(Pt(at.x, at.y))
                        var last = Pt(0f, 0f)
                        detectDragGestures(
                            onDragStart = { at ->
                                last = toWorld(at)
                                state.pressWorld(last)
                            },
                            onDrag = { change, amount ->
                                last = toWorld(change.position)
                                state.dragWorld(last, Pt(amount.x, amount.y))
                            },
                            onDragEnd = { state.releaseWorld(last) },
                            onDragCancel = { state.releaseWorld(last) },
                        )
                    }
                    .pointerInput(state) {
                        fun toWorld(at: Offset): Pt = state.currentViewport()
                            .forDevice(density.density)
                            .toWorld(Pt(at.x, at.y))
                        detectTapGestures(
                            onTap = { at ->
                                val world = toWorld(at)
                                state.pressWorld(world)
                                state.releaseWorld(world)
                            },
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    snapshot.wires.forEach { wire ->
                        val curve = wireScreenPath(wire.from, wire.to, deviceViewport)
                        val first = curve(0f)
                        var previous = Offset(first.x, first.y)
                        for (step in 1..24) {
                            val point = curve(step / 24f)
                            val next = Offset(point.x, point.y)
                            drawLine(Color(0xFF7D8CA8), previous, next, strokeWidth = 2f)
                            previous = next
                        }
                    }
                    snapshot.pending?.let { pending ->
                        val at = deviceViewport.toScreen(Pt(pending.x, pending.y))
                        drawCircle(
                            color = if (pending.error == null) Color(0xFF3A7D44) else Color(0xFFB23B3B),
                            radius = 8f,
                            center = Offset(at.x, at.y),
                        )
                    }
                }

                snapshot.nodes.forEach { node ->
                    val screen = deviceViewport.toScreen(Pt(node.x, node.y))
                    val left = with(density) { screen.x.toDp() }
                    val top = with(density) { screen.y.toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = left, y = top)
                            .size(
                                width = (node.width * snapshot.viewport.scale).dp,
                                height = (node.height * snapshot.viewport.scale).dp,
                            )
                            .background(
                                if (node.selected) Color(0xFFEAF1FF) else Color.White,
                                RoundedCornerShape(8.dp),
                            )
                            .border(
                                1.dp,
                                if (node.selected) Color(0xFF3F6FC7) else Color(0xFFC2CADA),
                                RoundedCornerShape(8.dp),
                            ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                node.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            Text(
                                node.summary,
                                color = Color(0xFF6B7488),
                                fontSize = 10.sp,
                                maxLines = 2,
                            )
                        }
                        node.ports.forEach { port ->
                            val portX = with(density) { ((port.x - node.x) * snapshot.viewport.scale).dp }
                            val portY = with(density) { ((port.y - node.y) * snapshot.viewport.scale).dp }
                            Box(
                                modifier = Modifier
                                    .offset(x = portX - 5.dp, y = portY - 5.dp)
                                    .size(10.dp)
                                    .background(
                                        color = if (port.isInput) Color(0xFF7D8CA8) else Color(0xFF3F6FC7),
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }

            PropertyPanel(
                snapshot = snapshot,
                onSelect = { id -> state.selectNode(id) },
                onEdit = { key, value -> state.setSelectedParameter(key, value) },
                modifier = Modifier.width(274.dp),
            )
        }

        ResultBar(
            runState = runState,
            running = runState.status == RunStatus.RUNNING,
            onRun = { Thread { state.triggerRun() }.apply { isDaemon = true }.start() },
            onCancel = { state.cancelRun() },
            modifier = Modifier.fillMaxWidth().height(132.dp),
        )
    }
}

/** The 节点类型 offered by the 高级模式 palette, in display order. */
internal fun advancedPaletteTypes(): List<Pair<String, String>> = listOf(
    DesktopNodeTypes.PROMPT to DesktopNodeTypes.prompt.title,
    DesktopNodeTypes.SD_SAMPLER to DesktopNodeTypes.sdSampler.title,
    DesktopNodeTypes.OUTPUT to DesktopNodeTypes.output.title,
    DesktopNodeTypes.IMAGE_INPUT to DesktopNodeTypes.imageInput.title,
)

@Composable
private fun NodePalette(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.White).padding(12.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("节点库", style = MaterialTheme.typography.h6)
        Text("点击添加到画布", color = Color(0xFF6B7488), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(12.dp))
        advancedPaletteTypes().forEach { (type, title) ->
            Button(
                onClick = { onAdd(type) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFF8FAFD),
                    contentColor = Color(0xFF243049),
                ),
            ) {
                Text(title)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PropertyPanel(
    snapshot: AdvancedModeSnapshot,
    onSelect: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.White).padding(12.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("属性面板", style = MaterialTheme.typography.h6)
        snapshot.nodes.forEach { node ->
            Button(
                onClick = { onSelect(node.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (node.selected) Color(0xFF3F6FC7) else Color(0xFFF8FAFD),
                    contentColor = if (node.selected) Color.White else Color(0xFF243049),
                ),
            ) {
                Text("${node.title} · ${node.id}", maxLines = 1)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        snapshot.fields.forEach { field ->
            AdvancedFieldRow(field = field, onEdit = onEdit)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AdvancedFieldRow(field: ParameterField, onEdit: (String, String) -> Unit) {
    when {
        field.kind == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = field.value == "true", onCheckedChange = { onEdit(field.key, it.toString()) })
            Text(field.label, fontSize = 12.sp)
        }

        field.options.isNotEmpty() -> {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Text(field.label, fontSize = 11.sp, color = Color(0xFF66738C))
                Box {
                    Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(field.value, fontSize = 12.sp)
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
private fun ResultBar(
    runState: RunSnapshot,
    running: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = runState.results.lastOrNull()
    Row(
        modifier = modifier.background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(92.dp).background(Color(0xFF6C8EBF), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (latest == null) "PNG" else "✓", color = Color.White, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("结果条", fontWeight = FontWeight.Bold)
            Text(
                latest?.path ?: "尚未运行；连线完成后点「运行」生成 PNG。",
                fontSize = 12.sp,
                color = Color(0xFF243049),
                maxLines = 1,
            )
            Text(
                when {
                    running -> "生成中 ${runState.progress.size} 张 · 日志 ${runState.log.size} 行"
                    runState.status == RunStatus.FAILURE || runState.status == RunStatus.CANCELLED ->
                        runState.error.orEmpty()

                    else -> "同一进度事件与结果由两种模式共享"
                },
                fontSize = 11.sp,
                color = if (runState.error != null) Color(0xFFB23B3B) else Color(0xFF6B7488),
                maxLines = 1,
            )
        }
        Button(onClick = onRun, enabled = !running) { Text(if (running) "运行中" else "运行") }
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

/** The same pure readout the copied canvas state layer uses. */
private fun zoomLabel(scale: Float): String = "%.2fx".format(java.util.Locale.ROOT, scale)

/**
 * The shared curve is defined in world units (the same one `wireAt` hit-tests).
 * Compose draws in pixels, so each sampled point goes through the viewport the
 * nodes and ports use; mixing the two spaces misaligns wires when zoomed,
 * panned or rendered at a non-1 device density (ADR-0006).
 */
internal fun wireScreenPath(from: Pt, to: Pt, viewport: Viewport): (Float) -> Pt {
    val world = wirePath(from, to)
    return { t -> viewport.toScreen(world(t)) }
}
