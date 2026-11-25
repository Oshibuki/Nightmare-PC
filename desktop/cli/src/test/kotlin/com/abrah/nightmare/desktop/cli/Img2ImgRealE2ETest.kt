package com.abrah.nightmare.desktop.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The same CLI seam as [Img2ImgTest], but with the real cached SD1.5 weights
 * and the real shared `sd-cli` binary. Per the spec this is **not** a hard
 * prerequisite for closing the ticket: [assumeTrue] skips it when the cache is
 * absent, and the fake-engine tests carry the contract on their own.
 *
 * When it does run it checks the same observable property a person would:
 * with a fixed seed, strength 0 reproduces the source image and strength 1
 * changes it substantially. Nothing here inspects the engine's internals.
 */
class Img2ImgRealE2ETest {

    private val out = StringBuilder()
    private val err = StringBuilder()

    private lateinit var dir: File
    private lateinit var model: File
    private lateinit var engine: File
    private lateinit var initPng: File

    @Before
    fun setUp() {
        val home = System.getProperty("user.home")
        val modelsRoot = System.getenv("NIGHTMARE_MODELS")?.takeIf { it.isNotBlank() }
            ?: File(home, ".cache/nightmare-desktop/models").path
        model = File(modelsRoot, "sd15/v1-5-pruned-emaonly.safetensors")
        engine = System.getenv("NIGHTMARE_SD_BIN")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(home, ".cache/nightmare-desktop/engine/sd-cli")

        assumeTrue("real SD1.5 E2E needs the cached model at $model", model.isFile)
        assumeTrue("real SD1.5 E2E needs a built engine at $engine", engine.isFile && engine.canExecute())

        dir = Files.createTempDirectory("nm-img2img-real-e2e").toFile()
        initPng = File(dir, "source.png")
        val size = 256 // a multiple of 8: the engine's UNet downsamples by 8
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, (((x * 3) and 0xFF) shl 16) or (((y * 5) and 0xFF) shl 8) or ((x + y) and 0xFF))
            }
        }
        ImageIO.write(image, "png", initPng)
    }

    private fun generate(strength: String, target: File): Long {
        out.setLength(0)
        err.setLength(0)
        val start = System.nanoTime()
        val code = Cli.run(
            listOf(
                "img2img",
                "--model", model.absolutePath,
                "--init-img", initPng.absolutePath,
                "--out", target.absolutePath,
                "--engine", engine.absolutePath,
                "--prompt", "a red fox",
                "--width", "256", "--height", "256",
                "--steps", "2",
                "--seed", "42",
                "--strength", strength,
            ),
            out,
            err,
        )
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals("strength $strength, stderr: $err", Cli.EXIT_OK, code)
        assertTrue("strength $strength must leave a message on stdout: $out", out.isNotEmpty())
        val decoded = ImageIO.read(target) ?: throw AssertionError("ImageIO could not decode $target")
        assertEquals(256, decoded.width)
        assertEquals(256, decoded.height)
        return millis
    }

    /** Mean absolute per-channel RGB difference in [0, 255]; 0 means identical pixels. */
    private fun meanAbsDiff(a: File, b: File): Double {
        val left = ImageIO.read(a) ?: throw AssertionError("ImageIO could not decode $a")
        val right = ImageIO.read(b) ?: throw AssertionError("ImageIO could not decode $b")
        var sum = 0L
        var channels = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                val l = left.getRGB(x, y) and 0xFFFFFF
                val r = right.getRGB(x, y) and 0xFFFFFF
                sum += kotlin.math.abs((l shr 16 and 0xFF) - (r shr 16 and 0xFF)).toLong()
                sum += kotlin.math.abs((l shr 8 and 0xFF) - (r shr 8 and 0xFF)).toLong()
                sum += kotlin.math.abs((l and 0xFF) - (r and 0xFF)).toLong()
                channels += 3
            }
        }
        return sum.toDouble() / channels
    }

    @Test
    fun realSd15StrengthZeroPreservesTheSourceAndStrengthOneChangesIt() {
        val zero = File(dir, "zero.png")
        val one = File(dir, "one.png")

        val zeroMs = generate("0", zero)
        val oneMs = generate("1", one)

        val diffZero = meanAbsDiff(initPng, zero)
        val diffOne = meanAbsDiff(initPng, one)
        println(
            "[real SD1.5 img2img] seed=42 steps=2 size=256x256 " +
                "strength0=${zeroMs}ms meanAbsDiff=$diffZero " +
                "strength1=${oneMs}ms meanAbsDiff=$diffOne",
        )
        assertTrue(
            "strength 0 should preserve the source (VAE round-trip only), got mean diff $diffZero",
            diffZero < 25.0,
        )
        assertTrue(
            "strength 1 should change the pixels well beyond the strength-0 round-trip: " +
                "diffOne=$diffOne, diffZero=$diffZero",
            diffOne > 25.0 && diffOne > diffZero + 10.0,
        )
    }
}
