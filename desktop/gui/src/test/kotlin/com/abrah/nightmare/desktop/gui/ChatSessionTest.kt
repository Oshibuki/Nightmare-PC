package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.gui.fake.assumePosix
import com.abrah.nightmare.desktop.gui.fake.fakeCliPng
import com.abrah.nightmare.desktop.gui.fake.ScriptChatModel
import com.abrah.nightmare.desktop.gui.fake.toolCall
import com.abrah.nightmare.desktop.gui.fake.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话回合 state-layer tests: one sentence in, bubbles + the shared workflow +
 * the result snapshot out. No Compose UI test framework: [ChatSession]'s two
 * seams make a turn run synchronously on the JUnit thread with the real agent
 * loop and the real CLI runner.
 */
class ChatSessionTest {

    @Test
    fun `the system prompt names the CLI scheduler and sampler vocabularies`() {
        val prompt = ChatSession.SYSTEM_PROMPT
        assertTrue("scheduler must use CLI values", prompt.contains("discrete"))
        assertTrue("normal is called out as invalid", prompt.contains("normal"))
        assertTrue("sampler must use engine names", prompt.contains("euler"))
    }

    @Test
    fun `one sentence drives five tool commands a fake CLI render and a Picture bubble`() {
        assumePosix()
        val (config, png) = fakeCliPng("chat-session-test", "chat-result.png", captureArgv = true)
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(config)
        val add = { id: String, type: String ->
            JSONObjectOf("id" to id, "type" to type)
        }
        val model = ScriptChatModel(
            toolCall("c1", "new_workflow", "{}"),
            toolCall("c2", "add_node", add("prompt", DesktopNodeTypes.PROMPT)),
            toolCall("c3", "add_node", add("sampler", DesktopNodeTypes.SD_SAMPLER)),
            toolCall("c4", "add_node", add("output", DesktopNodeTypes.OUTPUT)),
            toolCall("c5", "set_parameter", JSONObjectOf("nodeId" to "prompt", "key" to "prompt", "value" to "tool-built dawn")),
            toolCall("c6", "set_parameter", JSONObjectOf("nodeId" to "sampler", "key" to "seed", "value" to "11")),
            toolCall("c7", "connect", JSONObjectOf("fromNode" to "prompt", "toNode" to "sampler", "toPort" to "prompt")),
            toolCall("c8", "connect", JSONObjectOf("fromNode" to "sampler", "toNode" to "output", "toPort" to "image")),
            toolCall("c9", "run", "{}"),
            words("Built and ran the workflow."),
        )
        val chat = ChatSession(
            session = session,
            runner = runner,
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("build a dawn image"))

        val shown = chat.snapshot()
        assertEquals(ChatItem.User("build a dawn image"), shown.first())
        assertEquals(ChatItem.Assistant("Built and ran the workflow."), shown.last())
        val tools = shown.filterIsInstance<ChatItem.Tool>()
        assertEquals(9, tools.size)
        assertTrue(tools.all { it.ok })
        assertEquals(
            setOf("new_workflow", "add_node", "set_parameter", "connect", "run"),
            tools.map { it.name }.toSet(),
        )
        assertTrue("the rendered PNG became a Picture bubble", ChatItem.Picture(png.path) in shown)
        assertTrue("engine日志走日志面板，不进气泡", shown.none { it is ChatItem.Log })

        // The one shared workflow was really edited, not a copy in the agent.
        assertEquals(listOf("prompt", "sampler", "output"), session.workflow.nodes.map { it.id })
        assertEquals(2, session.workflow.connections.size)
        assertEquals(
            "tool-built dawn",
            session.workflow.nodes.first { it.type == DesktopNodeTypes.PROMPT }.params["prompt"],
        )
        assertEquals(
            "11",
            session.workflow.nodes.first { it.type == DesktopNodeTypes.SD_SAMPLER }.params["seed"],
        )

        // The same run is observable on the existing listener/snapshot path.
        val run = runner.snapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(png.path, run.results.single().path)
        assertEquals("11", run.results.single().params["seed"])
        assertFalse(chat.running)
    }

    @Test
    fun `the system prompt teaches the model the tools node types ids and ports`() {
        val model = ScriptChatModel(words("ok"))
        val chat = ChatSession(
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("hello"))

        val system = model.seen.single().getJSONObject(0)
        assertEquals("system", system.getString("role"))
        val text = system.getString("content")
        listOf("new_workflow", "add_node", "set_parameter", "connect", "run").forEach { tool ->
            assertTrue("$tool should be named in the system prompt: $text", text.contains(tool))
        }
        listOf(
            DesktopNodeTypes.PROMPT,
            DesktopNodeTypes.SD_SAMPLER,
            DesktopNodeTypes.OUTPUT,
        ).forEach { type -> assertTrue("$type should be legal in the system prompt: $text", text.contains(type)) }
        assertTrue("id rule is stated: $text", text.contains("_") && text.contains("-"))
        assertTrue("port names are stated: $text", text.contains("prompt") && text.contains("image"))
        assertTrue("all values are strings: $text", text.contains("字符串"))
        assertTrue("the typical order is stated: $text", text.indexOf("new_workflow") < text.indexOf("run"))
    }

    @Test
    fun `without a configured provider the turn ends in an explicit error bubble`() {
        val chat = ChatSession(
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("hello"))

        val error = chat.snapshot().filterIsInstance<ChatItem.Error>().single()
        assertTrue("error names the missing provider: ${error.text}", "provider" in error.text || "未配置" in error.text)
        assertFalse(chat.running)
    }

    @Test
    fun `only one turn may run at a time and a finished turn frees the send button`() {
        val queued = mutableListOf<Runnable>()
        val chat = ChatSession(
            model = ScriptChatModel(words("first ok"), words("third ok")),
            postToUi = { it.run() },
            startTurn = { queued += it },
        )

        assertTrue(chat.send("first"))
        assertTrue(chat.running)
        assertFalse("a second sentence while the first turn is live is refused", chat.send("second"))
        assertEquals(1, queued.size)

        queued.removeAt(0).run()
        assertFalse(chat.running)
        assertTrue(chat.send("third"))
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertFalse(chat.running)
        assertTrue(chat.snapshot().contains(ChatItem.User("third")))
    }

    // ---- harness: POSIX fake CLI + bounded waits come from ChatTestHarness --

    private fun JSONObjectOf(vararg pairs: Pair<String, String>): String =
        org.json.JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }.toString()
}
