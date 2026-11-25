# 09 - 复制首批 JVM 可复用代码与测试并跑绿

Status: ready-for-agent
Type: task
Blocked by: 04

## 背景

复制式复用（ADR-0001）：从 `app/src/main/java/com/abrah/nightmare/` 单向复制与 Android 无关的 JVM 可测代码到 `desktop/core/`，允许就地修改，不回移。

## 首批候选（探索确认可在 JVM 运行）

- `ModelCatalog`、GGUF 导入相关
- `Executor` / `Scheduler`（执行调度）
- `Sampler` 参数逻辑
- `canvas/CanvasModel.kt`、`CanvasState.kt`（纯几何，为 Phase 2 预置）

## 步骤

1. 逐文件复制，文件头加来源注释（原路径 + 复制日期），必要时剥离 Android import。
2. 对应的测试从 `app/src/test/` 一并复制（约 201 个测试中与上述相关的部分），改造到 `desktop/` 的测试源集。
3. 每个复制文件记录到 `desktop/docs/copied-files.md`（来源 → 桌面路径），作为唯一同步追踪点（无自动同步机制）。

## 验收

- 复制代码的测试在 `desktop/` 全绿。
- `copied-files.md` 与实际文件一一对应。

## 备注

- 遇到 Android 依赖过深无法剥离的文件：跳过并记录原因，不强行改写。
