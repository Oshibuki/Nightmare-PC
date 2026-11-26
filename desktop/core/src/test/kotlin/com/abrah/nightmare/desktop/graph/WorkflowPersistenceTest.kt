package com.abrah.nightmare.desktop.graph

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** External behaviour of workflow persistence: files in, workflow objects out. */
class WorkflowPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `explicit save then load round trips the chained txt2img workflow without temp leftovers`() {
        val dir = tmp.newFolder("roundtrip").toPath()
        val file = dir.resolve("flow.json")
        val workflow = chainedWorkflow()

        val saved = WorkflowPersistence.save(workflow, file)
        assertTrue(saved is WorkflowResult.Ok)

        val text = Files.readString(file)
        assertEquals(1, JSONObject(text).getInt("format"))
        assertTrue(dir.toFile().list()!!.none { it.endsWith(".tmp") })

        val loaded = WorkflowPersistence.load(file)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(workflow, (loaded as WorkflowResult.Ok).value)
    }

    @Test
    fun `an image input node with string params survives a format one save load cycle`() {
        val file = tmp.newFolder("image-node").toPath().resolve("img2img.json")
        val image = DesktopNodeTypes.imageInput.createNode(
            "init",
            params = mapOf("path" to "/tmp/source.png", "strength" to "0.4"),
        )
        val base = chainedWorkflow()
        val workflow = (base.copy(nodes = base.nodes + image)
            .connect("init", "sampler", "initImage") as ConnectionResult.Connected).workflow

        val saved = WorkflowPersistence.save(workflow, file)
        assertTrue(saved is WorkflowResult.Ok)

        val json = JSONObject(Files.readString(file))
        assertEquals(1, json.getInt("format"))
        val savedImage = (0 until json.getJSONArray("nodes").length())
            .map { json.getJSONArray("nodes").getJSONObject(it) }
            .first { it.getString("id") == "init" }
        assertEquals("core.image", savedImage.getString("type"))
        assertEquals("/tmp/source.png", savedImage.getJSONObject("params").getString("path"))
        assertEquals("0.4", savedImage.getJSONObject("params").getString("strength"))
        val savedSampler = (0 until json.getJSONArray("nodes").length())
            .map { json.getJSONArray("nodes").getJSONObject(it) }
            .first { it.getString("id") == "sampler" }
        assertEquals("init:image", savedSampler.getJSONObject("inputs").getString("initImage"))

        val loaded = WorkflowPersistence.load(file)
        assertTrue(loaded is WorkflowResult.Ok)
        assertEquals(workflow, (loaded as WorkflowResult.Ok).value)
    }

    @Test
    fun `new workflow is empty and survives a save load cycle`() {
        val file = tmp.newFolder("fresh").toPath().resolve("empty.json")
        val fresh = WorkflowPersistence.newWorkflow()

        assertTrue(fresh.nodes.isEmpty())
        assertTrue(WorkflowPersistence.save(fresh, file) is WorkflowResult.Ok)
        assertEquals(0, JSONObject(Files.readString(file)).getJSONArray("nodes").length())
        assertEquals(fresh, (WorkflowPersistence.load(file) as WorkflowResult.Ok).value)
    }

    @Test
    fun `a workflow from another format reports version incompatibility and leaves the current workflow unchanged`() {
        val file = tmp.newFolder("version").toPath().resolve("future.json")
        Files.writeString(file, """{"format": 99, "nodes": []}""")
        val current = chainedWorkflow()
        val before = current

        val result = WorkflowPersistence.load(file)

        assertTrue(result is WorkflowResult.Err)
        val error = (result as WorkflowResult.Err).error
        assertEquals(WorkflowError.Kind.INCOMPATIBLE_VERSION, error.kind)
        assertTrue(error.message, error.message.contains("版本不兼容"))
        assertTrue(error.message, error.message.contains("99"))
        assertEquals(before, current)
    }

    @Test
    fun `a format that only looks like one after numeric truncation is still refused`() {
        val dir = tmp.newFolder("truncated-format").toPath()
        listOf("4294967297", "1.5", "\"1\"").forEachIndexed { index, value ->
            val file = dir.resolve("format-$index.json")
            Files.writeString(file, """{"format": $value, "nodes": []}""")

            val result = WorkflowPersistence.load(file)

            assertTrue("$value should be refused: $result", result is WorkflowResult.Err)
            assertEquals(
                "$value kind",
                WorkflowError.Kind.INCOMPATIBLE_VERSION,
                (result as WorkflowResult.Err).error.kind,
            )
        }
    }

    @Test
    fun `malformed JSON reports a readable reason and leaves the current workflow unchanged`() {
        val file = tmp.newFolder("garbage").toPath().resolve("broken.json")
        Files.writeString(file, "this is not json")
        val current = chainedWorkflow()
        val before = current

        val result = WorkflowPersistence.load(file)

        assertTrue(result is WorkflowResult.Err)
        val error = (result as WorkflowResult.Err).error
        assertEquals(WorkflowError.Kind.INVALID_JSON, error.kind)
        assertTrue(error.message, error.message.contains("JSON"))
        assertEquals(before, current)
    }

    @Test
    fun `unsafe node ids are refused on load`() {
        val dir = tmp.newFolder("unsafe").toPath()
        val unsafeIds = listOf(".", "..", "a/b", "a\\b", "a\u0001b", "a:b")
        unsafeIds.forEachIndexed { index, id ->
            val file = dir.resolve("unsafe-$index.json")
            Files.writeString(file, """{"format":1,"nodes":[{"id":"$id","type":"core.prompt"}]}""")

            val result = WorkflowPersistence.load(file)

            assertTrue("$id should be refused: $result", result is WorkflowResult.Err)
            val error = (result as WorkflowResult.Err).error
            assertEquals(error.message, WorkflowError.Kind.UNSAFE_NODE_ID, error.kind)
            assertTrue(error.message, error.message.contains("不安全"))
        }
    }

    @Test
    fun `optional requires and view fields are accepted without drifting the node data`() {
        val file = tmp.newFolder("optional").toPath().resolve("with-extras.json")
        Files.writeString(
            file,
            """{"format":1,"requires":[{"id":"plugin","version":"0.1.0"}],""" +
                """"view":{"x":5,"y":6,"scale":1.5,"zoomLock":true,"panLock":false},""" +
                """"nodes":[{"id":"p","type":"core.prompt","w":300,"lines":2,"params":{"prompt":"hi","negative":""},"inputs":{},"position":{"x":3,"y":4}}]}""",
        )

        val result = WorkflowPersistence.load(file)

        assertTrue(result is WorkflowResult.Ok)
        assertEquals(
            Node("p", "core.prompt", mapOf("prompt" to "hi", "negative" to ""), emptyMap(), Position(3f, 4f)),
            (result as WorkflowResult.Ok).value.nodes.single(),
        )
    }

    @Test
    fun `save to an unwritable path reports the failure and leaves the workflow unchanged`() {
        val dir = tmp.newFolder("unwritable").toPath()
        val notADirectory = dir.resolve("not-a-directory")
        Files.writeString(notADirectory, "occupied")
        val target = notADirectory.resolve("flow.json")
        val current = chainedWorkflow()
        val before = current

        val result = WorkflowPersistence.save(current, target)

        assertTrue(result is WorkflowResult.Err)
        val error = (result as WorkflowResult.Err).error
        assertEquals(WorkflowError.Kind.UNWRITABLE_PATH, error.kind)
        assertTrue(error.message, error.message.contains("不可写"))
        assertEquals(before, current)
        assertTrue(dir.toFile().list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `save refuses an unsafe node id before touching the file system`() {
        val dir = tmp.newFolder("unsafe-save").toPath()
        val target = dir.resolve("flow.json")
        val badWorkflow = Workflow(listOf(Node("../escape", "core.prompt")))

        val result = WorkflowPersistence.save(badWorkflow, target)

        assertTrue(result is WorkflowResult.Err)
        assertEquals(WorkflowError.Kind.UNSAFE_NODE_ID, (result as WorkflowResult.Err).error.kind)
        assertTrue(!Files.exists(target))
        assertTrue(dir.toFile().list()!!.isEmpty())
    }

    @Test
    fun `save refuses duplicate node ids so every saved file can be loaded back`() {
        val dir = tmp.newFolder("duplicate-save").toPath()
        val target = dir.resolve("flow.json")
        val workflow = Workflow(listOf(Node("dup", "core.prompt"), Node("dup", "core.output")))

        val result = WorkflowPersistence.save(workflow, target)

        assertTrue(result is WorkflowResult.Err)
        val error = (result as WorkflowResult.Err).error
        assertEquals(WorkflowError.Kind.INVALID_JSON, error.kind)
        assertTrue(error.message, error.message.contains("重复"))
        assertTrue(!Files.exists(target))
    }

    @Test
    fun `save as writes a second file without changing the original`() {
        val dir = tmp.newFolder("save-as").toPath()
        val original = dir.resolve("first.json")
        val copy = dir.resolve("second.json")
        val workflow = chainedWorkflow()
        assertTrue(WorkflowPersistence.save(workflow, original) is WorkflowResult.Ok)

        val result = WorkflowPersistence.saveAs(workflow, copy)

        assertTrue(result is WorkflowResult.Ok)
        assertEquals(Files.readString(original), Files.readString(copy))
        assertEquals(workflow, (WorkflowPersistence.load(copy) as WorkflowResult.Ok).value)
        assertTrue(dir.toFile().list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `temp directory with synthetic bad files reports each error without changing the workflow`() {
        val dir = tmp.newFolder("bad-files").toPath()
        val cases = listOf(
            "garbage.json" to ("JSON" to WorkflowError.Kind.INVALID_JSON),
            "future.json" to ("版本不兼容" to WorkflowError.Kind.INCOMPATIBLE_VERSION),
            "traversal.json" to ("不安全" to WorkflowError.Kind.UNSAFE_NODE_ID),
            "no-nodes.json" to ("nodes" to WorkflowError.Kind.INVALID_JSON),
        )
        val contents = listOf(
            "{ this is broken",
            """{"format":2,"nodes":[]}""",
            """{"format":1,"nodes":[{"id":"../evil","type":"core.prompt"}]}""",
            """{"format":1}""",
        )
        cases.zip(contents).forEach { (case, text) ->
            Files.writeString(dir.resolve(case.first), text)
        }
        val current = chainedWorkflow()
        val before = current

        cases.forEach { (name, expectation) ->
            val result = WorkflowPersistence.load(dir.resolve(name))
            assertTrue("$name should fail: $result", result is WorkflowResult.Err)
            val error = (result as WorkflowResult.Err).error
            assertEquals("$name kind", expectation.second, error.kind)
            assertTrue("$name message was '${error.message}'", error.message.contains(expectation.first))
            assertEquals("$name changed the current workflow", before, current)
        }
        assertTrue(dir.toFile().list()!!.none { it.endsWith(".tmp") })
    }

    private fun chainedWorkflow(): Workflow {
        val first = Workflow(
            listOf(
                DesktopNodeTypes.prompt.createNode("prompt", Position(10f, 20f)),
                DesktopNodeTypes.sdSampler.createNode("sampler", Position(100f, 25f)),
                DesktopNodeTypes.output.createNode("output"),
            ),
        ).connect("prompt", "sampler", "prompt") as ConnectionResult.Connected
        return (first.workflow.connect("sampler", "output", "image") as ConnectionResult.Connected).workflow
    }
}
