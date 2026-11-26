package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Provider
import java.time.LocalDate

/**
 * A one-shot, non-blocking self-check for the four `NIGHTMARE_LLM_*` settings.
 *
 * The only network boundary is [ProviderProbe.Transport]; it is injected so the
 * report can be exercised without contacting a provider. Configuration is read
 * through [ChatProviderSettings.resolved] so the diagnostic sees exactly what
 * the chat shell would use. A missing setting is a SKIP, never a failure: the
 * tool must not attempt a real call when no key/base URL is configured.
 */
class ProviderDiagnostics(
    private val transport: ProviderProbe.Transport = ProviderProbe.Companion.HttpTransport,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    /** [report] is what the user reads; [exitCode] is what the shell sees. */
    data class Result(val report: String, val exitCode: Int)

    fun run(env: Map<String, String>): Result {
        val config = ChatProviderSettings(env = { env }).resolved()
        val lines = mutableListOf<String>()
        lines += "Nightmare LLM provider diagnostics"
        lines += envLine(ChatProviderSettings.ENV_PROVIDER, env, config.provider.name)
        lines += envLine(ChatProviderSettings.ENV_BASE_URL, env, config.baseUrl)
        lines += keyLine(env)
        lines += envLine(ChatProviderSettings.ENV_MODEL, env, config.model.ifBlank { "（未设置时自动选择）" })

        val missing = mutableListOf<String>()
        if (config.key.isBlank() && config.provider != Provider.CUSTOM) {
            missing += ChatProviderSettings.ENV_KEY
        }
        if (config.provider == Provider.CUSTOM && config.baseUrl.isBlank()) {
            missing += ChatProviderSettings.ENV_BASE_URL
        }
        if (missing.isNotEmpty()) {
            lines += "SKIP: 连通性检查未运行 —— 缺少必要配置: ${missing.joinToString(", ")}"
            lines += "如何配置: 用环境变量设置 NIGHTMARE_LLM_PROVIDER / NIGHTMARE_LLM_BASE_URL / NIGHTMARE_LLM_KEY / NIGHTMARE_LLM_MODEL。" +
                "托管服务需要密钥；NIGHTMARE_LLM_PROVIDER=CUSTOM 的本地服务需要 NIGHTMARE_LLM_BASE_URL（本地无需密钥）。"
            return Result(redact(lines.joinToString("\n"), config.key), EXIT_OK)
        }

        val ids = try {
            ProviderProbe(transport = transport, today = today).models(config)
        } catch (e: Exception) {
            lines += "连通性: 未连接 —— ${e.message?.takeIf { it.isNotBlank() } ?: "未知原因"}"
            return Result(redact(lines.joinToString("\n"), config.key), EXIT_UNREACHABLE)
        }
        lines += "连通性: 已连接（GET /models, ${ProviderProbe.CONNECT_TIMEOUT_MS / 1000}s/${ProviderProbe.READ_TIMEOUT_MS / 1000}s）"
        if (ids.isEmpty()) {
            lines += "可用 chat 模型: 无（端点可达但未发现可用 chat 模型）"
        } else {
            lines += "可用 chat 模型 (${ids.size}):"
            ids.forEach { lines += "  - $it" }
        }
        return Result(redact(lines.joinToString("\n"), config.key), EXIT_OK)
    }

    private fun envLine(name: String, env: Map<String, String>, effective: String): String =
        if (env[name].isNullOrBlank()) "$name: unset (生效值: $effective)"
        else "$name: SET ($effective)"

    /** The key is never echoed: only whether it is configured (ADR-0012). */
    private fun keyLine(env: Map<String, String>): String =
        if (env[ChatProviderSettings.ENV_KEY].isNullOrBlank()) {
            "${ChatProviderSettings.ENV_KEY}: unset (密钥未配置)"
        } else {
            "${ChatProviderSettings.ENV_KEY}: SET (密钥已配置)"
        }

    /**
     * Belt and braces: no report line may carry the resolved key, its tail four
     * digits or its head four, even when the transport echoes them in an error
     * (ADR-0012). Literal replacement only — a key is never a regex here.
     */
    private fun redact(report: String, key: String): String {
        var safe = redactSecret(report, key)
        if (key.length >= 4) {
            safe = safe.replace(key.takeLast(4), "[redacted]").replace(key.take(4), "[redacted]")
        }
        return safe
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_UNREACHABLE = 1
    }
}
