# 09: 对话模式的边界与桌面落地方式

Type: wayfinder:grilling
Status: open

## Question

移动版已有对话模式：用户用对话提供 prompt 或修改意见，由 AI 改写为可用提示词，或参考用户给的参考图出图。代码在 `agent/`（`Agent.kt`、`LlmClient.kt`、`AgentSession.kt`），`ChatItem` 会流式吐出用户/助手/工具调用/图片/错误，依赖 LLM provider 与密钥；桌面侧目前没有这些资产。

桌面 Phase 2 是否纳入对话模式？若纳入：采用哪种接缝（复制/改写 `agent/`、复用本地 HTTP、还是外接 provider），密钥放在哪，无网络/无密钥时如何测试？若不纳入：明确它属于哪一轮 effort，以及本轮 spec 应留什么接口以免将来返工。
