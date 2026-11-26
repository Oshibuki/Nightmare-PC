package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Agent
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.desktop.graph.Workflow
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val model: ChatModel = ProviderChatModel(ChatProviderStore.settings),
    private val postToUi: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val startTurn: (Runnable) -> Unit = { runnable ->
        Thread(runnable, "gui-chat-turn").apply { isDaemon = true }.start()
    },
    askTimeoutMillis: Long = AskGate.DEFAULT_TIMEOUT_MILLIS,
    // Production uses the ADR-0009 window-close path; tests count the call.
    private val onCloseProcess: () -> Unit = { WorkflowRunner.cancelActiveProcess(runner.processHandle()) },
) {
    private val items = Collections.synchronizedList(mutableListOf<ChatItem>())
    private val listeners = mutableListOf<() -> Unit>()
    private val turnActive = AtomicBoolean(false)
    private val turnLock = Any()
    private val turnEpoch = AtomicLong(0)
    private var turnCancel = AtomicBoolean(false)

    /**
     * The Ask handshake lives here, not in the copied agent core: [ask] and
     * [answer] only edit the in-memory bubbles and the waiting future, so an
     * Ask can never leak into the workflow JSON or the run snapshot.
     */
    private val gate = AskGate(
        onAsk = { item -> postToUi { append(item) } },
        onResolved = { id, option -> postToUi { resolveAsk(id, option) } },
        timeoutMillis = askTimeoutMillis,
    )

    /** The one run control the tool commands and the result panel both read. */
    private val runControl = object : WorkflowRunControl {
        override fun triggerRun(): Boolean = runner.run(session.workflow)
        override fun runSnapshot(): RunSnapshot = runner.snapshot()
    }

    /** Stateless command surface, shared by the tools and the replay reset. */
    private val tools = ToolInterface(session, runControl)

    /**
     * One agent keeps the whole 对话回合 chain in memory; [replayLast]
     * deliberately replaces it with a fresh history for a reproducible experiment.
     * Visible to this module so ticket 07 can change the prompt/tool box in one
     * place without introducing a second construction path.
     */
    internal fun newAgent(model: ChatModel): Agent =
        Agent(model, DesktopToolBox(tools, runControl, ::ask), SYSTEM_PROMPT)

    private var agent: Agent = newAgent(model)

    init {
        session.addChangeListener { postToUi { notifyChanged() } }
        runner.addChangeListener { postToUi { notifyChanged() } }
    }

    /** Read-only view of the one workflow, for the breadcrumb and its drawer. */
    val workflow: Workflow get() = session.workflow

    /** True while a turn owns the single background thread. */
    val running: Boolean get() = turnActive.get()

    /** True while a turn thread is blocked waiting for an Ask option. */
    val awaitingAsk: Boolean get() = gate.awaiting

    /** Raises a 结构化追问 on the calling (background turn) thread and returns the picked option. */
    fun ask(question: String, options: List<String>): String = gate.ask(question, options)

    /** Release one Ask with the exact id and show the picked option in its bubble. */
    fun answer(id: Int, option: String): Boolean = gate.answer(id, option)

    /**
     * The cancellation primitive ticket 12's single entry builds on: release
     * every waiter with CANCEL and ask the live turn to stop at its next step.
     * It never touches the CLI process — ADR-0009 stays in the window-close
     * branch of [cancelTurn]/[closeWindow].
     */
    fun cancelPendingAsks(): Int {
        turnCancel.set(true)
        return gate.cancelAll()
    }

    /**
     * The one cancel entry the chat shell's 「取消」 button calls. Cooperative
     * by design: it flags the turn and releases any waiting Ask, then lets the
     * in-flight tool finish. It never interrupts the turn thread and never
     * destroys the CLI child — only [closeWindow] does that.
     */
    fun cancelTurn() = cancel(cleanupProcess = false)

    /**
     * The one window-close entry, called from Main.kt's window-level
     * DisposableEffect. It runs the same Ask/flag orchestration as [cancelTurn]
     * and only then destroys the CLI child through the existing ADR-0009 path.
     * A mode switch never disposes that effect, so switching shells keeps the
     * turn and its process alive.
     */
    fun closeWindow() = cancel(cleanupProcess = true)

    private fun cancel(cleanupProcess: Boolean) {
        val wasRunning = turnActive.get()
        val alreadyCancelled = turnCancel.get()
        cancelPendingAsks() // ticket 11's primitive also raises the turn flag
        if (wasRunning && !alreadyCancelled) {
            postToUi { append(ChatItem.Assistant(CANCELLED_BUBBLE)) }
        }
        if (cleanupProcess) onCloseProcess()
    }

    /** Immutable bubble list for the shell; reads never see a partial emit. */
    fun snapshot(): List<ChatItem> = synchronized(items) { items.toList() }

    /** The last sentence the person actually sent; an unsubmitted draft is invisible here. */
    fun lastUserText(): String? = synchronized(items) {
        items.filterIsInstance<ChatItem.User>().lastOrNull()?.text
    }

    /** Truncated preview for the single replay button's tooltip. */
    fun replayTooltip(): String? = lastUserText()?.let { text ->
        val maxChars = 48
        if (text.length <= maxChars) text else "${text.take(maxChars)}…"
    }

    /**
     * 重发上一句: the one reproducible-experiment entry. It sends the last
     * already-sent sentence through [send] — the single send path, so the busy
     * gate and the Ask-CANCEL bypass stay identical. Before that turn starts it
     * resets the one workflow through the existing 新建工作流 command and swaps
     * in a fresh Agent (clean history) over the SAME injected [model]: provider
     * settings are still read by `settings.resolved()` on every completion.
     * Old bubbles and generated pictures are never cleared.
     */
    fun replayLast(): Boolean = synchronized(turnLock) {
        val text = lastUserText() ?: return@synchronized false
        // Same gate as send(): a refused replay must not reset the workflow.
        if (turnActive.get() && !gate.awaiting) return@synchronized false
        tools.dispatch(ToolCommand.NewWorkflow)
        agent = newAgent(model)
        send(text)
    }

    /** Same listener/snapshot shape as the other two mode states. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }

    /** The existing 运行态 read, so 运行日志 and result panel stay unchanged. */
    fun runSnapshot(): RunSnapshot = runner.snapshot()

    /**
     * Starts one turn. Returns false when a turn is already live and busy —
     * the shell disables 「发送」 and never queues a second request. The one
     * exception is an Ask wait: typing there first releases the waiter with
     * CANCEL and then starts a NEW turn (map ticket 05).
     */
    fun send(text: String): Boolean {
        synchronized(turnLock) {
            val busy = turnActive.get()
            if (busy && !gate.awaiting) return false
            if (busy) {
                // CANCEL handshake before the new sentence, using ticket 11's
                // primitive rather than a second Ask-release implementation.
                cancelPendingAsks()
            } else {
                turnActive.set(true)
            }
            gate.beginTurn()
            val epoch = turnEpoch.incrementAndGet()
            val cancel = AtomicBoolean(false)
            turnCancel = cancel
            postToUi { append(ChatItem.User(text)) }
            startTurn { runTurn(text, epoch, cancel) }
            return true
        }
    }

    private fun runTurn(text: String, epoch: Long, cancel: AtomicBoolean) {
        try {
            agent.send(text, { item ->
                // Engine log lines stay on the run-log panel, never in a bubble.
                if (item !is ChatItem.Log) postToUi { append(item) }
            }, { cancel.get() || epoch != turnEpoch.get() })
        } catch (t: Throwable) {
            // A malformed tool reply must not kill the turn thread silently.
            postToUi { append(ChatItem.Error(t.message ?: t.javaClass.simpleName)) }
        } finally {
            // A superseded turn must not clear the newer turn's send gate.
            synchronized(turnLock) {
                if (epoch == turnEpoch.get()) {
                    turnActive.set(false)
                    turnCancel.set(false)
                }
            }
            postToUi { notifyChanged() }
        }
    }

    private fun append(item: ChatItem) {
        synchronized(items) { items += item }
        notifyChanged()
    }

    /** Fill only the matching Ask bubble; its four fields never leave memory. */
    private fun resolveAsk(id: Int, option: String) {
        synchronized(items) {
            val index = items.indexOfFirst { it is ChatItem.Ask && it.id == id }
            if (index >= 0) items[index] = (items[index] as ChatItem.Ask).copy(answer = option)
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        val current = synchronized(listeners) { listeners.toList() }
        current.forEach { it() }
    }

    companion object {
        /**
         * 真实模型只看到这段系统提示和工具的 JSON schema（ADR-0012）：名字、
         * 四种节点类型、id 规则、端口名、图生图参数与调用顺序都必须写在这里，
         * 否则模型会猜出被 ToolInterface 拒绝的 id 或节点类型。
         */
        val SYSTEM_PROMPT: String = """
            你是桌面出图助手。用可用工具修改唯一的桌面工作流并运行，最后用一句话说明结果。

            可用工具共十二个：new_workflow（新建空工作流）、add_node（添加节点）、
            set_parameter（设置已有节点参数）、connect（连线）、run（运行工作流）、
            list_models（列出本地模型）、get_canvas（读当前工作流 JSON）、new_flow（同 new_workflow 的空工作流）、
            save_flow（保存工作流）、list_loras（列出 LoRA）、download_lora（下载 LoRA）、delete_lora（删除 LoRA）。
            有副作用的工具会先发确认型追问，必须等用户点选后才继续：
            save_flow 覆盖已存在的文件要选「保存」才写入；download_lora 达到 512 MB 阈值要选「下载」才落盘；
            delete_lora 一定要选「删除」才删除。用户选「取消」就是不做，别重复发起同一操作。
            合法节点类型只有四种：core.prompt（提示词）、core.image（图片输入）、sd.sample（采样器）、core.output（输出）。
            add_node 的 id 必须唯一，只能使用字母、数字、下划线 _ 或连字符 -，不支持其它字符或重复 id。
            所有工具参数与节点参数的值一律是字符串，数字也要写成字符串，例如 steps 传 "20"。
            sd.sample 的 steps 最大 $MAX_RUN_STEPS：超过会被拒绝；载入的旧工作流里更大的步数在运行时钳制为 $MAX_RUN_STEPS。
            sd.sample 的 scheduler 必须传 CLI 值（discrete、karras、exponential、sgm_uniform、simple、beta 等），不要传 normal 或 Normal；
            sampler 必须传引擎名（如 euler、dpm++2m），不要传显示名。
            端口名：core.prompt 输出 prompt；core.image 输出 image；sd.sample 输入 prompt 与 initImage、输出 image；core.output 输入 image。
            图生图（img2img）：用 core.image 节点提供本地 PNG 图片路径与重绘强度；path 是字符串形式的本地图片路径，
            strength 是 0..1 的字符串，默认 "0.75"（不写 strength 时自动取默认值）。
            把 core.image 的 image 输出连到 sd.sample 的 initImage 输入，prompt 仍连到 sd.sample 的 prompt；
            工作流里有 core.image 时运行器会投影成 img2img 子命令（带 --init-img/--strength，没有 --batch）。
            典型流程：new_workflow → add_node 三次（prompt、sampler、output）→
            connect 两次（prompt:prompt 到 sampler:prompt，sampler:image 到 output:image）→
            set_parameter 设置提示词或种子 → run。
            图生图流程：new_workflow → add_node（core.image，写 path 与 strength）→
            add_node（prompt、sampler、output）→
            connect 三次（core.image:image 到 sampler:initImage、prompt:prompt 到 sampler:prompt、sampler:image 到 output:image）→ run。
        """.trimIndent()

        /** The visible marker on the turn bubble after a cancel. */
        const val CANCELLED_BUBBLE = "已取消"
    }
}
