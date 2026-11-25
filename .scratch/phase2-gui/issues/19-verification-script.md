# 19: 验收脚本与三平台编译口径

**What to build:** 作为 AI coding agent，我有一条命令贯通构建、测试、假 runner 端到端与可选的真实冒烟，每步都有超时与日志；Windows/macOS 只需本地编译通过，不因平台阻塞交付。

**Blocked by:** 15 — 失败、取消与超时的运行态收口; 16 — 工作流保存、载入与版本校验; 17 — 外部工具驱动接口预留; 18 — 高级模式——自由节点画布.

**Status:** resolved

- [x] 一条验收命令串起 GUI 编译校验、全量构建与测试、假 runner 端到端，以及缓存存在时的真实 SD1.5 冒烟
- [x] 每一步都有超时并留存日志；无头图形运行套虚拟显示
- [x] 无权重缓存时跳过真实冒烟且不阻塞，结果明确说明已跳过
- [x] Linux CI 继续跑构建并上传测试报告，不新建 Windows/macOS CI job
- [x] 文档写明 Windows/macOS 本地编译校验命令与当前验收边界
- [x] 任一步失败时验收命令返回非零退出码并保留日志

## Comments

**提交**
- `96a6d3a` — Add one-command Phase 2 GUI verification script（脚本、有界 shell 断言测试、README 验收段）
- 分支：`gui-ticket-19`（未 push、未加 remote）；完成前 `git merge desktop-migration` 结果为「已经是最新的」，无冲突；合并后 shell 断言复跑仍绿。
- 本文件随工单收口提交。

**改动**
- 新增 `desktop/verify-gui.sh`（0755）：一条命令串起 `:gui:classes` → `build` → `:gui:test`（显式列出 `WorkflowRunnerTest`/`ToolInterfaceTest`/`DefaultModeStateTest`/`AdvancedModeStateTest`）→ `:core:test --tests WorkflowPersistenceTest` → 缓存存在时的 `:cli:test --tests Img2ImgRealE2ETest`；每步 `timeout` + `tee` 独立日志，首步失败即非零退出并保留全部日志。无权重写 `已跳过（无权重缓存）`，无引擎写 `已跳过（无引擎二进制）`，均不阻塞。`VERIFY_GUI_LAUNCH=1` 的可选 `:gui:run` 步骤优先用现有 `DISPLAY`，无显示时套 `xvfb-run -a`，两者皆无则写 `已跳过（无显示且无 xvfb-run）`；窗口活到超时（rc 124）视为成功。
- 新增 `desktop/verify-gui.test.sh`（0755）：39 条有界 shell 断言，用 stub gradlew 验证退出码、日志、跳过说明、四类名与持久化类的显式调用、失败快速退出、DISPLAY/xvfb/无显示三种图形分支；不依赖 Gradle、网络或真实权重。
- 改 `desktop/README.md`：新增「Phase 2 acceptance: one command (Linux)」，列步骤/超时/日志表与环境变量，写明可选真实冒烟、图形显示策略和 shell 断言命令；新增「Windows and macOS: compile only」，给出 `.\gradlew.bat --no-daemon :gui:classes` 与 `./gradlew --no-daemon :gui:classes`，明确本轮只编译、不冒烟、不建 CI job；说明现有 `.github/workflows/desktop.yml` 保持不变。
- 未改 `.github/workflows/desktop.yml`：现有 Linux `build` job 已跑全量构建并在失败时上传 `desktop/*/build/reports/tests/`，满足工单且不引入 Windows/macOS job。未改 `app/`、`backend-patches/`、任何 `desktop/*/` 源码或共享构建脚本。

**真实命令与结果**
- 红：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17 && cd desktop && timeout 120 bash verify-gui.test.sh 2>&1 | tee /tmp/verify-gui-test-red.log` → 脚本不存在，33 项失败 / 6 项通过，测试退出码 1（预期）。
- 绿：`... timeout 120 bash verify-gui.test.sh 2>&1 | tee /tmp/verify-gui-test-green2.log` → 39 passed, 0 failed，退出码 0。
- 修正断言辅助函数后复跑：`... > /tmp/verify-gui-test-final.log 2>&1` → 39 passed, 0 failed；`git merge desktop-migration` 后再跑 `... > /tmp/verify-gui-test-postmerge.log 2>&1` → 39 passed, 0 failed（退出码 0）。
- 参考机真实跑通「无权重缓存跳过冒烟」完整路径：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17 && cd desktop && NIGHTMARE_MODELS=/tmp/verify-gui-no-weights timeout 2400 ./verify-gui.sh 2>&1 | tee /tmp/verify-gui-real-run.log` → `SCRIPT_RC=0`，总耗时 65 s。`:gui:classes` `BUILD SUCCESSFUL in 13s`；`build` `BUILD SUCCESSFUL in 23s`（core/cli/gui 全量测试 PASSED）；`fake-runner` 38/38 GUI 测试 PASSED，`BUILD SUCCESSFUL in 22s`；`persistence` 12/12 `WorkflowPersistenceTest` PASSED，`BUILD SUCCESSFUL in 5s`；真实冒烟日志写 `已跳过（无权重缓存）: /tmp/verify-gui-no-weights/sd15/v1-5-pruned-emaonly.safetensors`；末行 `verify-gui: PASS`。
- 语法检查：`bash -n desktop/verify-gui.sh && bash -n desktop/verify-gui.test.sh` → OK。

**验收证据**
- 日志（真实运行，均留存）：`/tmp/verify-gui-real-run.log`（整条命令）、`/tmp/verify-gui-classes.log`、`/tmp/verify-gui-build.log`、`/tmp/verify-gui-fake-runner.log`、`/tmp/verify-gui-persistence.log`、`/tmp/verify-gui-real-smoke.log`（跳过说明）。
- 断言日志：`/tmp/verify-gui-test-red.log`、`/tmp/verify-gui-test-green2.log`、`/tmp/verify-gui-test-final.log`、`/tmp/verify-gui-test-postmerge.log`。合并日志：`/tmp/verify-gui-merge.log`；提交日志：`/tmp/verify-gui-commit1.log`。
- 失败路径由 shell 断言覆盖：stub 在 `build` 步强制退出 7，脚本返回 7，`verify-gui-build.log` 保留报错，stdout 写 `verify-gui: FAILED` 与日志目录，且后续 `fake-runner` 未执行（快速失败）。
- 假 runner 的四类失败信号（非零退出、stderr、超时、缺模型）与进度行断言在 `:gui:test` 真实运行中全部 PASSED；真实运行把四个 GUI 类与 `WorkflowPersistenceTest` 明确列为独立步骤并有独立日志。

**偏离与风险（未验证项如实记录）**
- 有真实权重时的 SD1.5 冒烟**未在参考机实际跑到**：真实运行刻意把 `NIGHTMARE_MODELS` 指向空目录以覆盖「无缓存跳过」路径；「有权重则调用 `Img2ImgRealE2ETest`」仅由 stub gradlew 的 shell 断言证明。若将来缓存/引擎变动，需以默认 `NIGHTMARE_MODELS` 再跑一次脚本确认真实推理路径。
- 可选 GUI 图形步骤（`VERIFY_GUI_LAUNCH=1`）**未在参考机真实弹窗验证**：DISPLAY、xvfb 包装、无显示跳过三条分支均由 stub 断言覆盖；真实运行的默认路径不含该步。参考机据 README 有 `DISPLAY=:0` 且无 Xvfb，脚本按 ADR-0011 直接使用已有 display。
- Windows/macOS **未实机编译**，只写入文档命令并依据规格/ADR 把边界限定为 `:gui:classes`；未建任何跨平台 CI job，GitHub Actions 也未在本次本机执行（仅静态核对现有 `desktop.yml` 的 Linux build + 失败上传）。
- 真实运行的部分 Gradle 任务显示 `FROM-CACHE`/`UP-TO-DATE`，因此它证明的是「脚本串起的命令在参考机成功且测试全绿」，不是一次 `--no-build-cache cleanTest` 的冷构建；工单 18 的收口记录另有一次 `--no-build-cache cleanTest build` 全绿证据。
