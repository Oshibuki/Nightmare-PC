# 13: 验收脚本的对话出图步骤

**What to build:** 作为维护者，我能在 Linux 上用一条既有验收命令闭环验证对话出图——假 ChatModel + 假 CLI 跑通「对话→工作流→出图」全链路、追问的放行与取消双路径都被断言，无需真实 provider 密钥或网络；Windows/macOS 只要求编译通过。

**Blocked by:** 09 — 第三条对话模式与一条对话回合; 10 — provider 设置与密钥注入; 11 — 结构化追问（Ask）的放行与回写; 12 — 回合生命周期——取消、并发、切模式、重试

**Status:** resolved

- [x] 在既有验收脚本的假 runner 步之后新增 chat-turn 步，用既有 run_step 封装、独立日志、硬超时；不新建脚本（ADR-0011 单一入口）
- [x] 断言假路径全链路：假 ChatModel 脚本驱动真实 agent 循环 → 五条工具命令 → 假 CLI → 产物图；断言搭出 `prompt→sampler→output`、seed、图片事件、结果入面板
- [x] 断言追问双路径：点选 `answer(id,option)` 后同回合继续出图；取消/关窗时以 `CANCEL` 放行、后台线程不挂起、气泡标「已取消」
- [x] 真 provider 冒烟标注为可选手工，缺环境变量或网络则跳过、不阻塞；Windows/macOS 只要求本地编译通过、不建 CI
- [x] 一条命令全绿：全量构建与测试通过，验收命令返回零退出码并保留各步日志

## Comments

- 实现（ADR-0011 单一入口）：只在既有 `desktop/verify-gui.sh` 的 fake-runner 之后插入第 4 步 `chat-turn`，复用 `run_step` + 独立 `verify-gui-chat-turn.log` + 硬超时（默认 600 s，可用 `VERIFY_GUI_CHAT_TURN_TIMEOUT` 覆盖以便有界测试）。`--tests` 指名 `com.abrah.nightmare.desktop.gui.fake.FakeChatModelWorkflowTest`、`ChatSessionTest`、`AskSessionTest`、`TurnLifecycleTest`；不新建脚本、不新建测试、不改功能代码。`desktop/verify-gui.test.sh` 的 `assert_has_logs` 清单补入新日志，并新增顺序、四条过滤器、失败放行与超时断言；`desktop/README.md` 同步步骤表、超时变量与边界说明。
- 断言覆盖（复用既有 `:gui` JUnit4 用例，未新增重复测试）：假 ChatModel 脚本驱动真实 Agent 循环 → 五条 `ToolCommand` → POSIX 假 CLI → 产物图，断言 `prompt→sampler→output` 节点/两连线、`seed=11`、`ChatItem.Picture` 与 `WorkflowRunner` listener/snapshot 里的成功结果；Ask 放行路径断言 `answer(id, option)` 后同回合继续出图且选项写回气泡，取消路径断言 `CANCEL` 放行、等待线程不挂起、气泡标「已取消」、关窗统一清进程。无 Compose UI 测试依赖、无真实 provider、无网络。
- TDD 红→绿：先改 `verify-gui.test.sh` 后运行，红日志 `/tmp/nm-desktop/logs/ticket-13-script-test-red.log`（exit 1：40 passed / 17 failed，缺 chat-turn 日志、过滤器、顺序、失败与超时断言）；加入脚本后绿日志 `/tmp/nm-desktop/logs/ticket-13-script-test-green.log`（exit 0：57 passed / 0 failed）。`bash -n` 两脚本均通过。
- 真实一键验收（非 stub）：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17`、`cd desktop`、`VERIFY_GUI_LOG_DIR=/tmp/nm-desktop/logs timeout 2400s ./verify-gui.sh`，exit 0，完整输出 `/tmp/nm-desktop/logs/ticket-13-verify-gui.log`，结尾 `verify-gui: PASS (all enabled steps)`。各步独立日志 `verify-gui-classes.log`、`verify-gui-build.log`、`verify-gui-fake-runner.log`、`verify-gui-chat-turn.log`、`verify-gui-persistence.log`、`verify-gui-real-smoke.log` 均留存；chat-turn 步实际执行 `> Task :gui:test`，17 个用例 PASSED、0 FAILED，日志内为 `verify-gui: chat-turn OK`。`gui-classes`/`build` 两步因 Gradle 缓存显示 UP-TO-DATE；fake-runner、chat-turn、persistence、real-smoke 四步均真实执行。
- 强制非缓存复核：`timeout 600s bash ./gradlew --no-daemon :gui:test --rerun-tasks` 加同样四条 `--tests` 过滤器，exit 0，17 PASSED、11 tasks executed，日志 `/tmp/nm-desktop/logs/ticket-13-gui-chat-turn-rerun.log`。
- 边界：真 provider 冒烟是可选手工项，`NIGHTMARE_LLM_PROVIDER/_BASE_URL/_KEY/_MODEL` 在本机均未设置（仅检查是否设置，未读取值），故未做真实 provider 调用，自动验收不依赖密钥或网络；脚本中的 real-smoke 是 SD1.5 权重/引擎冒烟，本机权重与引擎齐全所以真实执行并通过，并非跳过。Windows/macOS 端到端未在本 Linux 主机验证，按 ADR-0011 只要求本地 `:gui:classes`（本轮 Linux 已编译通过），未建 CI、未改 workflow。Compose 鼠标/关窗事件端到端、功能代码、`app/`、`backend-patches/` 均未改动。
- 集成分支：`git merge desktop-chat-mode` 无冲突（`already up to date`，`/tmp/nm-desktop/logs/ticket-13-merge.log`）；合并后重跑脚本自测仍绿（`/tmp/nm-desktop/logs/ticket-13-post-merge-script-test.log`）。
