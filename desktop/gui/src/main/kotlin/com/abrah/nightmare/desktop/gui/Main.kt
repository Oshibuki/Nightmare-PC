package com.abrah.nightmare.desktop.gui

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

/** Entry point for the Compose Desktop shell: open directly in 默认模式. */
fun main() = singleWindowApplication(
    title = "Nightmare Desktop — 默认模式",
    state = WindowState(width = 1280.dp, height = 800.dp),
) {
    MaterialTheme {
        DefaultModeScreen()
    }
}
