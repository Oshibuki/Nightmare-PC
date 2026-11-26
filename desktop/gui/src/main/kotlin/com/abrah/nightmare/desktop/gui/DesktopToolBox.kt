package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ToolBox
import com.abrah.nightmare.agent.ToolResult as AgentToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production-side glue between the copied agent core and the desktop command
 * surface: one agent tool name per [ToolCommand] variant. It has the same shape
 * as the test-source `ToolInterfaceToolBox`; it is NOT that fake moved into
 * main, and it never speaks to a provider (ADR-0008/0012).
 *
 * A successful [ToolCommand.Run] blocks in [WorkflowRunner.run] and then reads
 * the completed run snapshot, so the same PNG both becomes a `ChatItem.Picture`
 * bubble (via the tool result) and stays on the existing result-panel path.
 */
class DesktopToolBox(
    private val tools: ToolInterface,
    private val runControl: WorkflowRunControl,
) : ToolBox {

    override val specs: JSONArray = TOOL_NAMES.fold(JSONArray()) { acc, name ->
        acc.put(
            JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", name)),
        )
    }

    override fun call(name: String, args: JSONObject): AgentToolResult {
        val command = when (name) {
            "new_workflow" -> ToolCommand.NewWorkflow
            "add_node" -> ToolCommand.AddNode(
                id = args.getString("id"),
                type = args.getString("type"),
                x = args.optDouble("x", 0.0).toFloat(),
                y = args.optDouble("y", 0.0).toFloat(),
                params = args.optJSONObject("params")
                    ?.let { p -> p.keys().asSequence().associateWith { k -> p.getString(k) } }
                    ?: emptyMap(),
            )

            "set_parameter" -> ToolCommand.SetParameter(
                args.getString("nodeId"),
                args.getString("key"),
                args.getString("value"),
            )

            "connect" -> ToolCommand.Connect(
                args.getString("fromNode"),
                args.getString("toNode"),
                args.getString("toPort"),
                args.optString("fromPort").takeIf { it.isNotBlank() },
            )

            "run" -> ToolCommand.Run
            else -> return AgentToolResult(JSONObject().put("error", "unknown tool: $name"))
        }

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

    companion object {
        val TOOL_NAMES = listOf("new_workflow", "add_node", "set_parameter", "connect", "run")
    }
}
