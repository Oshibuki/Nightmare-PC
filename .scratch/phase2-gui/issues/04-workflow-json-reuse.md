# 04: 核实工作流 JSON 的可复制性

Type: wayfinder:task
Status: resolved
Unblocks: 05

## Question

把 Android 侧 `WorkflowIo.kt` 与 `Workflows.kt` 的依赖关系列清楚，判断去掉 R 资源依赖后能否直接复制到 `desktop/core`；若不能，给出桌面侧最小 JSON 格式必须覆盖的字段。

只做核实与记录，不改桌面代码。结论用于解锁「工作流的保存与载入范围」这一决策。

## Answer

`WorkflowIo.kt`（1040 行）自身**没有 R 引用**，只依赖 `org.json`、`java.io.File` 与 `com.abrah.nightmare` 图模型；但**不能去 R 后直接复制**，因为传递依赖会把 Phase 1 明确排除的图执行与视频/NPU 资产一起拖进来：`Graph.kt`（1046 行，定义 `Graph`/`Node`/`Source`）本身被 `Executor.kt:411,2722` 的 `NodeType`/`NODE_TYPES` 阻塞（`desktop/docs/copied-files.md:102`），而 `WorkflowIo.kt` 还引用 `npu.WanVideoNode`、`ModelCatalog`、`Family`、`SdSampler`、`AddObjects`、`MaskCropNode`、`PasteNode`、`MediaOutputNode`、`UpscaleNode`、`FRAMING_TYPES`、`INPAINT_TYPES`。`Workflows.kt`（1112 行）有约 30 处 `R.string.*` 且 import `ModelCatalog`/`SelectedModel`，同样不能直接复制。

因此桌面侧应**自写最小 JSON 读写**（不复制这两个文件），可借用其线格式形状以保持兼容：根 `format`(=1)、`nodes`、`requires?`、`view?`；节点 `id`（必须是安全文件名，拒绝 `.`/`..`/`/`/`\`/ISO 控制符，`WorkflowIo.kt:156-157`）、`type`、`params`（string→string）、`inputs`（port→`"node"` 或 `"node:port"`）、`x`、`y`，可选 `w`/`lines`；`requires` 项 `{id,version}`；`view` `{x,y,scale,zoomLock,panLock}`。Phase 2 的 txt2img 切片最低只需 `format` + `nodes[id,type,params,inputs,x,y]`；`requires`/`view`/`w`/`lines` 是否纳入由工单 05 决定。可借鉴 `WorkflowStore`（`WorkflowIo.kt:758-859`）的「唯一临时文件 + `ATOMIC_MOVE`」保存模式（纯 JVM，但同样不直接复制）。可移植回归基线：`app/src/test/java/com/abrah/nightmare/canvas/WorkflowIoTest.kt`、`WorkflowStoreTest.kt`。
