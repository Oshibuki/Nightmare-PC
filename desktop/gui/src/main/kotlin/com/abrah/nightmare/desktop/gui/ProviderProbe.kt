package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.LlmClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The save-time connectivity check. Deliberately separate from
 * `LlmClient.models()` (20 s connect / 180 s read): this one is 3 s / 8 s and
 * never blocks saving. It reuses the copied core's pure [LlmClient.fitsChat]
 * rule to choose a chat-capable model, without touching the core's timeouts.
 */
class ProviderProbe(
    private val transport: Transport = HttpTransport,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    /** A one-shot GET. [key] is only ever a header, never part of [url]. */
    fun interface Transport {
        fun get(url: String, key: String, connectTimeoutMs: Int, readTimeoutMs: Int): String
    }

    class Unreachable(message: String) : Exception(message)

    /** Chat-capable model ids, sorted; an unreachable provider throws [Unreachable]. */
    fun models(config: ChatProviderConfig): List<String> {
        val body = try {
            transport.get(
                config.baseUrl.trimEnd('/') + "/models",
                config.key,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
            )
        } catch (e: Unreachable) {
            // The transport's own message must not escape with a key in it.
            throw Unreachable(redactSecret(e.message.orEmpty(), config.key))
        }
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { LlmClient.fitsChat(it, null, today()) }
            .mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }
            .sorted()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 8_000

        /** The only production transport: loopback tests inject their own. */
        object HttpTransport : Transport {
            override fun get(url: String, key: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
                val connection = try {
                    URL(url).openConnection() as HttpURLConnection
                } catch (e: Exception) {
                    throw Unreachable("bad provider address")
                }
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = connectTimeoutMs
                    connection.readTimeout = readTimeoutMs
                    connection.setRequestProperty("Accept", "application/json")
                    if (key.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $key")
                    }
                    val code = connection.responseCode
                    val text = (if (code < 400) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    if (code >= 400) throw Unreachable("provider answered HTTP $code")
                    return text
                } catch (e: IOException) {
                    throw Unreachable(redactSecret("could not reach provider: ${e.message}", key))
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
