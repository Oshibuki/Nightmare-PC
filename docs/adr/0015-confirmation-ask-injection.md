---
status: accepted
date: 2026-11-26
---

# 0015 - 确认型追问只在 GUI 层注入，不改复制的 agent 核心

移动版 `Agent.kt` 的 `ToolBox` 接口本就没有 ask——`AgentTools` 通过宿主注入的 `(question, options) -> String` 函数发起追问（`AgentSession.kt:103,189-196`），答案由宿主写回、阻塞的后台回合线程继续。桌面复制的 `Agent.kt`（ADR-0001）与移动版逐字相同，`Agent.send` 不产生 Ask 气泡；`AskGate` + `ChatSession::ask` 已经是等价的宿主 seam。我们决定：需要用户确认的工具（覆盖保存 `save_flow`、下载/删除 LoRA）通过**在 `DesktopToolBox` 构造函数注入 `ask` 函数**来发起确认型追问，由 `ChatSession::ask` 提供；**不改 core 的 `ToolBox` 接口**，不动复制纪律。

**Considered Options**：
1. 改 core `ToolBox` 加 ask 参数——否决：与移动版接口形状不一致，牵连 send 调用点与三个 ToolBox 实现（含两个测试假对象），还要更新 copied-files.md 的逐字记录；收益为零。
2. Ask 接口先建、工具下一批再接——否决：当前五个工具都无确认需求，先建的是无消费者接口（Speculative Generality，code-review 明确抓过的 smell）；改为 Ask 注入与需要它的工具同批交付，由真实需求塑造接口。
3. GUI 层注入 + 工具同批（采纳）：变更面最小、对齐移动版模式，`AskGate` 的 CANCEL/超时/同回合回写纪律原样复用。

本批只做有底座的确认场景：save_flow 覆盖确认、download_lora/delete_lora（需新建 HTTP/文件管理能力）；`new_flow` 未保存改动确认不做（桌面 new_flow 是空工作流，无此语义）。术语上把「结构化追问」分为澄清型与确认型，共用回写机制但触发方不同（GLOSSARY.md 已更新）。
