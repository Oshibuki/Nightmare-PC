---
status: accepted
date: 2026-11-26
---

# 0013 - 桌面图生图以图片输入节点承载，由运行器按节点切换 CLI 子命令

桌面 CLI 已完整支持独立的 `img2img` 子命令，但桌面工作流与 `WorkflowRunner` 只投影 `txt2img`，三种节点类型里也没有图片输入。为在对话模式里打通「给一张图 + 一句描述 → AI 改工作流 → 出图」，我们决定在工作流里新增最小的图片输入节点（携带 `path`/`strength` 参数，连到 sampler），并由 `WorkflowRunner` 检测该节点来决定投影成 `txt2img` 还是 `img2img` 子命令；仍保持单一运行入口与单一 CLI 子进程（ADR-0009），不新增独立的运行路径。

**Considered Options**：
1. 图片输入节点 + 运行器按节点切换（采纳）——图片是工作流的一等输入，契合「同一份工作流」与对话工具面；代价是节点注册表（ADR-0007 桌面自持）和运行器投影都要改。
2. 不建节点，把 `init-image` 作为全局运行参数另开一条 CLI 路径——改动小，但图片游离于工作流之外，AI 无法把「用这张图」表达为工具调用，违背第一条切片走对话工具面的决定。
3. 直接调 `Img2Img` 入口而非子进程——破坏 ADR-0009 的单运行/取消/关窗清进程语义，否决。

本轮范围只含普通图生图（init image + strength/denoise）；inpaint（局部重绘）与参考图输入（flux_edit / H3 reference 一类）明确排除，与既有规格的 Out of Scope 一致。strength 由 AI 经工具参数设置（沿用 CLI 默认 0.75、范围 0..1），首切片不做用户侧控件或结构化追问。

**端口约定（实现注释）**：图片输入节点输出 `image:IMAGE`，采样节点保留原有 `prompt:PROMPT` 并新增可选的 `initImage:IMAGE` 输入；既有文生图连线语义不变。运行器只按工作流里是否存在图片节点切换 CLI 子命令，`--init-img`/`--strength` 取自节点字符串参数，strength 越界由 CLI 报 usage error；不引入全局运行参数，也不直调引擎入口。
