package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.desktop.gui.fake.awaitBubble
import com.abrah.nightmare.desktop.gui.fake.awaitStateOnce
import com.abrah.nightmare.desktop.gui.fake.assumePosix
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Ask 放行/回写的 state-layer tests. No Compose test framework: the fake model
 * calls the public [ChatSession.ask] from the background turn thread, a latch
 * waits for the bubble, and a real turn thread is joined with a hard bound.
 */
class AskSessionTest {

    @Test(timeout = 15_000)
    fun `tapping an option releases the wait and the same turn renders a picture`() {
        assumePosix()
        val (config, png) = fakeCliPng("ask-session-test", "ask-result.png")
        lateinit var chat: ChatSession
        val selected = mutableListOf<String>()
        var modelCalls = 0
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (modelCalls++) {
                0 -> {
                    selected += chat.ask("固定 seed 还是随机？", listOf("固定 seed", "随机 seed"))
                    toolCall("a1", "set_parameter", json("nodeId" to "sampler", "key" to "seed", "value" to "11"))
                }
                1 -> toolCall("a2", "run", "{}")
                2 -> words("按你的选择重跑完成")
                else -> words("unexpected extra model call")
            }
        }
        chat = ChatSession(
            model = model,
            runner = WorkflowRunner(config),
            postToUi = { it.run() },
            askTimeoutMillis = 5_000L,
        )

        val asked = CountDownLatch(1)
        chat.addChangeListener { if (chat.snapshot().any { it is ChatItem.Ask }) asked.countDown() }
        assertTrue(chat.send("按固定 seed 重跑"))
        assertTrue("the turn must raise an Ask before waiting", asked.await(5, SECONDS))

        val ask = chat.snapshot().filterIsInstance<ChatItem.Ask>().single()
        assertEquals("固定 seed 还是随机？", ask.question)
        assertEquals(listOf("固定 seed", "随机 seed"), ask.options)
        assertNull("no option is auto-selected", ask.answer)
        assertTrue(chat.awaitingAsk)
        assertTrue(chat.answer(ask.id, "固定 seed"))

        awaitBubble(chat) { it is ChatItem.Picture }
        awaitStateOnce(chat) { !chat.running && !chat.awaitingAsk }
        assertEquals(listOf("固定 seed"), selected)
        assertEquals(3, modelCalls)
        val bubbles = chat.snapshot()
        assertEquals(1, bubbles.count { it is ChatItem.User })
        assertEquals("固定 seed", bubbles.filterIsInstance<ChatItem.Ask>().single().answer)
        assertTrue("the same turn kept running and produced the PNG", ChatItem.Picture(png.path) in bubbles)
        assertTrue(bubbles.any { it is ChatItem.Tool && it.name == "run" })
        assertFalse(chat.running)
        assertFalse(chat.awaitingAsk)
    }

    @Test(timeout = 15_000)
    fun `typing while an Ask waits releases it with CANCEL and starts a new turn`() {
        val seenAnswers = mutableListOf<String>()
        var modelCalls = 0
        lateinit var chat: ChatSession
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (modelCalls++) {
                0 -> {
                    seenAnswers += chat.ask("要固定 seed 吗？", listOf("是", "否"))
                    words("first turn finished")
                }
                1 -> words("second turn ok")
                else -> words("unexpected extra model call")
            }
        }
        chat = ChatSession(model = model, postToUi = { it.run() }, askTimeoutMillis = 5_000L)

        val asked = CountDownLatch(1)
        chat.addChangeListener { if (chat.awaitingAsk) asked.countDown() }
        assertTrue(chat.send("第一句"))
        assertTrue(asked.await(5, SECONDS))
        assertEquals(1, chat.snapshot().count { it is ChatItem.Ask })

        // Switching shells only disposes the composable's listener: the state
        // is hoisted at window level (Main.kt), so the waiting Ask survives.
        val unsubscribe = chat.addChangeListener {}
        unsubscribe()
        assertTrue("mode switch must not cancel a waiting Ask", chat.awaitingAsk)
        chat.addChangeListener {}

        assertTrue("typing during an Ask must start a new turn", chat.send("第二句"))
        awaitBubble(chat) { it is ChatItem.Assistant && it.text == "second turn ok" }
        awaitStateOnce(chat) { !chat.running && !chat.awaitingAsk }

        assertEquals(listOf(AskGate.CANCEL), seenAnswers)
        val bubbles = chat.snapshot()
        assertEquals(2, bubbles.count { it is ChatItem.User })
        assertEquals(AskGate.CANCEL, bubbles.filterIsInstance<ChatItem.Ask>().single().answer)
        assertTrue(bubbles.any { it is ChatItem.Assistant && it.text == "first turn finished" })
    }

    @Test(timeout = 10_000)
    fun `the cancel primitive releases every waiter and a late answer is a no-op`() {
        val chat = ChatSession(postToUi = { it.run() }, askTimeoutMillis = 5_000L)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val waiters = listOf("要重跑吗？", "要换模型吗？").map { question ->
            Thread(
                { results += chat.ask(question, listOf("选项一", "选项二")) },
                "test-ask-waiter",
            ).apply { isDaemon = true }
        }
        waiters.forEach { it.start() }
        awaitStateOnce(chat) { chat.snapshot().count { item -> item is ChatItem.Ask } == 2 && chat.awaitingAsk }

        assertEquals(2, chat.cancelPendingAsks())
        waiters.forEach { thread ->
            thread.join(4_000)
            assertFalse("a released waiter must not hang", thread.isAlive)
        }
        assertEquals(listOf(AskGate.CANCEL, AskGate.CANCEL), results)
        val asks = chat.snapshot().filterIsInstance<ChatItem.Ask>()
        assertEquals(2, asks.size)
        assertTrue("cancellation is never auto-picking option one", asks.all { it.answer == AskGate.CANCEL })
        assertFalse(chat.awaitingAsk)
        assertFalse("a late click must not revive a released Ask", chat.answer(asks.first().id, "选项一"))
    }

    @Test(timeout = 10_000)
    fun `an unanswered Ask is released by its bounded wait instead of hanging`() {
        val chat = ChatSession(postToUi = { it.run() }, askTimeoutMillis = 200L)
        val answer = chat.ask("没人回答的问题", listOf("是", "否"))
        assertEquals(AskGate.CANCEL, answer)
        assertFalse(chat.awaitingAsk)
        assertEquals(AskGate.CANCEL, chat.snapshot().filterIsInstance<ChatItem.Ask>().single().answer)
    }

    @Test(timeout = 10_000)
    fun `all four Ask fields stay in memory never in workflow JSON or run snapshot`() {
        val question = "唯一追问问题XYZ"
        val options = listOf("选项甲XYZ", "选项乙XYZ")
        val chat = ChatSession(postToUi = { it.run() }, askTimeoutMillis = 5_000L)
        val waiter = Thread({ chat.ask(question, options) }, "test-ask-fields").apply { isDaemon = true }
        waiter.start()
        awaitStateOnce(chat) { chat.snapshot().any { it is ChatItem.Ask } }
        val raised = chat.snapshot().filterIsInstance<ChatItem.Ask>().single()
        assertTrue(chat.answer(raised.id, options[0]))
        waiter.join(4_000)
        assertFalse(waiter.isAlive)

        val bubble = chat.snapshot().filterIsInstance<ChatItem.Ask>().single()
        assertEquals(raised.id, bubble.id)
        assertEquals(question, bubble.question)
        assertEquals(options, bubble.options)
        assertEquals(options[0], bubble.answer)

        val workflowJson = chat.workflow.toJson()
        val runState = chat.runSnapshot().toString()
        listOf(question, options[0], options[1]).forEach { field ->
            assertFalse("Ask field leaked into 工作流 JSON: $field", workflowJson.contains(field))
            assertFalse("Ask field leaked into 运行态: $field", runState.contains(field))
        }
    }

    // ---- harness: POSIX fake CLI + bounded waits come from ChatTestHarness --

    private fun json(vararg pairs: Pair<String, String>): String =
        JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }.toString()
}
