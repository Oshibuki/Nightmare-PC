# 12: 回合生命周期——取消、并发、切模式、重试

**What to build:** 作为桌面用户，我在出图运行中切到默认/高级模式后台仍继续、点取消在当前工具收尾后停下并保留已出的图、失败后重试保留对话上下文——回合的整个生命周期行为一致且不破坏既有的单运行/关窗清进程纪律。本工单拥有**唯一的取消/关窗入口**，统一编排回合标志、追问放行（调用 11 提供的原语）与进程清理。

**Blocked by:** 09 — 第三条对话模式与一条对话回合; 11 — 结构化追问（Ask）的放行与回写

**Status:** resolved

- [x] **唯一取消/关窗入口**：点取消或关窗时统一编排——置回合取消标志、调用 11 提供的追问 CANCEL 放行原语、关窗时清 CLI 子进程；取消为协作式：等当前工具调用收尾、下一轮首真正停，不发新 LLM 请求、不硬中断线程；取消气泡标「已取消」、保留已产出图与历史
- [x] 回合运行期间发送按钮禁用并提示「运行中」，不排队、不丢已输入的文字
- [x] 切模式只切外壳、后台回合继续、结果仍入同一份工作流；关窗沿用既有清进程路径（ADR-0009）
- [x] 失败/取消后允许重试：以新用户消息追加历史、起新回合，不抹掉旧气泡；对话历史只在内存，切换保留、重启清空
- [x] 不修改既有运行器的单运行/可取消/关窗清进程语义

## Comments

- 实现：`ChatSession` 新增唯一编排 `cancelTurn()` / `closeWindow()`，二者都进私有 `cancel(cleanupProcess)`：置回合取消标志并调用工单 11 的 `cancelPendingAsks()`（CANCEL 放行所有等待中的 Ask），运行中的回合追加 `ChatItem.Assistant("已取消")`；只有关窗分支调 `onCloseProcess`，生产默认值即既有 `WorkflowRunner.cancelActiveProcess(runner.processHandle())`（ADR-0009）。11 的放行原语没有重写；`send()` 的输入绕过分支也改为复用 `cancelPendingAsks()`，不再第二处 `gate.cancelAll()`。回合线程不被 interrupt，Agent 在下一轮首检查取消标志，不追加模型请求；已出的 Picture 与旧气泡保留。
- UI：`ChatModeScreen` 在运行中显示「取消」按钮并调用 `state.cancelTurn()`；发送按钮运行中禁用/显示「运行中」（Ask 等待沿用工单 05 的「发送新指令」绕过），`draft` 只在 `send` 返回 true 时清空，所以拒绝发送不丢文字。`Main.kt` 窗口级 `DisposableEffect` 改为 `chatState.closeWindow()`；模式切换不销毁窗口级 `ChatSession`/`WorkflowSession`/`WorkflowRunner`，后台回合与同一份工作流继续。
- TDD 红→绿：新增 `TurnLifecycleTest.kt`（7 个用例，全部 `@Test(timeout=...)` + `CountDownLatch`/有界 fallback，无 sleep、无真实网络）。红日志 `ticket-12-red-01.log`（缺 `cancelTurn`）、`red-02.log`（缺 `closeWindow`/`onCloseProcess`）；绿日志 `green-01.log` / `01b` / `01c`、`green-02.log`、`green-03.log`、`green-04.log`、`green-05.log`、`green-06.log` / `06b`、`ui-compile-and-test.log`，均在 `/tmp/nm-desktop/logs/`，`JAVA_HOME=/home/chjlxxc/Android/jdk-17`。
- 断言覆盖：FIFO 假 CLI 卡住一次 `run` 工具 → 取消后子进程仍存活、无第三次模型调用、放行后停在下一轮首、已出的 PNG 与工作流编辑保留且气泡标「已取消」；关窗同时放行等待中的 Ask（收到 `Cancel`、线程不挂起）并调用进程清理 seam 一次，默认 seam 实测把活着的 CLI 子进程打成 `CANCELLED`；运行中第二次 `send` 被拒、draft 原文保留、只有一条 User 气泡；切模式只卸装 composable listener，回合继续出图进同一 `WorkflowSession`/`WorkflowRunner`；失败（LlmError）与取消之后都能以新用户消息重试，旧气泡/图/agent 内存历史不丢，新会话快照为空（重启清空）。
- 全量：`timeout 600s bash ./gradlew --no-daemon :core:test :gui:test --rerun-tasks` EXIT=0（`ticket-12-full-tests.log`，298 PASSED / 0 FAILED，含 7 个新生命周期用例）；`timeout 600s bash ./gradlew --no-daemon build` EXIT=0（`ticket-12-build.log`）；`:gui:classes :gui:test` EXIT=0（`ticket-12-ui-compile-and-test.log`，76 GUI tests 绿）。`git merge desktop-chat-mode` 无冲突（`ticket-12-merge.log`）。
- 未验证：Compose 真实鼠标点击「取消」与真实窗口关闭事件的 UI 端到端（规格明确不测、未加 Compose UI 测试依赖）；真实 provider/网络；Windows/macOS 端到端。UI 接线只经过 `:gui:classes` 编译与状态层行为断言。`WorkflowRunner.kt` / `AskGate.kt` / `Agent.kt` / `app/` 均未改动，未新增协程、配置文件、事件总线或远端。
