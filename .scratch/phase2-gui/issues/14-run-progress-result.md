# 14: 运行——子进程、进度事件与成功产物

**What to build:** 作为桌面用户，我点运行后 GUI 以子进程驱动 CLI，逐行看到每张图的进度事件与引擎日志；成功生成的 PNG 进入结果面板，我立刻知道哪张图对应哪次设置。

**Blocked by:** 13 — 默认模式——加载内置工作流并编辑参数.

**Status:** resolved

- [x] GUI 以子进程启动 CLI 并持有进程句柄，不直接调用进程内的纯函数入口
- [x] stdout 的进度行被解析为进度事件（序号、总数、seed、目标路径）
- [x] 引擎原始输出被转发到同一日志面板，与进度事件并存
- [x] 每张产物恰好对应一条进度事件，并由测试断言引擎输出仍被转发
- [x] 成功退出后，产物路径与参数进入结果面板；只有成功产物入面板
- [x] CLI 保持无状态，工作流由 GUI 投影为命令参数
- [x] 假 CLI runner 的端到端用例覆盖 happy path，命令带超时与日志

## Comments

- 2026-11-26：分支 `gui-ticket-14`（隔离 worktree `.codebuddy/worktrees/gui-14`）；实现提交 `417bbc0`「Run workflow through a GUI-owned CLI child process」，合并 `desktop-migration` 的提交 `cb1eacf`。仅 5 个源文件 + 本工单跟踪文件，全部在 `desktop/gui/` 与本工单范围；未改 `desktop/core/`、`app/`、`backend-patches/`、README、共享构建脚本。
- 2026-11-26：新增 `WorkflowRunner.kt`（`ProcessBuilder` 子进程 + 句柄 + 看门狗超时 + stdout/stderr 双流逐行日志 + 进度行解析 + 成功退出才收集产物）与 `RunState.kt`（`RunStatus`/`ProgressEvent`/`ResultArtifact`/`RunSnapshot`/`RunConfig`）；`DefaultModeState` 暴露 `triggerRun()`/`runSnapshot()`/`runHandle()`；`DefaultModeScreen` 的运行按钮改为可用并在运行中禁用，新增进度/日志/结果面板。
- 2026-11-26：TDD 红-绿记录——红 1（runner 类型不存在，编译失败 `/tmp/gui14-red-1.log`）→ 绿 1（`/tmp/gui14-green-1c.log`）→ 红 2（日志未转发 `/tmp/gui14-red-2.log`）→ 绿 2（`/tmp/gui14-green-2.log`）→ 红 3（结果面板为空 `/tmp/gui14-red-3.log`）→ 绿 3（`/tmp/gui14-green-3.log`）→ 红 4（状态未接线 `/tmp/gui14-red-4.log`）→ 绿 4（`/tmp/gui14-green-4b.log`）。`--no-build-cache cleanTest :gui:build` → `BUILD SUCCESSFUL`（`/tmp/gui14-clean-build.log`，13/13 GUI 用例）；合并后全量 `--no-build-cache cleanTest build` → `BUILD SUCCESSFUL in 39s`（`/tmp/gui14-postmerge-build.log`），GUI 13/13、core 全量（含工单 16 的 `WorkflowPersistenceTest` 12/12）、CLI 全量（含真实 SD1.5 冒烟）均 PASSED。
- 2026-11-26：`WorkflowRunnerTest` 的假 CLI 是真实 shell 脚本（POSIX，Windows 上 `Assume` 跳过），把 argv 落到文件供断言；用 `CountDownLatch` 断言「运行中拒绝第二次运行」，全部等待有超时并留存日志。

## 供工单 15/18 复用的运行态 API

- 同包 `com.abrah.nightmare.desktop.gui`：`WorkflowRunner(config).run(workflow): Boolean`（false = 已有运行）、`snapshot(): RunSnapshot`、`isRunning()`、`processHandle(): Process`、`addChangeListener { } -> () -> Unit`。
- `RunSnapshot.status` 为 `IDLE/RUNNING/SUCCESS/FAILURE/CANCELLED`；`progress` 为解析出的 `ProgressEvent(index, total, seed, path)`；`log` 是 stdout+stderr 原始行；`results` 只在成功退出后填充；`command` 为投影出的完整命令行（含可执行文件）。
- `DefaultModeState` 同包暴露 `triggerRun()`/`runSnapshot()`/`runHandle()`，并把 runner 变更接到 `addChangeListener`，高级模式与外部工具可读同一份运行态。

## 偏离与风险（未验证项不夸大）

- 本工单只交付 happy path 与进度/日志/结果呈现。**取消按钮、关窗 `destroy` 子进程、超时/缺模型/stderr/非零退出的完整失败 UI 留给工单 15**；此处只提供 `processHandle()`、看门狗超时终止与基础 `FAILURE` 状态（含可读原因），未声称工单 15 的验收已达成。
- 结果面板按进度行的目标路径检查文件存在性，且只在退出码 0 时填充；CLI 先打印进度行再生成 PNG，因此存在性在进程退出后统一判定，而不是解析时判定。
- 模型与输出路径由 `RunConfig` 提供（默认读取 `NIGHTMARE_GUI_CLI`/`NIGHTMARE_GUI_MODEL`，默认可执行名 `nightmare-desktop`），本工单未做模型选择 UI 或工作流保存接线（后两者属工单 16/19）。真实 CLI 定位文档由工单 19 负责。
- GUI 外壳只做 Compose 编译校验，未引入 UI 测试框架、未启动真实窗口验证交互（spec 明确不新增该接缝）；runner 的监听器从子进程读取线程回调，Compose 状态跨线程更新在真实窗口下的表现未实机验证。
- 假 CLI 端到端未使用真实引擎；真实 SD1.5 冒烟由 `:cli` 测试覆盖，GUI 直接驱动真实 CLI 的端到端未在工单 14 验证。
- 未验证 Windows/macOS（本轮只要求编译通过，且本机只有 Linux）。
