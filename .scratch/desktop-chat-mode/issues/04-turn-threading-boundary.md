# 04: 对话回合的线程与运行态桥接

Type: wayfinder:grilling
Status: resolved
Blocked by: 02, 03

## Question

后台线程跑 agent 的具体边界是什么？裁决至少覆盖：一个「对话回合」何时算开始/结束；运行中用户再发消息、切到默认/高级模式、关窗或点取消时分别怎么处理；`addChangeListener`/snapshot 如何映射到对话气泡与既有运行日志面板；失败后是否允许同一会话重试、重试是否保留历史。

注意 ADR-0009 的单运行/可取消/关窗清进程语义必须保持不变，`WorkflowRunner` 不改为异步。

**来自工单 01/03 的已查明事实（勿重复调查）**：`Agent.send`/`LlmClient.complete`/`ToolInterface.Run` 全链路同步阻塞（03 实测：假 CLI 秒级返回才使同步断言成立，真 provider/CLI 会卡住整个 send）；`Agent.send` 有两个取消检查点——轮首 `cancelled()` 为真则静默 return 不 emit，call 前取消仅把当前 tool 降级为 error 并继续剩余调用，直到下一轮首才停；桌面 `WorkflowRunner.run` 明确阻塞至子进程退出、无异步 future，仅 `addChangeListener` + `snapshot` + `cancelActiveProcess`；GUI 结果面板已由 phase2-gui 工单 14 用 listener/snapshot 驱动。gui 构建脚本无 kotlinx.coroutines 依赖。本工单只裁决「怎么把这些事实包成一个后台回合」的边界，不重新调查这些事实。

## Answer

两轮 grilling 裁决完毕（人逐项确认），结论如下：

**1. 线程机制**：每个「对话回合」起一个命名后台 Thread（如 `chat-turn-N`）跑 `Agent.send`；**不引入 kotlinx.coroutines**（gui 无该依赖，沿用现有裸 Thread 风格，符合 charting 轮「不引入新协程框架」）。同一时刻只允许一个回合线程。回合边界：用户点发送→起线程→`send()` 返回（正常收尾 / 错误 / 取消）→线程结束；一个回合＝一次 `send()` 调用。

**2. 取消语义（协作式停止，快速响应）**：点「取消」→置 `cancelled` 标志→等当前 tool call 收尾后在下一轮首真正停（`Agent.send` 现有检查点语义，不硬杀线程、不 Thread.interrupt）；不发新 LLM 请求。气泡显示「已取消」错误，**保留已产出图与历史**。取消不算「生成失败」。

**3. 切模式与关窗**：切到默认/高级只切外壳、后台回合继续跑（共用 `WorkflowSession`，结果照常入工作流）；关窗沿用 `DisposableEffect → cancelActiveProcess` 清 CLI 子进程，回合线程随之收尾（ADR-0009 不变）。对话历史切换保留、重启清空（charting 轮⑨）。

**4. 事件回传**：构造 `Agent` 时传 `emit` 回调，把 User/Assistant/Tool/Picture/Error/Ask 六类 `ChatItem` 经 `runOnUiThread` 切回主线程、加入 Compose 的 `mutableStateListOf`；Picture 同时按既有 listener/snapshot 路径入结果面板。不新增事件总线、不污染 `WorkflowRunner.addChangeListener` 的运行态通知链（Log 事件不进气泡，仍走运行日志面板——charting 轮⑥）。

**5. 并发发送**：回合运行期间输入框仍可输入，但发送按钮禁用并显示「运行中，请等待或取消」；不排队、不丢消息，与 ADR-0009 单运行语义一致。

**6. 失败重试**：失败（错误/超时/取消）后允许重试；重试是把新的用户消息追加到历史、起新回合继续跑，**不抹掉旧历史**。历史仍只在内存、切换保留、重启清空。

**事实 vs 决策**：线程/取消/监听的事实来自工单 01/03 与源码；上述 1–6 为人的决策。ADR-0009 单运行/可取消/关窗清进程语义贯穿始终、未改动。

**交给下游**：05（Ask 回写契约）继承「协作式取消、历史只在内存、重试追加新气泡」的结论。
