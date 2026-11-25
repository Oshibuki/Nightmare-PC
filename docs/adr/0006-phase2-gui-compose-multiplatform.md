---
status: accepted
date: 2026-11-25
---

# 0006 - Phase 2 桌面 GUI 采用 Compose Multiplatform

桌面 GUI 不重写 Web 栈（Tauri/Electron），而用 Compose Multiplatform：移动版约 237 个 Kotlin 主源文件是最大可复制资产（ADR-0001），且节点图的几何/命中逻辑已刻意分层在无 Compose 依赖的 `CanvasModel.kt`/`CanvasState.kt`（可在 JVM 测试），正中跨平台复用面；自绘渲染层（`GraphCanvas.kt` 的 DrawScope 部分）按桌面平台重写。
