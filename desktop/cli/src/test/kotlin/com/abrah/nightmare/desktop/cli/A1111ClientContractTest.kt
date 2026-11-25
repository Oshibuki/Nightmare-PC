package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
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
 * Contract probe for the A1111 subset reachable through `nightmare-desktop serve`.
 *
 * No unmodified third-party A1111 client is installed on the reference machine
 * (see desktop/docs/a1111-client-compat.md), so this is a **contract** test, not
 * a claim of full client certification. The fake engine below speaks the part
 * of `/sdapi/v1/txt2img` that the vendored `sd-server` documents; the test only
 * observes what a client can see: HTTP status, JSON fields, a decodable PNG and
 * the loopback-only engine arguments.
 *
 * Every wait is bounded: JUnit 30 s, socket 5 s, ready-file 10 s, fake server
 * 12 s, future 20 s. A missing request fails instead of hanging the suite.
 */
class A1111ClientContractTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var argvFile: File
    private lateinit var requestFile: File
    private lateinit var readyFile: File
    private var port: Int = 0
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nm-a1111-contract").toFile()
        val header = """{"w":{"dtype":"F16","shape":[2],"data_offsets":[0,4]}}"""
            .toByteArray(Charsets.UTF_8)
        model = File(dir, "model.safetensors").apply {
            writeBytes(ByteBuffer.allocate(8 + header.size).putLong(header.size.toLong()).put(header).array())
        }
        argvFile = File(dir, "argv.txt")
        requestFile = File(dir, "request.json")
        readyFile = File(dir, "ready")
        port = ServerSocket(0).use { it.localPort }
        executor = Executors.newSingleThreadExecutor { Thread(it, "nm-a1111-contract") }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    /** A one-shot stand-in for the engine's `sd-server`, with no A1111 code in it. */
    private fun fakeSdServer(png: File): File {
        val script = File(dir, "sd-server")
        script.writeText(
            """
            |#!/usr/bin/env python3
            |import base64, json, sys
            |from http.server import BaseHTTPRequestHandler, HTTPServer
            |
            |PNG = r'${png.absolutePath}'
            |ARGV = r'${argvFile.absolutePath}'
            |REQUEST = r'${requestFile.absolutePath}'
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
            |        if self.path != '/sdapi/v1/txt2img':
            |            self.send_error(404)
            |            return
            |        length = int(self.headers.get('Content-Length', '0'))
            |        payload = json.loads(self.rfile.read(length) or b'{}')
            |        with open(REQUEST, 'w') as f:
            |            json.dump(payload, f)
            |        # Match routes_sdapi.cpp: parameters echoes the request, and
            |        # info is deliberately a JSON *string* for A1111 clients.
            |        info = json.dumps({
            |            'prompt': payload.get('prompt', ''),
            |            'negative_prompt': payload.get('negative_prompt', ''),
            |            'seed': payload.get('seed', 0),
            |            'steps': payload.get('steps', 0),
            |            'cfg_scale': payload.get('cfg_scale', 0),
            |            'width': payload.get('width', 0),
            |            'height': payload.get('height', 0),
            |            'sampler_name': payload.get('sampler_name', ''),
            |            'version': 'stable-diffusion.cpp',
            |        })
            |        body = json.dumps({'images': [image], 'parameters': payload, 'info': info}).encode()
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

    private fun serve(engine: File): Future<Int> = executor.submit(
        Callable {
            Cli.run(
                listOf(
                    "serve", "--model", model.absolutePath,
                    "--port", port.toString(), "--engine", engine.absolutePath,
                ),
                out, err,
            )
        },
    )

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!readyFile.isFile && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("the server never bound; stdout=$out stderr=$err", readyFile.isFile)
    }

    /** The body a stock A1111 API client would send for a tiny txt2img request. */
    private fun postA1111Request(): String {
        val payload = """{
            "prompt":"a red fox, photograph",
            "negative_prompt":"blurry",
            "steps":1,
            "cfg_scale":1.5,
            "width":64,
            "height":64,
            "seed":1234,
            "sampler_name":"euler",
            "scheduler":"discrete",
            "batch_size":1,
            "n_iter":1
        }""".trimIndent()
        val connection = (URL("http://127.0.0.1:$port/sdapi/v1/txt2img").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        assertEquals("HTTP response: ${connection.errorStream?.readBytes()}", 200, code)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    @Test(timeout = 30000)
    fun anA1111StylePostYieldsParametersInfoAndADecodablePng() {
        val engine = fakeSdServer(TestPng.file(dir, 64, 64, "contract.png"))
        val served = serve(engine)
        try {
            awaitReady()
            val response = postA1111Request()

            assertTrue("an A1111 client expects images: $response", response.contains("\"images\""))
            assertTrue("an A1111 client expects parameters: $response", response.contains("\"parameters\""))
            assertTrue("an A1111 client expects info: $response", response.contains("\"info\""))
            assertTrue("info should name the engine: $response", response.contains("stable-diffusion.cpp"))
            assertTrue("the request should be echoed: $response", response.contains("a red fox, photograph"))

            val encoded = Regex("\"images\"\\s*:\\s*\\[\\s*\"([A-Za-z0-9+/=]+)\"")
                .find(response)?.groupValues?.get(1)
                ?: throw AssertionError("no base64 image in the response: $response")
            val received = File(dir, "from-api.png").apply { writeBytes(Base64.getDecoder().decode(encoded)) }
            val chunks = TestPng.chunks(received)
            assertEquals("IEND", chunks.last().first)
            val ihdr = chunks.first { it.first == "IHDR" }.second
            assertEquals(64, ByteBuffer.wrap(ihdr, 0, 4).int)
            assertEquals(64, ByteBuffer.wrap(ihdr, 4, 4).int)

            val forwarded = requestFile.readText()
            for (field in listOf("negative_prompt", "cfg_scale", "sampler_name", "scheduler", "seed")) {
                assertTrue("the engine must receive '$field': $forwarded", forwarded.contains(field))
            }
            val argv = argvFile.readLines()
            assertTrue(
                "engine must bind loopback, got $argv",
                argv.indexOf("--listen-ip").let { it >= 0 && argv[it + 1] == "127.0.0.1" },
            )
            assertTrue(
                "engine must use the selected port, got $argv",
                argv.indexOf("--listen-port").let { it >= 0 && argv[it + 1] == port.toString() },
            )
            assertEquals("engine output must be forwarded: $out", 0, served.get(20, TimeUnit.SECONDS))
        } finally {
            served.get(20, TimeUnit.SECONDS)
        }
    }
}
