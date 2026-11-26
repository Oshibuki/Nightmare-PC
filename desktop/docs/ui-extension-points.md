# Desktop GUI 组件库扩展接缝

本次改动为将来的 UI 组件库替换预留了两个接缝，**当前没有引入任何新依赖**，组件仍然委托给 `androidx.compose.material`。

## 接缝位置

- `gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/ui/Theme.kt`
  `AppTheme.colors` / `AppTheme.dims` / `AppTheme.typography` 集中保存颜色、间距、圆角与字号 token。
- `gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/ui/Components.kt`
  `AppButton` / `AppCard` / `AppText` / `AppTextField` 是薄包装，视觉取值一律来自 `AppTheme`。

`ResultPreview`（默认模式与对话模式共用）和 `ChatBubble` 已改用这些包装组件作为示范。

## 将来替换组件库时

只需要改 `ui/Components.kt` 与 `ui/Theme.kt`：把包装内部从 Material 委托切换到新组件库，并把 token 映射到新主题。调用方只认识 `App*` 组件和 `AppTheme`，不需要逐个 shell 修改。

## 本次刻意不做的事

- 不做全仓大规模重构：其余 composable（链芯片、参数抽屉、设置对话框等）仍直接使用 Material，只在后续工单按需迁移。
- 不新增 UI 测试依赖，也不新增协程依赖：自动滚动使用 Compose 运行时的 `LaunchedEffect`；图片解码使用 `compose.desktop.currentOs` 已带的 Skiko。
- 不改动 Kotlin 状态层语义、`WorkflowRunner` 或 `ToolInterface`。
