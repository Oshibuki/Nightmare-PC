package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Guard for `desktop/engine/PATCHES.md`: the per-file inventory of the mobile
 * patch library (ticket 09).
 *
 * The inventory is the only place that says what happens to each of the ~190
 * `.patch` files in `backend-patches/`, so the failure mode worth testing is not
 * "a row is wrong" — no test can read the intent behind a row — but "a file has
 * no row at all". A new patch added without a verdict is invisible to every
 * human review of a 190-row table; here it is a red test.
 *
 * Asserts the document's contract only: one row per patch file, one verdict per
 * row, and no rows for files that do not exist. How the table is worded, ordered
 * or sectioned is not this test's business.
 */
class PatchInventoryTest {

    private val repoRoot: File = File(
        System.getProperty("nightmare.repoRoot")
            ?: error("nightmare.repoRoot is not set -- run this test through Gradle, not an IDE run configuration"),
    )

    private val patchRoot = File(repoRoot, "backend-patches")

    private val inventoryFile = File(repoRoot, "desktop/engine/PATCHES.md")

    /** The four verdicts, spelled exactly as the inventory writes them. */
    private val verdicts = listOf(
        "`portable`",
        "`excluded-hexagon`",
        "`excluded-mobile-gpu`",
        "`deferred`",
    )

    /** Every patch file, as a path relative to `backend-patches/`, e.g. `dit/004-....sdcpp.patch`. */
    private fun patchFiles(): List<String> {
        assertTrue("no patch library at ${patchRoot.absolutePath}", patchRoot.isDirectory)
        Files.walk(patchRoot.toPath()).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".patch") }
                .map { patchRoot.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                .sorted()
                .toList()
        }
    }

    private fun inventoryLines(): List<String> {
        assertTrue(
            "the patch inventory is missing: ${inventoryFile.absolutePath}",
            inventoryFile.isFile,
        )
        return inventoryFile.readLines()
    }

    /** The inventory row whose first cell is this patch, e.g. ``| `dit/004-....sdcpp.patch` | ...``. */
    private fun rowsFor(rel: String, lines: List<String>): List<String> =
        lines.filter { it.startsWith("| `$rel` |") }

    @Test
    fun `every patch in the library has exactly one row`() {
        val lines = inventoryLines()
        val missing = mutableListOf<String>()
        val duplicated = mutableListOf<String>()

        for (rel in patchFiles()) {
            when (rowsFor(rel, lines).size) {
                0 -> missing += rel
                1 -> Unit
                else -> duplicated += rel
            }
        }

        assertEquals("patch files with no row in PATCHES.md", emptyList<String>(), missing)
        assertEquals("patch files with more than one row in PATCHES.md", emptyList<String>(), duplicated)
    }

    @Test
    fun `every row names exactly one verdict`() {
        val lines = inventoryLines()
        val wrong = patchFiles().mapNotNull { rel ->
            val row = rowsFor(rel, lines).singleOrNull() ?: return@mapNotNull null
            val named = verdicts.filter { row.contains(it) }
            if (named.size == 1) null else "$rel names ${named.size}: $named"
        }

        assertEquals("rows that do not name exactly one verdict", emptyList<String>(), wrong)
    }

    @Test
    fun `the inventory has no rows for files that do not exist`() {
        val lines = inventoryLines()
        val onDisk = patchFiles().toSet()
        // Rows that name a literal file. A first cell containing `*` is a routing
        // pattern (`*.sdcpp.patch`), not a claim about one file, so it is not a row.
        val rowPaths = lines
            .filter { it.startsWith("| `") }
            .map { it.removePrefix("| `").substringBefore('`') }
            .filter { it.endsWith(".patch") && '*' !in it }

        assertEquals(
            "inventory rows that name no patch file under backend-patches/",
            emptyList<String>(),
            rowPaths.filter { it !in onDisk },
        )
        // Every file has one row (asserted above), so the row count is the file count.
        assertEquals("rows vs patch files on disk", onDisk.size, rowPaths.size)
    }
}
