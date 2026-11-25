# 06: 进度与失败路径的 GUI 契约

Type: wayfinder:grilling
Status: open
Blocked by: 03

## Question

GUI 如何消费 CLI stdout 的 `[i/N] seed=... -> path` 进度行：逐行解析成状态事件，还是整段转发到日志面板？

非零退出码、stderr 输出、运行超时、缺模型这四种失败分别怎样在界面表达，并进入假 CLI runner 的测试断言？
