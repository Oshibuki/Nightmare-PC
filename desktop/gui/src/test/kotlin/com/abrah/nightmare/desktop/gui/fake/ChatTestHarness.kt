package com.abrah.nightmare.desktop.gui.fake

import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.desktop.gui.ChatSession
import com.abrah.nightmare.desktop.gui.RunConfig
import org.junit.Assert.assertTrue
import org.junit.Assume
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Shared POSIX fake-CLI and bounded-wait harness for chat-mode state tests.
 * The two state-wait helpers stay deliberately distinct: AskSessionTest's
 * one-shot 5 s latch and TurnLifecycleTest's 10 s deadline with 50 ms re-poll
 * have different bounded-wait semantics, and neither is changed here.
 */

fun assumePosix() {
    Assume.assumeFalse(
        "fake CLI runner is a POSIX shell script",
        System.getProperty("os.name").startsWith("Windows"),
    )
}

/** One-shot fake CLI; [captureArgv] keeps the argv-dump line some tests rely on. */
fun fakeCliPng(
    tempPrefix: String,
    pngName: String,
    captureArgv: Boolean = false,
    timeoutMillis: Long = 10_000L,
): Pair<RunConfig, File> {
    val dir = Files.createTempDirectory(tempPrefix).toFile()
    val png = File(dir, pngName)
    val argv = File(dir, "argv.txt")
    val script = File(dir, "fake-cli.sh")
    val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
    script.writeText(
        buildString {
            append("#!/bin/sh\n")
            if (captureArgv) append("printf '%s\\n' \"\$@\" > ${quote(argv.path)}\n")
            append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
            append(": > ${quote(png.path)}\n")
            append("exit 0\n")
        },
    )
    script.setExecutable(true)
    return RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = timeoutMillis) to png
}

/** A fake CLI that blocks inside one tool call until the test releases it. */
data class BlockingCli(val config: RunConfig, val png: File, val release: () -> Unit)

fun blockingCliPng(tempPrefix: String, pngName: String): BlockingCli {
    val dir = Files.createTempDirectory(tempPrefix).toFile()
    val png = File(dir, pngName)
    val fifo = File(dir, "release.fifo")
    val made = ProcessBuilder("mkfifo", fifo.path).start().waitFor(10, SECONDS)
    Assume.assumeTrue("mkfifo is unavailable", made)
    val script = File(dir, "blocking-cli.sh")
    val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
    script.writeText(
        buildString {
            append("#!/bin/sh\n")
            append("cat ${quote(fifo.path)} > /dev/null\n")
            append("echo ${quote("[1/1] seed=11 -> ${png.path}")}\n")
            append(": > ${quote(png.path)}\n")
            append("exit 0\n")
        },
    )
    script.setExecutable(true)
    val release = { FileOutputStream(fifo).use { it.write(0x78) } }
    return BlockingCli(
        RunConfig(cliExecutable = script.path, outputPath = png.path, timeoutMillis = 15_000L),
        png,
        release,
    )
}

/** AskSessionTest's listener-driven one-shot wait, bounded to 5 seconds. */
fun awaitStateOnce(chat: ChatSession, predicate: () -> Boolean) {
    val latch = CountDownLatch(1)
    val unsubscribe = chat.addChangeListener { if (predicate()) latch.countDown() }
    try {
        if (!predicate()) assertTrue("state never settled", latch.await(5, SECONDS))
    } finally {
        unsubscribe()
    }
}

/** AskSessionTest's bubble wait, bounded to 5 seconds. */
fun awaitBubble(chat: ChatSession, predicate: (ChatItem) -> Boolean) {
    val latch = CountDownLatch(1)
    val unsubscribe = chat.addChangeListener {
        if (chat.snapshot().any(predicate)) latch.countDown()
    }
    try {
        assertTrue("expected bubble did not arrive", latch.await(5, SECONDS))
    } finally {
        unsubscribe()
    }
}

/** TurnLifecycleTest's listener-driven wait with a 10 s deadline and 50 ms re-poll. */
fun awaitStatePolling(chat: ChatSession, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + SECONDS.toNanos(10)
    val latch = CountDownLatch(1)
    val unsubscribe = chat.addChangeListener { if (predicate()) latch.countDown() }
    try {
        while (!predicate()) {
            assertTrue("state never settled", System.nanoTime() < deadline)
            latch.await(50, MILLISECONDS)
        }
    } finally {
        unsubscribe()
    }
}
