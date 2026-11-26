package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Workflow
import com.abrah.nightmare.desktop.graph.WorkflowPersistence
import com.abrah.nightmare.desktop.graph.WorkflowResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM seam for the external-tool command surface (ADR-0008): a future chat
 * effort drives the same 工作流 the default shell renders. Tests observe only
 * [ToolResult] plus the state snapshots — no UI test framework and no second
 * persistence format.
 */
class ToolInterfaceTest {

    @Test
    fun `a tool can start from an empty workflow, add nodes and set their parameters`() {
        val session = WorkflowSession()
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)

        assertTrue(tools.dispatch(ToolCommand.NewWorkflow) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT, 0f, 0f)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("sampler", DesktopNodeTypes.SD_SAMPLER, 260f, 0f)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "a quiet mountain lake")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "steps", "12")) is ToolResult.Ok)

        val snapshot = state.snapshot()
        assertEquals(listOf("prompt", "sampler"), snapshot.chips.map { it.nodeId })
        assertEquals("a quiet mountain lake", snapshot.chips.first().summary)
        assertTrue(state.selectNode("sampler"))
        assertEquals("12", state.snapshot().fields.single { it.key == "steps" }.value)
    }

    @Test
    fun `unsafe duplicate and unknown node additions are rejected without changing the workflow`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)

        val unsafe = tools.dispatch(ToolCommand.AddNode("bad.id", DesktopNodeTypes.PROMPT))
        assertTrue(unsafe is ToolResult.Rejected)
        assertTrue((unsafe as ToolResult.Rejected).reason.contains("id"))
        assertEquals(emptyList<String>(), state.snapshot().chips.map { it.nodeId })

        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.OUTPUT)) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.AddNode("mystery", "does.not.exist")) is ToolResult.Rejected)
        assertEquals(listOf("prompt"), state.snapshot().chips.map { it.nodeId })
    }

    @Test
    fun `a tool can wire nodes and illegal wires are rejected without changing the graph`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)
        listOf(
            "prompt" to DesktopNodeTypes.PROMPT,
            "sampler" to DesktopNodeTypes.SD_SAMPLER,
            "output" to DesktopNodeTypes.OUTPUT,
        ).forEach { (id, type) -> assertTrue(tools.dispatch(ToolCommand.AddNode(id, type)) is ToolResult.Ok) }

        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "sampler", "prompt")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("sampler", "output", "image")) is ToolResult.Ok)
        val wired = session.workflow.connections
        assertEquals(2, wired.size)
        assertEquals("prompt", wired[0].from.node)
        assertEquals("sampler", wired[0].toNode)
        assertEquals("sampler", wired[1].from.node)
        assertEquals("output", wired[1].toNode)

        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "prompt", "prompt")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.Connect("ghost", "sampler", "prompt")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.Connect("output", "sampler", "prompt")) is ToolResult.Rejected)
        assertEquals(wired, session.workflow.connections)
    }

    @Test
    fun `setting an unknown parameter or node is rejected and leaves the workflow unchanged`() {
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session)
        val tools = ToolInterface(session, state)
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "dawn")) is ToolResult.Ok)

        val before = session.workflow
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "not-a-widget", "x")) is ToolResult.Rejected)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("ghost", "prompt", "x")) is ToolResult.Rejected)
        assertEquals(before, session.workflow)
    }

    @Test
    fun `a tool cannot raise steps above the cap and the rejection names the cap`() {
        val session = WorkflowSession()
        val tools = ToolInterface(session, DefaultModeState(session))
        val before = session.workflow

        val rejected = tools.dispatch(ToolCommand.SetParameter("sampler", "steps", "50"))
        assertTrue(rejected is ToolResult.Rejected)
        assertTrue("the tool must tell the model why: $rejected", (rejected as ToolResult.Rejected).reason.contains("20"))
        assertEquals(before, session.workflow)

        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "steps", "20")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "steps", "12")) is ToolResult.Ok)
        assertEquals("12", session.workflow.byId["sampler"]!!.params["steps"])
    }

    @Test
    fun `a fake tool chain assembles a txt2img workflow from nothing and runs it through the real CLI seam`() {
        assumePosix()
        val (config, png) = fakeCliPng()
        val session = WorkflowSession(Workflow())
        val state = DefaultModeState(session, WorkflowRunner(config))
        val tools = ToolInterface(state)

        listOf("prompt", "sampler", "output").zip(
            listOf(DesktopNodeTypes.PROMPT, DesktopNodeTypes.SD_SAMPLER, DesktopNodeTypes.OUTPUT),
        ).forEach { (id, type) -> assertTrue(tools.dispatch(ToolCommand.AddNode(id, type)) is ToolResult.Ok) }
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "tool-built dawn")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("sampler", "seed", "11")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("prompt", "sampler", "prompt")) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.Connect("sampler", "output", "image")) is ToolResult.Ok)

        assertEquals(listOf("prompt", "sampler", "output"), state.snapshot().chips.map { it.nodeId })
        assertEquals(2, session.workflow.connections.size)

        assertTrue(tools.dispatch(ToolCommand.Run) is ToolResult.Ok)
        val run = state.runSnapshot()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(1, run.progress.size)
        assertEquals(png.path, run.results.single().path)
        assertEquals("11", run.results.single().params["seed"])
        assertTrue("the tool-built prompt reached the CLI: ${run.command}", run.command.contains("tool-built dawn"))
    }

    @Test
    fun `a tool-built workflow uses the one persisted format with no parallel representation`() {
        val session = WorkflowSession(Workflow())
        val tools = ToolInterface(session, DefaultModeState(session))
        assertTrue(tools.dispatch(ToolCommand.AddNode("prompt", DesktopNodeTypes.PROMPT)) is ToolResult.Ok)
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "dawn")) is ToolResult.Ok)

        val file = Files.createTempFile("tool-interface-persist", ".json")
        val saved = WorkflowPersistence.save(session.workflow, file)
        assertTrue(saved is WorkflowResult.Ok)
        val loaded = WorkflowPersistence.load(file)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(session.workflow, (loaded as WorkflowResult.Ok).value)
    }

    @Test
    fun `list_loras lists safetensors under lora directories and keeps broken ones visible`() {
        val root = Files.createTempDirectory("tool-list-loras")
        val lora = root.resolve("loras/character/dream.safetensors")
        val upperLora = root.resolve("LoRA/style.safetensors")
        val checkpoint = root.resolve("checkpoints/base.safetensors")
        val broken = root.resolve("loras/broken.safetensors")
        writeSafetensors(lora)
        writeSafetensors(upperLora)
        writeSafetensors(checkpoint)
        Files.writeString(broken, "truncated")

        val result = ToolInterface(WorkflowSession(), StubRunControl())
            .dispatch(ToolCommand.ListLoras(root.toString()))
        assertTrue(result is ToolResult.Ok)
        val listing = JSONObject((result as ToolResult.Ok).summary)
        val loras = listing.getJSONArray("loras")
        val paths = (0 until loras.length()).map { loras.getJSONObject(it).getString("path") }
        assertTrue("a readable lora must appear: $paths", paths.any { it.endsWith("dream.safetensors") })
        assertTrue("the directory match is case-insensitive: $paths", paths.any { it.endsWith("style.safetensors") })
        assertFalse("a checkpoint is not a lora: $paths", paths.any { it.contains("checkpoints") })

        val rejected = listing.getJSONArray("rejected")
        val rejectedPaths = (0 until rejected.length()).map { rejected.getJSONObject(it).getString("path") }
        assertTrue("a broken lora must not vanish: $rejectedPaths", rejectedPaths.any { it.endsWith("broken.safetensors") })
    }

    @Test
    fun `delete_lora confirms first, keeps the file on cancel and refuses paths outside the lora tree`() {
        val root = Files.createTempDirectory("tool-delete-lora")
        val lora = root.resolve("loras/character/gone.safetensors")
        writeSafetensors(lora)
        val outside = root.resolve("checkpoints/base.safetensors")
        writeSafetensors(outside)
        val tools = ToolInterface(WorkflowSession(), StubRunControl())
        val asked = mutableListOf<Pair<String, List<String>>>()

        val cancelled = tools.dispatch(ToolCommand.DeleteLora(lora.toString(), root.toString())) { q, opts ->
            asked += q to opts
            "取消"
        }
        assertTrue(cancelled is ToolResult.Ok)
        assertTrue("a cancel must not delete", Files.exists(lora))
        assertEquals(1, asked.size)
        assertTrue("the question must name a LoRA: ${asked.single().first}", asked.single().first.contains("LoRA"))
        assertEquals(listOf("删除", "取消"), asked.single().second)

        val escaped = tools.dispatch(ToolCommand.DeleteLora(outside.toString(), root.toString())) { q, _ ->
            throw AssertionError("a path outside the lora tree must not ask: $q")
        }
        assertTrue(escaped is ToolResult.Rejected)
        assertTrue("an out-of-tree file must never be deleted", Files.exists(outside))

        val removed = tools.dispatch(ToolCommand.DeleteLora(lora.toString(), root.toString())) { _, _ -> "删除" }
        assertTrue(removed is ToolResult.Ok)
        assertFalse("a confirmed delete must remove the file", Files.exists(lora))
    }

    @Test
    fun `download_lora uses the fake transport, asks only above the threshold and writes nothing on cancel`() {
        val root = Files.createTempDirectory("tool-download-lora")
        val small = FakeLoraDownloader(size = 10L)
        val smallTools = ToolInterface(WorkflowSession(), StubRunControl(), downloader = small, largeFileBytes = 100L)
        val unexpected: (String, List<String>) -> String = { q, _ -> throw AssertionError("small download must not ask: $q") }

        val smallResult = smallTools.dispatch(
            ToolCommand.DownloadLora("https://example.invalid/small.safetensors", root.toString()),
            unexpected,
        )
        assertTrue(smallResult is ToolResult.Ok)
        assertEquals(1, small.downloadCalls)
        assertTrue(Files.exists(root.resolve("loras/small.safetensors")))

        val large = FakeLoraDownloader(size = 1_000L)
        val largeTools = ToolInterface(WorkflowSession(), StubRunControl(), downloader = large, largeFileBytes = 100L)
        val asked = mutableListOf<Pair<String, List<String>>>()
        val url = "https://example.invalid/big.safetensors"
        val cancelled = largeTools.dispatch(ToolCommand.DownloadLora(url, root.toString())) { q, opts ->
            asked += q to opts
            "取消"
        }
        assertTrue(cancelled is ToolResult.Ok)
        assertEquals(0, large.downloadCalls)
        assertFalse("a cancel must not touch the disk", Files.exists(root.resolve("loras/big.safetensors")))
        assertEquals(listOf("下载", "取消"), asked.single().second)

        val confirmed = largeTools.dispatch(ToolCommand.DownloadLora(url, root.toString())) { _, _ -> "下载" }
        assertTrue(confirmed is ToolResult.Ok)
        assertEquals(1, large.downloadCalls)
        assertTrue(Files.exists(root.resolve("loras/big.safetensors")))
    }

    @Test
    fun `download_lora derives a safe file name from the URL and never echoes a query token`() {
        val root = Files.createTempDirectory("tool-download-name")
        val fake = FakeLoraDownloader(size = 1L)
        val tools = ToolInterface(WorkflowSession(), StubRunControl(), downloader = fake, largeFileBytes = 100L)

        val result = tools.dispatch(
            ToolCommand.DownloadLora(
                "https://example.invalid/cute%20fox.safetensors?token=secret-value",
                root.toString(),
            ),
        ) { _, _ -> "下载" }
        assertTrue(result is ToolResult.Ok)
        assertTrue("the name must be sanitized: ${fake.lastDest}", Files.exists(root.resolve("loras/cute_fox.safetensors")))
        assertFalse("a URL token must not leak: ${(result as ToolResult.Ok).summary}", result.summary.contains("secret-value"))
    }

    @Test
    fun `list_models reports the readable models a directory holds and rejects a missing root`() {
        val dir = Files.createTempDirectory("tool-list-models")
        val model = dir.resolve("checkpoints/dream.safetensors")
        writeSafetensors(model)
        Files.writeString(dir.resolve("notes.txt"), "not a model")
        val tools = ToolInterface(WorkflowSession(), StubRunControl())

        val result = tools.dispatch(ToolCommand.ListModels(dir.toString()))
        assertTrue(result is ToolResult.Ok)
        val listing = JSONObject((result as ToolResult.Ok).summary)
        assertEquals(dir.toAbsolutePath().toString(), listing.getString("root"))
        val models = listing.getJSONArray("models")
        assertEquals(1, models.length())
        val found = models.getJSONObject(0)
        assertTrue(found.getString("path").endsWith("dream.safetensors"))
        assertEquals("safetensors", found.getString("format"))
        assertEquals(1, found.getInt("tensors"))

        val missing = tools.dispatch(ToolCommand.ListModels(dir.resolve("no-such-dir").toString()))
        assertTrue(missing is ToolResult.Rejected)
        assertTrue((missing as ToolResult.Rejected).reason.contains("模型库"))
    }

    @Test
    fun `save_flow writes a loadable workflow and rejects an unwritable path`() {
        val dir = Files.createTempDirectory("tool-save-flow")
        val session = WorkflowSession()
        val tools = ToolInterface(session, DefaultModeState(session))
        assertTrue(tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "saved by tool")) is ToolResult.Ok)

        val target = dir.resolve("flow.json")
        val saved = tools.dispatch(ToolCommand.SaveFlow(target.toString()))
        assertTrue(saved is ToolResult.Ok)
        assertTrue(Files.exists(target))
        val loaded = WorkflowPersistence.load(target)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(session.workflow, (loaded as WorkflowResult.Ok).value)
        assertEquals("saved by tool", loaded.value.byId["prompt"]!!.params["prompt"])

        val fileNotDir = dir.resolve("not-a-directory")
        Files.writeString(fileNotDir, "occupied")
        val rejected = tools.dispatch(ToolCommand.SaveFlow(fileNotDir.resolve("flow.json").toString()))
        assertTrue(rejected is ToolResult.Rejected)
        assertTrue("the failure must reach the caller: $rejected", (rejected as ToolResult.Rejected).reason.contains("不可写"))
    }

    @Test
    fun `save_flow asks before overwriting a file and a cancel leaves the old file untouched`() {
        val dir = Files.createTempDirectory("tool-save-confirm")
        val target = dir.resolve("flow.json")
        val original = "an earlier workflow"
        Files.writeString(target, original)
        val session = WorkflowSession()
        val tools = ToolInterface(session, DefaultModeState(session))
        val asked = mutableListOf<Pair<String, List<String>>>()

        val cancelled = tools.dispatch(ToolCommand.SaveFlow(target.toString())) { q, opts ->
            asked += q to opts
            "取消"
        }
        assertTrue(cancelled is ToolResult.Ok)
        assertEquals(original, Files.readString(target))
        assertEquals(listOf("覆盖已保存的工作流?" to listOf("保存", "取消")), asked)

        val saved = tools.dispatch(ToolCommand.SaveFlow(target.toString())) { _, _ -> "保存" }
        assertTrue(saved is ToolResult.Ok)
        val loaded = WorkflowPersistence.load(target)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(session.workflow, (loaded as WorkflowResult.Ok).value)
    }

    @Test
    fun `save_flow asks nothing when the target file does not exist`() {
        val target = Files.createTempDirectory("tool-save-new").resolve("fresh.json")
        val tools = ToolInterface(WorkflowSession(), StubRunControl())
        val unexpected: (String, List<String>) -> String = { q, _ -> throw AssertionError("unexpected ask: $q") }

        assertTrue(tools.dispatch(ToolCommand.SaveFlow(target.toString()), unexpected) is ToolResult.Ok)
        assertTrue(Files.exists(target))
    }

    @Test
    fun `new_flow clears the shared workflow just like new_workflow`() {
        val session = WorkflowSession()
        val tools = ToolInterface(session, DefaultModeState(session))
        assertEquals(3, session.workflow.nodes.size)

        val result = tools.dispatch(ToolCommand.NewFlow)
        assertTrue(result is ToolResult.Ok)
        assertTrue(session.workflow.nodes.isEmpty())
        assertTrue(session.workflow.connections.isEmpty())
    }

    @Test
    fun `get_canvas exposes the current workflow as readable JSON with nodes parameters and wires`() {
        val session = WorkflowSession()
        val tools = ToolInterface(session, DefaultModeState(session))
        assertTrue(
            tools.dispatch(ToolCommand.SetParameter("prompt", "prompt", "dawn at the lake")) is ToolResult.Ok,
        )

        val result = tools.dispatch(ToolCommand.GetCanvas)
        assertTrue(result is ToolResult.Ok)
        val canvas = JSONObject((result as ToolResult.Ok).summary)
        assertEquals(1, canvas.getInt("format"))
        val nodes = canvas.getJSONArray("nodes")
        assertEquals(3, nodes.length())
        val byId = (0 until nodes.length()).map { nodes.getJSONObject(it) }.associateBy { it.getString("id") }
        assertEquals(DesktopNodeTypes.SD_SAMPLER, byId.getValue("sampler").getString("type"))
        assertEquals("dawn at the lake", byId.getValue("prompt").getJSONObject("params").getString("prompt"))
        assertEquals("prompt:prompt", byId.getValue("sampler").getJSONObject("inputs").getString("prompt"))
        assertEquals("sampler:image", byId.getValue("output").getJSONObject("inputs").getString("image"))
    }

    @Test
    fun `a run command is rejected while a run is already in flight`() {
        val busy = StubRunControl(status = RunStatus.RUNNING)
        val rejected = ToolInterface(WorkflowSession(), busy).dispatch(ToolCommand.Run)
        assertTrue(rejected is ToolResult.Rejected)
        assertEquals(0, busy.triggerCalls)

        val refusing = StubRunControl(status = RunStatus.IDLE, acceptsRun = false)
        assertTrue(ToolInterface(WorkflowSession(), refusing).dispatch(ToolCommand.Run) is ToolResult.Rejected)
        assertEquals(1, refusing.triggerCalls)

        val ready = StubRunControl(status = RunStatus.IDLE)
        assertTrue(ToolInterface(WorkflowSession(), ready).dispatch(ToolCommand.Run) is ToolResult.Ok)
        assertEquals(1, ready.triggerCalls)
    }

    @Test
    fun `a failed run is rejected with the CLI reason instead of claiming an image`() {
        val failed = StubRunControl(
            statusAfter = RunStatus.FAILURE,
            error = "退出码 2：option '--scheduler' must be one of [discrete, karras]",
        )
        val result = ToolInterface(WorkflowSession(), failed).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        val reason = (result as ToolResult.Rejected).reason
        assertTrue("the failure reason must reach the caller: $reason", reason.contains("退出码 2"))
        assertTrue(reason.startsWith("运行失败："))
        assertEquals(1, failed.triggerCalls)
    }

    @Test
    fun `a failure without an error line still reports the first readable reason`() {
        val failed = StubRunControl(
            statusAfter = RunStatus.FAILURE,
            firstReason = "model not found",
        )
        val result = ToolInterface(WorkflowSession(), failed).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        assertTrue((result as ToolResult.Rejected).reason.contains("model not found"))
    }

    @Test
    fun `a cancelled run is rejected with the cancellation reason`() {
        val cancelled = StubRunControl(
            statusAfter = RunStatus.CANCELLED,
            firstReason = "已取消：子进程已结束",
        )
        val result = ToolInterface(WorkflowSession(), cancelled).dispatch(ToolCommand.Run)

        assertTrue(result is ToolResult.Rejected)
        assertTrue((result as ToolResult.Rejected).reason.contains("已取消"))
    }

    // ---- harness -----------------------------------------------------------

    /** A tiny but valid safetensors header, so listing needs no real 4 GB weights. */
    private fun writeSafetensors(path: Path) {
        val header = """{"tensor0":{"dtype":"F16","shape":[1],"data_offsets":[0,2]}}"""
        val bytes = header.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(bytes.size.toLong())
        buffer.put(bytes)
        Files.createDirectories(path.parent)
        Files.write(path, buffer.array())
    }

    /** No network: records the destination and writes a tiny stand-in file. */
    private class FakeLoraDownloader(private val size: Long) : LoraDownloader {
        var downloadCalls: Int = 0
            private set
        var lastDest: Path? = null
            private set

        override fun contentLength(url: String): Long = size

        override fun download(url: String, dest: Path): Long {
            downloadCalls++
            lastDest = dest
            val bytes = "fake-lora".toByteArray()
            Files.createDirectories(dest.parent)
            Files.write(dest, bytes)
            return bytes.size.toLong()
        }
    }

    /** [statusAfter] is the terminal snapshot a blocking triggerRun() leaves behind. */
    private class StubRunControl(
        status: RunStatus = RunStatus.IDLE,
        private val statusAfter: RunStatus = RunStatus.SUCCESS,
        private val acceptsRun: Boolean = true,
        private val error: String? = null,
        private val firstReason: String? = null,
    ) : WorkflowRunControl {
        private var currentStatus = status
        var triggerCalls: Int = 0
            private set

        override fun triggerRun(): Boolean {
            triggerCalls++
            if (acceptsRun) currentStatus = statusAfter
            return acceptsRun
        }

        override fun runSnapshot(): RunSnapshot = RunSnapshot(
            status = currentStatus,
            error = error,
            firstReason = firstReason,
        )
    }

    private fun assumePosix() {
        Assume.assumeFalse("fake CLI runner is a POSIX shell script", System.getProperty("os.name").startsWith("Windows"))
    }

    private fun fakeCliPng(): Pair<RunConfig, File> {
        val dir = Files.createTempDirectory("tool-interface-test").toFile()
        val png = File(dir, "tool-result.png")
        val argv = File(dir, "argv.txt")
        val script = File(dir, "fake-cli.sh")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        script.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("printf '%s\\n' \"\$@\" > ${quote(argv.path)}\n")
                append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
                append(": > ${quote(png.path)}\n")
                append("exit 0\n")
            },
        )
        script.setExecutable(true)
        return RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = 10_000L) to png
    }
}
