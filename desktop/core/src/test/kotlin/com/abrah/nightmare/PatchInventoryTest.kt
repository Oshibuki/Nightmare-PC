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

    /** Rows of the ticket-09 inventory table; the ticket-22 section has its own rows for the same paths. */
    private fun inventoryTableLines(): List<String> = inventoryLines().takeWhile { it != conclusionHeading }

    @Test
    fun `every patch in the library has exactly one row`() {
        val lines = inventoryTableLines()
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
        val lines = inventoryTableLines()
        val wrong = patchFiles().mapNotNull { rel ->
            val row = rowsFor(rel, lines).singleOrNull() ?: return@mapNotNull null
            val named = verdicts.filter { row.contains(it) }
            if (named.size == 1) null else "$rel names ${named.size}: $named"
        }

        assertEquals("rows that do not name exactly one verdict", emptyList<String>(), wrong)
    }

    @Test
    fun `the inventory has no rows for files that do not exist`() {
        val lines = inventoryTableLines()
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

    /** The ticket-22 section that settles each deferred patch, separate from the ticket-09 verdict. */
    private val conclusionHeading = "## Deferred patch verdicts"

    /** The three desktop conclusions, spelled exactly as the section writes them. */
    private val conclusions = listOf("`applicable`", "`excluded`", "`conditional`")

    /**
     * One conclusion per deferred patch, decided from the patch body and the pinned engine,
     * not derived from PATCHES.md. The expected value is the independent source of truth:
     * if the document drifts, this comparison goes red.
     */
    private val expectedConclusions = mapOf(
        "dit/007-qwen21-graph-cut.sdcpp.patch" to "`conditional`",
        "dit/008-anima-fused-ops.sdcpp.patch" to "`conditional`",
        "dit/009-wan-fused-ops.sdcpp.patch" to "`excluded`",
        "dit/029-ideogram4-fused-rope.sdcpp.patch" to "`excluded`",
        "dit/035-moss-fused-rope.sdcpp.patch" to "`excluded`",
        "dit/037-iris-fused-rope.sdcpp.patch" to "`excluded`",
        "dit/043-flux3-fused-rope.sdcpp.patch" to "`excluded`",
        "dit/055-flux3-graph-cut.sdcpp.patch" to "`excluded`",
        "dit/060-graph-cut-use-counts.sdcpp.patch" to "`conditional`",
        "dit/071-minimax-h3-fused-partial-rope.sdcpp.patch" to "`excluded`",
        "dit/074-minimax-h3-vae-fused-rope.sdcpp.patch" to "`excluded`",
        "dit/093-moss-lora-fused-qkv.sdcpp.patch" to "`conditional`",
        "dit/094-lowres-spectrum-spike.sdcpp.patch" to "`conditional`",
        "dit/095-keep-runners-spike.sdcpp.patch" to "`conditional`",
        "dit/spike-dit-no-fa.patch" to "`excluded`",
        "dit/spike-nan-probe.sdcpp.patch" to "`conditional`",
        "dit/spike-sd-cli.patch" to "`excluded`",
        "dit/spike-segment-timing.sdcpp.patch" to "`conditional`",
    )

    /** Cells of each `| `path` | `conclusion` | reason |` row in the ticket-22 section. */
    private fun conclusionRows(): List<List<String>> {
        val lines = inventoryLines()
        val start = lines.indexOf(conclusionHeading)
        assertTrue("PATCHES.md has no '$conclusionHeading' section", start >= 0)
        return lines.drop(start + 1)
            .takeWhile { !it.startsWith("## ") }
            .filter { it.startsWith("| `") && ".patch`" in it }
            .map { line -> line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
    }

    private fun deferredPatches(): List<String> {
        val lines = inventoryTableLines()
        return patchFiles().filter { rel ->
            rowsFor(rel, lines).singleOrNull()?.contains(verdicts[3]) ?: false
        }
    }

    @Test
    fun `each deferred patch has exactly one desktop conclusion row`() {
        val deferred = deferredPatches()
        val paths = conclusionRows().map { it[0].removeSurrounding("`") }
        assertEquals("deferred patches with no conclusion row", emptyList<String>(), deferred - paths.toSet())
        assertEquals(
            "conclusion rows for patches that are not deferred",
            emptyList<String>(),
            paths - deferred.toSet(),
        )
        assertEquals(
            "patches with more than one conclusion row",
            emptyList<String>(),
            paths.groupingBy { it }.eachCount().filterValues { it != 1 }.keys.toList(),
        )
    }

    @Test
    fun `each desktop conclusion is unique and carries a reason`() {
        val wrong = conclusionRows().mapNotNull { cells ->
            val given = conclusions.filter { it == cells.getOrNull(1) }
            val reason = cells.getOrNull(2).orEmpty()
            if (given.size != 1 || reason.isBlank()) "$cells" else null
        }
        assertEquals("rows without exactly one conclusion and a reason", emptyList<String>(), wrong)
        // A repeated boilerplate reason is not a per-patch verdict: it is the old generic
        // "deferred to Phase 1" remark wearing a new column.
        assertEquals(
            "identical reasons across deferred patches",
            emptyList<String>(),
            conclusionRows().map { it[2] }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList(),
        )
    }

    @Test
    fun `desktop conclusions match the recorded evidence`() {
        val actual = conclusionRows().associate { it[0].removeSurrounding("`") to it[1] }
        assertEquals("ticket-22 desktop conclusions", expectedConclusions, actual)
    }
}
