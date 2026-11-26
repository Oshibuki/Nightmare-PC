# 05: 「重发上一句」按钮与可复现重放

**What to build:** 作为桌面用户，我能在输入框旁点一个「重发上一句」按钮，把上一条已发出的用户消息当作新消息再发、起新回合；每次重放都从干净的工作流与 agent 上下文开始，让我改了配置或提示词后能用同一句反复验证、得到可比的结果，而旧气泡与已生成的图都保留。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] 对话模式输入框旁新增单一「重发上一句」按钮，不在错误/取消/用户气泡上分设；仅当存在上一条已发出的用户消息时可用；tooltip 显示该句被截断的文本
- [x] 点击后取上一条用户消息文本当新消息发、起新回合；输入框草稿不参与；UI 对话气泡照常追加、既有气泡与产物图保留
- [x] 重放前用既有「新建工作流」命令复位工作流，并用干净 agent 上下文起回合；同一句重放两次得到一致的工作流与产物
- [x] 干净上下文只重建 Agent 实例以清掉其累积 history，复用同一个注入的 ChatModel；provider 配置由既有 `settings.resolved()` 动态读取，不重新接线或重建 model——把 Agent 构造抽成工厂供重放调用（该工厂也是后续对话工具面改 `SYSTEM_PROMPT` 的落点）
- [x] 运行中按钮禁用并提示「运行中」，二次重发被拒且不丢已输入文字；追问等待期间点按钮等同发送新指令——先以 CANCEL 放行等待中的追问再起新回合，不新增特例
- [x] 「失败/取消后重试保留上下文」的既有路径语义不变，与「重发上一句」互不干扰；两条路径各有状态层断言
- [x] 既有测试不回归，新增断言只覆盖外部可见行为（复位、上下文干净、气泡追加、并发拒绝、追问放行）

## Comments

实现提交 `c454483`：ChatSession 抽 `internal fun newAgent(model)` 工厂、`model` 改为保留的成员；新增 `lastUserText()` / `replayTooltip()` / `replayLast()`。重放以既有 `ToolCommand.NewWorkflow` 复位工作流、换新 Agent（干净 history）、复用同一注入 ChatModel，再走唯一入口 `send()`；ChatComposer 同一 Row 加单一按钮（`BasicTooltipBox` tooltip，运行中禁用并显示「运行中」，追问等待可点）。草稿未参与 `onReplay`，既有气泡/产物不清除。

TDD 红-绿-重构（工作目录 `desktop`，`JAVA_HOME=/home/chjlxxc/Android/jdk-17`，均 `timeout` + `tee`）：
- 红：`:gui:test --tests ...ChatSessionTest --tests ...TurnLifecycleTest` 退出码 1（新增 replay API 未解析），日志 `/tmp/nm-desktop/logs/retry-img2img-05-red.log`。
- 绿：同一命令退出码 0（11 项 ChatSessionTest + 7 项 TurnLifecycleTest 全通过），日志 `/tmp/nm-desktop/logs/retry-img2img-05-green-state.log`。
- UI 接线：`:gui:classes` 退出码 0，日志 `/tmp/nm-desktop/logs/retry-img2img-05-ui-compile.log`。
- 全量：`:core:test :gui:test --rerun-tasks` 退出码 0，日志 `/tmp/nm-desktop/logs/retry-img2img-05-full-tests.log`。
- 构建：`build` 退出码 0，日志 `/tmp/nm-desktop/logs/retry-img2img-05-build.log`。
- 集成分支合并：`git merge desktop-retry-img2img` 退出码 0（already up to date），日志 `/tmp/nm-desktop/logs/retry-img2img-05-merge.log`。

逐条证据：
1. `ChatSessionTest.the replay tooltip previews only the last sent sentence` 断言无历史时不可用、80 字句截断为 48 字+省略号、短句原样；UI 编译确认按钮位于 composer 同一 Row、tooltip 由 `BasicTooltipBox` 显示。
2. `replay resends the last sentence as a new turn with a clean agent context` 断言两次用户气泡文本相同、旧助手气泡保留、重放回合首次模型调用只含 system+该句；`onReplay` 不读写 draft。
3. `replaying the same sentence twice rebuilds the same workflow and picture` 用假 CLI：三个回合开始时工作流均为空、两次重放后 `Workflow` 数据类相等、三条同路径 Picture 气泡、所有工具被接受。
4. `a live turn refuses replay without resetting the workflow or adding a bubble` 与 `replay during an Ask wait releases it with CANCEL and starts a new turn` 分别覆盖并发拒绝与 Ask 放行（复用 `send()` 既有分支）。
5. `TurnLifecycleTest` 的失败重试/取消重试各加一条「send 重试不复位工作流」断言，原有历史/气泡/图片断言全部保留并通过。
6. 全量测试无回归；新增断言按字段/数据类比较，未做 JSON 字符串全等。

未验证边界：没有做窗口级鼠标点击/悬浮的 GUI 端到端（规格明确不测）；按钮外观与 tooltip 仅经编译验证，未人工启动窗口观察。
