package com.abrah.nightmare.desktop.gui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics tool is observed at the [ProviderDiagnostics] seam: the
 * [ProviderProbe.Transport] boundary is faked so no provider address is ever
 * contacted. These tests assert the report a user reads and the exit code a
 * shell sees, never the tool's internal structure.
 */
class ProviderDiagnosticsTest {

    @Test(timeout = 10_000)
    fun `with no provider configuration it skips the check without calling the network`() {
        var calls = 0
        val transport = ProviderProbe.Transport { _, _, _, _ ->
            calls++
            "{}"
        }

        val result = ProviderDiagnostics(transport = transport).run(emptyMap())

        assertEquals(0, result.exitCode)
        assertEquals(0, calls) // missing config must never reach the network
        assertTrue(result.report.contains("SKIP"))
        assertTrue(result.report.contains(ChatProviderSettings.ENV_PROVIDER))
        assertTrue(result.report.contains(ChatProviderSettings.ENV_BASE_URL))
        assertTrue(result.report.contains(ChatProviderSettings.ENV_KEY))
        assertTrue(result.report.contains(ChatProviderSettings.ENV_MODEL))
        assertTrue(result.report.contains("unset"))
        assertTrue(result.report.contains("如何配置"))
    }

    @Test(timeout = 10_000)
    fun `a fully configured provider reports each setting and its chat models without echoing the key`() {
        val key = "sk-secret-9876"
        val env = mapOf(
            ChatProviderSettings.ENV_PROVIDER to "OPENROUTER",
            ChatProviderSettings.ENV_KEY to key,
            ChatProviderSettings.ENV_MODEL to "an/old-model",
        )
        val chatModel = JSONObject()
            .put("id", "a-chat-model")
            .put("context_length", 65_536)
            .put("supported_parameters", JSONArray().put("tools"))
            .put("architecture", JSONObject().put("output_modalities", JSONArray().put("text")))
            .put("pricing", JSONObject().put("prompt", "0").put("completion", "0").put("request", "0"))
        val imageModel = JSONObject()
            .put("id", "image-only-model")
            .put("context_length", 65_536)
            .put("supported_parameters", JSONArray())
            .put("architecture", JSONObject().put("output_modalities", JSONArray().put("image")))
        var calls = 0
        val transport = ProviderProbe.Transport { _, _, connect, read ->
            calls++
            assertEquals(ProviderProbe.CONNECT_TIMEOUT_MS, connect)
            assertEquals(ProviderProbe.READ_TIMEOUT_MS, read)
            JSONObject().put("data", JSONArray().put(imageModel).put(chatModel)).toString()
        }

        val result = ProviderDiagnostics(
            transport = transport,
            today = { "2026-11-26" },
        ).run(env)

        assertEquals(0, result.exitCode)
        assertEquals(1, calls)
        assertTrue(result.report.contains("${ChatProviderSettings.ENV_PROVIDER}: SET"))
        assertTrue(result.report.contains("${ChatProviderSettings.ENV_KEY}: SET"))
        assertTrue(result.report.contains("密钥已配置"))
        assertTrue(result.report.contains("${ChatProviderSettings.ENV_MODEL}: SET"))
        assertTrue(result.report.contains("${ChatProviderSettings.ENV_BASE_URL}: unset"))
        assertFalse(result.report.contains("SKIP"))
        assertTrue(result.report.contains("已连接"))
        assertTrue(result.report.contains("a-chat-model"))
        assertFalse(result.report.contains("image-only-model"))
        assertFalse(result.report.contains(key))
        assertFalse(result.report.contains("9876"))
    }

    @Test(timeout = 10_000)
    fun `an unreachable provider reports a redacted reason and a non-zero exit without retrying`() {
        val key = "secret-redact-me-5555"
        val env = mapOf(
            ChatProviderSettings.ENV_PROVIDER to "OPENROUTER",
            ChatProviderSettings.ENV_KEY to key,
        )
        var calls = 0
        val transport = ProviderProbe.Transport { _, _, _, _ ->
            calls++
            throw ProviderProbe.Unreachable("refused: $key, tail 5555")
        }

        val result = ProviderDiagnostics(transport = transport, today = { "2026-11-26" }).run(env)

        assertEquals(1, result.exitCode)
        assertEquals(1, calls) // one probe, no retry
        assertTrue(result.report.contains("未连接"))
        assertFalse(result.report.contains(key))
        assertFalse(result.report.contains("5555"))
    }

    @Test(timeout = 10_000)
    fun `a keyless CUSTOM server with a base URL is checked while one without a base URL is skipped`() {
        val baseUrl = "http://127.0.0.1:13305/v1"
        var calls = 0
        val okTransport = ProviderProbe.Transport { _, _, _, _ ->
            calls++
            JSONObject().put("data", JSONArray()).toString()
        }
        val withUrl = ProviderDiagnostics(transport = okTransport, today = { "2026-11-26" }).run(
            mapOf(
                ChatProviderSettings.ENV_PROVIDER to "CUSTOM",
                ChatProviderSettings.ENV_BASE_URL to baseUrl,
            ),
        )
        assertEquals(0, withUrl.exitCode)
        assertEquals(1, calls) // a local server needs no key
        assertFalse(withUrl.report.contains("SKIP"))
        assertTrue(withUrl.report.contains("已连接"))

        val withoutUrl = ProviderDiagnostics(transport = okTransport, today = { "2026-11-26" }).run(
            mapOf(ChatProviderSettings.ENV_PROVIDER to "CUSTOM"),
        )
        assertEquals(0, withoutUrl.exitCode)
        assertEquals(1, calls) // no new probe when the base URL is missing
        assertTrue(withoutUrl.report.contains("SKIP"))
        assertTrue(withoutUrl.report.contains(ChatProviderSettings.ENV_BASE_URL))
    }
}
