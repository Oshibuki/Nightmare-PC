package com.abrah.nightmare.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/** JVM migration of the server half of the mobile `ApiTest` (no Robolectric: `org.json` is a JVM artifact here). */
class MiniHttpTest {

    private fun <T> serving(handler: (MiniHttp.Request, MiniHttp.Response) -> Unit, body: (Int) -> T): T {
        val s = MiniHttp(0, handler)
        s.start()
        try {
            return body(s.boundPort)
        } finally {
            s.stop()
        }
    }

    private fun call(port: Int, path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test(timeout = 10_000)
    fun aRequestReachesTheHandlerWithItsPathQueryAndBody() {
        serving({ req, res ->
            res.json(200, """{"m":"${req.method}","p":"${req.path}","q":"${req.query["a"]}","b":"${req.text()}"}""")
        }) { port ->
            val (code, text) = call(port, "/run?a=x%20y", "POST", "hello")
            assertEquals(200, code)
            assertEquals("""{"m":"POST","p":"/run","q":"x y","b":"hello"}""", text)
        }
    }

    @Test(timeout = 10_000)
    fun aHandlerThatThrowsIsA500NotAHang() {
        serving({ _, _ -> throw IllegalStateException("boom") }) { port ->
            val (code, text) = call(port, "/x")
            assertEquals(500, code)
            assertTrue(text.contains("boom"))
        }
    }

    @Test(timeout = 10_000)
    fun eventsStreamInOrder() {
        serving({ _, res ->
            val sse = res.sse()
            sse.event("node", "{\"node\":\"a\"}")
            sse.event("done", "{}")
        }) { port ->
            val (_, text) = call(port, "/run", "POST", "{}")
            assertEquals("event: node\ndata: {\"node\":\"a\"}\n\nevent: done\ndata: {}\n\n", text)
        }
    }

    @Test(timeout = 10_000)
    fun onlyLoopbackPeersMayConnect() {
        fun loopback(a: String) = MiniHttp.isLoopbackPeer(InetAddress.getByName(a))
        assertTrue(loopback("127.0.0.1"))
        assertTrue(loopback("::1"))
        // ⚠ The mobile allow-list also admitted LAN and tailnet peers; the desktop service never does.
        assertFalse(loopback("192.168.1.20"))
        assertFalse(loopback("10.0.0.5"))
        assertFalse(loopback("100.101.102.103"))
        assertFalse(loopback("8.8.8.8"))
        assertFalse(loopback("2001:4860:4860::8888"))
    }

    /** ⭐ Acceptance: the listening socket is on 127.0.0.1, so a LAN interface cannot reach it at all. */
    @Test(timeout = 10_000)
    fun theServerIsNotReachableThroughANonLoopbackAddress() {
        val lan = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
        assumeTrue("no non-loopback IPv4 interface on this host", lan != null)
        serving({ _, res -> res.json(200, "{}") }) { port ->
            Socket().use { sock ->
                try {
                    sock.connect(InetSocketAddress(lan!!, port), 1_000)
                    throw AssertionError("a LAN client reached the API on port $port")
                } catch (e: ConnectException) {
                    // expected: the socket is bound to the loopback address only
                }
            }
        }
    }

    /** ⭐ A body larger than OPEN_BODY is rejected on the headers, before the bytes are read. */
    @Test(timeout = 10_000)
    fun anUnadmittedBodyIsRefusedBeforeItIsRead() {
        val s = MiniHttp(0, { _, res -> res.json(200, "{}") }, admit = { false })
        s.start()
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", s.boundPort), 2_000)
                sock.soTimeout = 2_000
                sock.getOutputStream().write(
                    "POST /run HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\n".toByteArray(),
                )
                sock.getOutputStream().flush()
                val answer = sock.getInputStream().bufferedReader().useLines { it.first() }
                assertTrue(answer.startsWith("HTTP/1.1 401"))
            }
        } finally {
            s.stop()
        }
    }
}
