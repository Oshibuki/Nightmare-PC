package com.abrah.nightmare.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** JVM migration of the two CORS tests in the mobile `ApiTest`, over a real loopback socket. */
class ApiCorsTest {

    private fun <T> serving(cors: Boolean, body: (Int) -> T): T {
        val handler: (MiniHttp.Request, MiniHttp.Response) -> Unit = { req, res ->
            if (!corsPreflight(req, res, cors)) {
                if (req.headers["authorization"] == "Bearer t") res.json(200, "{}")
                else res.json(401, MiniHttp.err("a token is needed"))
            }
        }
        val s = MiniHttp(0, handler)
        s.start()
        try {
            return body(s.boundPort)
        } finally {
            s.stop()
        }
    }

    private fun preflight(port: Int, auth: String? = null, method: String = "OPTIONS"): HttpURLConnection =
        (URL("http://127.0.0.1:$port/run").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Origin", "http://example.com")
            setRequestProperty("Access-Control-Request-Method", "POST")
            if (auth != null) setRequestProperty("Authorization", auth)
        }

    @Test(timeout = 10_000)
    fun withBrowserPagesAllowedAPreflightIsAnsweredBeforeTheTokenAndEveryAnswerCarriesTheHeaders() {
        serving(cors = true) { port ->
            val pre = preflight(port)
            assertEquals(204, pre.responseCode)
            assertEquals("*", pre.getHeaderField("Access-Control-Allow-Origin"))
            assertTrue(pre.getHeaderField("Access-Control-Allow-Headers").contains("Authorization"))
            // ⭐ The token is still the gate: a real call without it is refused, with the headers so the page can read why.
            val bare = preflight(port, method = "GET")
            assertEquals(401, bare.responseCode)
            assertEquals("*", bare.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals(200, preflight(port, auth = "Bearer t", method = "GET").responseCode)
        }
    }

    @Test(timeout = 10_000)
    fun withBrowserPagesOffNothingChanges() {
        // ⚠ The control: off (the default), a preflight meets the token check and no answer has the headers.
        serving(cors = false) { port ->
            val pre = preflight(port)
            assertEquals(401, pre.responseCode)
            assertEquals(null, pre.getHeaderField("Access-Control-Allow-Origin"))
        }
    }
}
