package com.abrah.nightmare.desktop.gui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

private enum class InteractionMode { DEFAULT, ADVANCED, CHAT }

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
    // One chat state for the whole window: bubbles and the agent's history stay
    // in memory across mode switches. The session/runner above are still the
    // single workflow and run state all three shells share (ADR-0008/0009).
    val chatState = remember(session, runner) { ChatSession(session, runner) }
    // Window close disposes this effect (a mode switch does not). The chat
    // state owns the single cancel/window-close entry, so Ask release and the
    // ADR-0009 CLI cleanup happen together; the other shells keep using the
    // same runner and its unchanged cancel semantics.
    DisposableEffect(chatState) {
        onDispose { chatState.closeWindow() }
    }

    MaterialTheme {
        when (mode.value) {
            InteractionMode.DEFAULT -> DefaultModeScreen(
                state = defaultState,
                onSwitchToAdvanced = { mode.value = InteractionMode.ADVANCED },
                onSwitchToChat = { mode.value = InteractionMode.CHAT },
            )

            InteractionMode.ADVANCED -> AdvancedModeScreen(
                state = advancedState,
                onSwitchToDefault = { mode.value = InteractionMode.DEFAULT },
                onSwitchToChat = { mode.value = InteractionMode.CHAT },
            )

            InteractionMode.CHAT -> ChatModeScreen(
                state = chatState,
                onSwitchToDefault = { mode.value = InteractionMode.DEFAULT },
                onSwitchToAdvanced = { mode.value = InteractionMode.ADVANCED },
            )
        }
    }
}
