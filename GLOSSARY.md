# Nightmare（跨平台迁移上下文）

把 Android 端 AI 图像/视频生成能力（nightmare-mobile）迁移到 Windows/Linux/macOS 的上下文。桌面能力由独立的 Nightmare Desktop 工程承载。

## Language

**Nightmare Desktop**:
面向 Windows/Linux/macOS 的独立版本，位于仓库顶层 `desktop/` 目录，构建与依赖同 Android 版完全解耦。
_Avoid_: 桌面骨架工程、desktop skeleton（口语可用，文档统一用本名）

**无头引擎（Headless Engine）**:
不带 UI 的推理交付形态，通过 CLI 与本地 HTTP API 对外提供服务，是 GUI 阶段之前的交付目标。
_Avoid_: 后端模式、server mode

**桌面补丁集（Desktop Patch Set）**:
从移动版 `backend-patches/` 中挑选出的、与 Hexagon/QNN 后端无关的引擎补丁子集，供桌面引擎构建使用。
_Avoid_: 把 `backend-patches/`（移动版全集）与桌面子集混用

**复制式复用（Copy Reuse）**:
从 Android 工程单向复制源文件到桌面工程、允许就地修改的复用方式；两版各自演进，无同步机制。
_Avoid_: 共享模块、代码共享

**纵向切片（Tracer Bullet）**:
一次交付即可打通端到端主链路的最小工单；验收覆盖真实输入到真实输出，而不是只完成一个孤立的横向技术层。
_Avoid_: 追踪弹（文档统一用本名，口语可说 tracer bullet）

**工作流（Workflow）**:
由节点、连线与参数构成的生成流程，可在 GUI 中搭建、保存与复用。随应用分发的预置工作流称「内置工作流」。
_Avoid_: 节点链、生成链、配方（Recipe，移动版实现类名，统一用本名）

**节点类型（Node Type）**:
决定节点在画布上如何连线、如何显示与如何被复用的类别标识。
_Avoid_: node type（口语可用，文档统一用本名）

**交互模式（Interaction Mode）**:
桌面上同一工作流的三种交互外壳：对话模式（用对话打磨提示词或参考图出图）、默认模式（链芯片 + 参数抽屉）、高级模式（自由节点画布）。
_Avoid_: 模式（单独使用指代不明，需写明哪一种）

**对话回合（Chat Turn）**:
对话模式里从用户发出一条消息到 agent 完成本轮工具调用与产物的一个完整周期；一次回合可含多次工具调用与一张或多张产物图。
_Avoid_: 一轮对话、一次请求（文档统一用本名）

**结构化追问（Ask）**:
agent 在对话回合中向用户提出的澄清问题，带候选项；用户点选后其答案写回会话并让该回合继续。
_Avoid_: Ask 气泡、选项问题（文档统一用本名）

**重试（Retry）**:
把上一条已发出的用户消息当作一条新消息再次提交、开启一个新的对话回合；既有的对话气泡与产物图保留，未发送的草稿不参与。
_Avoid_: 原地重放、重跑、粘贴重发

**图生图（Img2Img）**:
以一张初始图与重绘强度（strength）为输入、按文字描述改写画面的生成方式；初始图是工作流里的图片输入节点，区别于只给描述从零生成的文生图。
_Avoid_: 参考图输入、局部重绘（inpaint，本轮范围外）

**运行态（Run State）**:
GUI 表示一次生成的生命周期：空闲、运行中、成功、失败、取消，连同当前进程句柄与结果。
_Avoid_: 任务状态、生成状态（文档统一用本名）

**进度事件（Progress Event）**:
GUI 从 CLI stdout 解析出的一次生成进度记录：序号、总数、seed、目标路径；引擎原始输出另行归入运行日志。
_Avoid_: 进度行（CLI 输出称进度行，GUI 状态统一用本名）

**验收脚本（Verification Script）**:
一条命令完成构建、测试、假 runner 端到端与可选的真实冒烟，并为每步留存超时与日志。
_Avoid_: 冒烟脚本、测试脚本（文档统一用本名）
