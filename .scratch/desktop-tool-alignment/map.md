# Map: 桌面工具面对齐移动版 16 工具（本轮 5 个有底座的）

Type: wayfinder:map
Status: charted

<!-- 事后追认的精简地图：决策来自 grilling，权威记录是 ADR-0014；已实现并合并到 main（合并提交 faabe81）。本图只记决策可追溯性。 -->

## Destination

把「对齐 16 工具」精确为**按桌面可行性分组对齐**——能映射的映射，映射不了的明确放弃，目标是最大化桌面可实现的工具覆盖，不凑 16 这个数字。本轮交付 5 个有底座的工具，全部已合并。

## Notes

- 权威决策：`docs/adr/0014-desktop-tool-surface-alignment.md`（含三个被否方案）。
- 桌面工具面是**重写**（扩 `ToolCommand`/`ToolInterface`/`DesktopToolCommands`），不复制 Android 耦合的 `AgentTools.kt`（ADR-0001/0007）。
- 验证接缝：`:gui:verifyRealProvider`（话术可注入），真 LLM 一次选对并填对新工具 schema，无需改提示词/schema。

## Decisions so far

- [目标按可行性分组对齐](docs/adr/0014-desktop-tool-surface-alignment.md)：16 工具分四类——已支持 4（add_node/connect/run/set_parameter）、有底座 5（本轮）、需新建 6（后续）、不可行 1（civitai_settings 依赖 Android Keystore 且与 ADR-0012 冲突，明确放弃）。
- [本轮只做有底座的 5 个](docs/adr/0014-desktop-tool-surface-alignment.md)：list_models / get_canvas / new_flow(增强) / save_flow / list_loras；需新建的 6 个拆后续，避免一轮塞 11 个工具导致验收失焦。
- [Ask 确认留到后续](docs/adr/0014-desktop-tool-surface-alignment.md)：本轮 5 个都只读/纯保存，不碰 `ToolBox` 接口；需确认的工具（覆盖保存/删除/下载）放到 ADR-0015 那批。
- [list_loras 复用模型库扫描](desktop/gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/ToolInterface.kt)：用 `ModelLibrary.resolve/scan` 筛「目录名含 lora」的 safetensors，不新增 CLI `--lora-dir`；平铺根目录的 LoRA 不列出（已写进 schema 描述）。

## Not yet specified

- 需新建的 6 个工具（list_flows/disconnect/remove_node/search_loras/download_lora/delete_lora）——其中 download_lora/delete_lora 已随 ADR-0015 那批实现，余下见后续。

## Out of scope

- civitai_settings、复制 `AgentTools.kt`、改 `app/`、new_flow 未保存改动确认（桌面 new_flow 是空工作流）。
