# 11: 结构化追问（Ask）的放行与回写

**What to build:** 作为桌面用户，我能点选 AI 的追问选项让同一轮出图继续；我改用输入框打字时，那个追问不会把程序卡住——追问的等待与放行是同一回合内的同步握手，不是一条新消息。本工单只提供追问子系统的**放行机制与原语**（点选放行、输入绕过、取消原语），不接 UI 的取消/关窗入口——统一取消入口由 12 编排（避免取消语义散落两处）。

**Blocked by:** 09 — 第三条对话模式与一条对话回合

**Status:** resolved

- [x] 追问气泡渲染为可点击选项；点选后 `answer(id, option)` 放行后台等待，agent 拿选项在同一回合继续工具调用，不另起回合
- [x] 追问的 id/问题/选项/答案四字段全留内存，用于精确匹配与显示选中项；不进工作流 JSON、不落盘
- [x] 追问等待时用户改用输入框：先以 `CANCEL` 值放行再处理新消息起新回合；切模式不影响等待中的追问（切回仍可点选）
- [x] **提供取消原语**：暴露一个「以 `CANCEL` 值放行所有等待中追问」的原语供 12 的统一取消/关窗入口调用；不自动选第一个选项、不允许追问永久挂起。本工单**不**自己接 UI 取消按钮或关窗逻辑——那是 12 的职责，避免同一取消入口两处实现
- [x] 桌面适配器复刻移动版追问语义但不复制其 Android 耦合宿主层；本工单与 10 改 gui 不同区域，可并行开发

## Comments

- 实现：`ChatSession` 新增 `ask`/`answer`/`cancelPendingAsks`/`awaitingAsk`，回合用 `turnEpoch` 区分被绕过/取消的旧回合；新文件 `AskGate.kt` 用 `CompletableFuture` + 有界等待（默认 300 s，测试注入 200 ms/5 s）复刻移动版 `AgentSession.ask/answer/stop`，CANCEL 字面值与 `AgentTools.CANCEL` 一致；未复制 Android 宿主层，未改 `Agent.kt`/`WorkflowRunner`/`app/`。
- UI：`ChatModeScreen` 的 Ask 气泡改为可点选按钮（显示选中项，CANCEL 显示「已取消」）；输入框在追问等待时保持可用，发送由 `ChatSession.send` 先以 CANCEL 放行再起新回合。模式切换只卸装 composable listener，窗口级单例 `ChatSession`（Main.kt）不销毁；未接取消按钮/关窗逻辑。
- TDD：红→绿循环日志 `ticket-11-red-01.log`（编译失败：缺 ask/answer/awaitingAsk）、`ticket-11-green-01.log`、`ticket-11-red-02.log`（输入绕过被单回合约束拒绝）、`ticket-11-green-02.log` / `green-02b.log`、`ticket-11-green-03.log`、`ticket-11-green-04.log`、`ticket-11-ui-compile.log`；全部在 `/tmp/nm-desktop/logs/`，`JAVA_HOME=/home/chjlxxc/Android/jdk-17`。
- 全量：`timeout 600s bash ./gradlew --no-daemon :core:test :gui:test` EXIT=0（`ticket-11-full-tests.log`，含 5 个 AskSessionTest 与既有 ChatSessionTest 全绿）；`timeout 600s bash ./gradlew --no-daemon build` EXIT=0（`ticket-11-build.log`）。
- 断言覆盖：点选后同回合继续 `set_parameter`→`run` 并出 PNG（假 CLI）；输入绕过收到 CANCEL 并起第二回合；两个等待者被取消原语同时放行且晚到的 answer 无效；无人作答在 200 ms 超时后以 CANCEL 返回；四字段留在气泡且不进 `workflow.toJson()` / 运行态快照。
- 未验证：Compose 真实鼠标点击与模式切换的 UI 端到端（规格明确不测），取消按钮/关窗入口属于工单 12；provider/密钥属于工单 10，本工单只使用既有假 ChatModel 接缝。
