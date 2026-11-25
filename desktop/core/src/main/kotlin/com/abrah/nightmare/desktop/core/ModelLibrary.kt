package com.abrah.nightmare.desktop.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * ⭐⭐ **The local model library**: where weights live, and what is actually in
 * there.
 *
 * Three lists, and the third and second are the point. A directory listing tells
 * a person nothing about whether a 4 GB file finished downloading; a scan says
 * "these are models, this one is not what its name claims, and this one is not a
 * format this build reads" -- so a broken download is a message, never a
 * ten-minute engine load that ends in a crash or a noise image.
 *
 * ⚠ The root is `--dir`, else `$NIGHTMARE_MODELS`, else the shared cache. The
 * shared cache is deliberate: several worktrees and the CI baseline read the
 * same 4 GB file instead of each downloading their own (see `desktop/docs/models.md`).
 */
object ModelLibrary {

    /** Overrides the library root. */
    const val ENV_MODELS: String = "NIGHTMARE_MODELS"

    /** The default root, relative to the user's home: mirrors the engine cache. */
    const val DEFAULT_SUBPATH: String = ".cache/nightmare-desktop/models"

    /** A model file whose header was read. */
    data class Found(val path: Path, val bytes: Long, val contents: ModelFile.Contents)

    /** A file that claims a model extension but is not a readable model. */
    data class Rejected(val path: Path, val bytes: Long, val reason: String)

    /** What one directory tree holds. */
    data class Scan(
        val root: Path,
        val found: List<Found>,
        val rejected: List<Rejected>,
        /** Files whose extension is not a format this build reads. */
        val others: List<Path>,
    )

    /** The shared cache: `~/.cache/nightmare-desktop/models`. */
    fun defaultRoot(): Path = Paths.get(System.getProperty("user.home")).resolve(DEFAULT_SUBPATH)

    /**
     * Where the library is: [dirArg] when given, else [ENV_MODELS], else
     * [defaultRoot]. A blank value is ignored rather than read as "the current
     * directory", which is how a script with an unset variable would otherwise
     * scan whatever it happened to be run from.
     */
    fun resolve(dirArg: String?, env: Map<String, String>): Path {
        dirArg?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        env[ENV_MODELS]?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        return defaultRoot()
    }

    /**
     * Scans [root] recursively for model files.
     *
     * ⚠ Throws [IOException] when [root] is not a directory -- an empty library
     * and a mistyped path are different answers, and only one of them is worth
     * an exit code of its own.
     */
    fun scan(root: Path): Scan {
        val absolute = root.toAbsolutePath()
        if (!Files.exists(absolute)) {
            throw IOException(
                "'$absolute' does not exist. Download the models there, or point " +
                    "\$$ENV_MODELS / --dir at the directory that holds them (see desktop/docs/models.md)",
            )
        }
        if (!Files.isDirectory(absolute)) {
            throw IOException("'$absolute' is not a directory; \$$ENV_MODELS and --dir name the directory that holds models")
        }

        val found = ArrayList<Found>()
        val rejected = ArrayList<Rejected>()
        val others = ArrayList<Path>()

        Files.walkFileTree(absolute, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // ⚠ Hidden files are not models: `.gitkeep`, editor droppings and
                // the partial-download markers a browser leaves would otherwise
                // bury the listing in noise nobody asked to see.
                if (file.fileName.toString().startsWith(".")) return FileVisitResult.CONTINUE
                val bytes = runCatching { Files.size(file) }.getOrDefault(0L)
                if (ModelFile.formatOf(file) == null) {
                    others.add(file)
                    return FileVisitResult.CONTINUE
                }
                try {
                    found += Found(file, bytes, ModelFile.read(file))
                } catch (e: IOException) {
                    rejected += Rejected(file, bytes, e.message ?: "cannot be read")
                }
                return FileVisitResult.CONTINUE
            }

            // ⚠ A directory we cannot enter is reported, not swallowed: silently
            // missing models is exactly the confusion this scan exists to remove.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                rejected += Rejected(file, 0L, exc.message ?: "cannot be read")
                return FileVisitResult.CONTINUE
            }
        })

        fun <T> List<T>.sortedByPath(pathOf: (T) -> Path): List<T> =
            sortedBy { pathOf(it).toString() }

        return Scan(
            root = absolute,
            found = found.sortedByPath { it.path },
            rejected = rejected.sortedByPath { it.path },
            others = others.sortedBy { it.toString() },
        )
    }
}
