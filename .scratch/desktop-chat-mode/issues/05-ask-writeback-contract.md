# 05: 结构化追问（Ask）的回写契约

Type: wayfinder:grilling
Status: resolved
Blocked by: 02, 04

## Question

Ask 选项被点选后，`answer` 写回哪里、由谁继续 agent 回合？用户不点选而直接输入、或切换模式时 Ask 怎么失效？`Ask(id, question, options, answer)` 的 id/options/answer 在桌面适配器里保留到什么程度，才既不丢移动版语义又不引入新持久化格式？

对话历史只在内存的前提已锁定，本工单只裁决回合内的回写与生命周期边界，不重新调查 provider 契约（事实来自工单 01）。

**来自工单 01/03/04 的已查明事实与裁决（勿重复调查/裁决）**：移动版 `Agent.kt` 的 `ChatItem.Ask(id, question, options, answer)` 由 `AgentSession`（Android 耦合、桌面重写）追加，Agent 核心本身只在 `send` 里 emit 其余六类、不主动 emit Ask；`ChatItem` 七类含 Ask 但桌面只复制 `Agent`/`LlmClient`（Ask 的发出方要在桌面适配器重写）。工单 02 裁决 Ask 呈现为可点击选项按钮；04 裁决每回合＝一次 `send()`、协作式取消、历史只在内存且重试追加新气泡。本工单只决定「点选项后 answer 如何进入下一回合」这一条接缝。

**移动版 Ask 实读事实（本轮查明，file:line）**：`AgentSession.kt:189-196` 的 `ask(question, options)` 用 `CompletableFuture` **阻塞 agent 线程**——`nextAsk.incrementAndGet()` 生成 id、`asks[id]=future`、emit `ChatItem.Ask(id,question,options)`、`f.get()` 等待；`:198-202` 的 `answer(id, option)` 把气泡 `answer` 字段填上并 `future.complete(option)`，agent 拿 option **继续同一轮** tool 调用；`:204-209` 的 `stop()` 把所有等待的 Ask 以 `AgentTools.CANCEL` 放行并 `host.cancelRun()`。Ask 不是新回合消息，而是同一回合内部的同步阻塞等待——这是桌面适配器要复刻的语义。

## Answer

两轮 grilling 裁决完毕（人逐项确认），结论如下：

**1. 等待机制——沿用阻塞等待**：桌面适配器写一个等价的 `ask`/`answer`：后台回合线程在 `ask` 处等 `CompletableFuture`，UI 点选用 `answer(id, option)` 放行，agent 拿 option **继续同一回合**的 tool 调用。与移动版语义一致、与工单 04「每回合一次 `send()`、后台线程」兼容。不把 Ask 变成新回合消息（那样会丢"同一轮内继续"语义、要重建上下文）。

**2. 绕过与失效**：Ask 等待时用户改用输入框发送 → 先把该 Ask 以 `CANCEL` 放行（释放阻塞线程），再把输入当**新用户消息起新回合**（复用工单 04 的追加历史机制）。切模式**不影响**等待中的 Ask（与 04「切模式继续跑」一致，切回仍可点选）。不排队、不强制先点选。

**3. 字段保留**：`Ask(id, question, options, answer)` 四字段**全保留在内存** ChatItem 列表：id 用于 `answer(id, option)` 精确匹配、answer 用于 UI 显示选中项；不进工作流 JSON、不落盘（沿用历史只在内存、不扩格式）。

**4. Ask 取消**：点「取消」或关窗时，把所有等待中的 Ask 以 `AgentTools.CANCEL` 值放行（释放阻塞线程）；agent 收到 CANCEL 后按 04 的协作式取消在下一轮首停；Ask 气泡显示「已取消」、不显示选中项。与移动版 `stop()` 语义一致。不自动选第一个选项（避免误触发出图）、不允许 Ask 永久挂起。

**术语纪律**：Ask 是「结构化追问」的实现类型名（GLOSSARY 已定义该领域术语），桌面适配器复刻其语义但不复制 Android 耦合的 `AgentSession`。

**事实 vs 决策**：`AgentSession.ask/answer/stop` 的实现为实读事实；上述 1–4 为人的决策。

**交给下游**：07（验收口径）需断言 Ask 的阻塞→放行→继续链路，以及取消时以 CANCEL 放行不挂起。
