---
status: accepted
date: 2026-11-26
---

# 0008 - 对话模式后置，桌面复用纯 JVM 的 LLM 核心并允许自定义 OpenAI 兼容端点

移动版对话模式由 `agent/` 提供：`LlmClient.kt` 与 `Agent.kt` 是纯 JVM，其余四个文件带 Android 依赖；它通过 `CanvasHost` 的工具调用直接改同一份工作流。桌面 Phase 2 只交付默认模式（C）与高级模式（A），本轮 spec 仅预留「工作流可由外部工具驱动」的接口，完整对话模式另起 effort。

将来落地时复制纯 JVM 的 `LlmClient`/`Agent`，Android 耦合的适配器层在桌面重写；provider 沿用移动版 5 家（OpenRouter / OpenAI / DeepSeek / xAI / Groq），并额外支持用户自填 base URL 的 OpenAI 兼容端点。密钥只经环境变量或本地配置注入，只放 `Authorization` 头，绝不进工作流 JSON、日志或截图。测试用假 `ChatModel` + 假 `CanvasHost` 的 JVM 用例，真 provider 调用为可选手工冒烟。

**Considered Options**：整份移植 `agent/` 会带回 R/Context；把对话模式纳入本轮切片会同时引入 provider、密钥与工具链三条新接缝；只做对话模式则丢掉纵向切片。

**Consequences**：本轮 spec 不写 LLM 实现，但工作流与节点类型必须能被外部工具驱动；将来 effort 继承上述接缝与密钥纪律。
