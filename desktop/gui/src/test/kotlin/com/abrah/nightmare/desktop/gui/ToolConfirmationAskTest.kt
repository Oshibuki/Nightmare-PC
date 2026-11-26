package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.desktop.gui.fake.awaitBubble
import com.abrah.nightmare.desktop.gui.fake.awaitStateOnce
import com.abrah.nightmare.desktop.gui.fake.toolCall
import com.abrah.nightmare.desktop.gui.fake.words
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * End-to-end proof that [ChatSession.newAgent] injects [ChatSession.ask] into
 * the production [DesktopToolBox]: a side-effecting tool raises a real 确认型
 * 结构化追问 bubble and the answer comes back on the same turn thread (ADR-0015).
 */
class ToolConfirmationAskTest {

    @Test(timeout = 15_000)
    fun `a tool confirmation Ask reaches ChatSession and a cancel keeps the saved file untouched`() {
        val target = Files.createTempDirectory("ask-tool-confirm").resolve("flow.json")
        Files.writeString(target, "old")
        lateinit var chat: ChatSession
        var modelCalls = 0
        val model = object : ChatModel {
            override fun complete(messages: JSONArray, tools: JSONArray): JSONObject = when (modelCalls++) {
                0 -> toolCall("save1", "save_flow", JSONObject().put("path", target.toString()).toString())
                1 -> words("保存回合完成")
                else -> words("unexpected extra model call")
            }
        }
        chat = ChatSession(model = model, postToUi = { it.run() }, askTimeoutMillis = 5_000L)

        val asked = CountDownLatch(1)
        chat.addChangeListener { if (chat.snapshot().any { it is ChatItem.Ask }) asked.countDown() }
        assertTrue(chat.send("保存工作流"))
        assertTrue("the tool must raise a confirmation Ask", asked.await(5, SECONDS))

        val bubble = chat.snapshot().filterIsInstance<ChatItem.Ask>().single()
        assertEquals("覆盖已保存的工作流?", bubble.question)
        assertEquals(listOf("保存", "取消"), bubble.options)
        assertTrue(chat.answer(bubble.id, "取消"))

        awaitBubble(chat) { it is ChatItem.Assistant && it.text == "保存回合完成" }
        awaitStateOnce(chat) { !chat.running && !chat.awaitingAsk }
        assertEquals("a cancel must not overwrite the file", "old", Files.readString(target))
    }
}
