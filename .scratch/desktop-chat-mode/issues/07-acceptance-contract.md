# 07: 验收口径与测试接缝

Type: wayfinder:grilling
Status: resolved
Blocked by: 03, 04, 05, 06

## Question

规格里写死的验收口径是什么？裁决至少覆盖：假 `ChatModel` + 假 CLI 在 Linux 上跑通「对话→工作流→出图」的具体断言（含 Ask 回写路径与失败路径）；是扩展既有 `desktop/verify-gui.sh` 还是新增脚本；Windows/macOS 仅编译如何写入；真 provider 冒烟如何标注为可选且不阻塞。

结论要能直接落进规格的「验收」段落，并沿用 phase2-gui 已定的日志与超时纪律。

**已查明事实（勿重复调查）**：`desktop/verify-gui.sh` 是 ADR-0011 锁定的单一入口，用 `run_step <label> <timeout> <log> <cmd>` 封装每步（硬 timeout + tee 日志 + 非零即停），现有步骤为 gui-classes → build(全量 JVM 测试) → fake-runner(第 3 步，具名 --tests 断言) → persistence → 可选 real-smoke → 可选 GUI launch；缺权重跳过冒烟不失败。工单 03 已实测假 ChatModel 经真实 Agent 循环驱动 5 条 ToolInterface 命令搭出可运行工作流（FakeChatModelWorkflowTest）。

## Answer

一轮 grilling 裁决完毕（人逐项确认），结论如下，可直接落进规格的「验收」段落：

**1. 验收入口——扩展 `verify-gui.sh`**：在既有脚本第 3 步 fake-runner 之后新增一步 `chat-turn`，用 `run_step` 包装、独立日志（`verify-gui-chat-turn.log`，默认放 `VERIFY_GUI_LOG_DIR`）、硬 timeout；不新建脚本。符合 ADR-0011「改动 GUI 必须同步更新它」的单一入口纪律。

**2. 假路径端到端断言到全链路**：用假 ChatModel 脚本驱动真实 Agent 循环 → 5 条 `ToolInterface` 命令 → 假 CLI → 产物图。断言：①搭出 `prompt→sampler→output` 工作流；②seed（复用 03 的 `seed=11` 断言）；③`ChatItem.Picture` 事件产出；④结果按既有 listener/snapshot 路径入结果面板。沿用工单 03 的 `FakeChatModelWorkflowTest` 接缝与假 CLI，不连真 provider。不引入新依赖。

**3. Ask 两类路径都断言**：①放行路径——假 ChatModel 脚本发 Ask，断言点选 `answer(id,option)` 后 agent **同回合继续**出图（工单 05 语义）；②取消路径——断言取消/关窗时 Ask 以 `CANCEL` 放行、后台线程不挂起、气泡标「已取消」（工单 04/05 语义）。均用假 ChatModel 脚本触发，无需真人。

**4. 平台与真 provider 边界——沿用 ADR-0011**：Windows/macOS 只要求本地 `:gui:classes` 编译通过、不建 CI job；真 provider 冒烟标注为可选手工，缺 `NIGHTMARE_LLM_*` 环境或网络则跳过、不阻塞验收。验收脚本靠 `NIGHTMARE_LLM_*` 注入即可跑假路径（无需密钥）。

**验收口径一句话**：Linux 上一条 `verify-gui.sh` 闭环，新增 chat-turn 步骤以假 ChatModel + 假 CLI 断言「对话→工作流→出图」全链路与 Ask 双路径；真 provider 与 Windows/macOS 端到端均不在本轮阻塞范围。

**事实 vs 决策**：脚本结构、ADR-0011、工单 03 实测为事实；1–4 为人的决策。**本工单是地图最后一张——裁决后整张图前沿清空，可交 `/to-spec`。**

