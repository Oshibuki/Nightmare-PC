package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Guard for `desktop/engine/SDXL.md`: the per-file **application record** of
 * the SDXL-related patches in the mobile library. `PatchInventoryTest` guards
 * the desktop verdict of every patch; this one guards the narrower promise
 * ticket 14 makes -- that every patch about SDXL was individually considered
 * and says applied or skipped, with a reason, before the ticket can close.
 *
 * Only the document's contract is tested: one row per SDXL patch, one decision
 * per row, a non-empty reason. Whether a decision is the right one is a review
 * question, not something a test can read out of prose.
 */
class SdxlPatchRecordTest {

    private val repoRoot: File = File(
        System.getProperty("nightmare.repoRoot")
            ?: error("nightmare.repoRoot is not set -- run this test through Gradle, not an IDE run configuration"),
    )

    private val patchRoot = File(repoRoot, "backend-patches")

    private val recordFile = File(repoRoot, "desktop/engine/SDXL.md")

    /** A patch is SDXL-related if its name or a `+++ b/` target says SDXL. */
    private fun sdxlPatches(): List<String> {
        assertTrue("no patch library at ${patchRoot.absolutePath}", patchRoot.isDirectory)
        Files.walk(patchRoot.toPath()).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".patch") }
                .map { patchRoot.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                .filter { rel ->
                    rel.contains("sdxl", ignoreCase = true) ||
                        File(patchRoot, rel).useLines { lines ->
                            lines.any {
                                it.startsWith("+++ b/") && it.contains("sdxl", ignoreCase = true)
                            }
                        }
                }
                .sorted()
                .toList()
        }
    }

    private fun recordLines(): List<String> {
        assertTrue(
            "the SDXL application record is missing: ${recordFile.absolutePath}",
            recordFile.isFile,
        )
        return recordFile.readLines()
    }

    /** The record row whose first cell is this patch. */
    private fun rowsFor(rel: String, lines: List<String>): List<String> =
        lines.filter { it.startsWith("| `$rel` |") }

    /** Cells of a row, without the leading/trailing pipes. */
    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    @Test
    fun `every SDXL patch has exactly one row in the application record`() {
        val lines = recordLines()
        val patches = sdxlPatches()
        val missing = mutableListOf<String>()
        val duplicated = mutableListOf<String>()

        for (rel in patches) {
            when (rowsFor(rel, lines).size) {
                0 -> missing += rel
                1 -> Unit
                else -> duplicated += rel
            }
        }
        assertTrue("but the library has SDXL patches: $patches", patches.isNotEmpty())
        assertEquals("SDXL patches with no row in SDXL.md", emptyList<String>(), missing)
        assertEquals("SDXL patches with more than one row", emptyList<String>(), duplicated)
    }

    @Test
    fun `every SDXL row records a decision and a reason`() {
        val lines = recordLines()
        val bad = sdxlPatches().mapNotNull { rel ->
            val row = rowsFor(rel, lines).singleOrNull() ?: return@mapNotNull null
            val columns = cells(row)
            if (columns.size < 5) return@mapNotNull "$rel has ${columns.size} cells, expected 5"
            val decision = columns[3].removePrefix("`").removeSuffix("`")
            val reason = columns[4].trim()
            when {
                decision !in setOf("applied", "skipped") -> "$rel decision is '$decision'"
                reason.isEmpty() || reason == "-" -> "$rel has no reason"
                else -> null
            }
        }
        assertEquals("rows without a decision or a reason", emptyList<String>(), bad)
    }

    @Test
    fun `the application record has no rows for patches that do not exist`() {
        val lines = recordLines()
        val onDisk = sdxlPatches().toSet()
        val rowPaths = lines
            .filter { it.startsWith("| `") }
            .map { it.removePrefix("| `").substringBefore('`') }
            .filter { it.endsWith(".patch") && '*' !in it }

        assertEquals(
            "record rows that are not SDXL patches in the library",
            emptyList<String>(),
            rowPaths.filter { it !in onDisk },
        )
        assertEquals("record rows vs SDXL patches on disk", onDisk.size, rowPaths.size)
    }
}
