# 03: GUI 状态与 CLI 进程生命周期的边界

Type: wayfinder:grilling
Status: resolved
Unblocks: 06

## Question

谁持有工作流与运行结果，谁负责启动和取消 CLI 子进程；窗口关闭、运行失败、进程超时这三种情况下，子进程与临时产物如何处理？

要把 `CanvasState` 的不可变状态与 CLI 的同步进程调用放在一起，且不新增接缝——这两者之间的状态流转边界需要当场决定。

## Answer

**调用方式**：GUI 以子进程启动 CLI 可执行文件、持有 `Process` 句柄、逐行读 stdout；不直接调 `Cli.run`（无句柄），也不碰 internal object。**归属**：GUI 状态持有工作流与结果，CLI 完全无状态——每次运行把工作流投影成 CLI 参数。**运行态**：同一时刻只允许一次运行，运行中禁用「运行」；提供取消，靠进程句柄 `destroy` 子进程，关闭窗口也必须结束子进程，避免孤儿进程。**失败/取消产物**：只有成功退出才把 PNG 纳入结果面板，失败或取消的临时文件删除、错误信息进状态，与 05 的「工作流未变」原子性一致。

术语「运行态（Run State）」：GUI 表示一次生成的生命周期（空闲 / 运行中 / 成功 / 失败 / 取消，连同当前进程句柄与结果），已进 `GLOSSARY.md`。已立 `docs/adr/0009-gui-owns-cli-process-handle.md`。本工单解锁「进度与失败路径的 GUI 契约」（06）。
