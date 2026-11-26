# Map: 应用内真 provider 端到端验证

Type: wayfinder:map
Status: charted

<!-- 事后追认的精简地图：决策来自 grilling，无规格/ADR；验证已执行并记于 README.md。 -->

## Destination

第一次在**应用内**——而不是 curl 探针或测试里的假 ChatModel——验证「对话 → 真 LLM 工具调用 → 工作流 → 出图」这条主链路；跑通或得明确结论，不无限调试。

## Notes

- 历史背景：`verify-gui.sh` 的 chat-turn 步全程用脚本化假 ChatModel；`local-llm-chat-*.json` 只是 curl 级探针，都不覆盖应用接线。
- 本机有现成 CUSTOM 服务（lemond :13305 / ollama :11434 / llama-server :8001）与真 chat LLM（Qwen3.5-4B-GGUF），无需外部密钥（ADR-0012 的 CUSTOM 免 key 规则）。
- 验证形态：独立 gradle task `:gui:verifyRealProvider`，不并入 `verify-gui.sh`（ADR-0011 单一脚本不连真 provider）；缺配置 SKIP 退出 0。

## Decisions so far

- [目标锁定应用内端到端（非 curl、非持久化配置）](README.md)：curl 探针（A）已做、GUI 设置重启即丢是 ADR-0012 既有裁决，都不动；本轮只验 `ChatSession`+复制的 `Agent`+`LlmClient` 经 CUSTOM 真服务跑一句话出图。
- [服务选 lemond :13305 + Qwen3.5-4B](README.md)：有历史成功先例、符合文档 CUSTOM 端点；其余服务作对照。
- [一次性 env 临时注入](README.md)：不改持久化、不动 ADR-0012，验证脚本作为手工/可选工具。
- [跑通或明确结论，不无限调试](README.md)：目标含「模型能力不足时产出兼容性结论」。
- [独立脚本 + 三条可观察判据](desktop/gui/src/test/kotlin/com/abrah/nightmare/desktop/gui/RealProviderVerificationMain.kt)：真服务返回 tool_calls、工作流被工具改动、假 CLI 出图且 Picture 气泡出现；允许 agent 多步/Ask 追问。脚本后加 `NIGHTMARE_VERIFY_PROMPT` 话术注入，判据改为「本次调用 run 才要求 Picture」以支持只读/保存话术。
- [成功无需改产品代码](README.md)：Qwen3.5-4B 一次看懂现有系统提示与工具 schema，8 步工具调用全 ok；上一 effort 补的完整 schema 价值在此兑现。

## Not yet specified

- 未跑其他真模型（ollama/llama-server 是对照项）、未跑真托管 provider（需密钥）、未做多次重复稳定性统计、GUI 窗口鼠标交互未测。

## Out of scope

- 改持久化配置（ADR-0012）、把真 provider 并入 `verify-gui.sh` 必跑步（ADR-0011）、改 GLOSSARY（定义未稳定时不写）。
