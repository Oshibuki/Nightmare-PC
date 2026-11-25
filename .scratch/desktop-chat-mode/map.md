# Map: 桌面 GUI 对话出图模式

Type: wayfinder:map
Status: charted

<!-- 前沿已清空：7 张工单全部 resolved（01-07），Destination 达成，可交 /to-spec。 -->

## Destination

一份可交给 `/to-spec` 的「桌面对话出图模式」规格；本图只裁决决策、不写实现。验收口径＝Linux 上以假 `ChatModel` + 假 CLI 跑通「对话给 prompt/修改意见 → AI 经既有 5 条 `ToolCommand` 改同一份工作流 → 运行 → 结果面板看到图」，Windows/macOS 仅要求 GUI 模块编译通过。

## Notes

- 领域术语读 `GLOSSARY.md`；`phase2-gui` 的全部前提（ADR-0006～0011、工作流 JSON `format=1`、节点类型注册表、运行态/进度事件契约、`ToolInterface` 命令面、Linux 端到端 + Windows/macOS 仅编译）与 ADR-0008 已锁定的 provider / 密钥 / 测试接缝均沿用，不重新辩论。本图只裁决对话模式特有的新决策。
- 每会话先只读本 map，再从 `issues/` 取第一张「未阻塞且未认领」的工单；动手前把 `Status:` 改为 `claimed` 并保存，解决后改为 `resolved`、把结论写进该工单的 `## Answer`，并在下面 Decisions so far 追加一行结论指针（gist + 链接）。
- Charting 轮已锁定、不再重问的前提：①目的地是规格而非实现；②最小闭环是上述对话→工作流→出图，参考图输入后置；③沿用 `phase2-gui` 全部前提；④工具面本轮只用既有 5 条 `ToolCommand`（`NewWorkflow`/`AddNode`/`SetParameter`/`Connect`/`Run`），对齐移动版 16 工具留到后期 effort；⑤两侧阻塞（`Agent.send()` 同步 emit、`WorkflowRunner.run()` 阻塞至子进程退出）由后台线程跑 agent + 既有 listener/snapshot 更新解决，不改 `WorkflowRunner`、保持 ADR-0009；⑥除 Log 外全部 `ChatItem` 进气泡，Ask 用可点击选项并写回 answer；⑦provider 配置在对话模式内设置入口，密钥只走 `Authorization` 头、兼容环境变量、不进 JSON/日志/截图；⑧无密钥/连不上给明确错误气泡、不崩溃、不改工作流；⑨对话历史只在内存（切换保留、重启清空），不扩 JSON 格式。
- 已核实事实（charting 轮子代理实读，勿重复调查）：纯 JVM 只有 `Agent.kt`、`LlmClient.kt`；带 Android 的是 `AgentConfig.kt`/`AgentTools.kt`/`AgentSession.kt`/`CanvasHost.kt`。`ChatItem` 7 类＝User/Assistant/Tool(name,summary,ok)/Picture(path)/Error/Ask(id,question,options,answer)/Log(lines)；`Agent.send(text, emit, cancelled)` 同步阻塞、逐条 emit、非 token 流式、`MAX_STEPS=12`。移动版 `AgentTools` 16 个工具中可映射到桌面 5 条的是 new_flow/add_node/set_params/connect/run，其余 11 个桌面无命令。纯 JVM 可复制的测试假对象＝`AgentTest.kt` 的 `Script:ChatModel`、`Tools:ToolBox` 与 `ChatFitTest.kt` 的 `/models` 构造；`AgentCanvasTest.kt` 依赖 Robolectric + 真实 ViewModel，不可直接复制。
- 工单类型与技能：`research` 由子代理调用 research 技能；`prototype` 由人与 agent 现场调用 prototype 技能；`grilling` 由人与 agent 现场调用 grilling + domain-modeling 技能；`task` 为让决策可见的前置 work。HITL 工单的「人」一侧不得由 agent 代答。本仓库无远端，research/task 分支只在本地保留、不开 PR。
- 术语纪律：`GLOSSARY.md` 已定义「对话回合」「结构化追问」等本图用语；若工单裁决引入新术语或推翻旧 ADR，按 domain-modeling 技能就地更新术语表或新立 ADR（仅在难逆转、反直觉、经真实取舍三条同时成立时立 ADR）。

## Decisions so far

<!-- 每会话解决一张工单后，在此追加一行：gist + 链接。 -->

- [核实 LLM 核心的可复制边界与 provider 契约](issues/01-llm-core-portability.md)：ADR-0008 断言成立——`LlmClient`/`Agent` 逐字节复制进 `:core` 实测编译成功、0 行改动（隐藏依赖仅 `org.json`，桌面已 pin）；provider 契约、ChatModel 最小形状、测试键序坑均已查明，详见 [research 文档](research-branch:research/llm-core-portability@4002113)。
- [对话模式的界面粗胚](issues/02-chat-ui-outline.md)：裁决选 **B 对话终端**——单列对话为主体、结果图内联成 Picture 气泡、工作流收为顶部面包屑；借 A 的细节给面包屑节点加只读抽屉以显示 agent 改动。04/05/06 的阻塞已解除。原型全套在丢弃分支 `prototype/02-chat-ui-outline` commit `5ebe600`。
- [provider 设置入口与密钥存储边界](issues/06-provider-settings-boundary.md)：配置四件（provider/base URL/密钥/模型）；env 持久（`NIGHTMARE_LLM_*`）+ GUI 输入仅内存覆盖、**不落盘**；保存时 3s/8s 异步探针失败仍可存；两类错误文案 +「打开设置」；密钥只显示「已配置/未配置」、只进 Authorization 头；模型 fitsChat 自动选 + 可覆盖。已立 ADR-0012。
- [假 ChatModel 在桌面测试源集的最小骨架](issues/03-fake-chatmodel-skeleton.md)：可行——假 ChatModel/ToolBox 零 Android 编译，约 60 行 `ToolInterfaceToolBox` 粘合让真实 Agent 循环驱动 5 条命令搭出可运行工作流（2 PASSED，全量 core 222/gui 41 绿）。`Agent`/`LlmClient` 已复制进 `:core`，gui 加 1 行 testImplementation(json)。限制：全链路阻塞→喂给工单 04，specs 无参数 schema。详见 [task 笔记](task-branch:task/03-fake-chatmodel@496c7e9)。
- [对话回合的线程与运行态桥接](issues/04-turn-threading-boundary.md)：每回合一个命名后台 Thread 跑 `Agent.send`（不引协程）；取消为协作式（等当前 tool 收尾、下一轮首停，保留已产出图与历史）；切模式继续跑、关窗沿 ADR-0009 清进程；六类 ChatItem 经 emit + runOnUiThread 入 SnapshotStateList；运行中禁用发送；失败/取消后重试追加新气泡、不抹历史。ADR-0009 未改。
- [结构化追问（Ask）的回写契约](issues/05-ask-writeback-contract.md)：Ask 复刻移动版阻塞语义——后台回合线程在 ask 等 CompletableFuture、点选 answer(id,option) 放行后**同回合继续**；输入绕过＝先以 CANCEL 放行再起新回合；切模式不影响；四字段全留内存；取消/关窗以 CANCEL 放行不挂起。桌面不复制 Android 耦合的 AgentSession。
- [验收口径与测试接缝](issues/07-acceptance-contract.md)：扩展 `verify-gui.sh` 加 chat-turn 步（run_step + 独立日志）；假 ChatModel + 假 CLI 端到端断言「对话→工作流→出图」四要素；Ask 放行/取消双路径都断言；Windows/macOS 仅编译、真 provider 可选，沿用 ADR-0011。

## Not yet specified

- （空）前沿已清空，无仍在范围内的未指定雾。原本此处的两项已随工单裁决收敛：模式切换视觉衔接由工单 02/04 锁定（切模式只切外壳、后台回合继续、历史保留）；真 provider 冒烟由工单 07 锁定为可选手工。

## Out of scope

- 对齐移动版 `AgentTools` 全部 16 个工具（列模型、保存、LoRA 下载/删除、Civitai 设置等）：用户明确留到后期 effort，本轮只用既有 5 条命令。
- 参考图输入（用户给参考图让 AI 参考出图）：本轮最小闭环不含，见 Destination。
- 对话历史持久化与工作流 JSON 扩展：已裁决只在内存，`format=1` 不变。
- 真实 provider 调用、账号开通与密钥采购：属可选手工冒烟，不阻塞规格。
- 移动版任何改动或自动同步：沿用 `phase2-gui` / ADR-0007 的复制式复用纪律。
- Windows/macOS 端到端验收：沿用 ADR-0011，仅要求 GUI 模块本地编译通过。
