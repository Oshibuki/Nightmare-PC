# 13: 默认模式——加载内置工作流并编辑参数

**What to build:** 作为桌面用户，我打开应用就进入默认模式，看到一条内置 txt2img 工作流的链芯片与参数抽屉；选中芯片即可修改参数，并立即看到状态变化，不需要先理解自由画布。

**Blocked by:** 10 — Compose GUI 骨架与空窗口; 11 — 最小图模型与节点类型.

**Status:** resolved

- [x] 默认模式按原型 C 布局：顶部横向链芯片、左侧大结果预览、右侧参数抽屉
- [x] 内置工作流只含提示词、采样器、输出三类节点，加载后三个芯片可见
- [x] 选中芯片后可编辑该类参数：提示词与负提示；采样器、调度器、步数、CFG、seed、尺寸；保存选项
- [x] 参数变更反映到运行态与界面，且与高级模式共享同一份工作流数据
- [x] 无真实引擎与权重时也能用合成数据演示加载与编辑
- [x] 不出现第四种交互模式，也不引入表单化步骤变体

## Comments

- 2026-11-26：分支 `gui-ticket-13`，提交 `6d07844`「Add default-mode workflow shell with shared parameter state」；以 `--no-ff` 合并进集成分支 `desktop-migration`，合并提交 `0b28f1b`。仅改 `desktop/gui/`（5 files / 573 insertions）：`build.gradle.kts` 仅加 `testImplementation(libs.junit)` + `tasks.test`；新增 `DefaultModeScreen.kt`（原型 C 布局）、`DefaultModeState.kt`（共享 `WorkflowSession` + 状态快照 + 内置工作流）、`DefaultModeStateTest.kt`（7 个 JVM 行为测试）；改 `Main.kt` 入口。未碰共享构建脚本、`desktop/core/`、`app/`、`backend-patches/`、README 或移动版。
- 2026-11-26：TDD 与构建——红 `/tmp/ticket13-red.log`（`DefaultModeState`/`WorkflowSession` 未定义）→ 绿 `/tmp/ticket13-green2.log` 7/7 PASSED → `:gui:build` 成功（`/tmp/ticket13-ui-build.log`）→ 全量 `cleanTest build` 成功（`/tmp/ticket13-full-build.log`，gui/core/cli 全绿）。合并后复跑无缓存 `:gui:cleanTest :gui:test` 7/7 PASSED（`/tmp/ticket13-postmerge-test-nocache.log`；此前一次 FROM-CACHE 结果不作为证据）。`git merge desktop-migration` 无冲突。
- 2026-11-26：实机 GUI 在 `DISPLAY=:0` 用 `:gui:run` 启动（60 秒有界超时），截图 `/tmp/ticket13-gui.png` 与 `/tmp/ticket13-gui-sampler.png` 证实三芯片 rail、左预览、右抽屉布局及选中采样器后参数抽屉切换；全程合成数据（"合成预览（尚未运行引擎）"），未用引擎/权重；`pgrep` 无遗留 GUI 进程。
- 2026-11-26：**未验证边界（不夸大）**：键盘键入后的可视回显未通过 UI 自动化确认——`xdotool` 键入未改变 Compose 文本框，属自动化驱动限制而非应用报错；状态层对三类节点参数编辑与非法键拒绝有测试覆盖，界面抽屉切换有截图，代码由监听 revision 触发重组。参数"与高级模式共享同一份工作流数据"由单一 `WorkflowSession` 持有者保证（外部工具改同一 session 后默认模式可见的测试通过），但高级模式（工单 18）尚未实现，其实现必须注入同一 session，否则集成时需调整。
- 2026-11-26：Run 按钮为禁用占位；执行/进度/结果 PNG 属后续工单（14/15），本工单不涉及 CLI 子进程。未引入 UI 测试框架或新测试接缝。
