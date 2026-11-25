package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
