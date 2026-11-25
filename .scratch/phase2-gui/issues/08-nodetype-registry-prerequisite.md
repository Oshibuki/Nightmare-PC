# 08: NodeType 注册表与几何层的复制前置

Type: wayfinder:grilling
Status: resolved
Unblocks: 02

## Question

`desktop/docs/copied-files.md:102-104` 记录：`Graph.kt`、`CanvasModel.kt`、`CanvasState.kt` 都依赖 `Executor.kt:411,2722` 的 `NodeType`/`NODE_TYPES`，而 `Executor.kt` 是 Android 耦合的后端宿主；`.scratch/desktop-migration/spec.md` 又明确排除「移动版任何功能或代码改动」。

要让桌面 GUI 拥有节点图几何/状态层，选哪条：(a) 在 `desktop` 自写最小 `NodeType`（只覆盖 txt2img 链所需类型）；(b) 改移动版把注册表拆成平台无关文件（需重新审视 out-of-scope）；(c) 不复制 `CanvasModel`/`CanvasState`，GUI 自写几何与状态层？

请决定复制边界是否破例；若破例，是否需要单独立一份 ADR 记录这个反直觉的取舍。

## Answer

选 A + A：桌面自写最小图模型（`Graph`/`Node`/`Source`/`Port` 几个 data class）与最小「节点类型」，只保留端口、标题、尺寸、是否显示结果等**表现成员**，默认覆盖 txt2img 三类节点：`core.prompt`、一个 SD 采样器、`core.output`。`CanvasModel`/`CanvasState` 照常按 ADR-0001 复制，就地把类型引用换成桌面定义；不复制 `Graph.kt` 的执行/迁移部分，不改移动版一行代码，也不搬 `NodeType` 的执行语义（`contextKey`/`cacheable`/`appSide`/`outputSize` 等）。

理由：`NodeType` 约 24 个成员中一半是执行语义，`NODE_TYPES` 还注册了视频/NPU 节点（`Executor.kt:2722` 起），而几何层只用到端口/标题/宽度；拆移动版注册表会破 spec 的 out-of-scope 并制造同步义务，全自写几何/状态层会丢掉三个 JVM 测试基线。

已立 `docs/adr/0007-desktop-minimal-node-type-registry.md`，并把「节点类型（Node Type）」写入 `GLOSSARY.md`。本工单解锁「节点链编辑的交互形态」（02）。
