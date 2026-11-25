# 16: 工作流保存、载入与版本校验

**What to build:** 作为桌面用户，我能显式保存、另存为与载入工作流 JSON；坏文件、版本不符或路径不可写时只报错，不会破坏当前正在编辑的工作流。

**Blocked by:** 13 — 默认模式——加载内置工作流并编辑参数.

**Status:** resolved

- [x] 支持新建、显式保存、另存为与载入；不做自动保存
- [x] 线格式为根 `format=1` 加节点数组，节点含 id、类型、参数、输入连线与位置；可选字段保持兼容形状
- [x] 节点 id 做安全文件名校验，拒绝点、斜杠与控制字符
- [x] 严格接受 `format=1`，其他值报「版本不兼容」
- [x] 保存采用唯一临时文件加原子替换的思路
- [x] 非法 JSON、版本不兼容、路径不可写三类失败统一走错误条且工作流不变
- [x] 临时目录加合成坏文件的端到端用例断言错误文案与「工作流未变」

## Comments

- 2026-11-26：分支 `gui-ticket-16`，提交 `fbfcea3`「Add desktop workflow persistence with strict format validation」；以 `--no-ff` 合并进集成分支 `desktop-migration`，合并提交 `38df99c`。仅 2 个文件、均在独占目录：`desktop/core/src/main/kotlin/com/abrah/nightmare/desktop/graph/WorkflowPersistence.kt`（169 行 API）与 `WorkflowPersistenceTest.kt`（239 行、12 个 JUnit4 用例）。未改 `desktop/gui/`、`app/`、`backend-patches/`、README、共享构建脚本。
- 2026-11-26：TDD 与构建——红 1（API 不存在，编译失败，`/tmp/gui16-tdd-red-1.log`）→ 绿 1（2 tests，`/tmp/gui16-tdd-green-1.log`）→ 10 tests（`/tmp/gui16-tdd-full-persistence.log`）；红 2（format 4294967297 被误收，`/tmp/gui16-tdd-red-2.log`）→ 修复后 11 tests（`/tmp/gui16-tdd-green-2.log`）；红 3（重复 id 保存后无法载入，`/tmp/gui16-tdd-red-3.log`）→ 修复后 12 tests（`/tmp/gui16-tdd-green-3.log`）。全量 `cleanTest build` → `BUILD SUCCESSFUL in 40s`（`/tmp/gui16-full-build.log`），`MinimalGraphTest` 4/4、`WorkflowPersistenceTest` 12/12、`DefaultModeStateTest` 7/7、CLI 全量（含真实 SD1.5 img2img）均 PASSED。合并后图测试 16/16（`/tmp/gui16-post-merge-tests.log`）。

## GUI 接线所需 API（供集成/工单 17，本工单未接线）

- 同包 `com.abrah.nightmare.desktop.graph`：`WorkflowPersistence.newWorkflow()`、`load(path): WorkflowResult<Workflow>`、`save(workflow, path): WorkflowResult<Path>`、`saveAs(workflow, path): WorkflowResult<Path>`。
- `WorkflowResult.Ok(value)` / `Err(error)`；`WorkflowError(kind, message, cause)`，`Kind = INVALID_JSON | INCOMPATIBLE_VERSION | UNSAFE_NODE_ID | UNWRITABLE_PATH`。错误条显示 `error.message`；失败时**不替换当前工作流**（`Ok` 才赋值，`Err` 只显示错误）。

## 偏离与风险（未验证项不夸大）

- 线格式写入严格复用 `Workflow.toJson()`/`WORKFLOW_FORMAT`（位置写成 `position:{x,y}`）；读取额外容忍扁平 `x`/`y`，不产生写入漂移。未知节点类型读取时接受（前向兼容，异于移动版拒绝）；重复 id 读写两侧都拒绝（保证"保存的文件一定能载入"）。
- 仅用 `ATOMIC_MOVE`，不支持原子移动的文件系统上保存报 `UNWRITABLE_PATH`，不退化为非原子替换。
- **GUI 保存/载入按钮未接线**（文件所有权限制）：需后续集成或工单 17 按上述 API 接线；本工单未验证 GUI 按钮交互。
- 未复制移动版 `WorkflowIoTest.kt`/`WorkflowStoreTest.kt`（带 Robolectric 与执行依赖），只借鉴形状与保存模式。
- 未验证 Windows/macOS 编译与真实引擎冒烟（范围外）。
