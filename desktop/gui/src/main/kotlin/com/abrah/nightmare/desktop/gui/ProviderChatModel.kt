package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.agent.LlmClient
import com.abrah.nightmare.agent.LlmError
import com.abrah.nightmare.agent.Provider
import org.json.JSONArray
import org.json.JSONObject

/** The two user-visible provider failures the chat bubble distinguishes. */
enum class ProviderIssue { UNCONFIGURED, UNREACHABLE, OTHER }

private const val UNCONFIGURED_MARKER = "尚未配置 provider"

private const val UNREACHABLE_MARKER = "连不上 provider"

/** Classified from the error sentence, so the state layer needs no UI types. */
fun providerIssueOf(message: String): ProviderIssue = when {
    message.contains(UNCONFIGURED_MARKER) -> ProviderIssue.UNCONFIGURED
    message.contains(UNREACHABLE_MARKER) -> ProviderIssue.UNREACHABLE
    else -> ProviderIssue.OTHER
}

/** Never echoes a key: the two known classes are fixed sentences, not provider text. */
fun providerIssueText(message: String): String = when (providerIssueOf(message)) {
    ProviderIssue.UNCONFIGURED ->
        "$UNCONFIGURED_MARKER、base URL 或密钥。点「打开设置」填写；本次没有发起网络请求。"

    ProviderIssue.UNREACHABLE ->
        "$UNREACHABLE_MARKER 或鉴权失败。配置未改动，不会自动重试；点「打开设置」检查 base URL、密钥与模型。"

    ProviderIssue.OTHER ->
        "对话未能完成。点「打开设置」检查 provider 配置。"
}

/**
 * Production ChatModel: resolves the settings on every turn so a dialog save
 * takes effect without rebuilding the agent, then hands off to the copied
 * :core [LlmClient]. A blank key or base URL fails locally — no network call.
 */
class ProviderChatModel(
    private val settings: ChatProviderSettings,
    private val clientFactory: (ChatProviderConfig) -> ChatModel = { config ->
        LlmClient(
            baseUrl = config.baseUrl,
            key = config.key,
            model = config.model,
            capReply = config.provider == Provider.OPENROUTER,
        )
    },
) : ChatModel {

    override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        val config = settings.resolved()
        if (config.key.isBlank() || config.baseUrl.isBlank()) {
            throw LlmError("$UNCONFIGURED_MARKER：请打开设置填写 provider、base URL 和密钥。")
        }
        val model = try {
            settings.autoModel()
        } catch (e: ProviderProbe.Unreachable) {
            throw LlmError("$UNREACHABLE_MARKER：${redact(e.message.orEmpty(), config.key)}")
        }
        return try {
            clientFactory(config.copy(model = model)).complete(messages, tools)
        } catch (e: LlmError) {
            throw LlmError("$UNREACHABLE_MARKER：${redact(e.message.orEmpty(), config.key)}")
        }
    }

    private fun redact(text: String, key: String): String =
        if (key.isBlank()) text else text.replace(key, "[redacted]")
}
