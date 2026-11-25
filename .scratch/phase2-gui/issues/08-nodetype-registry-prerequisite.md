# 08: NodeType 注册表与几何层的复制前置

Type: wayfinder:grilling
Status: open
Unblocks: 02

## Question

`desktop/docs/copied-files.md:102-104` 记录：`Graph.kt`、`CanvasModel.kt`、`CanvasState.kt` 都依赖 `Executor.kt:411,2722` 的 `NodeType`/`NODE_TYPES`，而 `Executor.kt` 是 Android 耦合的后端宿主；`.scratch/desktop-migration/spec.md` 又明确排除「移动版任何功能或代码改动」。

要让桌面 GUI 拥有节点图几何/状态层，选哪条：(a) 在 `desktop` 自写最小 `NodeType`（只覆盖 txt2img 链所需类型）；(b) 改移动版把注册表拆成平台无关文件（需重新审视 out-of-scope）；(c) 不复制 `CanvasModel`/`CanvasState`，GUI 自写几何与状态层？

请决定复制边界是否破例；若破例，是否需要单独立一份 ADR 记录这个反直觉的取舍。
