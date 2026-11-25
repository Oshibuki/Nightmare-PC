package com.abrah.nightmare.desktop.gui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

private enum class InteractionMode { DEFAULT, ADVANCED }

/** Entry point for the Compose Desktop shell: both modes over one 工作流 session. */
fun main() = singleWindowApplication(
    title = "Nightmare Desktop",
    state = WindowState(width = 1280.dp, height = 800.dp),
) {
    val mode = remember { mutableStateOf(InteractionMode.DEFAULT) }
    // One workflow and one run state for the whole window. Switching modes
    // changes only the shell; it never creates a second chain (ADR-0008/0009).
    val session = remember { WorkflowSession() }
    val runner = remember { WorkflowRunner() }
    val defaultState = remember(session, runner) { DefaultModeState(session, runner) }
    val advancedState = remember(session, runner) { AdvancedModeState(session, runner) }
    // Window close disposes this effect (a mode switch does not), so a run
    // started in either mode is ended once for the whole window (ADR-0009).
    DisposableEffect(runner) {
        onDispose { WorkflowRunner.cancelActiveProcess(runner.processHandle()) }
    }

    MaterialTheme {
        when (mode.value) {
            InteractionMode.DEFAULT -> DefaultModeScreen(
                state = defaultState,
                onSwitchToAdvanced = { mode.value = InteractionMode.ADVANCED },
            )

            InteractionMode.ADVANCED -> AdvancedModeScreen(
                state = advancedState,
                onSwitchToDefault = { mode.value = InteractionMode.DEFAULT },
            )
        }
    }
}
