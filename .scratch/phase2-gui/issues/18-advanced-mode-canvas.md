# 18: 高级模式——自由节点画布

**What to build:** 作为桌面用户，我能切到高级模式，用节点库、自由画布、连线与属性面板搭建同一条 txt2img 工作流；切回默认模式后工作流和参数都还在，并能在高级模式直接运行。

**Blocked by:** 12 — 复制几何/状态层并换类型引用; 14 — 运行——子进程、进度事件与成功产物.

**Status:** resolved

- [x] 高级模式按原型 A 布局：节点库、带连线的自由画布、属性面板与结果条
- [x] 使用工单 12 已就位的几何/状态层与工单 11 的节点类型，三类节点可从节点库添加
- [x] 端口可连线并给出明确反馈；缩放平移沿用已复制状态层的能力
- [x] 两种交互模式切换时工作流不丢、参数一致，不产生第二份数据
- [x] 高级模式能触发运行并看到同一进度事件与结果面板
- [x] 不引入表单化步骤变体或第四种交互模式

## Comments

**提交**
- `771ebc0` — Add advanced free-canvas mode over the shared workflow
- `fa3c193` — Keep one CLI run alive across mode switches
- 分支：`gui-ticket-18`；完成前 `git merge desktop-migration --no-edit` 两次均为 “已经是最新的”。

**改动**
- 新增 `desktop/gui/.../AdvancedModeState.kt`：把已复制的 `CanvasState`/几何层桥接到同一个 `WorkflowSession`；节点坐标写回 `Node.position`，不另存第二份工作流；提供端口拖拽、连线反馈、缩放/平移与共享运行控制。
- 新增 `desktop/gui/.../AdvancedModeScreen.kt`：原型 A 的节点库 / 自由画布 / 属性面板 / 结果条；拖拽手势经由 `CanvasState.press/drag/release`，拒绝连线在画布与消息条显示原因。
- 改 `Main.kt`：窗口级共享 `WorkflowSession` + `WorkflowRunner`，只在默认/高级两个 shell 间切换；窗口级 `DisposableEffect` 负责关窗结束子进程，模式切换不杀运行。
- 改 `DefaultModeScreen.kt`：加入切到高级模式按钮；不再在屏幕 dispose 时杀进程。
- 新增 `AdvancedModeStateTest.kt`：8 个 JVM 外部行为测试，无 UI 测试框架。

**真实命令与结果**
- 红：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17 && cd desktop && timeout 900 ./gradlew --no-daemon :gui:test --tests 'com.abrah.nightmare.desktop.gui.AdvancedModeStateTest' 2>&1 | tee /tmp/gui18-red.log` → `compileTestKotlin FAILED`，`Unresolved reference 'AdvancedModeState'`（预期）。
- 绿：`... --tests '...AdvancedModeStateTest' 2>&1 | tee /tmp/gui18-green-2.log` → 8/8 PASSED，`BUILD SUCCESSFUL in 9s`。
- GUI 全量：`... :gui:test 2>&1 | tee /tmp/gui18-gui-test-5.log` → 38/38 GUI tests PASSED，`BUILD SUCCESSFUL in 28s`。
- 最终全量：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17 && cd desktop && timeout 1200 ./gradlew --no-daemon --no-build-cache cleanTest build 2>&1 | tee /tmp/gui18-final-build-2.log` → core/gui/cli 全量测试 PASSED，`BUILD SUCCESSFUL in 31s`。
- 工作区：`git status --short` 干净（提交工单文档前）。

**验收证据**
- 布局与三类节点：`AdvancedModeStateTest.advanced mode renders ...`、`palette adds all three ...`；画布由 `layout()`/`wires()` 生成节点盒与连线端点。
- 端口连线与反馈：`dragging from an output port to a compatible input ...`、`dragging between incompatible ports refuses ...`；兼容连线写入共享图，类型不符保持 `workflow.toJson()` 不变并返回 `PROMPT does not fit a IMAGE port`。
- 缩放平移：`zoom and pan reuse the copied viewport state`；焦点缩放往返误差 < 0.01，平移 delta 精确。
- 单一数据源：`editing in advanced and default modes ...`、`a node added in advanced mode appears in the default chain rail`；`assertSame(session, ...)` 且节点数保持 3。
- 运行：`advanced run uses the shared runner ...` 用 POSIX 假 CLI 验证同一 `RunSnapshot` 的进度事件、PNG 路径与 `seed=11` 参数。
- 端口命名核对：工单 12 风险提到的 `media` 只出现在原型 HTML；桌面注册表实际为 `core.prompt` 输出 `prompt/PROMPT`、`sd.sample` 输入 `prompt/PROMPT` 输出 `image/IMAGE`、`core.output` 输入 `image/IMAGE`。本实现按注册表命名接线，未引入第四种端口词汇。
- 未引入表单化步骤变体或第四种交互模式：仅 `InteractionMode.DEFAULT/ADVANCED` 两个枚举值；高级 shell 是自由画布。

**偏离与风险**
- 未做真实 GUI 指针/截图端到端验证：spec 禁止引入 UI 测试框架，本轮仅以 JVM 状态测试 + Compose 模块编译证明外部行为；鼠标拖拽在真实窗口中的像素坐标转换仍需人工冒烟。
- 高级画布未复刻移动版全部编辑器能力（多选、裁剪、预览缩放等）；本工单只要求三类节点的库 / 连线 / 属性 / 结果条与共享运行，未越界。
- 缩放通过窗口按钮与拖拽平移暴露；滚轮缩放未接入（当前 Compose 版本没有可用的 `awaitPointerEventScope` API，改用 `detectDragGestures`/`detectTapGestures`），复用的是同一 `Viewport.zoomedAround` 状态层能力。
