---
status: accepted
date: 2026-11-26
---

# 0010 - GUI 解析 CLI stdout 的进度行，不新增进度端点

CLI 每张产物输出一行 `[i/N] seed=<seed> -> <path>`，引擎原始输出随后进入同一 stdout；本地 A1111 服务的 `progress`/`interrupt` 目前返回 404。桌面 GUI 逐行解析进度行形成进度事件，并把引擎输出转发到同一日志面板；非零退出码、stderr、超时、缺模型四类失败统一转成运行态失败。超时由 GUI 设可配置上限（默认 10 分钟）并结束子进程，CLI 不加内部计时。

**Considered Options**：整段转发 stdout 会让 GUI 拿不到结构化进度；解析 A1111 `/progress` 需先改造引擎端点；引入 Compose UI 测试会新增接缝。

**Consequences**：CLI 的 stdout 行格式成为 GUI 依赖的契约，改动需同步 GUI 测试；假 CLI runner 必须复现该格式才能做端到端断言。
