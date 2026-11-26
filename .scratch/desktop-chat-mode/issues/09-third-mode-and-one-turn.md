# 09: 第三条对话模式与一条对话回合

**What to build:** 作为桌面用户，我能在默认/高级之外看到一个对话模式入口；输入一句自然语言后，看到 AI 的回复、工具调用摘要和生成的图以气泡出现，工作流收为顶部面包屑，生成的图进入结果面板——一条从对话到出图的完整路径。

**Blocked by:** 08 — 落地 agent 核心与假对象底座

**Status:** resolved

- [x] 新增第三种交互模式，与默认/高级并列、共享唯一工作流会话；切换只换外壳、对话历史保留
- [x] 单列对话为主体：用户/助手/工具摘要/图片/错误六类事件以气泡呈现（追问气泡本工单先占位，交互在 11 完成）；工作流收为顶部面包屑，节点可点开只读抽屉查看 AI 改动
- [x] 输入一句话触发一个命名后台线程回合：真实 agent 循环经五条工具命令改同一份工作流，假 CLI 出图，图片按既有监听/快照路径入结果面板
- [x] 事件经 emit 回调切回主线程写入可观察对话列表；引擎日志仍走既有运行日志面板，不进气泡、不污染运行态通知链
- [x] 不新增协程/事件总线/配置文件等接缝，不修改既有运行器的阻塞语义（ADR-0009）；同一时刻只允许一个回合线程

**粒度备注（拆分备选，非本工单要求）**：本工单把对话 UI 外壳与 agent 运行回路合为一条完整纵向切片，是 6 张中最重的一张——对照 phase2-gui 是把 UI（工单 13）与运行（工单 14）分开的。之所以合并，是因为对话气泡本就是事件的直接呈现，拆开后纯 UI 工单无法独立 demo（只能看空壳）。若实现时发现单上下文窗口装不下，再拆为「09a 对话 UI + 合成事件 demo」与「09b 接 agent 回路 + 假 CLI 出图」两张；默认不拆。

## Comments

2026-11-26 — 实现于独立 worktree `chat-ticket-09-impl`（基线 = 集成分支 `desktop-chat-mode@660509a`，`git merge desktop-chat-mode` 已执行且为 up-to-date，无冲突）。未拆 09a/09b。

**TDD（有界，真实命令 / 退出码 / 日志）**，均在 `desktop/` 且 `export JAVA_HOME=/home/chjlxxc/Android/jdk-17`：

1. RED — `timeout 180s bash ./gradlew --no-daemon :gui:test --tests 'com.abrah.nightmare.desktop.gui.ChatSessionTest'` → **退出码 1**，`:gui:compileTestKotlin` 报 `Unresolved reference 'ChatSession'`；日志 `/tmp/nm-desktop/logs/ticket-09-red.log`。
2. GREEN（第一次）— 同上 → **退出码 1**，`:gui:compileKotlin` 报 `org.json` 不在 main 编译类路径；补 `implementation(libs.json)` 后修同包 `ToolResult` 引用。
3. GREEN（最终）— 同上 → **退出码 0**，`ChatSessionTest` 3/3 PASSED；日志 `/tmp/nm-desktop/logs/ticket-09-green3.log`。测试断言：一句话 → 五类工具名共 9 条命令 → 搭出 prompt→sampler→output（seed=11、prompt=tool-built dawn）→ 假 CLI 出图 → `ChatItem.Picture` 气泡 + `RunSnapshot.SUCCESS` 结果；默认未配置 provider → 明确错误气泡；第二个回合在首个回合存活时被拒、回合结束后恢复发送。JSON 均按键名取值，不做字符串全等。
4. UI 编译 — `timeout 180s bash ./gradlew --no-daemon :gui:classes` → **退出码 0**；日志 `/tmp/nm-desktop/logs/ticket-09-gui-classes.log`。
5. 全量测试 — `timeout 600s bash ./gradlew --no-daemon :core:test :gui:test` → **退出码 0**；日志 `/tmp/nm-desktop/logs/ticket-09-full-tests.log`（core 全绿、gui 全绿，含既有 `WorkflowRunnerTest`/`ToolInterfaceTest`/`FakeChatModelWorkflowTest`）。
6. 构建 — `timeout 600s bash ./gradlew --no-daemon build` → **退出码 0**；日志 `/tmp/nm-desktop/logs/ticket-09-build.log`。

**落地边界**：`Main.kt` 加 `CHAT` 第三值与窗口级唯一 `ChatSession(session, runner)`，切模式只换外壳；`ChatModeScreen.kt` 单列气泡（User/Assistant/Tool/Picture/Error/Ask 六类，Ask 只读占位）+ 顶部面包屑 + 只读抽屉 + 输入框/发送按钮，复用既有 `ResultPreview` 与 `RunLogPanel`（两者仅从 `private` 放宽为 `internal`）；`ChatSession.kt` 命名后台 daemon Thread 跑一次 `Agent.send`，`postToUi` 默认投到 AWT EDT（Compose Desktop 主线程），`ChatItem.Log` 不入气泡；`DesktopToolBox.kt` 是 main 源集的生产粘合，形状对齐测试侧假对象但未移动它，成功 `run` 从既有 run 快照取图路径作为 Picture。测试侧仅新增 `ChatSessionTest.kt`，未新增 Compose UI 测试依赖；`gui/build.gradle.kts` 仅加 main 的 `implementation(libs.json)`。

**未验证 / 留给后续工单**：Ask 选项点击与 answer 回写（11）；回合取消、关窗时 Ask 以 CANCEL 放行（12）；真实 provider 设置/密钥/LlmClient 接线（10）——生产默认模型只抛「未配置 provider」错误、不发起网络；Compose 窗口的实际鼠标/视觉端到端（测试只覆盖状态层与编译）；Windows/macOS 端到端（仅 `:gui:classes` 级别保证）。未改 `WorkflowRunner` 阻塞/取消语义、`format=1`、`app/`，未新增协程/事件总线/配置文件。
