package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.gui.fake.ScriptChatModel
import com.abrah.nightmare.desktop.gui.fake.assumePosix
import com.abrah.nightmare.desktop.gui.fake.awaitStatePolling
import com.abrah.nightmare.desktop.gui.fake.fakeCliPng
import com.abrah.nightmare.desktop.gui.fake.toolCall
import com.abrah.nightmare.desktop.gui.fake.words
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

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
    fun `the system prompt states the steps cap and that oversized values are refused`() {
        val prompt = ChatSession.SYSTEM_PROMPT

        assertTrue("the cap must be visible to the model: $prompt", prompt.contains("20"))
        assertTrue("steps and the cap must be linked: $prompt", prompt.contains("steps"))
        assertTrue("oversized values must be called out: $prompt", prompt.contains("拒绝") || prompt.contains("钳制"))
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

    @Test
    fun `the replay tooltip previews only the last sent sentence`() {
        val chat = ChatSession(
            model = ScriptChatModel(words("ok")),
            postToUi = { it.run() },
            startTurn = { it.run() },
        )
        assertNull("there is no sentence to replay before the first turn", chat.replayTooltip())
        assertFalse("nothing to replay", chat.replayLast())

        assertTrue(chat.send("x".repeat(80)))
        assertEquals("x".repeat(48) + "…", chat.replayTooltip())
        assertTrue(chat.send("short one"))
        assertEquals("short one", chat.replayTooltip())
    }

    @Test
    fun `replay resends the last sentence as a new turn with a clean agent context`() {
        val model = ScriptChatModel(
            words("first answer"),
            toolCall("r1", "add_node", JSONObjectOf("id" to "note", "type" to DesktopNodeTypes.PROMPT)),
            words("replay answer"),
        )
        val chat = ChatSession(
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("第一句"))
        assertEquals("第一句", chat.lastUserText())
        assertTrue(chat.replayLast())
        assertFalse(chat.running)

        val bubbles = chat.snapshot()
        assertEquals(listOf("第一句", "第一句"), bubbles.filterIsInstance<ChatItem.User>().map { it.text })
        assertEquals("first answer", bubbles.filterIsInstance<ChatItem.Assistant>().first().text)
        assertEquals("replay answer", bubbles.filterIsInstance<ChatItem.Assistant>().last().text)

        // The replay turn's first model call carries a fresh history: system +
        // this one sentence, never the previous turn's assistant/tool messages.
        assertEquals(3, model.seen.size)
        val replayStart = model.seen[1]
        assertEquals(2, replayStart.length())
        assertEquals("system", replayStart.getJSONObject(0).getString("role"))
        assertEquals(ChatSession.SYSTEM_PROMPT, replayStart.getJSONObject(0).getString("content"))
        assertEquals("user", replayStart.getJSONObject(1).getString("role"))
        assertEquals("第一句", replayStart.getJSONObject(1).getString("content"))
    }

    @Test(timeout = 20_000)
    fun `replaying the same sentence twice rebuilds the same workflow and picture`() {
        assumePosix()
        val (config, png) = fakeCliPng("replay-test", "replay-result.png", captureArgv = true)
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(config)
        val workflowAtTurnStart = Collections.synchronizedList(mutableListOf<List<String>>())

        // The replay turns deliberately omit new_workflow: if replay did not
        // reset the shared workflow, these add_node calls would be rejected.
        val turn = listOf(
            toolCall("b1", "add_node", JSONObjectOf("id" to "prompt", "type" to DesktopNodeTypes.PROMPT)),
            toolCall("b2", "add_node", JSONObjectOf("id" to "sampler", "type" to DesktopNodeTypes.SD_SAMPLER)),
            toolCall("b3", "add_node", JSONObjectOf("id" to "output", "type" to DesktopNodeTypes.OUTPUT)),
            toolCall("b4", "set_parameter", JSONObjectOf("nodeId" to "prompt", "key" to "prompt", "value" to "same input")),
            toolCall("b5", "connect", JSONObjectOf("fromNode" to "prompt", "toNode" to "sampler", "toPort" to "prompt")),
            toolCall("b6", "connect", JSONObjectOf("fromNode" to "sampler", "toNode" to "output", "toPort" to "image")),
            toolCall("b7", "run", "{}"),
            words("done"),
        )
        val script = ScriptChatModel(*(turn + turn + turn).toTypedArray())
        var atTurnStart = true
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
                if (atTurnStart) {
                    workflowAtTurnStart += session.workflow.nodes.map { it.id }
                    atTurnStart = false
                }
                val reply = script.complete(messages, tools)
                if (!reply.has("tool_calls")) atTurnStart = true
                return reply
            }
        }
        val chat = ChatSession(
            session = session,
            runner = runner,
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("同一句"))
        val firstWorkflow = session.workflow
        assertTrue(chat.replayLast())
        val secondWorkflow = session.workflow
        assertEquals(firstWorkflow, secondWorkflow)
        assertTrue(chat.replayLast())
        assertEquals(firstWorkflow, session.workflow)

        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList()), workflowAtTurnStart.toList())
        val bubbles = chat.snapshot()
        assertTrue("every replay tool was accepted", bubbles.filterIsInstance<ChatItem.Tool>().all { it.ok })
        assertEquals(3, bubbles.count { it is ChatItem.User })
        assertEquals(3, bubbles.count { it is ChatItem.Picture && it.path == png.path })
        assertEquals(RunStatus.SUCCESS, runner.snapshot().status)
        assertEquals("same input", session.workflow.nodes.first { it.type == DesktopNodeTypes.PROMPT }.params["prompt"])
    }

    @Test
    fun `a live turn refuses replay without resetting the workflow or adding a bubble`() {
        val queued = mutableListOf<Runnable>()
        val session = WorkflowSession(Workflow())
        val chat = ChatSession(
            session = session,
            model = ScriptChatModel(words("first ok"), words("replay ok")),
            postToUi = { it.run() },
            startTurn = { queued += it },
        )

        assertTrue(chat.send("第一句"))
        val before = session.workflow
        assertFalse("a busy turn refuses replay", chat.replayLast())
        assertEquals(before, session.workflow)
        assertEquals(1, queued.size)
        assertEquals(1, chat.snapshot().count { it is ChatItem.User })

        queued.removeAt(0).run()
        assertFalse(chat.running)
        assertTrue("a finished turn accepts replay", chat.replayLast())
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf("第一句", "第一句"), chat.snapshot().filterIsInstance<ChatItem.User>().map { it.text })
    }

    @Test(timeout = 20_000)
    fun `replay during an Ask wait releases it with CANCEL and starts a new turn`() {
        val seenAnswers = Collections.synchronizedList(mutableListOf<String>())
        var calls = 0
        lateinit var chat: ChatSession
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (calls++) {
                0 -> {
                    seenAnswers += chat.ask("等一下？", listOf("继续", "取消"))
                    words("first done")
                }
                else -> words("after replay")
            }
        }
        chat = ChatSession(
            model = model,
            postToUi = { it.run() },
            askTimeoutMillis = 5_000L,
        )

        assertTrue(chat.send("第一句"))
        awaitStatePolling(chat) { chat.awaitingAsk }
        assertTrue("an Ask wait is not a busy turn for replay", chat.replayLast())
        assertEquals(AskGate.CANCEL, chat.snapshot().filterIsInstance<ChatItem.Ask>().single().answer)
        assertEquals(listOf(AskGate.CANCEL), seenAnswers.toList())
        assertEquals(listOf("第一句", "第一句"), chat.snapshot().filterIsInstance<ChatItem.User>().map { it.text })
        awaitStatePolling(chat) { !chat.running }
    }

    // ---- harness: POSIX fake CLI + bounded waits come from ChatTestHarness --

    private fun JSONObjectOf(vararg pairs: Pair<String, String>): String =
        JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }.toString()
}
