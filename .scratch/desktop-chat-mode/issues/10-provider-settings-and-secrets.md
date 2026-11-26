# 10: provider 设置与密钥注入

**What to build:** 作为桌面用户，我能在对话模式里通过齿轮设置入口配置自己的 provider、base URL 和密钥；这些配置真正喂给 agent 的生产 ChatModel，让我描述的画面能经真实 provider 出图；无密钥或连不上时看到明确错误与「打开设置」出口，密钥在任何界面都不回显——全程不把密钥写到磁盘。

**Blocked by:** 09 — 第三条对话模式与一条对话回合

**Status:** resolved

- [x] 设置弹窗提供 provider 选择（五家 + 自填 base URL 的 Custom）、base URL、密钥、可选模型输入；配置由环境变量持久、弹窗输入只在本次进程内存、覆盖环境变量、重启回退，不写任何配置文件
- [x] 保存时用独立短超时探针在后台做一次连通性检查，失败仍允许保存；不改 agent 核心硬编码超时；模型自动挑一个可用者作默认、可手动覆盖；优先级 内存 > 环境变量 > 默认
- [x] **生产接线**：provider 配置（provider/base URL/密钥/模型）经环境变量与内存注入到 agent 的 ChatModel，由 `:core` 复制来的 `LlmClient` 承担生产实现；假 ChatModel 仍只留在测试源集。这补上 08「复制 LlmClient」与 09「假 ChatModel 跑通回路」之间的缝隙——否则测试全绿但真实 provider 出图无人负责
- [x] 无配置/连不上分两类错误文案；错误气泡带「打开设置」按钮、不自动重试、不改变工作流
- [x] 密钥框为密码类型；关闭后界面只显示「已配置/未配置」，不回显、不显示尾四位；密钥只进 Authorization 头（仅非空白时发送），不进工作流 JSON、日志或截图
- [x] 本工单与 11 改 gui 不同区域，可并行开发；合并后全量测试通过

## Comments

2026-11-26 — 实现于独立 worktree `chat-ticket-10-impl`（基线 = 集成分支 `desktop-chat-mode@116f1e2`；`git merge desktop-chat-mode` 已执行，输出「已经是最新的」，无冲突）。代码提交 `2229ef0`，收尾分支 `chat-ticket-10`。

**TDD（红→绿，真实命令 / 退出码 / 日志）**，均在 `desktop/` 且 `export JAVA_HOME=/home/chjlxxc/Android/jdk-17`：

1. RED-1 — `timeout 180s bash ./gradlew --no-daemon :gui:test --tests 'com.abrah.nightmare.desktop.gui.ChatProviderSettingsTest'` → **退出码 1**，`Unresolved reference 'ChatProviderSettings'`；日志 `/tmp/nm-desktop/logs/ticket-10-red-1.log`。GREEN-1 同命令 → **退出码 0**，6/6 PASSED；日志 `ticket-10-green-1.log`。
2. RED-2 — `:gui:test --tests '...ProviderProbeTest'` → **退出码 1**，`Unresolved reference 'ProviderProbe'`；日志 `ticket-10-red-2.log`。GREEN-2 加测 settings → **退出码 0**，13/13 PASSED；日志 `ticket-10-green-2b.log`（中途一条失败是测试夹具把 `\r\n` 写成 `\n` 的断言错误，已修夹具后重跑）。
3. RED-3 — `:gui:test --tests '...ProviderChatModelTest'` → **退出码 1**，`Unresolved reference 'ProviderChatModel'`；日志 `ticket-10-red-3.log`。GREEN-3 加 `ChatSessionTest` → **退出码 0**，6/6 + 3/3 PASSED；日志 `ticket-10-green-3.log`。
4. UI 编译 — `timeout 180s bash ./gradlew --no-daemon :gui:classes` → **退出码 0**；日志 `/tmp/nm-desktop/logs/ticket-10-gui-classes.log`。
5. 全量测试 — `timeout 600s bash ./gradlew --no-daemon :core:test :gui:test` → **退出码 0**；日志 `ticket-10-full-tests.log`（core 全绿；gui 含新增 6+8+6 条 provider 测试与既有 ChatSession/WorkflowRunner/ToolInterface/FakeChatModel 测试全绿）。
6. 构建 — `timeout 600s bash ./gradlew --no-daemon build` → **退出码 0**；日志 `ticket-10-build.log`。
7. 合并后复验 — 同上 `:core:test :gui:test` → **退出码 0**（13 tasks up-to-date）；日志 `ticket-10-post-merge-tests.log`；合并日志 `ticket-10-merge.log`。

**证据要点**：`ChatProviderSettingsTest` 断言 env 四件解析、默认 OPENROUTER、内存覆盖 env（env map 未被改写）、空密钥沿用已配置、Custom 无 base URL 留空、状态只显示「已配置/未配置」且无密钥或尾四位。`ProviderProbeTest` 记录到 3000/8000 ms 超时与 `/models` 地址，坏模型被 `fitsChat` 过滤，保存先写内存再后台检查（注入的队列未自动执行），失败不回滚，回环 HTTP 证实密钥只在 `Authorization: Bearer`、空密钥不带该头。`ProviderChatModelTest` 证实无密钥/无 base URL 在本地失败且不构造客户端、两类错误文案不同且带「打开设置」、保存后下一次回合读到新配置；真实 `:core` `LlmClient` 打回环 `/chat/completions` 时密钥只在头、不在请求行/正文，错误气泡无密钥回显。所有等待有 JUnit timeout、回环 socket 8 s soTimeout 或注入队列，无真实 provider 网络调用。

**落地边界**：新增 `ChatProviderSettings.kt`（env/内存优先级、掩码、后台检查与 fitsChat 默认模型）、`ProviderProbe.kt`（独立 3s/8s GET /models，不调用 `LlmClient.models()`）、`ProviderChatModel.kt`（生产 ChatModel + 两类错误分类）、`ProviderSettingsDialog.kt`（五家 + Custom、base URL、密码框密钥、可选模型）；`ChatSession.kt` 只换默认 ChatModel seam；`ChatModeScreen.kt` 只加面包屑齿轮入口/设置弹窗/错误气泡按钮。假 ChatModel/假 HTTP 仅在 `src/test`，未新增 Compose UI 测试依赖或持久化接缝；未改 `app/`、`WorkflowRunner`、`backend-patches/`、`:core` 硬编码超时、`format=1`。

**未验证 / 风险**：Compose 窗口的鼠标/视觉端到端未验证（只覆盖状态层与 `:gui:classes` 编译）；真实 provider 出图是可选手工冒烟，本轮只打回环假 HTTP；Windows/macOS 仅编译级别保证；与并行工单 11 共用 `ChatModeScreen.kt`/`ChatSession.kt`，合并时需在集成分支复验（本分支合并时集成分支仍在 116f1e2，尚无 11 的提交）。
