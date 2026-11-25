# 03: 假 ChatModel 在桌面测试源集的最小骨架

Type: wayfinder:task
Status: resolved
Blocked by: 01

## Question

在桌面测试源集里搭一个最小假 `ChatModel`（等价移动版 `Script:ChatModel` 与 `Tools:ToolBox`），验证它能不依赖 Android/Robolectric 编译，并能驱动既有 `ToolInterface` 的 5 条命令搭出一条可运行工作流。

这是一张让决策可见的前置 work，不是交付对话模式本身。Resolved 时在 `## Answer` 记录实际可行的假对象接口、编译/测试命令、产物与日志路径，以及任何与 charting 轮事实不符的发现。

**来自工单 01 的已查明事实（勿重复调查）**：`ChatModel = fun interface { complete(JSONArray, JSONArray): JSONObject }`，SAM 可写 `ChatModel { _, _ -> ... }`；`Script:ChatModel`/`Tools:ToolBox` 假对象无 Android 依赖可直拷。`AgentTest` 原样编译失败（Robolectric ×3、`AgentTools` ×2），裁剪后 6 用例 5 PASS / 1 FAIL，失败因 JVM `org.json` 默认 `HashMap` 键序变化导致字符串全等断言失配——你的骨架若复用该断言须改成顺序无关比较。两源文件逐字节复制进 `:core` 已实测编译成功（0 行改动，详见 research 分支 `4002113`）。

## Answer

子代理在丢弃分支 `task/03-fake-chatmodel`（commit `496c7e9`，worktree 干净）完成任务，笔记 `.scratch/desktop-chat-mode/task-notes/03-fake-chatmodel.md`（112 行）。**结论：可行**——假 `ChatModel`/`ToolBox` 在桌面 JVM 零 Android、零 Robolectric 编译运行，约 60 行粘合 `ToolInterfaceToolBox` 让真实 `Agent` 循环发出 9 条 tool_call 驱动全部 5 条 `ToolCommand` 搭出 `prompt→sampler→output`，假 CLI 跑到 `RunStatus.SUCCESS`、`seed=11`。

**已完成的实际工作（实测事实）**：
- `Agent.kt`/`LlmClient.kt` 复制进 `desktop/core/src/main/kotlin/com/abrah/nightmare/agent/`（各加一行溯源注释，core 构建脚本零改动）；假对象与粘合放 `desktop/gui/src/test/.../fake/`（core 不能反向依赖 gui，否则循环）；gui 构建脚本加 1 行 `testImplementation(libs.json)`（org.json 不跨模块泄漏）。
- 验证命令与结果：`bash ./gradlew -p desktop :core:compileKotlin :gui:test --tests "...FakeChatModelWorkflowTest"` → 2 PASSED；`:core:test :gui:test` 全绿（core 222 / gui 41）；testRuntimeClasspath 无 Robolectric、无 Android 插件。
- 接口签名、行号、产物路径、命令输出详见任务笔记 §2–§5。

**限制（为后续工单的输入，非阻塞）**：全链路阻塞（UI 必须把 `send()` 放后台线程——直接喂给工单 04）；`MAX_STEPS=12`；JVM `org.json` 键序使全字符串断言不可用（本测试按键名读取）；`specs` 仅含工具 name、无参数 schema，真模型要正确发 tool_calls 需补 schema（工单 06 的 fitsChat 自动选模依赖 GET /models，与此不冲突）；两个 `ToolResult` 同名不同包，靠 import alias 区分；未连真实 provider。

**推断/建议（未在本次验证）**：桌面对话模式应复用这套假对象与 `ToolInterfaceToolBox` 作测试底座，而非重造；若统一两个 `ToolResult` 类型，粘合可缩到约 40 行。
