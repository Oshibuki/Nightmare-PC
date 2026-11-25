package com.abrah.nightmare.desktop.gui

/**
 * Observable 运行态 for one GUI-owned CLI run (ADR-0009/0010). The GUI owns the
 * workflow and its results; the CLI is a stateless child process. Everything
 * here is plain JVM state so shells and external tools can read the same run
 * without a UI test framework.
 */

/** Lifecycle of a generation: 空闲 / 运行中 / 成功 / 失败 / 取消. */
enum class RunStatus { IDLE, RUNNING, SUCCESS, FAILURE, CANCELLED }

/**
 * 进度事件 parsed from one CLI stdout progress line:
 * `[i/N] seed=S -> path`. [path] is the CLI's target, not necessarily an
 * existing file until the process exits successfully.
 */
data class ProgressEvent(
    val index: Int,
    val total: Int,
    val seed: Long,
    val path: String,
)

/** A PNG that survived a successful run, together with the settings that made it. */
data class ResultArtifact(
    val path: String,
    val params: Map<String, String> = emptyMap(),
)

/** Everything a shell (or a tool) can observe about the current run. */
data class RunSnapshot(
    val status: RunStatus = RunStatus.IDLE,
    val progress: List<ProgressEvent> = emptyList(),
    val log: List<String> = emptyList(),
    val results: List<ResultArtifact> = emptyList(),
    val error: String? = null,
    val command: List<String> = emptyList(),
)

/**
 * How the GUI finds and drives the stateless CLI. The executable is resolved
 * from `NIGHTMARE_GUI_CLI` by default; tests inject a fake runner explicitly.
 * `timeoutMillis` bounds the child so a stuck inference cannot hang the shell.
 */
data class RunConfig(
    val cliExecutable: String = System.getenv("NIGHTMARE_GUI_CLI") ?: "nightmare-desktop",
    val modelPath: String? = System.getenv("NIGHTMARE_GUI_MODEL"),
    val outputPath: String = "nightmare-output.png",
    val batch: Int = 1,
    val timeoutMillis: Long = 600_000L,
    val env: Map<String, String> = emptyMap(),
)

/** The one CLI stdout line format this GUI depends on; see ADR-0010. */
internal object ProgressLines {
    private val pattern = Regex("""^\[(\d+)/(\d+)\] seed=(-?\d+) -> (.*)$""")

    fun parse(line: String): ProgressEvent? {
        val match = pattern.matchEntire(line) ?: return null
        return ProgressEvent(
            index = match.groupValues[1].toInt(),
            total = match.groupValues[2].toInt(),
            seed = match.groupValues[3].toLong(),
            path = match.groupValues[4],
        )
    }
}
