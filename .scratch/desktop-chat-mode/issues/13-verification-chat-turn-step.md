# 13: 验收脚本的对话出图步骤

**What to build:** 作为维护者，我能在 Linux 上用一条既有验收命令闭环验证对话出图——假 ChatModel + 假 CLI 跑通「对话→工作流→出图」全链路、追问的放行与取消双路径都被断言，无需真实 provider 密钥或网络；Windows/macOS 只要求编译通过。

**Blocked by:** 09 — 第三条对话模式与一条对话回合; 10 — provider 设置与密钥注入; 11 — 结构化追问（Ask）的放行与取消; 12 — 回合生命周期——取消、并发、切模式、重试

**Status:** ready-for-agent

- [ ] 在既有验收脚本的假 runner 步之后新增 chat-turn 步，用既有 run_step 封装、独立日志、硬超时；不新建脚本（ADR-0011 单一入口）
- [ ] 断言假路径全链路：假 ChatModel 脚本驱动真实 agent 循环 → 五条工具命令 → 假 CLI → 产物图；断言搭出 `prompt→sampler→output`、seed、图片事件、结果入面板
- [ ] 断言追问双路径：点选 `answer(id,option)` 后同回合继续出图；取消/关窗时以 `CANCEL` 放行、后台线程不挂起、气泡标「已取消」
- [ ] 真 provider 冒烟标注为可选手工，缺环境变量或网络则跳过、不阻塞；Windows/macOS 只要求本地编译通过、不建 CI
- [ ] 一条命令全绿：全量构建与测试通过，验收命令返回零退出码并保留各步日志
