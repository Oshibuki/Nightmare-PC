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
import java.io.File
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
        val argv = argvOf(config)
        assertEquals("txt2img", argv.first())
        assertNull("a txt2img turn carries no init image", argvValue(argv, "--init-img"))
        assertEquals("1", argvValue(argv, "--batch"))
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

    @Test
    fun `the system prompt teaches the image node its params and img2img ports`() {
        val prompt = ChatSession.SYSTEM_PROMPT

        assertTrue("the image node type must be legal: $prompt", prompt.contains(DesktopNodeTypes.IMAGE_INPUT))
        assertTrue("the sampler initImage port must be named: $prompt", prompt.contains("initImage"))
        assertTrue("the path param must be named: $prompt", prompt.contains("path"))
        assertTrue("the strength param must be named: $prompt", prompt.contains("strength"))
        assertTrue("the default strength must be visible: $prompt", prompt.contains("0.75"))
        assertTrue("the 0..1 range must be visible: $prompt", prompt.contains("0..1") || prompt.contains("0 到 1"))
    }

    @Test
    fun `the tool schema gives real providers the image type path strength and initImage port`() {
        var seenTools: JSONArray? = null
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
                seenTools = tools
                return words("ok")
            }
        }
        val chat = ChatSession(model = model, postToUi = { it.run() }, startTurn = { it.run() })
        assertTrue(chat.send("hello"))

        val tools = checkNotNull(seenTools)
        fun schema(name: String): JSONObject = (0 until tools.length())
            .map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }

        val add = schema("add_node")
        val props = add.getJSONObject("parameters").getJSONObject("properties")
        val enum = props.getJSONObject("type").getJSONArray("enum")
        assertTrue(
            "core.image must be in the add_node type enum",
            (0 until enum.length()).map { enum.getString(it) }.contains(DesktopNodeTypes.IMAGE_INPUT),
        )
        assertTrue("the description must name core.image", add.getString("description").contains(DesktopNodeTypes.IMAGE_INPUT))
        assertTrue(
            "the model must see path and strength as string params",
            props.getJSONObject("params").getString("description").let { it.contains("path") && it.contains("strength") },
        )
        assertTrue("connect must teach the initImage port", schema("connect").getString("description").contains("initImage"))
    }

    @Test(timeout = 20_000)
    fun `one sentence drives an img2img turn through the five existing commands`() {
        assumePosix()
        val initPath = "/tmp/nm-desktop-base.png"
        val (config, png) = fakeCliPng("chat-img2img-test", "img2img-result.png", captureArgv = true)
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(config)
        val add = { id: String, type: String -> JSONObjectOf("id" to id, "type" to type) }
        val imageArgs = JSONObject()
            .put("id", "base")
            // The short alias exercises the same name -> command mapping real models use.
            .put("type", "image")
            .put("params", JSONObject().put("path", initPath).put("strength", "0.4"))
            .toString()
        val model = ScriptChatModel(
            toolCall("m1", "new_workflow", "{}"),
            toolCall("m2", "add_node", imageArgs),
            toolCall("m3", "add_node", add("prompt", DesktopNodeTypes.PROMPT)),
            toolCall("m4", "add_node", add("sampler", DesktopNodeTypes.SD_SAMPLER)),
            toolCall("m5", "add_node", add("output", DesktopNodeTypes.OUTPUT)),
            toolCall("m6", "connect", JSONObjectOf("fromNode" to "base", "toNode" to "sampler", "toPort" to "initImage")),
            toolCall("m7", "connect", JSONObjectOf("fromNode" to "prompt", "toNode" to "sampler", "toPort" to "prompt")),
            toolCall("m8", "connect", JSONObjectOf("fromNode" to "sampler", "toNode" to "output", "toPort" to "image")),
            toolCall("m9", "run", "{}"),
            words("redrawn"),
        )
        val chat = ChatSession(
            session = session,
            runner = runner,
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("用这张图按这句描述重画"))

        val shown = chat.snapshot()
        val tools = shown.filterIsInstance<ChatItem.Tool>()
        assertEquals(9, tools.size)
        assertTrue("every img2img tool call was accepted", tools.all { it.ok })
        assertEquals("redrawn", (shown.last() as ChatItem.Assistant).text)
        assertTrue("the PNG became a Picture bubble", ChatItem.Picture(png.path) in shown)
        assertTrue("engine日志走日志面板，不进气泡", shown.none { it is ChatItem.Log })

        val imageNode = session.workflow.nodes.first { it.type == DesktopNodeTypes.IMAGE_INPUT }
        assertEquals(initPath, imageNode.params["path"])
        assertEquals("0.4", imageNode.params["strength"])
        val sampler = session.workflow.nodes.first { it.type == DesktopNodeTypes.SD_SAMPLER }
        assertEquals("base", sampler.inputs["initImage"]?.node)

        val argv = argvOf(config)
        assertEquals("img2img", argv.first())
        assertEquals(initPath, argvValue(argv, "--init-img"))
        assertEquals("0.4", argvValue(argv, "--strength"))
        assertNull("img2img has no --batch", argvValue(argv, "--batch"))

        val run = runner.snapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(png.path, run.results.single().path)
        assertFalse(chat.running)
    }

    @Test(timeout = 20_000)
    fun `an image node added without strength carries the 0 75 default into the run`() {
        assumePosix()
        val initPath = "/tmp/nm-desktop-default-base.png"
        val (config, png) = fakeCliPng("chat-img2img-default-test", "default-result.png", captureArgv = true)
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(config)
        val imageArgs = JSONObject()
            .put("id", "base")
            .put("type", DesktopNodeTypes.IMAGE_INPUT)
            .put("params", JSONObject().put("path", initPath))
            .toString()
        val model = ScriptChatModel(
            toolCall("d1", "new_workflow", "{}"),
            toolCall("d2", "add_node", imageArgs),
            toolCall("d3", "add_node", JSONObjectOf("id" to "sampler", "type" to DesktopNodeTypes.SD_SAMPLER)),
            toolCall("d4", "connect", JSONObjectOf("fromNode" to "base", "toNode" to "sampler", "toPort" to "initImage")),
            toolCall("d5", "run", "{}"),
            words("default done"),
        )
        val chat = ChatSession(
            session = session,
            runner = runner,
            model = model,
            postToUi = { it.run() },
            startTurn = { it.run() },
        )

        assertTrue(chat.send("按默认强度重画"))

        assertTrue("every tool call was accepted", chat.snapshot().filterIsInstance<ChatItem.Tool>().all { it.ok })
        assertEquals("0.75", session.workflow.nodes.first { it.type == DesktopNodeTypes.IMAGE_INPUT }.params["strength"])
        assertEquals("0.75", argvValue(argvOf(config), "--strength"))
        assertEquals(RunStatus.SUCCESS, runner.snapshot().status)
        assertTrue(ChatItem.Picture(png.path) in chat.snapshot())
    }

    // ---- harness: POSIX fake CLI + bounded waits come from ChatTestHarness --

    private fun argvOf(config: RunConfig): List<String> =
        File(File(config.cliExecutable).parentFile, "argv.txt").readLines()

    private fun argvValue(argv: List<String>, flag: String): String? {
        val index = argv.indexOf(flag)
        return if (index < 0) null else argv.getOrNull(index + 1)
    }

    private fun JSONObjectOf(vararg pairs: Pair<String, String>): String =
        JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }.toString()
}
