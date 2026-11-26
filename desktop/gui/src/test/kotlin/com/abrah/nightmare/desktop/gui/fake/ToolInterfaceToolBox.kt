package com.abrah.nightmare.desktop.gui.fake

import com.abrah.nightmare.agent.ToolBox
import com.abrah.nightmare.agent.ToolResult as AgentToolResult
import com.abrah.nightmare.desktop.gui.ToolCommand
import com.abrah.nightmare.desktop.gui.ToolInterface
import com.abrah.nightmare.desktop.gui.ToolResult as GuiToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The glue a desktop chat mode needs: the mobile [ToolBox] the agent loop
 * speaks, backed by the desktop [ToolInterface] the shells already render.
 * One tool name per [ToolCommand] variant; no new persistence format and no
 * provider call. Kept in the test source set because it is a seam, not yet a
 * product surface.
 */
class ToolInterfaceToolBox(private val tools: ToolInterface) : ToolBox {

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
            is GuiToolResult.Ok -> AgentToolResult(JSONObject().put("ok", result.summary))
            is GuiToolResult.Rejected -> AgentToolResult(JSONObject().put("error", result.reason))
        }
    }

    companion object {
        val TOOL_NAMES = listOf("new_workflow", "add_node", "set_parameter", "connect", "run")
    }
}
