package com.abrah.nightmare.desktop.gui

import org.json.JSONArray
import org.json.JSONObject

/**
 * The name↔command seam shared by the production [DesktopToolBox] and the
 * test-source `ToolInterfaceToolBox`: one agent tool name per [ToolCommand]
 * variant. Neither caller's ToolResult conversion lives here — production
 * still attaches run pictures, the fake stays independent (ADR-0008).
 */
object DesktopToolCommands {
    val TOOL_NAMES = listOf("new_workflow", "add_node", "set_parameter", "connect", "run")

    /** A fresh array per call, so each ToolBox keeps its own specs instance. */
    fun specs(): JSONArray = TOOL_NAMES.fold(JSONArray()) { acc, name ->
        acc.put(
            JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", name)),
        )
    }

    /** Null means the model called a tool the desktop command surface does not know. */
    fun fromName(name: String, args: JSONObject): ToolCommand? = when (name) {
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
        else -> null
    }
}
