# Map: 对话模式重试与图生图首切片

Type: wayfinder:map
Status: charted

<!-- 前沿已清空：4 张决策工单全部 resolved（01–04），Destination 达成，可交 /to-spec。 -->

## Destination

一份可交棒给实现的规格与工单图：在已交付的桌面对话出图模式上，①给「上一轮输入」加单一重发入口，让配置与提示词的改动能被同一句反复验证；②开辟图生图（Img2Img）的第一条纵向切片——用户给一张本地图与一句描述，经对话工具面改同一份工作流并出图。验收沿用 Linux 上一条 `verify-gui.sh` 命令（新增 img2img 步），Windows/macOS 仅要求 GUI 模块编译通过。

## Notes

- 领域术语见根 `GLOSSARY.md`（交互模式、对话回合、工作流、节点类型、运行态、进度事件、验收脚本、重试、图生图）；决策依据见 issues/01–04 的 Answer；反直觉取舍见 ADR-0007、0009、0011、0013。本图是把已清空的访谈结果交棒给实现的产物，不重新设计。
- 本轮决策全部来自一场 grilling + domain-modeling 现场访谈（2026-11-26，人侧由用户本人作答、agent 未代答），四条决策工单均已 resolved；地图不再新开决策工单。
- 已核实事实（子代理实读，勿重复调查）：CLI 的 `img2img` 子命令已完整（`--init-img`/`--strength`，strength 默认 0.75、范围 0..1，仅 PNG、尺寸 8 倍数、拒 GGUF），但桌面 `WorkflowRunner` 只投影 `txt2img`；节点注册表仅三种（core.prompt / sd.sample / core.output），无图片输入节点；5 条 `ToolCommand` 无图片路径入口；`format=1` 的 params 为字符串映射，可承载路径；重放若不先 `new_workflow`，重复 `add_node` 会因 id 重复被拒；`Agent` 单实例且 history 累积；草稿由对话界面本地持有、拒发时保留。
- 实现纪律：每张实现工单调用 tdd 技能、有界验证；测试接缝沿用既有两条（假 CLI 端到端、JVM 测试源集），验收口沿用 ADR-0011 单一入口，只扩展不新建。
- 术语纪律：「重试」「图生图」已入 `GLOSSARY.md`，ADR-0013 已落盘；实现中若遇地图未覆盖的边界，先回读对应决策工单的 Answer，不重新发明。

## Decisions so far

- [上一轮输入重试的语义与入口](issues/01-retry-semantics.md)：重放上一条已发出的用户消息、当新消息起新回合；输入框旁单一「重发上一句」按钮，不在气泡上分设；重放前 `new_workflow` 复位 + 干净 agent 上下文，UI 历史保留，与失败/取消后重试有意区分。
- [图生图的交付节奏与首切片形态](issues/02-img2img-slice.md)：先走完决策面再拆工单；第一条纵向切片走对话模式新工具（用户给图 + 描述 → AI 调工具改工作流），图片节点/画布后置。
- [图生图的范围、图片来源与工作流表示](issues/03-img2img-scope.md)：只做普通 img2img，inpaint 与参考图输入排除；初始图由用户在对话里给本地路径经工具参数传入；新增最小图片输入节点（path/strength 连 sampler），运行器按节点切换子命令；strength 由 AI 经工具参数定、默认 0.75。
- [图生图的验收边界与文档落盘](issues/04-img2img-verification.md)：`verify-gui.sh` 加独立 img2img 步、假 CLI 断言投影与产物入面板、真冒烟可选、Windows/macOS 仅编译；「图生图」入 `GLOSSARY.md`，ADR-0013 就已落盘。

## Not yet specified

- （空）前沿已清空，无仍在范围内的未指定雾。原本可能的雾（用户侧强度控件、结果面板选图、文件选择器）已随工单 03 裁决为后续 effort，不在本图。

## Out of scope

- inpaint（局部重绘）与参考图输入（flux_edit / H3 reference 一类）：与图生图语义不同，本轮明确排除，见工单 03。
- 从结果面板选上一张产物图作初始图、文件选择器/附件上传：后置，首切片只支持对话里给本地路径，见工单 03。
- 用户侧强度控件与专门的 Ask 追问：首切片 strength 由 AI 经工具参数定，用户控制留到后续 effort 看反馈，见工单 03/04。
- 真 provider 端到端冒烟：缺环境变量或网络则跳过、不阻塞，沿用 ADR-0011。
- Windows/macOS 端到端：仅要求 GUI 模块本地编译通过，沿用 ADR-0011。
- 移动版任何改动或自动同步：沿用复制式复用与 ADR-0007。
- 新增运行路径或独立脚本：仍是单运行入口、单一 CLI 子进程（ADR-0009）与单一验收脚本（ADR-0011）。
