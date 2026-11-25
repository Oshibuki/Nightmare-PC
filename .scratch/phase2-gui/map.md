# Map: Phase 2 桌面 GUI（Nightmare Desktop）

Type: wayfinder:map
Status: charted

## Destination

一份可交给 `/to-spec` 的 Phase 2 GUI 规格；验收口径为 Linux 出一条端到端纵向切片——搭建或载入一条 txt2img 工作流 → 运行 → 看到生成的图——Windows/macOS 同批只要求编译通过。

## Notes

- 领域术语读 `GLOSSARY.md`；已接受的 ADR-0006（Compose Multiplatform）是前提，不重新辩论。`.scratch/desktop-migration/spec.md` 的 Phase 2 段落只是背景，本图不复制其内容。
- 每会话先只读本 map，再从 `issues/` 取第一张「未阻塞且未认领」的工单；动手前把 `Status:` 改为 `claimed`，解决后改为 `resolved`、把结论写进该工单的 `## Answer`，并在下面 Decisions so far 追加一行结论指针。
- Charting 轮已锁定、不再开新工单重问的前提：①目的地是规格而非实现；②最小交付是端到端纵向切片；③Linux 可运行、Windows/macOS 仅编译；④GUI 到生成走 CLI 进程接缝，不新开 A1111 HTTP 或 Kotlin 直接调用接缝；⑤首批只复制 `CanvasModel`/`CanvasState` 与三份 JVM 测试，执行层 `Graph`/`Executor` 不复制，工作流沿用 Android 的「工作流（Workflow）」概念但桌面侧写最小导入导出；⑥进度消费 CLI stdout 的逐行输出；⑦测试沿用 Gradle 测试源集的假 CLI runner + 已有 SD1.5 CLI 冒烟，不引入 Compose UI 测试框架。
- HITL 工单（`prototype` / `grilling`）必须由人与 agent 现场对话解决，agent 不得替人作答。本仓库无远端，research 分支只在本地保留、不开 PR。
- 复制边界已由「NodeType 注册表与几何层的复制前置」锁定：桌面自写最小图模型/节点类型，复制几何/状态两层并就地换类型引用；移动版零改动，依据 ADR-0007。
- 交互模式按「节点链编辑的交互形态」裁决：默认=C、高级=A，共享同一工作流；对话模式是否纳入由工单 09 决定，不默认塞进纵向切片。

## Decisions so far

- [确认 Compose Multiplatform 的落地方式](issues/01-compose-build-setup.md)：保持 JVM 插件，新增 `:gui` 模块，Kotlin 2.4.21 + Compose compiler 2.4.21 + CMP 1.12.2，并在依赖仓库中加 `google()`；`cd desktop && ./gradlew :gui:run` 验证（结论文档在 research 分支提交 `beedd0e`）。
- [核实工作流 JSON 的可复制性](issues/04-workflow-json-reuse.md)：两个移动版文件都不能直接复制（传递依赖拖进 NPU/视频资产与 `R`）；桌面自写最小 JSON 读写，并借用 `format`/`nodes`/`requires`/`view` 线格式形状，安全 id 与原子保存模式照搬思路。
- [工作流的保存与载入范围](issues/05-persistence-scope.md)：最小闭环含新建/载入/保存，严格 `format=1`；显式保存，三类失败统一界面内错误条且工作流不变，用临时目录 + 合成坏文件做端到端断言；术语统一为「工作流/内置工作流」。
- [NodeType 注册表与几何层的复制前置](issues/08-nodetype-registry-prerequisite.md)：桌面自写最小图模型与节点类型（只含表现成员、三类 txt2img 节点），复制几何/状态两层并就地换类型引用；移动版零改动，已立 ADR-0007。
- [节点链编辑的交互形态](issues/02-node-chain-interaction.md)：默认模式用 C（链芯片 + 参数抽屉），高级模式用 A（自由画布），B 被否；三模式共享一份工作流与节点类型；对话模式另开工单 09。

## Not yet specified

- 节点库、连线、参数面板、缩放平移的具体交互形态：等「节点链编辑的交互形态」原型出结果后再定，一块雾可能落成好几张工单，也可能不落。
- Windows/macOS 的打包形态：当前只要求编译通过，不预切成工单。
- GUI 内是否只读集成 A1111 HTTP 的模型列表：当前排除，若将来重开范围应作为新一轮 effort。

## Out of scope

- 完整对标 Android 节点编辑器（Q2 选纵向切片，已排除全功能编辑器）。
- 原型变体 B（Guided stepper，表单化步骤）：在工单 02 中被否决，不进任何交互模式。
- A1111 HTTP 作为 GUI 的驱动接缝，以及 `progress`/`interrupt`/`options` 端点改造（Q4）。
- 为 GUI 引入 Compose UI 测试框架这一新接缝（Q7）。
- 视频模型、DiT 三模型端到端生成与移动版任何改动或自动同步（沿用 `.scratch/desktop-migration/spec.md` 的既有排除）。
