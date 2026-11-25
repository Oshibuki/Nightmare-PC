package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seam: [Cli.run] -- arguments in, exit code and the two output streams out.
 * Asserting here rather than on `main` keeps the test free of process spawning
 * while still describing exactly what a user (or a shell script) observes.
 *
 * Exit codes are part of the contract: 0 = did what was asked, 2 = the command
 * line itself was wrong.
 */
class CliTest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private fun run(vararg args: String): Int = Cli.run(args.toList(), out, err)

    @Test
    fun `version option prints the product banner and succeeds`() {
        assertEquals(0, run("--version"))
        assertEquals("nightmare-desktop 0.1.0", out.toString().trim())
        assertTrue("version must not write to stderr: $err", err.isEmpty())
    }

    @Test
    fun `help option succeeds and documents both options`() {
        assertEquals(0, run("--help"))
        val text = out.toString()
        assertTrue("help should list --help: $text", text.contains("--help"))
        assertTrue("help should list --version: $text", text.contains("--version"))
        assertTrue("help must not write to stderr: $err", err.isEmpty())
    }

    @Test
    fun `help documents the txt2img command and the exit codes`() {
        assertEquals(0, run("--help"))
        val text = out.toString()
        assertTrue("help should name the command: $text", text.contains("txt2img"))
        assertTrue("help should list --model: $text", text.contains("--model"))
        assertTrue("help should list --out: $text", text.contains("--out"))
        assertTrue("help should document exit code 2: $text", text.contains("Exit codes"))
        assertTrue("help should point at the model docs: $text", text.contains("desktop/docs/models.md"))
    }

    /**
     * Every knob a script can turn is documented, with its bounds and its
     * default -- a flag that only exists in the parser is a flag nobody finds.
     */
    @Test
    fun `help documents every txt2img parameter with its bounds`() {
        assertEquals(0, run("--help"))
        val text = out.toString()
        for (option in listOf(
            "--negative-prompt", "--width", "--height", "--steps",
            "--sampler", "--scheduler", "--cfg-scale", "--seed", "--batch", "--engine",
            "--diffusion-model", "--llm", "--vae", "--llm_vision",
        )) {
            assertTrue("help should document $option: $text", text.contains(option))
        }
        assertTrue("help should state the 1-150 step bound: $text", text.contains("1-150"))
        assertTrue("help should state the 1-16 batch bound: $text", text.contains("1-16"))
        assertTrue("help should state the multiple-of-8 size rule: $text", text.contains("multiple of 8"))
        // The sampler/scheduler values come from the copied table, so the help
        // text is the table -- a list written twice would drift.
        assertTrue("help should list the default sampler: $text", text.contains("default: euler"))
        assertTrue("help should list a sampler value: $text", text.contains("euler_a"))
        assertTrue("help should list the default scheduler: $text", text.contains("default: discrete"))
        assertTrue("help should list a scheduler value: $text", text.contains("sgm_uniform"))
        assertTrue("help should explain the batch naming: $text", text.contains("<stem>-<i>.png"))
        assertTrue("help should explain the batch seed: $text", text.contains("seed incrementing"))
        assertTrue("help should explain the metadata chunk: $text", text.contains("parameters"))
    }

    /** A flag that only exists in the parser is a flag nobody finds: help and README must agree. */
    @Test
    fun `README documents every txt2img option the parser accepts`() {
        assertEquals(0, run("--help"))
        // Gradle runs each subproject's tests from that subproject, so the
        // desktop README is one level above :cli.
        val readme = File("../README.md")
        assertTrue("README must be readable from :cli: ${readme.absolutePath}", readme.isFile)
        val documented = readme.readText()
        val section = out.toString()
            .substringAfter("txt2img options:")
            .substringBefore("img2img options:")
        val flags = Regex("^\\s*--[a-z0-9_-]+", RegexOption.MULTILINE)
            .findAll(section)
            .map { it.value.trim() }
            .toSet()
        assertTrue("no txt2img flags found in help: $section", flags.isNotEmpty())
        assertEquals(
            "help flags missing from README.md",
            emptyList<String>(),
            flags.filterNot { documented.contains(it) },
        )
    }

    @Test
    fun `help documents the img2img command and its options`() {
        assertEquals(0, run("--help"))
        val text = out.toString()
        assertTrue("help should name img2img: $text", text.contains("img2img"))
        assertTrue("help should document --init-img: $text", text.contains("--init-img"))
        assertTrue("help should document --strength: $text", text.contains("--strength"))
        assertTrue("help should state the strength range: $text", text.contains("[0, 1]"))
        assertTrue("help should state the 0.75 strength default: $text", text.contains("default: 0.75"))
    }

    @Test
    fun `unknown option fails and names the offending argument`() {
        assertEquals(2, run("--frobnicate"))
        assertTrue("a usage error must not print to stdout: $out", out.isEmpty())
        assertTrue("error should name --frobnicate: $err", err.contains("--frobnicate"))
    }

    @Test
    fun `no arguments fails and points at help`() {
        assertEquals(2, run())
        assertTrue("empty invocation should suggest --help: $err", err.contains("--help"))
    }
}
