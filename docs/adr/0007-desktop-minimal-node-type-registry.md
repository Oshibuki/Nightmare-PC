---
status: accepted
date: 2026-11-26
---

# 0007 - 桌面 GUI 自写最小节点类型与图模型，不拆移动版注册表

移动版的 `NodeType`/`NODE_TYPES` 定义在 Android 耦合的 `Executor.kt` 中，注册表还包含视频/NPU 节点；桌面 GUI 只需要节点的端口、标题、尺寸等表现信息。桌面侧自写最小「节点类型」与图模型，复制 `CanvasModel`/`CanvasState` 时就地替换类型引用，不改移动版一行代码。

**Considered Options**：拆移动版注册表会破 spec 的「移动版零改动」并制造同步义务；整份复制 `Graph.kt` 会把执行语义与视频节点带进桌面；全自写几何/状态层会丢掉三个 JVM 测试的回归基线。

**Consequences**：桌面节点类型只覆盖 txt2img 链所需的三类节点（`core.prompt`、一个 SD 采样器、`core.output`）；将来新增节点类型需在桌面手动补定义，与移动版没有自动同步。
