---
status: accepted
date: 2026-11-26
---

# 0014 - 桌面工具面按可行性对齐移动版 16 工具，而非逐个复制

移动版 `AgentTools.kt` 有 16 个工具，但它是 Android 耦合文件（`Context`/`SharedPreferences`/`AndroidKeyStore`/Bitmap），按 ADR-0001/0007 桌面不复制、自己重写工具面。我们决定把"对齐 16 工具"精确为**按桌面可行性分组对齐**：能映射的映射到桌面 `ToolCommand`/`ToolInterface`/`DesktopToolCommands`，映射不了的明确放弃——目标是最大化桌面可实现的工具覆盖，而不是凑齐 16 这个数字。分组如下：已支持 4（add_node/connect/run/set_parameter）；有底座只差接工具面 5（list_models/get_canvas/new_flow/save_flow/list_loras）；需新建 6（list_flows/disconnect/remove_node/search_loras/download_lora/delete_lora）；不可行 1（civitai_settings，依赖 Android Keystore/SharedPreferences 且与 ADR-0012 的 env 密钥纪律冲突，明确不迁移）。本轮只做有底座的 5 个，需新建的 6 个拆后续；涉删除/下载需 Ask 确认的工具留到 `ToolBox` 接 Ask 之后，本轮 5 个都是只读/纯保存，不碰 `ToolBox` 接口。

**Considered Options**：
1. 逐个复制移动版 16 工具——否决：`AgentTools.kt` Android 耦合，且 civitai_settings 依赖桌面刻意不引入的持久化密钥（ADR-0012），复制不可行也不必要。
2. 一轮做完所有 11 个可映射工具——否决：桌面 CLI 只有 4 个子命令，6 个需新建工具（含 `list_flows` 无 recipes 概念、LoRA 下载需 HTTP 客户端）会让地图/验收失焦；先交付有底座的 5 个，用现有 `:gui:verifyRealProvider` 接缝验证。
3. 按可行性分组、本轮 5 个、其余后续（采纳）——每步可独立验证，工具面按桌面能力自然生长，不为凑数造无底座的命令。
