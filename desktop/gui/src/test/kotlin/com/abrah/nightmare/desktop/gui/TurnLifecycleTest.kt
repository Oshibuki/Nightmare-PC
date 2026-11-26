package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.agent.LlmError
import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.gui.fake.ScriptChatModel
import com.abrah.nightmare.desktop.gui.fake.toolCall
import com.abrah.nightmare.desktop.gui.fake.words
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * 回合生命周期 state-layer tests: cancel/window-close orchestration, the
 * single-turn send gate and retry. All waits are bounded and every fake is a
 * JVM fake (fake ChatModel + POSIX fake CLI); no Compose UI test framework,
 * no real provider and no sleep polling.
 */
class TurnLifecycleTest {

    @Test(timeout = 20_000)
    fun `cancel waits for the in-flight tool then stops without a new LLM request`() {
        assumePosix()
        val cli = blockingCliPng()
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(cli.config)
        var modelCalls = 0
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (modelCalls++) {
                0 -> toolCall(
                    "t1", "add_node",
                    JSONObject().put("id", "prompt").put("type", DesktopNodeTypes.PROMPT)
                        .put("params", JSONObject().put("prompt", "cancel keeps this edit")).toString(),
                )
                1 -> toolCall("t2", "run", "{}")
                2 -> words("this model call must never happen after cancel")
                else -> words("unexpected extra model call")
            }
        }
        val chat = ChatSession(session = session, runner = runner, model = model, postToUi = { it.run() })

        assertTrue(chat.send("build then cancel"))
        awaitState(chat) { runner.isRunning() && runner.processHandle()?.isAlive == true }
        assertEquals(2, modelCalls)

        val child = runner.processHandle()!!
        chat.cancelTurn()
        assertTrue("cancel is cooperative: the in-flight CLI tool keeps running", child.isAlive)
        assertTrue("the turn ends only after the tool finishes", chat.running)
        assertEquals("cancel sends no new LLM request", 2, modelCalls)

        cli.release()
        awaitState(chat) { !chat.running }
        assertEquals("the next step sees the flag, not another model call", 2, modelCalls)

        val bubbles = chat.snapshot()
        assertTrue(ChatItem.User("build then cancel") in bubbles)
        assertTrue("cancel bubble is marked 已取消", ChatItem.Assistant("已取消") in bubbles)
        assertTrue("a picture already produced by the in-flight tool survives", ChatItem.Picture(cli.png.path) in bubbles)
        assertEquals(RunStatus.SUCCESS, runner.snapshot().status)
        assertEquals("cancel keeps this edit", session.workflow.nodes.single().params["prompt"])
    }

    @Test(timeout = 15_000)
    fun `window close releases a waiting Ask and cleans the process through one entry`() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var cleanups = 0
        lateinit var chat: ChatSession
        val model = object : ChatModel {
            private var calls = 0
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (calls++) {
                0 -> {
                    seen += chat.ask("关窗时怎么办？", listOf("是", "否"))
                    words("first turn finished")
                }
                else -> words("unexpected extra model call")
            }
        }
        chat = ChatSession(
            model = model,
            postToUi = { it.run() },
            askTimeoutMillis = 5_000L,
            onCloseProcess = { cleanups++ },
        )

        val asked = CountDownLatch(1)
        chat.addChangeListener { if (chat.awaitingAsk) asked.countDown() }
        assertTrue(chat.send("第一句"))
        assertTrue(asked.await(5, SECONDS))
        assertEquals("no process cleanup before the window closes", 0, cleanups)

        chat.closeWindow()
        assertEquals("window close cleans the CLI exactly once", 1, cleanups)
        awaitState(chat) { !chat.running && !chat.awaitingAsk }
        assertEquals(listOf(AskGate.CANCEL), seen)
        val bubbles = chat.snapshot()
        assertEquals(AskGate.CANCEL, bubbles.filterIsInstance<ChatItem.Ask>().single().answer)
        assertTrue("close marks the turn bubble 已取消", ChatItem.Assistant("已取消") in bubbles)
        assertTrue("the user's old bubble survives close", ChatItem.User("第一句") in bubbles)
    }

    @Test(timeout = 20_000)
    fun `window close destroys the live CLI child through the existing runner path`() {
        assumePosix()
        val cli = blockingCliPng()
        val runner = WorkflowRunner(cli.config)
        val chat = ChatSession(
            runner = runner,
            model = ScriptChatModel(toolCall("r1", "run", "{}")),
            postToUi = { it.run() },
        )

        assertTrue(chat.send("run then close the window"))
        awaitState(chat) { runner.isRunning() && runner.processHandle()?.isAlive == true }
        val child = runner.processHandle()!!
        chat.closeWindow()

        awaitState(chat) { !child.isAlive }
        awaitState(chat) { !chat.running }
        assertEquals("ADR-0009: the window-close path settles the run as CANCELLED", RunStatus.CANCELLED, runner.snapshot().status)
        assertTrue("the child is gone", runner.processHandle()?.isAlive != true)
        assertTrue(ChatItem.Assistant("已取消") in chat.snapshot())
    }

    @Test(timeout = 15_000)
    fun `a running turn refuses a second send and keeps the typed draft`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var modelCalls = 0
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
                if (modelCalls++ == 0) {
                    entered.countDown()
                    release.await(8, SECONDS)
                    return words("first turn ok")
                }
                return words("this second turn must never start")
            }
        }
        val chat = ChatSession(model = model, postToUi = { it.run() })

        assertTrue(chat.send("first"))
        assertTrue(entered.await(5, SECONDS))
        assertTrue(chat.running)

        // Mirrors ChatModeScreen's composer: a refused send leaves the text in
        // the input box, so nothing is queued and nothing typed is lost.
        var draft = "second sentence typed while running"
        val accepted = if (draft.isNotBlank() && chat.send(draft)) {
            draft = ""
            true
        } else {
            false
        }
        assertFalse("no second request while a turn is live", accepted)
        assertEquals("second sentence typed while running", draft)
        assertEquals(1, chat.snapshot().count { it is ChatItem.User })
        assertEquals("the second model call never happened", 1, modelCalls)

        release.countDown()
        awaitState(chat) { !chat.running }
        assertTrue("the first turn still finishes", ChatItem.Assistant("first turn ok") in chat.snapshot())
    }

    @Test(timeout = 20_000)
    fun `switching shells keeps the turn running and its picture in the same workflow`() {
        assumePosix()
        val (config, png) = simpleCliPng()
        val session = WorkflowSession(Workflow())
        val runner = WorkflowRunner(config)
        lateinit var chat: ChatSession
        val model = object : ChatModel {
            private var calls = 0
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (calls++) {
                0 -> {
                    chat.ask("切模式后继续吗？", listOf("继续", "取消"))
                    toolCall(
                        "m1", "add_node",
                        JSONObject().put("id", "prompt").put("type", DesktopNodeTypes.PROMPT)
                            .put("params", JSONObject().put("prompt", "same workflow")).toString(),
                    )
                }
                1 -> toolCall("m2", "run", "{}")
                2 -> words("切回来还在")
                else -> words("unexpected extra model call")
            }
        }
        chat = ChatSession(
            session = session,
            runner = runner,
            model = model,
            postToUi = { it.run() },
            askTimeoutMillis = 5_000L,
        )

        assertTrue(chat.send("切模式也要继续"))
        awaitState(chat) { chat.awaitingAsk }
        val ask = chat.snapshot().filterIsInstance<ChatItem.Ask>().single()

        // A mode switch disposes the ChatModeScreen composable, not the
        // window-level ChatSession: only a screen listener is unsubscribed.
        val shellListener = chat.addChangeListener {}
        shellListener()
        assertTrue("background turn survives a shell switch", chat.running)
        assertTrue("the waiting Ask survives a shell switch", chat.awaitingAsk)

        assertTrue(chat.answer(ask.id, "继续"))
        awaitState(chat) { ChatItem.Picture(png.path) in chat.snapshot() }
        awaitState(chat) { !chat.running }
        // Switching back reattaches a listener to the same hoisted state.
        val reattached = chat.addChangeListener {}
        reattached()

        val bubbles = chat.snapshot()
        assertEquals(1, bubbles.count { it is ChatItem.User })
        assertEquals("继续", bubbles.filterIsInstance<ChatItem.Ask>().single().answer)
        assertEquals("切回来还在", bubbles.filterIsInstance<ChatItem.Assistant>().single().text)
        assertEquals(png.path, runner.snapshot().results.single().path)
        assertEquals("same workflow", session.workflow.nodes.single().params["prompt"])
    }

    @Test(timeout = 15_000)
    fun `a failed turn retries with a new message without erasing bubbles or history`() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<JSONArray>())
        val model = object : ChatModel {
            private var calls = 0
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
                seen += JSONArray(messages.toString())
                return when (calls++) {
                    0 -> throw LlmError("provider is down")
                    1 -> words("retry ok")
                    else -> words("unexpected extra model call")
                }
            }
        }
        val chat = ChatSession(model = model, postToUi = { it.run() })

        assertTrue(chat.send("第一次"))
        awaitState(chat) { !chat.running }
        val error = chat.snapshot().filterIsInstance<ChatItem.Error>().single()
        assertTrue("provider is down" in error.text)

        assertTrue("a finished failed turn frees the send gate", chat.send("第二次重试"))
        awaitState(chat) { !chat.running && ChatItem.Assistant("retry ok") in chat.snapshot() }

        val bubbles = chat.snapshot()
        assertEquals(listOf("第一次", "第二次重试"), bubbles.filterIsInstance<ChatItem.User>().map { it.text })
        assertTrue("the old error bubble is not wiped", error in bubbles)
        assertTrue(ChatItem.Assistant("retry ok") in bubbles)
        assertTrue("the in-memory agent history carried the first message", seen.last().toString().contains("第一次"))
        assertTrue("a fresh session (app restart) starts with no bubbles", ChatSession(postToUi = { it.run() }).snapshot().isEmpty())
    }

    @Test(timeout = 20_000)
    fun `a cancelled turn can be retried and keeps the cancelled bubble and picture`() {
        assumePosix()
        val cli = blockingCliPng()
        val model = ScriptChatModel(
            toolCall("a1", "run", "{}"),
            words("retry ok"),
        )
        val runner = WorkflowRunner(cli.config)
        val chat = ChatSession(runner = runner, model = model, postToUi = { it.run() })

        assertTrue(chat.send("第一次"))
        awaitState(chat) { runner.isRunning() && runner.processHandle()?.isAlive == true }
        chat.cancelTurn()
        cli.release()
        awaitState(chat) { !chat.running }

        assertTrue("retry starts a new turn", chat.send("重试"))
        awaitState(chat) { !chat.running && ChatItem.Assistant("retry ok") in chat.snapshot() }
        val bubbles = chat.snapshot()
        assertEquals(listOf("第一次", "重试"), bubbles.filterIsInstance<ChatItem.User>().map { it.text })
        assertTrue("已取消 bubble survives retry", ChatItem.Assistant("已取消") in bubbles)
        assertTrue("the picture from the first turn survives retry", ChatItem.Picture(cli.png.path) in bubbles)
    }

    // ---- harness ---------------------------------------------------------

    /** Listener-driven with a bounded fallback poll: no Thread.sleep anywhere. */
    private fun awaitState(chat: ChatSession, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(10)
        val latch = CountDownLatch(1)
        val unsubscribe = chat.addChangeListener { if (predicate()) latch.countDown() }
        try {
            while (!predicate()) {
                assertTrue("state never settled", System.nanoTime() < deadline)
                latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            unsubscribe()
        }
    }

    private fun assumePosix() {
        Assume.assumeFalse(
            "the fake CLI is a POSIX shell script",
            System.getProperty("os.name").startsWith("Windows"),
        )
    }

    /** A fake CLI that blocks inside one tool call until the test releases it. */
    private data class BlockingCli(val config: RunConfig, val png: File, val release: () -> Unit)

    private fun simpleCliPng(): Pair<RunConfig, File> {
        val dir = Files.createTempDirectory("turn-lifecycle-fast").toFile()
        val png = File(dir, "fast-result.png")
        val script = File(dir, "fast-cli.sh")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        script.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
                append(": > ${quote(png.path)}\n")
                append("exit 0\n")
            },
        )
        script.setExecutable(true)
        return RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = 10_000L) to png
    }

    private fun blockingCliPng(): BlockingCli {
        val dir = Files.createTempDirectory("turn-lifecycle-test").toFile()
        val png = File(dir, "turn-result.png")
        val fifo = File(dir, "release.fifo")
        val made = ProcessBuilder("mkfifo", fifo.path).start().waitFor(10, SECONDS)
        Assume.assumeTrue("mkfifo is unavailable", made)
        val script = File(dir, "blocking-cli.sh")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        script.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("cat ${quote(fifo.path)} > /dev/null\n")
                append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
                append(": > ${quote(png.path)}\n")
                append("exit 0\n")
            },
        )
        script.setExecutable(true)
        val release = { FileOutputStream(fifo).use { it.write(0x78) } }
        return BlockingCli(
            RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = 15_000L),
            png,
            release,
        )
    }
}
