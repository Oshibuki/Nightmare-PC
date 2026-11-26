package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.desktop.gui.fake.fakeCliPng
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Manual, opt-in verification that the *application* — not a curl probe — can
 * drive a real OpenAI-compatible LLM through the copied Agent core into the
 * desktop workflow and a generated picture. Phase 2's verify-gui.sh deliberately
 * stays offline (ADR-0011), so this lives as a separate JavaExec task.
 *
 * It wires the real [ProviderChatModel] (no injected fake) to a [ChatSession]
 * driven synchronously, sends one Chinese sentence, and prints every observable
 * step as evidence. Missing config => SKIP, exit 0, no network call.
 *
 * Exit codes: 0 = SKIP or all three success criteria met; 1 = reached the
 * provider but a criterion failed; 2 = unexpected exception.
 */
object RealProviderVerificationMain {

    private const val EXIT_SKIP_OR_PASS = 0
    private const val EXIT_FAIL = 1
    private const val EXIT_ERROR = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val env = System.getenv()
        val config = ChatProviderStore.settings.resolved()
        val provider = env["NIGHTMARE_LLM_PROVIDER"]?.trim().orEmpty()
        val baseUrl = config.baseUrl.trim()

        println("== Nightmare 应用内真 provider 验证 ==")
        println("provider(env)=${provider.ifBlank { "(未设置)" }}  baseUrl=${baseUrl.ifBlank { "(未设置)" }}")
        println("model=${config.model.ifBlank { "(自动选择)" }}  key=${if (config.key.isBlank()) "未配置" else "已配置"}")

        // Same precondition as the diagnostics tool: a hosted provider needs a
        // key; CUSTOM needs a base URL. SKIP cleanly — never attempt a call.
        if (provider.isBlank() || (provider.uppercase(Locale.ROOT) != "CUSTOM" && config.key.isBlank()) ||
            (provider.uppercase(Locale.ROOT) == "CUSTOM" && baseUrl.isBlank())
        ) {
            println("SKIP: 缺少必要配置。未发起任何网络请求。")
            println("配置示例: NIGHTMARE_LLM_PROVIDER=CUSTOM NIGHTMARE_LLM_BASE_URL=http://127.0.0.1:13305/v1 NIGHTMARE_LLM_MODEL=Qwen3.5-4B-GGUF")
            System.out.flush()
            exitProcess(EXIT_SKIP_OR_PASS)
        }

        val (cliConfig, png) = fakeCliPng("real-provider-verify", "real-provider-result.png", captureArgv = true)
        val turnTimeoutMs = (env["NIGHTMARE_VERIFY_TIMEOUT_MS"]?.toLongOrNull() ?: 120_000L)
        return try {
            // Default model is the real ProviderChatModel — do NOT inject a fake.
            // Synchronous seams so the single send() finishes before we observe.
            val chat = ChatSession(
                runner = WorkflowRunner(cliConfig.copy(timeoutMillis = turnTimeoutMs)),
                postToUi = { it.run() },
                startTurn = { it.run() },
                askTimeoutMillis = 15_000L,
            )
            // Default phrasing drives the original five-command path. A custom
            // NIGHTMARE_VERIFY_PROMPT lets one run exercise newly mapped tools
            // (e.g. list_models / get_canvas / save_flow) without hardcoding
            // a second scenario into this opt-in verifier.
            val prompt = env["NIGHTMARE_VERIFY_PROMPT"]?.takeIf { it.isNotBlank() }
                ?: "请新建工作流，放一个提示词节点和一个采样节点、一个输出节点，把提示词设为「清晨薄雾的城市，柔和光线」，依次连成链后运行。"
            println("发送: $prompt")
            val accepted = chat.send(prompt)
            if (!accepted) {
                println("FAIL[发送]: 回合未被接受（运行中或追问等待冲突）。")
                System.out.flush()
                exitProcess(EXIT_FAIL)
            }

            val bubbles = chat.snapshot()
            println("--- 对话气泡（${bubbles.size}）---")
            bubbles.forEach { item ->
                when (item) {
                    is ChatItem.Tool -> println("  Tool[${if (item.ok) "ok" else "拒绝"}] ${item.name}: ${item.summary.take(120)}")
                    is ChatItem.Picture -> println("  Picture: ${item.path}")
                    is ChatItem.Assistant -> println("  Assistant: ${item.text.take(160)}")
                    is ChatItem.Error -> println("  Error: ${item.text.take(200)}")
                    is ChatItem.Ask -> println("  Ask: ${item.question.take(160)}")
                    is ChatItem.User -> println("  User: ${item.text.take(80)}")
                    is ChatItem.Log -> println("  Log: ${item.lines.size} 行")
                }
            }

            val toolCalls = bubbles.filterIsInstance<ChatItem.Tool>()
            val toolCallSeen = toolCalls.isNotEmpty() && toolCalls.all { it.ok }
            val nodes = chat.workflow.nodes
            val workflowChanged = nodes.size >= 3
            val pictureSeen = bubbles.any { it is ChatItem.Picture && File(it.path).exists() }
            // A render turn calls the `run` tool and expects a picture; a
            // read-only/save turn (list_models/get_canvas/save_flow/...) does
            // not. Deriving it from the actual tool calls avoids both a false
            // negative on read-only prompts and a false pass on render prompts.
            val expectsPicture = toolCalls.any { it.name == "run" }
            val argv = File(cliConfig.outputPath).parentFile?.let { File(it, "argv.txt") }
            println("--- 证据 ---")
            println("  工作流节点数: ${nodes.size} (${nodes.map { it.type }.joinToString()})")
            println("  CLI argv: ${argv?.takeIf { it.exists() }?.readText()?.trim()?.replace('\n', ' ') ?: "(无)"}")
            println("  假 CLI 产物存在: ${File(png.path).exists()} (${png.path})")
            println("  期望出图: $expectsPicture（由本次是否调用 run 决定）")

            val passed = toolCallSeen && workflowChanged && (!expectsPicture || pictureSeen)
            println(if (passed) "PASS: 判据满足（真服务 tool_calls 全 ok → 工作流可读 → ${if (expectsPicture) "产物图/Picture 气泡" else "只读/保存工具成功"}）。"
                    else "FAIL: tool_calls=$toolCallSeen 工作流改动=$workflowChanged 产物图=$pictureSeen（期望出图=$expectsPicture）")
            System.out.flush()
            exitProcess(if (passed) EXIT_SKIP_OR_PASS else EXIT_FAIL)
        } catch (t: Throwable) {
            println("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.out)
            System.out.flush()
            exitProcess(EXIT_ERROR)
        }
    }
}
