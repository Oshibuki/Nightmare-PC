package com.abrah.nightmare.desktop.gui.fake

import com.abrah.nightmare.agent.ToolBox
import com.abrah.nightmare.agent.ToolResult as AgentToolResult
import com.abrah.nightmare.desktop.gui.DesktopToolCommands
import com.abrah.nightmare.desktop.gui.ToolInterface
import com.abrah.nightmare.desktop.gui.ToolResult as GuiToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The glue a desktop chat mode needs: the mobile [ToolBox] the agent loop
 * speaks, backed by the desktop [ToolInterface] the shells already render.
 * It reuses [DesktopToolCommands] for the name→command mapping but keeps its
 * own ToolResult conversion and stays in the test source set: a seam, not yet
 * a product surface.
 */
class ToolInterfaceToolBox(private val tools: ToolInterface) : ToolBox {

    override val specs: JSONArray = DesktopToolCommands.specs()

    override fun call(name: String, args: JSONObject): AgentToolResult {
        val command = DesktopToolCommands.fromName(name, args)
            ?: return AgentToolResult(JSONObject().put("error", "unknown tool: $name"))

        return when (val result = tools.dispatch(command)) {
            is GuiToolResult.Ok -> AgentToolResult(JSONObject().put("ok", result.summary))
            is GuiToolResult.Rejected -> AgentToolResult(JSONObject().put("error", result.reason))
        }
    }
}
