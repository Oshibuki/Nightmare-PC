# 01: 核实 LLM 核心的可复制边界与 provider 契约

Type: wayfinder:research
Status: resolved

## Question

移动版 `app/src/main/java/com/abrah/nightmare/agent/LlmClient.kt` 与 `Agent.kt` 被 ADR-0008 认定为纯 JVM、可复制到桌面。核实这条断言在「复制到 `desktop/` 的 JVM 源集并能编译」这个层面是否成立，并给出规格可直接引用的事实清单：

- 5 家 provider（OpenRouter / OpenAI / DeepSeek / xAI / Groq）与 Custom 自填 base URL 的请求/响应契约、模型列表端点、`fitsChat`/`affordable` 的选模逻辑、`Authorization` 等请求头、错误与超时路径。
- 逐符号列出 `LlmClient.kt`/`Agent.kt` 原样复制所需的依赖与必须改动的行；若存在隐藏的 Android/AndroidX 依赖，明确指出。
- 复制后 `ChatModel` 接口的最小形状是什么（供桌面假对象实现），以及 `Agent.send()` 的 emit 序列与取消语义。

结论要细到下一张工单不必重新调查；把证据（分支、commit、编译命令、日志路径）写进 `## Answer`，并区分哪些是实读事实、哪些仍是推断。

## Answer

研究子代理实读一手源码并做临时复制编译/测试验证后结论如下（全文见 research 分支 `research/llm-core-portability` commit `4002113`，文件 `.scratch/desktop-chat-mode/research/01-llm-core-portability.md`，135 行逐条带 file:line）：

1. **ADR-0008 断言成立**：`LlmClient.kt`/`Agent.kt` 逐字节复制进 `desktop/core/src/main/kotlin/com/abrah/nightmare/agent/` 后，`:core:compileKotlin` 实测 BUILD SUCCESSFUL，**编译改动 0 行**。隐藏依赖不是 Android 而是 `org.json`——桌面已 pin `org.json:json:20250517`，无需改构建脚本。唯一约定性改动是首行溯源注释 + 登记 `copied-files.md`。
2. **provider 契约**：5 家 + Custom 共用 OpenAI chat-completions 线格式（POST `{base}/chat/completions`、GET `/models`），差异只在 base URL；`Authorization: Bearer` 仅 key 非空时发；`HTTP-Referer`/`X-Title` 发全部 provider；connect 20s / read 180s；`capReply` 遇 "can only afford N" 降到 N 重试；`fitsChat` 七条否决条件（详见 research 文档 §1）。
3. **ChatModel 最小形状**：`fun interface ChatModel { fun complete(messages: JSONArray, tools: JSONArray): JSONObject }`——SAM 转换，桌面假对象可写成 `ChatModel { _, _ -> ... }`。`send` 每轮固定 emit 顺序 Assistant→Tool→Log→Picture；取消有两个强度不同的检查点（轮首静默停、call 前仅降级当前 tool）。
4. **测试可移植性（影响工单 03）**：`Script:ChatModel`/`Tools:ToolBox` 假对象无 Android 依赖可直拷；`AgentTest` 原样编译失败（Robolectric ×3、`AgentTools` ×2），裁剪后 6 用例 5 PASS / 1 FAIL，失败因 JVM `org.json` 默认 `HashMap` 键序变化（字符串全等断言 `run_flow` 参数顺序），改顺序无关断言即可。

**事实 vs 推断**：上述 1–4 的行号、编译/测试实测输出、HashMap 探针均为实读事实；`fitsChat` 专为 OpenRouter 的意图、Custom 注入方式、键序不影响线上协议为推断；源码注释引用的 `docs/AGENT-API.md §4` 在本 worktree 不存在，未能核实。
