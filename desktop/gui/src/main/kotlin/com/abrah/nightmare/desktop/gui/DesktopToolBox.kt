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

    /**
     * The copied core's default is just the tool name, so a rejected call only
     * told the model — never the person — what went wrong. Show the compact
     * arguments in the bubble instead; tool arguments carry no provider key
     * (keys only ever live in the Authorization header, ADR-0012).
     */
    override fun summary(name: String, args: JSONObject): String {
        val compact = args.toString()
        val shown = if (compact.length <= MAX_SUMMARY_ARGS) compact else compact.take(MAX_SUMMARY_ARGS) + "…"
        return "$name $shown"
    }

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

    companion object {
        /** Keep a long prompt from burying the rest of the chat. */
        const val MAX_SUMMARY_ARGS = 120
    }
}
