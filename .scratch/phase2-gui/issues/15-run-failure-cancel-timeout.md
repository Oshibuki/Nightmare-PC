# 15: 失败、取消与超时的运行态收口

**What to build:** 作为桌面用户，运行失败、我主动取消或超时之后，能看到可读原因；工作流保持可编辑，结果面板不留下半成品，也不会有后台孤儿进程。

**Blocked by:** 14 — 运行——子进程、进度事件与成功产物.

**Status:** resolved

- [x] 非零退出码、stderr 报错、超时与缺模型统一转成运行态失败
- [x] 错误条显示退出码与首行原因，完整信息进入日志
- [x] 运行中可取消；取消与关闭窗口都结束子进程
- [x] GUI 侧提供可配置超时（默认十分钟），超时结束子进程并置失败
- [x] 失败或取消产生的临时产物被删除，结果面板没有半成品
- [x] 当前工作流保持不变，失败后可直接修改重跑
- [x] 假 CLI runner 对四类失败信号逐一断言，命令带超时与日志

## Comments

### 提交
- `99ff38b` Close run state on failure, cancel and timeout in default shell（4 个自有文件，+428/-26）。
- `3d3c7a4` Merge desktop-migration into gui-ticket-15 after ticket 17（ort 自动合并，无冲突）。

### 真实命令与结果
- `timeout 900 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m :gui:test --tests 'com.abrah.nightmare.desktop.gui.WorkflowRunnerTest'`：
  - 红：`/tmp/gui15-red.log`，`FailureKind` / `RunSnapshot.failureKind` / `GuiRunSettings` / `WorkflowRunner.cancelActiveProcess` 未解析，编译失败。
  - 绿：`/tmp/gui15-green4.log`，14/14 WorkflowRunnerTest PASSED，BUILD SUCCESSFUL。
- `timeout 900 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m :gui:test :gui:compileKotlin`：`/tmp/gui15-gui-green.log`，21/21 GUI 测试通过，Compose shell 编译通过。
- `timeout 900 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m cleanTest build`（合入 `desktop-migration` 后）：`/tmp/gui15-full-build.log`，BUILD SUCCESSFUL；GUI 28/28（7 DefaultModeState + 7 ToolInterface + 14 WorkflowRunner）通过，core/cli 全量测试也通过。

### 验收证据
- `RunSnapshot` 新增 `failureKind` / `exitCode` / `firstReason`；runner 把非零退出、stderr error、超时、缺模型四类信号统一归为 `FAILURE`，`error = "退出码 N：<首行原因>"`，全文保留在 `log`。
- `DefaultModeScreen.kt` 的 `RunStatusBanner` 渲染该错误条；结果面板只读 `results`，失败/取消时为空并显示“临时产物已清理”。
- 取消按钮与 `DisposableEffect.onDispose` 都调用 `WorkflowRunner.cancelActiveProcess(state.runHandle())`；runner 另有 JVM shutdown hook 兜底。`cancelling an active run...` 与 `window close cleanup...` 两个测试断言子进程 `isAlive == false`、运行态 `CANCELLED`、无结果、无残留 PNG。
- `RunConfig.timeoutMillis` 默认 600_000 ms；GUI 超时输入框写 `GuiRunSettings.timeoutMillis`，`the GUI timeout setting bounds a run...` 断言默认配置下 400 ms 超时杀进程并置失败，测试结束恢复原值。
- 产物清理以启动前目录快照为界，只删本次新增的进度路径与 `.tmp`，不碰用户旧文件；`a failed run leaves the workflow editable and rerunnable` 先失败再删除失败标记文件重跑成功，工作流参数改动保留。
- 假 CLI 是真实 POSIX shell 脚本；四类失败、取消、关窗、超时、清理均有独立测试，全部带内部超时（CountDownLatch/Thread.join）且整条 Gradle 命令有 `timeout` 与日志。

### 偏离与风险
- 按 spec 未引入 UI 测试框架：错误条文案、取消按钮、超时输入框的实际视觉渲染只通过 Compose 编译与数据快照间接验证，未做真实窗口点击验证。
- `Main.kt` 属其他工单范围未改；关窗清理依靠 `DisposableEffect.onDispose` + JVM shutdown hook 双路径，未在图形环境人工关闭窗口确认。
- 缺模型通过 stderr/stdout 文本正则识别（`model ... not found/missing/...`）；CLI 改文案需同步 `MISSING_MODEL_REGEX`。
- stderr 中含 `error/failed/failure/exception/cannot/unable` 的行即使退出码为 0 也判失败；`warning` 行不触发。若引擎把无害诊断写成 error 级日志可能误判。
- 产物清理只覆盖输出目录内本次新增文件；CLI 若把临时文件写到别处则无法覆盖。
