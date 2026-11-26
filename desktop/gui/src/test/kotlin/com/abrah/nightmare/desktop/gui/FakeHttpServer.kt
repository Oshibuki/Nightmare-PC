package com.abrah.nightmare.desktop.gui

import org.junit.Assert.assertTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Loopback-only HTTP stub: no provider address is ever contacted by GUI tests. */
internal fun fakeHttpServer(response: String): Pair<String, () -> String> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    server.soTimeout = 8_000
    val done = CountDownLatch(1)
    val captured = AtomicReference("")
    Thread {
        try {
            val socket = server.accept()
            socket.soTimeout = 8_000
            val reader = socket.getInputStream().bufferedReader()
            val lines = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                lines += line
                line = reader.readLine()
            }
            val length = response.toByteArray(Charsets.UTF_8).size
            captured.set(lines.joinToString("\n"))
            val bytes = response.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().use { out ->
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: $length\r\nConnection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8),
                )
                out.write(bytes)
                out.flush()
            }
            socket.close()
        } catch (t: Throwable) {
            captured.set("fake server error: ${t.javaClass.simpleName}")
        } finally {
            done.countDown()
        }
    }.apply { isDaemon = true }.start()
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    return baseUrl to {
        assertTrue("loopback request was received", done.await(8, TimeUnit.SECONDS))
        server.close()
        captured.get()
    }
}
