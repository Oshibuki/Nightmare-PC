package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The save-time check is observed at the probe seam: a recording transport for
 * timeouts and model filtering, plus a loopback-only HTTP server for the real
 * header discipline. No provider address is ever contacted.
 */
class ProviderProbeTest {

    private val chatModel = JSONObject()
        .put("id", "chat-capable")
        .put("context_length", 65_536)
        .put("supported_parameters", org.json.JSONArray().put("tools"))
        .put("architecture", JSONObject().put("output_modalities", org.json.JSONArray().put("text")))
        .put("pricing", JSONObject().put("prompt", "0").put("completion", "0").put("request", "0"))

    private val notChatModel = JSONObject()
        .put("id", "image-only")
        .put("context_length", 65_536)
        .put("supported_parameters", org.json.JSONArray())
        .put("architecture", JSONObject().put("output_modalities", org.json.JSONArray().put("image")))

    private data class Call(val url: String, val key: String, val connect: Int, val read: Int)

    @Test(timeout = 10_000)
    fun `probe uses a three second connect eight second read and filters models through fitsChat`() {
        val calls = mutableListOf<Call>()
        val transport = ProviderProbe.Transport { url, key, connectMs, readMs ->
            calls += Call(url, key, connectMs, readMs)
            JSONObject().put(
                "data",
                org.json.JSONArray().put(notChatModel).put(chatModel),
            ).toString()
        }
        val probe = ProviderProbe(transport = transport, today = { "2026-11-26" })

        val ids = probe.models(
            ChatProviderConfig(Provider.OPENROUTER, "https://example.invalid/v1/", "secret-4321", ""),
        )

        assertEquals(listOf("chat-capable"), ids)
        assertEquals(1, calls.size)
        assertEquals("https://example.invalid/v1/models", calls.single().url)
        assertEquals(3_000, calls.single().connect)
        assertEquals(8_000, calls.single().read)
        assertEquals("secret-4321", calls.single().key)
    }

    @Test(timeout = 10_000)
    fun `a failed probe is reported without echoing the key`() {
        val probe = ProviderProbe(
            transport = { _, key, _, _ -> throw ProviderProbe.Unreachable("refused: $key") },
            today = { "2026-11-26" },
        )

        val message = runCatching {
            probe.models(ChatProviderConfig(Provider.CUSTOM, "https://example.invalid/v1", "secret-4321", ""))
        }.exceptionOrNull()?.message.orEmpty()

        assertFalse(message.contains("secret-4321"))
        assertTrue(message.isNotBlank())
    }

    @Test(timeout = 10_000)
    fun `a save never waits for the probe and keeps the entry when the probe fails`() {
        val queued = mutableListOf<Runnable>()
        val settings = ChatProviderSettings(
            env = { emptyMap() },
            probe = { throw ProviderProbe.Unreachable("offline") },
            runProbe = { queued += it },
        )

        val saved = settings.save(
            ProviderSettingsInput(Provider.CUSTOM, "https://local.example/v1", "secret-4321", ""),
        )

        assertEquals(Provider.CUSTOM, saved.provider)
        assertEquals("https://local.example/v1", saved.baseUrl)
        assertEquals(saved, settings.resolved())
        assertEquals(1, queued.size) // the check itself did not run inside save
        queued.removeAt(0).run()
        assertEquals(saved, settings.resolved()) // failure does not roll the save back
        assertFalse("offline" in settings.lastProbeStatus() && "secret-4321" in settings.lastProbeStatus())
    }

    @Test(timeout = 10_000)
    fun `the status shown after a failed probe carries no key or last four digits`() {
        val queued = mutableListOf<Runnable>()
        val settings = ChatProviderSettings(
            env = { emptyMap() },
            probe = { throw ProviderProbe.Unreachable("refused secret-4321") },
            runProbe = { queued += it },
        )

        settings.save(ProviderSettingsInput(Provider.OPENROUTER, "", "secret-4321", "typed-model"))
        queued.removeAt(0).run()

        assertEquals("已配置", settings.statusLabel())
        assertFalse("secret-4321" in settings.lastProbeStatus())
        assertFalse("4321" in settings.lastProbeStatus())
    }

    @Test(timeout = 10_000)
    fun `a successful probe supplies the default model when the model field was left blank`() {
        val queued = mutableListOf<Runnable>()
        val settings = ChatProviderSettings(
            env = { mapOf(ChatProviderSettings.ENV_MODEL to "env-model") },
            probe = { listOf("picked-by-fits-chat") },
            runProbe = { queued += it },
        )

        settings.save(
            ProviderSettingsInput(Provider.OPENROUTER, "", "secret-4321", ""),
        )
        queued.removeAt(0).run()

        assertEquals("picked-by-fits-chat", settings.resolved().model)
    }

    @Test(timeout = 10_000)
    fun `a manual model entry wins over the probe default`() {
        val queued = mutableListOf<Runnable>()
        val settings = ChatProviderSettings(
            env = { emptyMap() },
            probe = { listOf("picked-by-fits-chat") },
            runProbe = { queued += it },
        )

        settings.save(
            ProviderSettingsInput(Provider.OPENROUTER, "", "secret-4321", "typed-model"),
        )
        queued.removeAt(0).run()

        assertEquals("typed-model", settings.resolved().model)
    }

    @Test(timeout = 10_000)
    fun `the real transport sends the key only in the authorization header`() {
        val (baseUrl, awaitRequest) = fakeHttpServer(JSONObject().put("data", org.json.JSONArray()).toString())
        val probe = ProviderProbe()

        probe.models(ChatProviderConfig(Provider.CUSTOM, baseUrl, "header-secret-4321", ""))
        val request = awaitRequest()

        assertTrue("Authorization: Bearer header-secret-4321" in request)
        assertFalse("the key is never in the path", "header-secret-4321" in request.substringBefore("\n"))
    }

    @Test(timeout = 10_000)
    fun `the real transport sends no authorization header for a blank key`() {
        val (baseUrl, awaitRequest) = fakeHttpServer(JSONObject().put("data", org.json.JSONArray()).toString())
        val probe = ProviderProbe()

        probe.models(ChatProviderConfig(Provider.CUSTOM, baseUrl, "", ""))
        val request = awaitRequest()

        assertFalse("authorization" in request.lowercase())
    }
}
