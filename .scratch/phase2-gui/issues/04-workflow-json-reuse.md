# 04: 核实工作流 JSON 的可复制性

Type: wayfinder:task
Status: open
Unblocks: 05

## Question

把 Android 侧 `WorkflowIo.kt` 与 `Workflows.kt` 的依赖关系列清楚，判断去掉 R 资源依赖后能否直接复制到 `desktop/core`；若不能，给出桌面侧最小 JSON 格式必须覆盖的字段。

只做核实与记录，不改桌面代码。结论用于解锁「工作流的保存与载入范围」这一决策。
