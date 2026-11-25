---
status: accepted
date: 2026-11-26
---

# 0011 - Phase 2 GUI 的验收在 Linux 用一条脚本闭环，跨平台 CI 后置

Phase 2 GUI 的验收沿用 Phase 0 的「一条命令到产物」思路：`desktop/verify-gui.sh` 串起 `:gui:classes`、全量 build/test、假 CLI runner 端到端，以及缓存存在时的真实 SD1.5 冒烟；每步 `timeout` 并 `tee` 日志，无权重时跳过冒烟而不阻塞。Windows/macOS 本轮只要求本地 `:gui:classes` 编译通过，不建 CI job。

**Considered Options**：沿用现有 build 命令会让 GUI 切片没有端到端验证；把引擎从零编译纳入同一条命令会拖长 agent 闭环；三平台 CI 会引入两个新 runner 且 GUI 尚无模块。

**Consequences**：验收脚本成为 Phase 2 的单一入口，改动 CLI 输出格式或 GUI 模块划分时必须同步更新它；跨平台验证在未来平台到达时独立安排。
