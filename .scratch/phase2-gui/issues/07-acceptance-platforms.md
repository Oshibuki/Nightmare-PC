# 07: 验收命令与三平台边界

Type: wayfinder:grilling
Status: resolved
Blocked by: 01, 05

## Question

Linux 可运行切片的「一条命令验收」具体包含哪些步骤——构建、Gradle 测试、假 CLI runner 的端到端测试、缓存存在时的真实 SD1.5 冒烟？

Windows/macOS 的「编译通过」用什么命令验证、是否进入 CI？各步的日志与超时证据落在哪里？

## Answer

**Linux 一条命令验收**：新增 `desktop/verify-gui.sh`，串起 `:gui:classes` → 全量 build/test → 假 CLI runner 的端到端用例 → 缓存存在时的真实 SD1.5 冒烟；每步 `timeout` + `tee` 到 `/tmp/*.log`，无权重时跳过冒烟且不阻塞；无头运行 GUI 用 `xvfb-run -a`。**跨平台**：Windows/macOS 本轮不建 CI job，只在 spec 写明各平台到达时独立做，并给本地 `:gui:classes` 命令。**证据**：复用 desktop 既有约定（`timeout ... 2>&1 | tee`、CI 上传测试报告、GUI 运行另存日志），不新建证据目录。真实权重冒烟是可选的，与 spec 验收分级一致。

术语「验收脚本（Verification Script）」已进 `GLOSSARY.md`；已立 `docs/adr/0011-phase2-gui-verification-scope.md`。这是地图最后一张工单，前沿已清空，可交接 `/to-spec`。
