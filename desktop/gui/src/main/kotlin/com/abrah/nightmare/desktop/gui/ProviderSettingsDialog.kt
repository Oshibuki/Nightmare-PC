package com.abrah.nightmare.desktop.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.agent.Provider

/**
 * The gear dialog. Inputs live only in this composable until 「保存」 writes
 * them into [ChatProviderSettings]' process memory; the key field is never
 * pre-filled and is a password field, so closing the dialog leaves only the
 * 「已配置 / 未配置」 status visible (ADR-0012).
 */
@Composable
fun ProviderSettingsDialog(
    settings: ChatProviderSettings,
    onClose: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val unsubscribe = settings.addChangeListener { revision++ }
        onDispose { unsubscribe() }
    }
    val current = remember(settings, revision) { settings.resolved() }

    var provider by remember { mutableStateOf(current.provider) }
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(current.model) }
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Provider 设置", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))
            Text("密钥：${settings.statusLabel()}", fontSize = 13.sp)
            Text(
                "持久来源为环境变量；此处保存只存本次进程内存，覆盖环境变量，重启后回退。密钥不写磁盘、不回显。",
                fontSize = 12.sp,
                color = Color(0xFF66738C),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Provider", fontSize = 12.sp)
            Button(onClick = { expanded = true }) { Text(provider.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Provider.entries.forEach { choice ->
                    DropdownMenuItem(onClick = {
                        provider = choice
                        expanded = false
                        if (choice != Provider.CUSTOM) baseUrl = choice.baseUrl
                    }) {
                        Text(choice.label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL（Custom 必填）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("密钥（留空表示沿用当前配置）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型（可选；留空由 fitsChat 自动选择）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(settings.lastProbeStatus(), fontSize = 12.sp, color = Color(0xFF66738C))
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onClose) { Text("取消") }
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Button(onClick = {
                    settings.save(ProviderSettingsInput(provider, baseUrl.trim(), key, model.trim()))
                    onClose()
                }) {
                    Text("保存")
                }
            }
        }
    }
}
