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
 * 默认模式 shell: prototype C's chain rail, large result preview and parameter
 * drawer. It renders only the shared workflow state; no second chain is kept.
 */
@Composable
fun DefaultModeScreen(state: DefaultModeState = remember { DefaultModeState() }) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) {
        val unsubscribe = state.addChangeListener { revision++ }
        onDispose(unsubscribe)
    }
    val snapshot = remember(state, revision) { state.snapshot() }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F7FB)),
    ) {
        ChainRail(snapshot = snapshot, onSelect = { nodeId -> state.selectNode(nodeId) })

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            ResultPreview(
                preview = snapshot.preview,
                modifier = Modifier.weight(0.58f),
            )
            ParameterDrawer(
                snapshot = snapshot,
                onEdit = { key, value -> state.setSelectedParameter(key, value) },
                modifier = Modifier.weight(0.42f),
            )
        }
    }
}

@Composable
private fun ChainRail(snapshot: DefaultModeSnapshot, onSelect: (String) -> Unit) {
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
        Button(onClick = {}, enabled = false) {
            Text("运行（后续工单接入）")
        }
    }
}

@Composable
private fun ResultPreview(preview: PreviewInfo, modifier: Modifier = Modifier) {
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
            Text(preview.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(preview.detail, color = Color(0xFFCFD8EA))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "内置工作流已加载；运行生成后在此查看 PNG。",
                color = Color(0xFF9FADC7),
                fontSize = 12.sp,
            )
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
