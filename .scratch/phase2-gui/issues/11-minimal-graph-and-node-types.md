# 11: 最小图模型与节点类型

**What to build:** 作为 AI coding agent，桌面侧有一套纯 JVM 的最小图模型与节点类型，只承载工作流的表现语义；三类 txt2img 节点能被创建、连线与序列化，为两种交互模式提供唯一的数据基础。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] 图模型以纯 JVM 数据表示节点、连线、端口与位置，不依赖任何 UI 框架
- [x] 节点类型只含表现成员（端口、标题、宽度、是否显示结果等），不含执行语义
- [x] 默认覆盖三类节点：提示词、SD 采样器、输出
- [x] 节点类型与工作流使用 `GLOSSARY.md` 的统一术语
- [x] JVM 测试覆盖三类节点创建、合法与非法连线、序列化形状
- [x] 不复制移动版执行与迁移逻辑，不引入视频或 NPU 节点
- [x] 移动版零改动，符合 ADR-0007

## Comments

- 2026-11-26：分支 `gui-ticket-11`，提交 `847b162`「Add desktop-only minimal workflow graph and node types」；以 `--no-ff` 合并进集成分支 `desktop-migration`，合并提交 `29521b5`。仅新增 3 个文件：`:core` 的 `graph/NodeType.kt`、`graph/Workflow.kt` 与 `MinimalGraphTest.kt`（326 行）；未改共享构建脚本与 README，移动版零改动。
- 2026-11-26：三轮 TDD 红绿循环——`/tmp/gui11-red1.log` Unresolved reference → `/tmp/gui11-green1.log` 1 test PASSED；`/tmp/gui11-red2.log` Workflow/ConnectionResult 未定义 → `/tmp/gui11-green2.log` 3 tests PASSED；`/tmp/gui11-red3.log` toJson 未定义 → `/tmp/gui11-green3.log` 4 tests PASSED。全量 `--no-build-cache cleanTest build` → `BUILD SUCCESSFUL`（`/tmp/gui11-build.log`），合并后复跑同命令新测试 4/4 PASSED（`/tmp/gui11-final-build.log`）。
- 2026-11-26：类型放在 `:core` 而非 `:gui`（已与工单 10 实施者协调避免冲突）；`:gui` 后续通过 `implementation(project(":core"))` 复用。序列化只写 `format=1 + nodes` 形状，读取/版本校验/原子保存留给工单 16，后续应复用 `Workflow.toJson()` 与 `WORKFLOW_FORMAT` 避免线格式漂移。未验证 Windows/macOS 编译与真实引擎冒烟（范围外）。
- 2026-11-26：集成分支合并 10、11 后复跑全量构建 → `BUILD SUCCESSFUL in 40s`，4 条 `MinimalGraphTest` 全 PASSED（`/tmp/gui-merge-verify.log`）。
