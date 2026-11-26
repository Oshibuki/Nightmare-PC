// Copied (trimmed) from app/src/test/java/com/abrah/nightmare/agent/AgentTest.kt on 2026-11-26 (ADR-0001).
// Robolectric and AgentTools references were removed; the desktop copy is the one to edit; never sync back.
package com.abrah.nightmare.desktop.gui.fake

import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.agent.ToolBox
import com.abrah.nightmare.agent.ToolResult as AgentToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scripted [ChatModel]: replays canned replies and records every turn it saw.
 * Faithful port of `AgentTest.Script` (AgentTest.kt:31-38); org.json + JDK only.
 */
class ScriptChatModel(vararg replies: JSONObject) : ChatModel {
    val seen = mutableListOf<JSONArray>()
    private val left = ArrayDeque(replies.toList())

    override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        seen += JSONArray(messages.toString())
        return left.removeFirst()
    }
}

/**
 * Recording [ToolBox]: logs calls and answers with a canned result.
 * Faithful port of `AgentTest.Tools` (AgentTest.kt:40-50); its only payloads are
 * org.json objects and path strings, so nothing here needs an Android type.
 */
class RecordingToolBox : ToolBox {
    val calls = mutableListOf<Pair<String, String>>()
    override val specs = JSONArray()

    override fun call(name: String, args: JSONObject): AgentToolResult {
        calls += name to args.toString()
        return when (name) {
            "run_flow" -> AgentToolResult(
                JSONObject().put("results", JSONArray().put("/results/r1/output.png")),
                pictures = listOf("/p/output.png"),
            )
            else -> AgentToolResult(JSONObject().put("error", "no tool"))
        }
    }
}

/** An assistant message that asks for one tool call; port of AgentTest.kt:21-27. */
fun toolCall(id: String, name: String, args: String): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", JSONObject.NULL)
    .put("tool_calls", JSONArray().put(
        JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args)),
    ))

/** A plain text answer; port of AgentTest.kt:29. */
fun words(text: String): JSONObject = JSONObject().put("role", "assistant").put("content", text)
