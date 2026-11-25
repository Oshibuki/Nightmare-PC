# 17: 外部工具驱动接口预留

**What to build:** 作为维护者，工作流能被外部工具用一组明确命令创建和修改，为将来对话模式留出接缝；本工单不实现 LLM、provider 或密钥，也不改变工作流的持久化格式。

**Blocked by:** 16 — 工作流保存、载入与版本校验.

**Status:** resolved

- [x] 提供等价的新建工作流、设置参数、添加节点、连线与运行的命令面，作用于同一份工作流
- [x] 命令面由 GUI 状态与运行态承载，不新增第二套持久化格式
- [x] 假工具调用能搭出一条可运行的 txt2img 工作流并触发运行
- [x] 不引入 LLM provider、密钥管理、HTTP 服务或对话界面
- [x] JVM 测试覆盖工具调用链的成功与被拒绝路径
- [x] 符合 ADR-0008 中「对话模式后置但预留接口」的约定

## Comments

- 2026-11-26：分支 `gui-ticket-17`，代码提交 `7dad320`「Add external-tool command surface over the shared workflow session」。仅改独占文件：`desktop/gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/DefaultModeState.kt`（+6/-3：实现 `WorkflowRunControl`、暴露 `sharedSession`）、新增同包 `ToolInterface.kt`（113 行）、新增 `ToolInterfaceTest.kt`（7 个 JUnit4 用例）。未改 `RunState.kt`、`WorkflowRunner.kt`、`DefaultModeScreen.kt`、`WorkflowRunnerTest.kt`、`Main.kt`、`desktop/core/`、`app/`、`backend-patches/`、README 或共享构建脚本。
- 2026-11-26：命令面 —— `ToolCommand` = `NewWorkflow` / `AddNode(id,type,x,y,params)` / `SetParameter(nodeId,key,value)` / `Connect(fromNode,toNode,toPort,fromPort?)` / `Run`；`ToolInterface.dispatch(command): ToolResult`，结果为 `Ok(summary)` 或 `Rejected(reason)`。便捷构造 `ToolInterface(defaultModeState)` 复用其 `sharedSession` 与 run control；测试可注入 `WorkflowSession` + `WorkflowRunControl`。`NewWorkflow` 直接调用 `WorkflowPersistence.newWorkflow()`，节点安全 id 复用 `isSafeNodeId`，节点创建复用 `DesktopNodeTypes.registry[type].createNode()`，连线复用 `Workflow.connect()`，运行复用 `triggerRun()` —— 没有第二套格式或图模型。
- 2026-11-26：TDD 红-绿-重构 —— 基线 `:gui:test` 11/11 PASSED（`/tmp/ticket17-baseline.log`）；红 1（`ToolInterface`/`ToolCommand`/`ToolResult` 未定义、编译失败，`/tmp/ticket17-red1.log`）→ 绿 1（`/tmp/ticket17-green1.log`、`/tmp/ticket17-green1b.log`）；红 2（`Connect` 未定义，`/tmp/ticket17-red2.log`）→ 绿 2（4 tests，`/tmp/ticket17-green2.log`）；红 3（`Run` 未定义，`/tmp/ticket17-red3.log`）→ 绿 3（6 tests，`/tmp/ticket17-green3.log`）。重构把 `NewWorkflow` 改为委托 `WorkflowPersistence.newWorkflow()`，并补一条「工具搭出的工作流 save/load 往返相等」用例。
- 2026-11-26：全量验证 —— `JAVA_HOME=/home/chjlxxc/Android/jdk-17 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m --no-build-cache cleanTest build` → `BUILD SUCCESSFUL in 41s`（`/tmp/ticket17-full-build.log`）。`ToolInterfaceTest` 7/7、`DefaultModeStateTest` 7/7、`WorkflowRunnerTest` 6/6、`core` 与 `cli` 全量用例均 PASSED（含真实 SD1.5 img2img 用例）。假工具链用例用真实 POSIX shell 假 CLI，从空工作流搭出 prompt→sampler→output 并触发 `Run`，断言 `SUCCESS`、1 条进度事件、1 个真实 PNG 产物、seed=11，且工具设置的 prompt 出现在子进程 argv 中。

- 2026-11-26：收口前 `git merge desktop-migration` → 「已经是最新的」（集成分支 tip 仍为 `998a027`，`/tmp/ticket17-merge.log`）；合并后再次全量 `--no-build-cache cleanTest build` → `BUILD SUCCESSFUL in 26s`，`ToolInterfaceTest` 7/7 PASSED（`/tmp/ticket17-post-merge-build.log`）。

## 偏离与风险（未验证项不夸大）

- `Run` 按工单要求复用 `DefaultModeState.triggerRun()`，该方法阻塞直到子进程退出；将来对话 effort 若需要非阻塞工具返回，需另做异步接线，本工单未改运行态实现。
- 「运行中重复触发被拒」由注入的 `WorkflowRunControl` 桩报告 `RUNNING` 来确定性验证；真实进程层的重复运行保护由既有 `WorkflowRunnerTest`（本工单未改）覆盖：`a run started while another is active is rejected without changing the run state` PASSED。
- 命令面尚未接入任何对话 UI 或 HTTP 入口 —— 这是 ADR-0008 的范围边界，不是遗漏；未引入 LLM provider、密钥管理、HTTP 服务或对话界面。
- 仅在 Linux 上验证 JVM/Gradle 测试；未验证 Windows/macOS 编译（本阶段仅要求 GUI 模块编译通过，且本轮未做跨平台编译）。
- 未做真实 SD1.5 端到端冒烟（本工单是接口预留，真引擎冒烟属于验收脚本 effort；假 CLI 只验证进程接缝与本命令链）。
