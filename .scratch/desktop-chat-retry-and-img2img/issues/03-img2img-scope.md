# 03: 图生图的范围、图片来源与工作流表示

Type: wayfinder:grilling
Status: resolved

## Question

裁决图生图首切片的四个边界：本轮范围（普通 img2img vs inpaint/参考图输入）？初始图来源（本地路径 vs 结果面板选图 vs 文件选择器）？图片在工作流里怎么表示（新节点 vs 运行参数）？strength 由谁定？

## Answer

- **范围**：只做**普通图生图**（给定初始图 + strength/denoise，按描述重画）。inpaint（局部重绘）与参考图输入（flux_edit / H3 reference 一类）明确排除，与既有规格 Out of Scope 一致。三种都是「给图 + 改」但语义不同，混在一轮会让地图与验收失焦。
- **图片来源**：用户在对话中给一个**本地文件路径**，经新工具参数传给工作流。「从结果面板选上一张产物图」与「文件选择器/附件上传」后置。理由：CLI 的 `--init-img` 就要路径，字符串参数直接落进 `format=1` 的 params，不引入文件选择器权限与 GUI 测试依赖。
- **工作流表示**：新增一个最小**图片输入节点**（携带 `path`/`strength` 参数，连到 sampler），由 `WorkflowRunner` 检测工作流里是否存在该节点来决定投影成 `txt2img` 还是 `img2img` 子命令；仍保持单一运行入口与单一 CLI 子进程（ADR-0009）。理由：图片是工作流的一等输入，契合「同一份工作流」与对话工具面；新节点受 ADR-0007 桌面自持纪律约束。另两个备选——把 init-image 当全局运行参数另开 CLI 路径、直接调 `Img2Img` 入口而非子进程——分别因违背工具面表达力与 ADR-0009 被否决，见 ADR-0013。
- **strength**：由 **AI 经工具参数设置**（沿用 CLI 默认 0.75、范围 0..1，移动版 `denoise=strength`），首切片不做用户侧控件或结构化追问；用户控制留到后续 effort 看反馈。术语「图生图（Img2Img）」已入 `GLOSSARY.md`，与「参考图输入」并列区分。
