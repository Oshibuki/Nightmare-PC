package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Seam: [Cli.run] and the files it leaves behind -- the same CLI/fake-engine
 * seam as [Txt2ImgFullCliTest], widened to the SD LoRA surface. The fake engine
 * records every argument and copies a real PNG, so the assertions are about
 * what a caller observes: exit code, stderr, the engine's command line, the
 * image and the parameters recorded in it.
 */
class Txt2ImgLoraTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var loraDir: File
    private lateinit var engine: File
    private lateinit var argvFile: File
    private lateinit var outPng: File

    @Before
    fun setUp() {
        assumeTrue("/bin/sh is required by the fake engine", File("/bin/sh").exists())
        dir = Files.createTempDirectory("nm-cli-lora-test").toFile()
        model = safetensors("model.safetensors")
        loraDir = File(dir, "loras").apply { mkdirs() }
        for (name in listOf(
            "pumpkinhead.safetensors", "illusion-pattern.safetensors",
            "first.safetensors", "second.safetensors",
            "style.safetensors", "theme.safetensors",
        )) File(loraDir, name).createNewFile()
        argvFile = File(dir, "argv.txt")
        outPng = File(dir, "out.png")
        engine = fakeEngine(TestPng.file(dir, 512, 512))
    }

    /** A real safetensors header: the CLI checks bytes before starting the engine. */
    private fun safetensors(name: String): File {
        val json = """{"w":{"dtype":"F16","shape":[2],"data_offsets":[0,4]}}"""
        val header = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + header.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.size.toLong())
            .put(header)
            .array()
        return File(dir, name).apply { writeBytes(bytes) }
    }

    /** Records each invocation's arguments, then copies a PNG to whatever `-o` names. */
    private fun fakeEngine(png: File): File = File(dir, "sd-cli").apply {
        writeText(
            """
            #!/bin/sh
            {
              for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done
              printf '###\n'
            } >> '${argvFile.absolutePath}'
            echo 'fake engine: generating'
            out=''
            prev=''
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = '-o' ]; then out="${'$'}a"; fi
              prev="${'$'}a"
            done
            cp '${png.absolutePath}' "${'$'}out"
            """.trimIndent() + "\n",
        )
        assertTrue("fake engine must be executable", setExecutable(true))
    }

    private fun runprompt(prompt: String, vararg extra: String): Int = Cli.run(
        listOf(
            "txt2img",
            "--model", model.absolutePath,
            "--prompt", prompt,
            "--out", outPng.absolutePath,
            "--engine", engine.absolutePath,
            *extra,
        ),
        out,
        err,
    )

    private fun txt2img(vararg extra: String): Int = runprompt("a red fox", *extra)

    private fun invocations(): List<List<String>> {
        if (!argvFile.isFile) return emptyList()
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in argvFile.readLines()) {
            if (line == "###") {
                blocks += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        return blocks
    }

    private fun argValue(argv: List<String>, flag: String): String? {
        val i = argv.indexOf(flag)
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }

    private fun parameters(): String =
        TestPng.parameters(outPng) ?: throw AssertionError("no 'parameters' chunk in $outPng")

    // ---- one LoRA ----------------------------------------------------------

    @Test
    fun aSingleLoraBecomesAnEnginePromptTagAndIsRecordedInThePng() {
        val code = txt2img(
            "--lora", "pumpkinhead.safetensors@0.6",
            "--lora-dir", loraDir.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals(loraDir.absolutePath, argValue(argv, "--lora-model-dir"))
        assertEquals("a red fox <lora:pumpkinhead.safetensors:0.6>", argValue(argv, "-p"))
        assertTrue("the PNG must record the applied LoRA: ${parameters()}", parameters().contains("Lora: pumpkinhead.safetensors@0.6"))
    }

    @Test
    fun aMultiplierOfOneIsWrittenBareAndANegativeMultiplierSurvives() {
        val code = txt2img(
            "--lora", "theme.safetensors@-0.4",
            "--lora-dir", loraDir.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals("a red fox <lora:theme.safetensors:-0.4>", argValue(argv, "-p"))

        out.setLength(0); err.setLength(0); argvFile.delete(); outPng.delete()
        assertEquals("stderr: $err", 0, txt2img("--lora", "style.safetensors", "--lora-dir", loraDir.absolutePath))
        assertEquals("a red fox <lora:style.safetensors:1>", argValue(invocations().single(), "-p"))
        assertTrue(parameters().contains("Lora: style.safetensors"))
    }

    // ---- several LoRAs -----------------------------------------------------

    @Test
    fun repeatedAndCommaSeparatedLorasReachTheEngineInOrder() {
        val code = txt2img(
            "--lora", "first.safetensors@0.5,second.safetensors@1.25",
            "--lora", "style.safetensors",
            "--lora-dir", loraDir.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals(
            "a red fox <lora:first.safetensors:0.5> <lora:second.safetensors:1.25> <lora:style.safetensors:1>",
            argValue(argv, "-p"),
        )
        assertEquals(loraDir.absolutePath, argValue(argv, "--lora-model-dir"))
        assertTrue(
            "the PNG must record every LoRA: ${parameters()}",
            parameters().contains("Lora: first.safetensors@0.5, second.safetensors@1.25, style.safetensors"),
        )
    }

    // ---- prompt-embedded tags (the engine's native A1111 syntax) -----------

    @Test
    fun promptTagsAreNormalisedAndWinOverAFlagForTheSameFile() {
        val code = runprompt(
            "a fox <lora:pumpkinhead:0.7>, <lyco:illusion-pattern>",
            "--lora", "pumpkinhead.safetensors@0.2",
            "--lora-dir", loraDir.absolutePath,
        )

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals(
            "a fox <lora:pumpkinhead.safetensors:0.7> <lora:illusion-pattern.safetensors:1>",
            argValue(argv, "-p"),
        )
        assertEquals(1, argValue(argv, "-p")!!.split("<lora:pumpkinhead").size - 1)
        assertEquals(loraDir.absolutePath, argValue(argv, "--lora-model-dir"))
        assertTrue(
            "the PNG must record the resolved files: ${parameters()}",
            parameters().contains("Lora: pumpkinhead.safetensors@0.7, illusion-pattern.safetensors"),
        )
    }

    @Test
    fun aPromptTagAloneStillSelectsTheLoraDirectory() {
        val code = runprompt("a fox <lora:style:0.3>", "--lora-dir", loraDir.absolutePath)

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertEquals(loraDir.absolutePath, argValue(argv, "--lora-model-dir"))
        assertEquals("a fox <lora:style.safetensors:0.3>", argValue(argv, "-p"))
    }

    @Test
    fun theDefaultLoraDirectoryFollowsTheModelLibraryRoot() {
        val library = File(dir, "library")
        val defaultDir = File(library, "lora").apply { mkdirs() }
        File(defaultDir, "style.safetensors").createNewFile()
        val code = Cli.run(
            listOf(
                "txt2img",
                "--model", model.absolutePath,
                "--prompt", "a red fox",
                "--out", outPng.absolutePath,
                "--engine", engine.absolutePath,
                "--lora", "style.safetensors@0.3",
            ),
            out,
            err,
            mapOf("NIGHTMARE_MODELS" to library.absolutePath),
        )

        assertEquals("stderr: $err", 0, code)
        assertEquals(defaultDir.absolutePath, argValue(invocations().single(), "--lora-model-dir"))
    }

    // ---- no silent no-ops --------------------------------------------------

    @Test
    fun aMissingLoraWeightFailsBeforeTheEngineRuns() {
        val code = txt2img("--lora", "missing.safetensors@0.5", "--lora-dir", loraDir.absolutePath)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the missing weight: $err", err.contains("missing.safetensors"))
        assertTrue("nothing may be claimed: $out", out.isEmpty())
        assertFalse("the engine must never run", argvFile.isFile)
    }

    @Test
    fun aMissingPromptTagWeightFailsBeforeTheEngineRuns() {
        val code = runprompt("a fox <lora:ghost:0.5>", "--lora-dir", loraDir.absolutePath)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the missing weight: $err", err.contains("ghost"))
        assertFalse("the engine must never run", argvFile.isFile)
    }

    @Test
    fun aMissingLoraDirectoryNamesThePathAndNeverStartsTheEngine() {
        val absent = File(dir, "no-loras-here")

        val code = txt2img("--lora", "style.safetensors", "--lora-dir", absent.absolutePath)

        assertEquals("stderr: $err", 1, code)
        assertTrue("stderr must name the directory: $err", err.contains(absent.absolutePath))
        assertFalse("the engine must never run", argvFile.isFile)
    }

    @Test
    fun aNonNumericMultiplierIsAUsageError() {
        val code = txt2img("--lora", "style.safetensors@many", "--lora-dir", loraDir.absolutePath)

        assertEquals("stderr: $err", 2, code)
        assertTrue("stderr must name the option: $err", err.contains("--lora"))
        assertFalse("the engine must never run", argvFile.isFile)
    }

    @Test
    fun withoutLorasTheEngineCommandIsUnchanged() {
        val code = txt2img()

        assertEquals("stderr: $err", 0, code)
        val argv = invocations().single()
        assertFalse("no --lora-model-dir without LoRAs: $argv", argv.contains("--lora-model-dir"))
        assertEquals("a red fox", argValue(argv, "-p"))
        assertFalse("no Lora line without LoRAs: ${parameters()}", parameters().contains("Lora:"))
    }
}
