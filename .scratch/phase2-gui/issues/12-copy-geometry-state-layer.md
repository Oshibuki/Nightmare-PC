# 12: 复制几何/状态层并换类型引用

**What to build:** 作为 AI coding agent，从移动版复制来的画布几何与手势状态在桌面编译通过，且既有三份 JVM 测试全绿——高级模式所需的表现层已经就位，移动版一行未改。

**Blocked by:** 11 — 最小图模型与节点类型.

**Status:** resolved

- [x] 几何层与状态层按复制式复用落地，就地把类型引用替换为工单 11 的桌面节点类型
- [x] 三份既有 JVM 测试全部迁移并通过；就地修改不得使其转红
- [x] 不复制执行与迁移逻辑，不引入 NPU/视频节点及其依赖
- [x] 复制来源有追踪记录，桌面自写部分明确标注
- [x] 测试命令带超时与日志，结果可复核
- [x] 遵守 ADR-0001 与 ADR-0007，移动版零改动

## Comments

- 2026-11-26：分支 `gui-ticket-12`，提交 `f992b00`「Copy desktop canvas geometry and gesture state with presentation-only types」（8 files / +4860 行）；合并 `desktop-migration` tip 于 `284a4b5`（零冲突），以 `--no-ff` 合并进集成分支为 `d288883`。全部改动在 `desktop/core/src/{main,test}/kotlin/com/abrah/nightmare/desktop/canvas/`：`CanvasModel.kt`（复制自 `canvas/CanvasModel.kt` 纯 JVM 几何段）、`CanvasState.kt`（复制自 `canvas/CanvasState.kt`）、`CropGeometry.kt`（仅取 `Framing.kt` 的 CropGeometry/PadRule 与 `CropEditor.kt` 的 CropRect/CropView/cropRectOf/wholePhotoFraming/asParams）、`CanvasSupport.kt`（明确标注 desktop-authored 胶水），加三份测试 `CanvasGeometryTest`(29)/`CanvasStateTest`(83)/`CropRectTest`(18) 与 `CanvasTestFixtures.kt`。每个复制文件首行标注 provenance；`app/`、`backend-patches/`、`desktop/gui/` diff 为空，新文件无 `android*`/`androidx*`/`R.string` import。
- 2026-11-26：TDD 与测试——红 `/tmp/gui12-red1.log`（Unresolved reference: `NodeType.prose`/`Workflow.connected`/`sizeRefusal` 等）→ 编译绿 → 三份测试迭代 130 tests/7 failed → 2 failed → 1 failed → **130/130 PASSED**（`/tmp/gui12-test4.log`）。合并前全量 `cleanTest build`（`/tmp/gui12-full-build.log`）、合并后全量（`/tmp/gui12-post-merge-build.log`）均 `BUILD SUCCESSFUL`；集成分支再跑一次 `--no-build-cache cleanTest build`（`/tmp/gui-12-13-merge-verify.log`，`BUILD_EXIT=0`），XML 统计 **322 tests / 0 failures / 0 errors / 0 skipped**（core+cli+gui，含 canvas 130、gui DefaultModeState 7）。
- 2026-11-26：范围裁决（team lead 查证）——三份 = `CanvasGeometryTest` + `CanvasStateTest` + `CropRectTest`；`WorkflowIoTest` 排除（Robolectric + NodeCtx/Value/迁移语义，属工单 16）。`Framing.kt` 只复制 CropRectTest 用到的纯几何，未带入 `deriveSizes`/`rendersNowhere`/`sizeMismatches` 执行函数，符合 ADR-0007「不搬执行语义」。未出现"必须拖入执行语义才能过测"的情况。

## 偏离与风险（集成时须处理，未验证项不夸大）

- `CanvasStateTest` 有 4 处执行语义断言**就地改写但未删测试**：`cacheKey`→params 快照、`topoSort`→悬空连线检查、匿名 `object : NodeType`（含 `run()`）→桌面 data class、`UpscaleNode`→本地 NodeType。断言意图保留，但原始执行断言依赖未复制的 `Executor`，该部分未验证。
- `NODE_TYPES`/`RECIPES` 用测试本地替身；端口命名沿用移动测试（`core.output` 输入 `media`、`sd15.sample` 输出 `image`），与工单 11 注册表（输入 `image`）不同。工单 18 接真实画布时须核对，属集成风险。
- `CanvasSupport.kt` 的执行替身：`modelById`/`videoPromptsFor` 返回 null，`sizeRefusal` 只留「裸图片→编码器须加 Crop」一条规则；高级模式接真实运行态前需补齐，目前只保证几何/手势行为。
- `desktop/docs/copied-files.md` 未更新（按分工只改 `desktop/core/`），provenance 现仅在文件首行，收口时建议补表。
- 未验证 Windows/macOS 编译与真实引擎冒烟（范围外）；三份测试均为同步 JUnit，无事件驱动内部超时需求。
