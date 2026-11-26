package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ToolBox
import com.abrah.nightmare.agent.ToolResult as AgentToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production-side glue between the copied agent core and the desktop command
 * surface. It shares the name→command mapping in [DesktopToolCommands] with
 * the test-source `ToolInterfaceToolBox`; it is NOT that fake moved into main,
 * and it never speaks to a provider (ADR-0008/0012).
 *
 * A successful [ToolCommand.Run] blocks in [WorkflowRunner.run] and then reads
 * the completed run snapshot, so the same PNG both becomes a `ChatItem.Picture`
 * bubble (via the tool result) and stays on the existing result-panel path.
 */
class DesktopToolBox(
    private val tools: ToolInterface,
    private val runControl: WorkflowRunControl,
) : ToolBox {

    override val specs: JSONArray = DesktopToolCommands.specs()

    override fun call(name: String, args: JSONObject): AgentToolResult {
        val command = DesktopToolCommands.fromName(name, args)
            ?: return AgentToolResult(JSONObject().put("error", "unknown tool: $name"))

        return when (val result = tools.dispatch(command)) {
            is ToolResult.Ok -> AgentToolResult(
                JSONObject().put("ok", result.summary),
                pictures = if (command is ToolCommand.Run) {
                    runControl.runSnapshot().results.map { it.path }
                } else {
                    emptyList()
                },
            )

            is ToolResult.Rejected -> AgentToolResult(JSONObject().put("error", result.reason))
        }
    }
}
