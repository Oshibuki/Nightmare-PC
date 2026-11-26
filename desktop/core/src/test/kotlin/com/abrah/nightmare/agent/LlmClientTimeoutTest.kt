package com.abrah.nightmare.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The copied core client's timeout contract, observed at the network seam: no
 * real provider is contacted, and the values are read back from the connection
 * rather than inferred from wall-clock timing.
 */
class LlmClientTimeoutTest {

    private data class Call(val method: String, val connectMs: Int, val readMs: Int)

    private class FakeConnection(
        private val bodyFor: (String) -> String,
        private val record: (Call) -> Unit,
    ) : HttpURLConnection(URL("http://localhost")) {
        override fun setReadTimeout(timeoutMs: Int) {
            super.setReadTimeout(timeoutMs)
            record(Call(requestMethod, connectTimeout, readTimeout))
        }

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = bodyFor(requestMethod).byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }

    private fun emptyModels() = """{"data":[]}"""

    @Test(timeout = 10_000)
    fun `models uses a three second connect and eight second read so a bad base URL reports quickly`() {
        val calls = mutableListOf<Call>()
        val client = LlmClient(
            "http://wrong.example/v1", "", "model",
            openConnection = { FakeConnection({ emptyModels() }) { calls += it } },
        )

        assertEquals(emptyList<String>(), client.models())

        assertEquals(1, calls.size)
        assertEquals("GET", calls.single().method)
        assertEquals(3_000, calls.single().connectMs)
        assertEquals(8_000, calls.single().readMs)
    }

    @Test(timeout = 10_000)
    fun `models timeouts can be injected for future adjustment`() {
        val calls = mutableListOf<Call>()
        val client = LlmClient(
            "http://localhost/v1", "", "model",
            modelsConnectTimeoutMs = 1_234,
            modelsReadTimeoutMs = 5_678,
            openConnection = { FakeConnection({ emptyModels() }) { calls += it } },
        )

        client.models()

        assertEquals(1_234, calls.single().connectMs)
        assertEquals(5_678, calls.single().readMs)
    }

    @Test(timeout = 10_000)
    fun `complete keeps the long inference timeouts and ignores the models ones`() {
        val calls = mutableListOf<Call>()
        val answer = """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
        val client = LlmClient(
            "http://localhost/v1", "", "model",
            modelsConnectTimeoutMs = 1,
            modelsReadTimeoutMs = 1,
            openConnection = { FakeConnection({ answer }) { calls += it } },
        )

        val message = client.complete(JSONArray(), JSONArray())

        assertEquals("hi", message.getString("content"))
        assertEquals("POST", calls.single().method)
        assertEquals(20_000, calls.single().connectMs)
        assertEquals(180_000, calls.single().readMs)
    }

    @Test(timeout = 10_000)
    fun `the chat only budget probe keeps the long inference timeouts`() {
        val calls = mutableListOf<Call>()
        val models = """{"data":[{"id":"paid","pricing":{"completion":"0.01"},"top_provider":{"max_completion_tokens":40000}}]}"""
        val client = LlmClient(
            "http://localhost/v1", "", "model",
            modelsConnectTimeoutMs = 1,
            modelsReadTimeoutMs = 1,
            openConnection = {
                FakeConnection({ verb -> if (verb == "GET") models else """{"choices":[]}""" }) { calls += it }
            },
        )

        client.models(chatOnly = true)

        assertEquals(2, calls.size)
        assertEquals("GET", calls[0].method)
        assertEquals(1, calls[0].connectMs)
        assertEquals(1, calls[0].readMs)
        assertEquals("POST", calls[1].method)
        assertEquals(20_000, calls[1].connectMs)
        assertEquals(180_000, calls[1].readMs)
    }
}
