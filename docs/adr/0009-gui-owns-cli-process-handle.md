---
status: accepted
date: 2026-11-26
---

# 0009 - GUI 以子进程驱动 CLI 并持有进程句柄，CLI 保持无状态

`Cli.run` 虽是现成纯函数，但同步阻塞且进程在内部创建，拿不到句柄，无法取消或关窗清理。桌面 GUI 改为以子进程启动 CLI 可执行文件、持有 `Process`，逐行读 stdout；工作流与运行结果归 GUI 状态，CLI 每次调用保持无状态。同一时刻只允许一次运行，运行中禁用「运行」；取消或关窗 `destroy` 子进程，只有成功退出才把 PNG 纳入结果面板。

**Considered Options**：后台线程直接调 `Cli.run` 会失去取消与关窗清理；直接调 `Txt2Img`/`Img2Img` 需扩大 internal 可见性，等于新开接缝；并行多跑会让进度线与结果归属变成多对多。

**Consequences**：GUI 要能定位 CLI 可执行文件，这是 spec 必须说明的配置；CLI 不需要新增取消 API，但 GUI 层需要一个运行态状态机承载进程句柄与结果。
