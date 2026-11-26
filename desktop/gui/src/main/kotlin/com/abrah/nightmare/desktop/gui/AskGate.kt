package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 结构化追问 handshake, the desktop counterpart of the mobile host's
 * `AgentSession.ask/answer/stop` — none of that Android-coupled host is copied.
 *
 * The background turn thread calls [ask], which raises one [ChatItem.Ask] and
 * blocks on a future. A click calls [answer(id, option)][answer] with the exact
 * id; [cancelAll] releases every still-waiting Ask with [CANCEL], and [ask]
 * itself releases with [CANCEL] if the bounded wait expires. The four Ask
 * fields live only in the in-memory bubble list; nothing here serializes them.
 *
 * One Ask never becomes a new turn: the future's value is returned to the
 * caller on the SAME turn thread, exactly as in the mobile agent loop
 * (spec §Ask 回写契约, map ticket 05).
 */
class AskGate(
    private val onAsk: (ChatItem.Ask) -> Unit,
    private val onResolved: (id: Int, answer: String) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val nextId = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val stopped = AtomicBoolean(false)

    /** True while at least one turn thread is blocked in an Ask. */
    val awaiting: Boolean get() = waiting.isNotEmpty()

    /** Called on the background turn thread; never call it on the UI thread. */
    fun ask(question: String, options: List<String>): String {
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<String>()
        waiting[id] = future
        onAsk(ChatItem.Ask(id, question, options))
        if (stopped.get()) {
            resolve(id, CANCEL, future)
            return CANCEL
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            resolve(id, CANCEL, future)
            CANCEL
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            resolve(id, CANCEL, future)
            CANCEL
        } finally {
            waiting.remove(id)
        }
    }

    /** Returns false for an unknown/already-released id; no bubble is changed. */
    fun answer(id: Int, option: String): Boolean {
        val future = waiting[id] ?: return false
        resolve(id, option, future)
        return true
    }

    /**
     * The single cancellation primitive for callers (ticket 12's unified
     * cancel/window-close entry owns when to call it). Releases every waiter
     * with [CANCEL] and makes any Ask raised before the next [beginTurn]
     * return [CANCEL] immediately. No option is auto-selected.
     */
    fun cancelAll(): Int {
        stopped.set(true)
        val ids = waiting.keys.toList()
        ids.forEach { id -> waiting[id]?.let { resolve(id, CANCEL, it) } }
        return ids.size
    }

    /** Opens a new 对话回合: cancellation from the previous turn no longer applies. */
    fun beginTurn() {
        stopped.set(false)
    }

    private fun resolve(id: Int, answer: String, future: CompletableFuture<String>) {
        // A click racing cancelAll must not write the bubble twice.
        if (!future.complete(answer)) {
            waiting.remove(id)
            return
        }
        onResolved(id, answer)
        waiting.remove(id)
    }

    companion object {
        /** Same value as the mobile `AgentTools.CANCEL` literal (ADR-0001). */
        const val CANCEL = "Cancel"

        /** A question must never hold a turn thread forever; tests pass a shorter bound. */
        const val DEFAULT_TIMEOUT_MILLIS = 300_000L
    }
}
