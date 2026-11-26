package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Agent
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.desktop.graph.Workflow
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * One 对话回合 session over the SAME [WorkflowSession] and [WorkflowRunner] the
 * two existing shells use. A sentence starts one named background thread that
 * runs a single `Agent.send`; the core's emit callback is posted to the Compose
 * main thread before it touches the observable bubble list.
 *
 * [postToUi] and [startTurn] are test seams only: production posts to the AWT
 * event dispatch thread (Compose Desktop's main thread) and starts a daemon
 * thread. Neither seam adds coroutines, an event bus or a config file, and
 * [WorkflowRunner]'s blocking/cancel semantics are untouched (ADR-0009).
 */
class ChatSession(
    private val session: WorkflowSession = WorkflowSession(),
    private val runner: WorkflowRunner = WorkflowRunner(),
    // Production now goes through the copied :core LlmClient with env+memory settings.
    // Tests keep injecting their own ChatModel at this same seam.
    model: ChatModel = ProviderChatModel(ChatProviderStore.settings),
    private val postToUi: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val startTurn: (Runnable) -> Unit = { runnable ->
        Thread(runnable, "gui-chat-turn").apply { isDaemon = true }.start()
    },
) {
    private val items = Collections.synchronizedList(mutableListOf<ChatItem>())
    private val listeners = mutableListOf<() -> Unit>()
    private val turnActive = AtomicBoolean(false)

    /** The one run control the tool commands and the result panel both read. */
    private val runControl = object : WorkflowRunControl {
        override fun triggerRun(): Boolean = runner.run(session.workflow)
        override fun runSnapshot(): RunSnapshot = runner.snapshot()
    }

    /** One agent instance, so its history keeps the conversation in memory. */
    private val agent = Agent(
        model,
        DesktopToolBox(ToolInterface(session, runControl), runControl),
        "你是桌面出图助手。用可用工具修改唯一的桌面工作流并运行，最后用一句话说明结果。",
    )

    init {
        session.addChangeListener { postToUi { notifyChanged() } }
        runner.addChangeListener { postToUi { notifyChanged() } }
    }

    /** Read-only view of the one workflow, for the breadcrumb and its drawer. */
    val workflow: Workflow get() = session.workflow

    /** True while a turn owns the single background thread. */
    val running: Boolean get() = turnActive.get()

    /** Immutable bubble list for the shell; reads never see a partial emit. */
    fun snapshot(): List<ChatItem> = synchronized(items) { items.toList() }

    /** Same listener/snapshot shape as the other two mode states. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }

    /** The existing 运行态 read, so 运行日志 and result panel stay unchanged. */
    fun runSnapshot(): RunSnapshot = runner.snapshot()

    /**
     * Starts one turn. Returns false when a turn is already live — the shell
     * disables 「发送」 and never queues a second request.
     */
    fun send(text: String): Boolean {
        if (!turnActive.compareAndSet(false, true)) return false
        postToUi { append(ChatItem.User(text)) }
        startTurn {
            try {
                agent.send(text, { item ->
                    // Engine log lines stay on the run-log panel, never in a bubble.
                    if (item !is ChatItem.Log) postToUi { append(item) }
                }, { false })
            } catch (t: Throwable) {
                // A malformed tool reply must not kill the turn thread silently.
                postToUi { append(ChatItem.Error(t.message ?: t.javaClass.simpleName)) }
            } finally {
                turnActive.set(false)
                postToUi { notifyChanged() }
            }
        }
        return true
    }

    private fun append(item: ChatItem) {
        synchronized(items) { items += item }
        notifyChanged()
    }

    private fun notifyChanged() {
        val current = synchronized(listeners) { listeners.toList() }
        current.forEach { it() }
    }
}
