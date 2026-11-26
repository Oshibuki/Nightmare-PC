# Map: 确认型 Ask 注入与三个需要它的工具

Type: wayfinder:map
Status: charted

<!-- 事后追认的精简地图：决策来自 grilling，权威记录是 ADR-0015 + GLOSSARY 的 Ask 分类；C1 已合并到集成分支 desktop-confirm-ask（合并提交 5ee5ea9）。 -->

## Destination

在 GUI 层把 `ChatSession::ask` 注入进 `DesktopToolBox`，并同批交付三个需要用户确认的工具（save_flow 覆盖确认、download_lora、delete_lora）。不单独先建"暂无消费者"的接口，不改复制的 agent 核心。

## Notes

- 权威决策：`docs/adr/0015-confirmation-ask-injection.md`；术语：`GLOSSARY.md` 的「结构化追问（Ask）」已分澄清型/确认型。
- 移动版 `ToolBox` 本就没有 ask——ask 靠宿主注入函数实现（`AgentSession.kt:103,189-196`）；桌面 `AskGate`/`ChatSession::ask` 已是等价 seam。
- 确认逻辑放 `ToolInterface.dispatch(command, ask)`（按调用传递），`DesktopToolBox` 保持纯转换并把构造注入的 ask 透传；原 `dispatch(command)` 签名不变，默认 ask 返回 CANCEL——直接 dispatch 的确认工具一律安全取消。

## Decisions so far

- [Ask 接口与需它的工具同批交付](docs/adr/0015-confirmation-ask-injection.md)：当前五个工具都无确认需求，先建接口是无消费者（Speculative Generality，code-review 抓过的 smell）；由真实需求塑造接口。
- [Ask 只在 GUI 层注入、不改 core](docs/adr/0015-confirmation-ask-injection.md)：`DesktopToolBox` 构造加 ask、`ChatSession.newAgent` 传 `::ask`；改 core `ToolBox` 会牵连 send 调用点+三个实现+copied-files.md 逐字记录，与移动版模式不一致。
- [本批三工具](docs/adr/0015-confirmation-ask-injection.md)：save_flow 覆盖确认（文件存在才问「保存/取消」，取消则字节不变）、download_lora（HTTP seam + 512MB 阈值确认、取消不落盘）、delete_lora（确认后删、越界路径拒绝）。new_flow 未保存改动确认不做（桌面空工作流无此语义）。
- [确认返回用 Ok 而非 Rejected](docs/adr/0015-confirmation-ask-injection.md)：把取消理解为用户主动选择，而非工具失败；AskGate 的 CANCEL/超时值非肯定选项，一律安全取消。

## Not yet specified

- 真 provider 联网验证：本轮注入是真模型复验用只读/保存话术（list_models/get_canvas/save_flow）跑过；确认型工具的真模型触发未单独验证（JVM 层已端到端覆盖 ask 两条路径）。

## Out of scope

- 改 core/ToolBox、动 ADR-0001 复制纪律、new_flow 确认、civitai_settings、app/。
