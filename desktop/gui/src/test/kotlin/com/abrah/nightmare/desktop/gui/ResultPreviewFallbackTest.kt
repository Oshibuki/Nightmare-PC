package com.abrah.nightmare.desktop.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A result PNG can disappear or be undecodable between the run finishing and
 * the preview rendering. The preview must fall back to its text information
 * instead of crashing the shell; these tests pin that behavior at the seam
 * where the file is read and decoded (no UI test framework needed).
 */
class ResultPreviewFallbackTest {

    @Test
    fun `a missing result file falls back instead of throwing`() {
        assertNull(loadPreviewSafely("/nonexistent/result-preview.png") { it.size })
    }

    @Test
    fun `a blank path never reaches the decoder`() {
        var decoded = false
        val result = loadPreviewSafely("") { bytes -> decoded = true; bytes.size }

        assertNull(result)
        assertFalse(decoded)
    }

    @Test
    fun `a decoder failure falls back instead of throwing`() {
        val file = tempResultFile(byteArrayOf(1, 2, 3))
        try {
            assertNull(loadPreviewSafely(file.absolutePath) { throw IllegalArgumentException("bad png") })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a decodable file returns the decoded value`() {
        val file = tempResultFile(byteArrayOf(1, 2, 3, 4, 5))
        try {
            assertEquals(5, loadPreviewSafely(file.absolutePath) { it.size })
        } finally {
            file.delete()
        }
    }

    private fun tempResultFile(bytes: ByteArray): File =
        File.createTempFile("result-preview", ".png").apply { writeBytes(bytes) }
}
