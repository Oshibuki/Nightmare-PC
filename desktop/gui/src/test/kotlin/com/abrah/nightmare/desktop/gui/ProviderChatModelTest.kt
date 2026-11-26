package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.ChatModel
import com.abrah.nightmare.agent.LlmError
import com.abrah.nightmare.agent.Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production wiring seam: the copied :core LlmClient is the real ChatModel, and
 * the two error classes are observable as bubble text. Loopback HTTP only.
 */
class ProviderChatModelTest {

    @Test(timeout = 10_000)
    fun `without a configured key the turn fails as unconfigured before any client is built`() {
        val settings = ChatProviderSettings(
            env = { emptyMap() },
            probe = { throw AssertionError("no probe without a key") },
        )
        val model = ProviderChatModel(settings) { throw AssertionError("no client without a key") }

        val error = assertLlmError { model.complete(JSONArray(), JSONArray()) }

        assertEquals(ProviderIssue.UNCONFIGURED, providerIssueOf(error.message.orEmpty()))
        assertTrue(providerIssueText(error.message.orEmpty()).contains("打开设置"))
    }

    @Test(timeout = 10_000)
    fun `a custom provider with no base url is also treated as unconfigured`() {
        val settings = ChatProviderSettings(
            env = { mapOf(ChatProviderSettings.ENV_PROVIDER to "CUSTOM", ChatProviderSettings.ENV_KEY to "secret-4321") },
            probe = { throw AssertionError("no probe without a base url") },
        )
        val model = ProviderChatModel(settings) { throw AssertionError("no client without a base url") }

        val error = assertLlmError { model.complete(JSONArray(), JSONArray()) }

        assertEquals(ProviderIssue.UNCONFIGURED, providerIssueOf(error.message.orEmpty()))
    }

    @Test(timeout = 10_000)
    fun `the copied LlmClient carries the key only in the authorization header`() {
        val env = mutableMapOf<String, String>()
        val (baseUrl, awaitRequest) = fakeHttpServer(
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "ok")))).toString(),
        )
        env[ChatProviderSettings.ENV_BASE_URL] = baseUrl
        env[ChatProviderSettings.ENV_KEY] = "completion-secret-4321"
        env[ChatProviderSettings.ENV_MODEL] = "typed-model"
        val model = ProviderChatModel(ChatProviderSettings(env = { env.toMap() }))

        val answer = model.complete(JSONArray(), JSONArray())

        assertEquals("ok", answer.optString("content"))
        val request = awaitRequest()
        assertTrue("path is chat completions", request.lines().first().contains("/chat/completions"))
        assertTrue("Authorization: Bearer completion-secret-4321" in request)
        assertFalse("the key is not in the request line", "completion-secret-4321" in request.lines().first())
    }

    @Test(timeout = 10_000)
    fun `a failed turn is a different error class with a settings exit and no key echo`() {
        val settings = ChatProviderSettings(
            env = {
                mapOf(
                    ChatProviderSettings.ENV_BASE_URL to "https://example.invalid/v1",
                    ChatProviderSettings.ENV_KEY to "secret-4321",
                    ChatProviderSettings.ENV_MODEL to "typed-model",
                )
            },
        )
        val model = ProviderChatModel(settings) { throw LlmError("could not reach host secret-4321") }

        val error = assertLlmError { model.complete(JSONArray(), JSONArray()) }

        assertEquals(ProviderIssue.UNREACHABLE, providerIssueOf(error.message.orEmpty()))
        assertFalse("the key is redacted in the bubble", "secret-4321" in providerIssueText(error.message.orEmpty()))
        assertTrue("打开设置" in providerIssueText(error.message.orEmpty()))
    }

    @Test(timeout = 10_000)
    fun `a save made after construction changes the model the next turn uses`() {
        val env = mutableMapOf(
            ChatProviderSettings.ENV_BASE_URL to "https://first.example/v1",
            ChatProviderSettings.ENV_KEY to "secret-4321",
            ChatProviderSettings.ENV_MODEL to "first-model",
        )
        val settings = ChatProviderSettings(env = { env.toMap() }, probe = { emptyList() }, runProbe = {})
        val seen = mutableListOf<ChatProviderConfig>()
        val model = ProviderChatModel(settings) { config ->
            seen += config
            ChatModel { _, _ -> JSONObject().put("content", "ok") }
        }

        model.complete(JSONArray(), JSONArray())
        settings.save(
            ProviderSettingsInput(Provider.GROQ, "https://second.example/v1", "secret-4321", "second-model"),
        )
        model.complete(JSONArray(), JSONArray())

        assertEquals("https://first.example/v1", seen[0].baseUrl)
        assertEquals("https://second.example/v1", seen[1].baseUrl)
        assertEquals("second-model", seen[1].model)
    }

    @Test(timeout = 10_000)
    fun `the default chat session reports the unconfigured error class`() {
        val chat = ChatSession(postToUi = { it.run() }, startTurn = { it.run() })

        assertTrue(chat.send("hello"))

        val error = chat.snapshot().filterIsInstance<ChatItem.Error>().single()
        assertEquals(ProviderIssue.UNCONFIGURED, providerIssueOf(error.text))
        assertTrue("打开设置" in providerIssueText(error.text))
    }

    private fun assertLlmError(block: () -> Unit): LlmError {
        try {
            block()
        } catch (e: LlmError) {
            return e
        }
        throw AssertionError("expected an LlmError")
    }
}
