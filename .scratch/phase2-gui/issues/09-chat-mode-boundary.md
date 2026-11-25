# 09: 对话模式的边界与桌面落地方式

Type: wayfinder:grilling
Status: resolved

## Question

移动版已有对话模式：用户用对话提供 prompt 或修改意见，由 AI 改写为可用提示词，或参考用户给的参考图出图。代码在 `agent/`（`Agent.kt`、`LlmClient.kt`、`AgentSession.kt`），`ChatItem` 会流式吐出用户/助手/工具调用/图片/错误，依赖 LLM provider 与密钥；桌面侧目前没有这些资产。

桌面 Phase 2 是否纳入对话模式？若纳入：采用哪种接缝（复制/改写 `agent/`、复用本地 HTTP、还是外接 provider），密钥放在哪，无网络/无密钥时如何测试？若不纳入：明确它属于哪一轮 effort，以及本轮 spec 应留什么接口以免将来返工。

## Answer

**范围**：对话模式不进本轮 Phase 2 切片；本轮 spec 只预留「工作流可由外部工具驱动」的接口（等价 `CanvasHost` 的命令面：`new_flow`/`set_params`/`add_node`/`connect`/`run`），完整对话模式另起 effort。**关系**：对话操作的是同一份工作流（工具经 `CanvasHost` 改画布），产物必须能被默认（C）/高级（A）模式直接打开，因此 05 的 JSON 格式与 08 的节点类型只定义一次。

**将来接缝**：复制纯 JVM 的 `LlmClient.kt`/`Agent.kt`，Android 耦合的 `AgentTools`/`AgentSession`/`AgentConfig`/`CanvasHost` 在桌面重写。Provider 沿用移动版 5 家（OpenRouter / OpenAI / DeepSeek / xAI / Groq），**并额外支持用户自填 base URL 的 OpenAI 兼容端点**。密钥用环境变量/桌面本地配置注入，只走 `Authorization` 头，绝不进工作流 JSON、日志或截图。测试用假 `ChatModel` + 假 `CanvasHost` 的 JVM 用例验证工具调用链，真 provider 为可选手工冒烟。

已立 `docs/adr/0008-chat-mode-deferred.md`，约定交接给将来的对话模式 effort。
