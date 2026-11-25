# 02: 节点链编辑的交互形态

Type: wayfinder:prototype
Status: resolved
Blocked by: 08
Prototype: prototype/02-node-chain-interaction @ 01a2d26, verdict @ 2326328

## Question

用从 Android 侧复制、按 ADR-0007 就地替换过类型引用的 `CanvasModel`/`CanvasState` 几何与手势状态，在桌面上搭一条 txt2img 工作流，最小交互到底长什么样？节点类型只有 `core.prompt`、一个 SD 采样器与 `core.output` 三类。

请用一个廉价原型（线框或可运行 stub）回答：节点从哪里选、怎样连线、参数在哪里改、运行按钮与结果放在哪、缩放平移是否沿用移动版手势。原型要能被链接，作为 spec 中「交互形态」决策的依据；不要在本工单里实现完整编辑器。

## Answer

裁决：**默认模式 = 变体 C（Chain rail + drawer）**，**高级模式 = 变体 A（Free-form canvas）**，**变体 B（Guided stepper）否决**。三种交互模式共享同一份工作流与最小节点类型（ADR-0007），切换只换交互外壳，不复制状态或 JSON 格式。移动版已有的对话模式不进本轮切片，另开「对话模式的边界与桌面落地方式」工单决定范围。

原型（三变体，`?variant=A|B|C`，无头 Chrome 验证可渲染）与裁决记录作为一手材料留在 `prototype/02-node-chain-interaction` 的提交 `01a2d26`、`2326328`；主分支只保留决策。术语「交互模式（Interaction Mode）」已进 `GLOSSARY.md`。
