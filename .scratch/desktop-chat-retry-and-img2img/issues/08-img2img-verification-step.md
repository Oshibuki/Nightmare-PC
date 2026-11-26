# 08: 验收脚本的图生图步骤

**What to build:** 作为维护者，我能在 Linux 上用一条既有的验收命令闭环验证图生图——假 CLI 断言工作流投影带了 `--init-img`/`--strength`、产物 PNG 进结果面板，无需真实模型或密钥；Windows/macOS 只要求编译通过。

**Blocked by:** 06 — 图片输入节点与运行器 img2img 投影; 07 — 对话模式经工具发起图生图

**Status:** resolved

- [x] 在既有验收脚本（ADR-0011 单一入口）的 chat-turn 步之后新增独立 img2img 步，用既有 run_step 封装、独立日志、硬超时；不新建脚本
- [x] 断言假路径全链路：含图片节点的工作流经运行器投影出 `--init-img`/`--strength`（默认 0.75）、假 CLI 产出 PNG、产物进结果面板；断言按参数名比较、不做字符串全等
- [x] 断言对话路径：一句话驱动的图生图经工具改工作流产出 Picture 气泡与结果快照；既有文生图步骤仍全绿
- [x] 脚本自测的期望日志清单同步纳入新步骤，顺序与超时断言不破坏既有步骤
- [x] 真 provider 冒烟标注为可选手工、缺环境变量或网络则跳过不阻塞；Windows/macOS 只要求本地 GUI 模块编译通过
- [x] 一条命令全绿、各步独立日志留存；未验证的边界（真模型、Windows/macOS 端到端）如实记录

## Comments

实现提交 `c9a4cc2`（分支 `retry-img2img-ticket-08`，基于集成分支 `desktop-retry-img2img` / `8f23de8`；`git merge desktop-retry-img2img` 为 already up to date，无冲突）。只改 `desktop/verify-gui.sh`、`desktop/verify-gui.test.sh`、`desktop/README.md` 与本工单文件；未改任何功能代码、`app/` 或依赖。

- TDD 红：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17`，`cd desktop`，`timeout 120s bash verify-gui.test.sh`（stub gradlew），退出码 1，115 passed / 14 failed——新日志缺失、顺序断言失败、img2img 失败/超时用例均未按预期触发。日志：`/tmp/nm-desktop/logs/retry-img2img-08-script-test-red.log`。
- TDD 绿：同一命令退出码 0，129 passed / 0 failed。日志：`/tmp/nm-desktop/logs/retry-img2img-08-script-test.log`；合并后再跑退出码 0、129 passed / 0 failed，日志 `/tmp/nm-desktop/logs/retry-img2img-08-script-test-postmerge.log`。新增用例覆盖 chat-turn → img2img → persistence 顺序、失败放行（退出码 7、persistence 不执行）、独立硬超时（1 s，退出码 124）与期望日志清单；`bash -n` 两脚本通过。
- 真实一键验收：`VERIFY_GUI_LOG_DIR=/tmp/nm-desktop/logs timeout 2400s ./verify-gui.sh`，退出码 0，末行 `verify-gui: PASS (all enabled steps)`。日志：`/tmp/nm-desktop/logs/retry-img2img-08-verify-gui.log`。各步独立日志均留存：`verify-gui-{classes,launch-config,build,fake-runner,chat-turn,img2img,persistence,real-smoke}.log`；`verify-gui-img2img.log` 含 `verify-gui: img2img OK` 及 `an image input node produces an img2img run with the source and default strength`、`one sentence drives an img2img turn through the five existing commands`、`add_node accepts type aliases and both node_type spellings` 的 PASSED。
- 假路径断言由工单 06/07 的既有 JUnit 测试承担（`WorkflowRunnerTest` 按参数名取 `--init-img`/`--strength` 默认 0.75、假 CLI PNG 进结果快照；`ChatSessionTest` 一句话驱动工具得 Picture 气泡与快照；`DesktopToolCommandsTest` 校验图片节点工具 schema）；本工单负责在单一入口中稳定点名这些测试，没有重写功能断言或字符串全等比较。既有文生图/chat-turn 步同轮全绿。
- 本次真实运行时权重缓存与 Vulkan 引擎均存在，故 real-smoke 实际执行并 PASSED（不是跳过）；缺权重/缺引擎的跳过分支由 `verify-gui.test.sh` 的 stub 用例断言为不阻塞。真 provider（网络 + `NIGHTMARE_LLM_*`）仍按 ADR/规格留作可选手工检查，本次未运行；Windows/macOS 端到端未在本机执行，仅保留既有 `:gui:classes` 编译入口与 README 说明。
