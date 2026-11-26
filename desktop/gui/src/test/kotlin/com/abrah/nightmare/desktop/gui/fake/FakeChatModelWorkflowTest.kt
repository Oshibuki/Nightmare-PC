package com.abrah.nightmare.desktop.gui.fake

import com.abrah.nightmare.agent.Agent
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.gui.DefaultModeState
import com.abrah.nightmare.desktop.gui.RunStatus
import com.abrah.nightmare.desktop.gui.ToolInterface
import com.abrah.nightmare.desktop.gui.WorkflowRunner
import com.abrah.nightmare.desktop.gui.WorkflowSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChatModelWorkflowTest {

    /** The ported fakes minus Robolectric: the real agent loop runs on a desktop JVM. */
    @Test
    fun `ported fakes drive the real agent loop on a desktop JVM`() {
        val model = ScriptChatModel(
            toolCall("c1", "run_flow", """{"flow":"txt2img","prompt":"a fox"}"""),
            words("Here is your fox."),
        )
        val tools = RecordingToolBox()
        val shown = mutableListOf<ChatItem>()
        Agent(model, tools, "sys").send("a fox please", { shown += it })

        // Read recorded args by field, not whole-string equality: JVM org.json
        // uses a HashMap, so key order in toString() is not guaranteed.
        assertEquals(1, tools.calls.size)
        assertEquals("run_flow", tools.calls[0].first)
        val recorded = JSONObject(tools.calls[0].second)
        assertEquals("txt2img", recorded.getString("flow"))
        assertEquals("a fox", recorded.getString("prompt"))
        assertEquals(
            listOf(
                ChatItem.Tool("run_flow", "run_flow", true),
                ChatItem.Picture("/p/output.png"),
                ChatItem.Assistant("Here is your fox."),
            ),
            shown,
        )

        // The second turn carried the tool's answer, tied to its call.
        val last = model.seen[1].getJSONObject(model.seen[1].length() - 1)
        assertEquals("tool", last.getString("role"))
        assertEquals("c1", last.getString("tool_call_id"))
        assertTrue(last.getString("content").contains("results"))
    }

    /** The fake chat model drives all five ToolInterface commands into a real run. */
    @Test
    fun `a fake chat model drives all five ToolInterface commands into a real run`() {
        assumePosix()
        val (config, png) = fakeCliPng("fake-chatmodel-test", "tool-result.png", captureArgv = true)
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session, WorkflowRunner(config))
        val tools = ToolInterface(state)
        val box = ToolInterfaceToolBox(tools)

        val add = { id: String, type: String ->
            JSONObject().put("id", id).put("type", type).toString()
        }
        val model = ScriptChatModel(
            toolCall("c1", "new_workflow", "{}"),
            toolCall("c2", "add_node", add("prompt", DesktopNodeTypes.PROMPT)),
            toolCall("c3", "add_node", add("sampler", DesktopNodeTypes.SD_SAMPLER)),
            toolCall("c4", "add_node", add("output", DesktopNodeTypes.OUTPUT)),
            toolCall("c5", "set_parameter", """{"nodeId":"prompt","key":"prompt","value":"tool-built dawn"}"""),
            toolCall("c6", "set_parameter", """{"nodeId":"sampler","key":"seed","value":"11"}"""),
            toolCall("c7", "connect", """{"fromNode":"prompt","toNode":"sampler","toPort":"prompt"}"""),
            toolCall("c8", "connect", """{"fromNode":"sampler","toNode":"output","toPort":"image"}"""),
            toolCall("c9", "run", "{}"),
            words("Built and ran the workflow."),
        )

        val shown = mutableListOf<ChatItem>()
        Agent(model, box, "sys").send("build a dawn image", { shown += it })

        // All nine tool calls were accepted (5 command kinds, 3 nodes, 2 wires).
        val toolLines = shown.filterIsInstance<ChatItem.Tool>()
        assertEquals(9, toolLines.size)
        assertTrue(toolLines.all { it.ok })
        assertTrue(shown.last() is ChatItem.Assistant)

        // The workflow the shells render was actually built and run.
        assertEquals(listOf("prompt", "sampler", "output"), state.snapshot().chips.map { it.nodeId })
        assertEquals(2, session.workflow.connections.size)
        val run = state.runSnapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(png.path, run.results.single().path)
        assertEquals("11", run.results.single().params["seed"])
        assertTrue("the built prompt reached the CLI: ${run.command}", run.command.contains("tool-built dawn"))
    }

    // ---- harness: POSIX fake CLI + bounded waits come from ChatTestHarness --
}
