# 03: GUI 状态与 CLI 进程生命周期的边界

Type: wayfinder:grilling
Status: open
Unblocks: 06

## Question

谁持有工作流与运行结果，谁负责启动和取消 CLI 子进程；窗口关闭、运行失败、进程超时这三种情况下，子进程与临时产物如何处理？

要把 `CanvasState` 的不可变状态与 CLI 的同步进程调用放在一起，且不新增接缝——这两者之间的状态流转边界需要当场决定。
