package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Seam: [Cli.run] plus a real HTTP request against the child "engine".
 *
 * The engine here is a bounded Python HTTP server: it listens on the address
 * the CLI gives it, answers one `/sdapi/v1/txt2img` POST with a base64 PNG,
 * and exits. Nothing in this test knows how `serve` is implemented; the
 * assertions are the caller's observations -- exit code, stderr, the engine's
 * command line, the HTTP response and the PNG decoded from it.
 *
 * Every wait has a deadline: `@Test(timeout=...)`, socket connect/read
 * timeouts, a bounded ready-file wait and a one-request server with its own
 * timeout. A missing request fails the test instead of hanging it.
 */
class ServeTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var argvFile: File
    private lateinit var readyFile: File
    private var port: Int = 0
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nm-serve-test").toFile()
        model = File(dir, "model.safetensors").apply { writeBytes(safetensorsHeader()) }
        argvFile = File(dir, "argv.txt")
        readyFile = File(dir, "ready")
        port = ServerSocket(0).use { it.localPort }
        executor = Executors.newSingleThreadExecutor { Thread(it, "nm-serve-under-test") }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun safetensorsHeader(): ByteArray {
        val json = """{"w":{"dtype":"F16","shape":[2],"data_offsets":[0,4]}}"""
        val header = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + header.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong())
            .put(header)
            .array()
    }

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    /** A one-shot HTTP server masquerading as the engine's `sd-server`. */
    private fun fakeServer(png: File): File {
        val script = File(dir, "sd-server")
        script.writeText(
            """
            |#!/usr/bin/env python3
            |import base64, json, sys
            |from http.server import BaseHTTPRequestHandler, HTTPServer
            |
            |PNG = r'${png.absolutePath}'
            |ARGV = r'${argvFile.absolutePath}'
            |READY = r'${readyFile.absolutePath}'
            |with open(ARGV, 'w') as f:
            |    f.write('\n'.join(sys.argv[1:]) + '\n')
            |args = sys.argv[1:]
            |ip = args[args.index('--listen-ip') + 1]
            |port = int(args[args.index('--listen-port') + 1])
            |with open(PNG, 'rb') as f:
            |    image = base64.b64encode(f.read()).decode('ascii')
            |
            |class Handler(BaseHTTPRequestHandler):
            |    def do_POST(self):
            |        length = int(self.headers.get('Content-Length', '0'))
            |        request = json.loads(self.rfile.read(length) or b'{}')
            |        body = json.dumps({'images': [image], 'parameters': request, 'info': ''}).encode()
            |        self.send_response(200)
            |        self.send_header('Content-Type', 'application/json')
            |        self.send_header('Content-Length', str(len(body)))
            |        self.end_headers()
            |        self.wfile.write(body)
            |    def log_message(self, *unused):
            |        pass
            |
            |server = HTTPServer((ip, port), Handler)
            |server.timeout = 12
            |open(READY, 'w').write('ready')
            |print('fake sd-server: ready', flush=True)
            |server.handle_request()
            |server.server_close()
            """.trimMargin() + "\n",
        )
        assertTrue("fake sd-server must be executable", script.setExecutable(true))
        return script
    }

    private fun serve(engine: File, vararg extra: String): Future<Int> {
        val args = listOf(
            "serve",
            "--model", model.absolutePath,
            "--port", port.toString(),
            "--engine", engine.absolutePath,
        ) + extra
        return executor.submit(Callable { Cli.run(args, out, err) })
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!readyFile.isFile && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("the server never bound its port; stdout=$out stderr=$err", readyFile.isFile)
    }

    private fun postTxt2Img(): String {
        val connection = (URL("http://127.0.0.1:$port/sdapi/v1/txt2img").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        connection.outputStream.use {
            it.write("""{"prompt":"a red fox","steps":4,"width":64,"height":64}""".toByteArray(Charsets.UTF_8))
        }
        val code = connection.responseCode
        assertEquals("HTTP response: ${connection.errorStream?.readBytes()}", 200, code)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    @Test(timeout = 30000)
    fun helpDocumentsServeAsAnExplicitLoopbackOnlyOptIn() {
        assertEquals(0, run("--help"))
        val text = out.toString()
        assertTrue("help should name the serve command: $text", text.contains("serve"))
        assertTrue("help should name the A1111 API: $text", text.contains("A1111"))
        assertTrue("help should document --port: $text", text.contains("--port"))
        assertTrue("help should promise a loopback bind: $text", text.contains("127.0.0.1"))
        assertTrue("help should say the service is opt-in: $text", text.contains("only when"))
    }

    @Test(timeout = 30000)
    fun noServerStartsWithoutTheServeCommand() {
        val engine = fakeServer(TestPng.file(dir, 64, 64))

        val code = run("--engine", engine.absolutePath, "--model", model.absolutePath)

        assertEquals(2, code)
        assertTrue("a bare flag must not start the service: $err", err.contains("--engine"))
        assertFalse("the engine must not run: ${dir.list()?.toList()}", argvFile.isFile())
    }

    @Test(timeout = 30000)
    fun serveRefusesANonLoopbackBindAttemptBeforeStartingTheEngine() {
        val engine = fakeServer(TestPng.file(dir, 64, 64))

        val code = run(
            "serve", "--model", model.absolutePath, "--port", port.toString(),
            "--engine", engine.absolutePath, "--listen-ip", "0.0.0.0",
        )

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr must name the rejected flag: $err", err.contains("--listen-ip"))
        assertFalse("the engine must never see a non-loopback bind: $argvFile", argvFile.isFile())
    }

    @Test(timeout = 30000)
    fun serveRejectsPortsOutsideTheTcpRange() {
        val engine = fakeServer(TestPng.file(dir, 64, 64))
        for (bad in listOf("0", "65536", "port")) {
            out.setLength(0); err.setLength(0)
            val code = run(
                "serve", "--model", model.absolutePath, "--port", bad,
                "--engine", engine.absolutePath,
            )
            assertEquals("$bad should be a usage error, stderr: $err", 2, code)
            assertTrue("$bad must name --port on stderr: $err", err.contains("--port"))
        }
        assertFalse("no engine may run on a bad command line: $argvFile", argvFile.isFile())
    }

    @Test(timeout = 30000)
    fun aLocalRequestGetsAnImageAndTheEngineIsToldToBindLoopback() {
        val png = TestPng.file(dir, 64, 64, "engine.png")
        val engine = fakeServer(png)

        val served = serve(engine)
        try {
            awaitReady()
            val response = postTxt2Img()

            assertTrue("the endpoint must echo the request: $response", response.contains("a red fox"))
            val encoded = Regex("\"images\"\\s*:\\s*\\[\\s*\"([A-Za-z0-9+/=]+)\"")
                .find(response)?.groupValues?.get(1)
                ?: throw AssertionError("no base64 image in the response: $response")
            val received = File(dir, "from-api.png").apply { writeBytes(Base64.getDecoder().decode(encoded)) }
            val chunks = TestPng.chunks(received)
            assertEquals("the decoded image must end at IEND", "IEND", chunks.last().first)
            val ihdr = chunks.first { it.first == "IHDR" }.second
            assertEquals(64, ByteBuffer.wrap(ihdr, 0, 4).int)
            assertEquals(64, ByteBuffer.wrap(ihdr, 4, 4).int)

            val argv = argvFile.readLines()
            assertTrue("engine should get the model: $argv", argv.contains(model.absolutePath))
            assertTrue("engine must bind loopback: $argv", argv.contains("--listen-ip"))
            assertTrue(
                "engine must bind 127.0.0.1, got $argv",
                argv.indexOf("--listen-ip").let { it >= 0 && argv[it + 1] == "127.0.0.1" },
            )
            assertTrue(
                "engine must use the requested port, got $argv",
                argv.indexOf("--listen-port").let { it >= 0 && argv[it + 1] == port.toString() },
            )
            assertEquals("engine output must be forwarded: $out", 0, served.get(20, TimeUnit.SECONDS))
            assertTrue("the engine's own log line must reach stdout: $out", out.contains("fake sd-server: ready"))
        } finally {
            served.get(20, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 30000)
    fun aMissingServerBinaryFailsWithoutStartingAnything() {
        val missing = File(dir, "no-sd-server-here")

        val code = run("serve", "--model", model.absolutePath, "--port", port.toString(), "--engine", missing.path)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the missing binary: $err", err.contains(missing.path))
        assertTrue("stderr must point at the build script: $err", err.contains("build.sh"))
        assertTrue("a failure must not print a success line to stdout: $out", out.isEmpty())
    }

    @Test(timeout = 30000)
    fun aNonZeroServerExitFailsTheCommandAndSaysWhy() {
        val script = File(dir, "failing-sd-server")
        script.writeText("#!/bin/sh\necho 'bind failed' >&2\nexit 7\n")
        assertTrue(script.setExecutable(true))

        val code = run("serve", "--model", model.absolutePath, "--port", port.toString(), "--engine", script.path)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must carry the engine's exit code: $err", err.contains("7"))
        assertTrue("the engine's output must still be forwarded: $out", out.contains("bind failed"))
    }
}
