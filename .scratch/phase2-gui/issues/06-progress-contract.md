# 06: 进度与失败路径的 GUI 契约

Type: wayfinder:grilling
Status: resolved
Blocked by: 03

## Question

GUI 如何消费 CLI stdout 的 `[i/N] seed=... -> path` 进度行：逐行解析成状态事件，还是整段转发到日志面板？

非零退出码、stderr 输出、运行超时、缺模型这四种失败分别怎样在界面表达，并进入假 CLI runner 的测试断言？

## Answer

**进度**：GUI 逐行解析 `[i/N] seed=S -> path` 成进度事件（序号/总数/seed/目标路径），同时把引擎原始输出转发到同一日志面板，两者不二选一。**失败**：非零退出码、stderr、超时、缺模型统一转成运行态「失败」，错误条显示退出码 + 首行原因、全量进日志，工作流保持不变。**超时**：GUI 设可配置超时（默认 10 分钟），超时 `destroy` 子进程并置失败；CLI 不加内部计时——它明确由调用方设限。**验证**：假 CLI runner 输出逐行进度与四类失败信号，断言运行态进度/失败与「无部分产物入结果面板」，沿用 Gradle 测试源集，不引入 UI 测试框架。

术语「进度事件（Progress Event）」已进 `GLOSSARY.md`；已立 `docs/adr/0010-cli-stdout-progress-contract.md`。
