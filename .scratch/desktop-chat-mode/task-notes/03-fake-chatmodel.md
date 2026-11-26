# 工单 03：桌面测试源集的最小假 ChatModel

分支 `task/03-fake-chatmodel`，worktree `.codebuddy/worktrees/task-fake-chatmodel`。日期 2026-11-26。

## 结论

**可行。** 一个只依赖 `org.json` + JDK 的假 `ChatModel` / `ToolBox` 能在桌面 JVM 上零 Android、零 Robolectric 编译并运行；加一层约 60 行的粘合 `ToolInterfaceToolBox`，就能让真实 `Agent` 循环按脚本发出 9 条 tool_call，驱动 `ToolInterface` 的全部 5 条命令搭出 `prompt → sampler → output` 工作流，并用假 CLI 跑到 `RunStatus.SUCCESS`。

## 源集选择与理由

假对象、粘合层与驱动测试都放在 `desktop/gui/src/test/kotlin/com/abrah/nightmare/desktop/gui/fake/`。

1. 被驱动的 `ToolInterface` 在 `desktop/gui`，只有 gui 的测试类路径天然拥有它。
2. gui 通过 `implementation(project(":core"))` 拿到 `Agent` / `ChatModel` / `ToolBox`，无需把 core 的测试产物跨模块暴露。
3. 若放 `desktop/core/src/test`，core 不能反过来依赖 gui（会形成 `core → gui → core` 循环），就无法驱动 `ToolInterface`。
4. 唯一额外构建改动：`desktop/gui/build.gradle.kts` 增加一行 `testImplementation(libs.json)`。实测 `:gui:dependencies --configuration testCompileClasspath` 查不到 `org.json`——core 的 `implementation(libs.json)` 不会泄漏到消费方的编译类路径。

## 实际可行的假对象接口签名（事实，含行号）

```
fun interface ChatModel { fun complete(messages: JSONArray, tools: JSONArray): JSONObject }
  desktop/core/src/main/kotlin/com/abrah/nightmare/agent/LlmClient.kt:11
class LlmError(message: String) : Exception(message)     LlmClient.kt:16

class ToolResult(val json: JSONObject, val pictures: List<String> = emptyList(),
                 val log: List<String> = emptyList()) { val ok get() = !json.has("error") }
  desktop/core/src/main/kotlin/com/abrah/nightmare/agent/Agent.kt:28
interface ToolBox { val specs: JSONArray; fun call(name: String, args: JSONObject): ToolResult;
                    fun summary(name: String, args: JSONObject): String = name }
  Agent.kt:38
class Agent(private val model: ChatModel, private val tools: ToolBox, system: String)  Agent.kt:55
const val MAX_STEPS = 12                                  Agent.kt:107

class ScriptChatModel(vararg replies: JSONObject) : ChatModel
  desktop/gui/src/test/kotlin/.../fake/FakeChatModels.kt:15
class RecordingToolBox : ToolBox                          FakeChatModels.kt:30
class ToolInterfaceToolBox(private val tools: ToolInterface) : ToolBox
  desktop/gui/src/test/kotlin/.../fake/ToolInterfaceToolBox.kt:18
```

要点（事实）：
- `ChatModel` 是 `fun interface`，SAM 可写 `ChatModel { _, _ -> ... }`；Agent 只调用 `complete`，只捕获 `LlmError`（`Agent.kt:61-67`）。
- 移动版假对象 `AgentTest.Script`（AgentTest.kt:31-38）与 `AgentTest.Tools`（:40-50）只用 `org.json` + JDK 集合；裁剪掉 `@RunWith(RobolectricTestRunner)` 与 `AgentTools.refusal` 后即可直拷，语义不变。
- 桌面 `ToolCommand` 五条命令：`NewWorkflow` / `AddNode` / `SetParameter` / `Connect` / `Run`（`ToolInterface.kt:20-41`），统一入口 `dispatch(command): ToolResult`（:62）。
- 桌面 `ToolResult` 是 `Ok(summary)` / `Rejected(reason)`（`ToolInterface.kt:44-47`），与 mobile `ToolResult` 同名不同包，粘合层用 import alias 区分。

## 粘合代码量（事实）

- `ToolInterfaceToolBox.kt`：1 个类、5 个工具名 → 5 个 `ToolCommand`、`GuiToolResult.Ok/Rejected` → mobile `ToolResult` 的 `{"ok":...}` / `{"error":...}` 形状，约 60 行。
- 脚本序列 10 步：9 条 tool_call（NewWorkflow×1、AddNode×3、SetParameter×2、Connect×2、Run×1）+ 1 条最终文本；`MAX_STEPS=12` 够用。
- 假 CLI 复用既有 `ToolInterfaceTest.fakeCliPng()`（ToolInterfaceTest.kt:178-195）的接缝，约 15 行 POSIX shell，不连真实 provider、不引入新依赖。

## 编译 / 测试命令与结果（事实）

环境：`java` 不在 PATH；使用 `JAVA_HOME=/home/chjlxxc/Android/jdk-17`（OpenJDK 17），`gradlew` 无执行位，统一用 `bash ./gradlew -p desktop ...`。

1. 基线
   `bash ./gradlew -p desktop :core:compileKotlin :gui:compileTestKotlin --console=plain`
   → `BUILD SUCCESSFUL in 28s`，8 actionable tasks。
2. 针对性测试
   `bash ./gradlew -p desktop :core:compileKotlin :gui:test --tests "com.abrah.nightmare.desktop.gui.fake.FakeChatModelWorkflowTest" --console=plain`
   → `FakeChatModelWorkflowTest > ported fakes drive the real agent loop on a desktop JVM PASSED`
   `FakeChatModelWorkflowTest > a fake chat model drives all five ToolInterface commands into a real run PASSED`
   → `BUILD SUCCESSFUL in 3s`。
3. 完整回归
   `bash ./gradlew -p desktop :core:test :gui:test --console=plain`
   → `BUILD SUCCESSFUL in 18s`；测试 XML 汇总 `core 222 tests`、`gui 41 tests`（含新增 2 条），无 FAIL。
4. 无 Android / Robolectric 证据
   - `bash ./gradlew -p desktop :gui:dependencies --configuration testRuntimeClasspath --console=plain | grep -i robolectric` → 无输出。
   - `grep -rni "com.android\|android application\|android library" desktop/*.gradle.kts desktop/*/build.gradle.kts` → 无输出。
   - 新增源码的 import 只有 `org.json` / `org.junit` / `java.*` / 本仓库包；源码中出现的 "Robolectric" 仅为注释文字。
   - 说明：gui 的运行时类路径含 `androidx.*`，来自 Compose Desktop 的通用 artifact，不是 Android 插件或 Robolectric。

## 产物路径（事实）

| 内容 | 路径 |
| --- | --- |
| 复制的 agent 核心（各加一行溯源注释） | `desktop/core/src/main/kotlin/com/abrah/nightmare/agent/{Agent,LlmClient}.kt` |
| 假 ChatModel / 假 ToolBox | `desktop/gui/src/test/kotlin/com/abrah/nightmare/desktop/gui/fake/FakeChatModels.kt` |
| 粘合层 | `desktop/gui/src/test/kotlin/com/abrah/nightmare/desktop/gui/fake/ToolInterfaceToolBox.kt` |
| 驱动测试 | `desktop/gui/src/test/kotlin/com/abrah/nightmare/desktop/gui/fake/FakeChatModelWorkflowTest.kt` |
| 构建改动 | `desktop/gui/build.gradle.kts`（+1 行 `testImplementation(libs.json)`） |
| 编译产物 | `desktop/core/build/classes/kotlin/main/`、`desktop/gui/build/classes/kotlin/test/` |
| 测试报告 | `desktop/gui/build/test-results/test/TEST-com.abrah.nightmare.desktop.gui.fake.FakeChatModelWorkflowTest.xml`、`desktop/gui/build/reports/tests/test/index.html` |
| 本笔记 | `.scratch/desktop-chat-mode/task-notes/03-fake-chatmodel.md` |

## 限制与发现

1. **阻塞式 run（事实）**：`Agent.send`、`LlmClient.complete`、`ToolInterface.Run` 全链路同步阻塞（`Agent.kt:52` 注释、`DefaultModeState.triggerRun() → runner.run(...)` 同步执行）。真实 provider / CLI 会把整个 `send()` 卡住；桌面聊天 UI 必须把循环放到后台线程。本次假 CLI 秒级返回，测试中同步断言成立。
2. **键序断言（事实，来自工单 01，本次未复测）**：JVM `org.json` 底层是 `HashMap`，`JSONObject.toString()` 键序不稳定。原样复制 `AgentTest.aToolCallRunsAndItsAnswerGoesBack` 的 `assertEquals(listOf("run_flow" to """{"flow":"txt2img","prompt":"a fox"}"""), tools.calls)` 会 FAIL（工单 01 实测 expected 与实际键序反转）。本测试改为按键名读字段，规避该问题；协议本身按键名读取，键序不影响线上行为。
3. **步数上限（事实）**：`MAX_STEPS=12`；脚本化命令超过 12 条会被截断并 `emit(ChatItem.Error(...))`（`Agent.kt:103`）。本用例 10 步通过。
4. **工具 schema 是最小形状（事实）**：`ToolInterfaceToolBox.specs` 只给 `name`，不含 `parameters` JSON Schema。脚本模型不读 specs，但真实模型要正确发 tool_calls 需要补 schema。
5. **两个 `ToolResult` 同名（事实）**：mobile `com.abrah.nightmare.agent.ToolResult` 与 desktop `com.abrah.nightmare.desktop.gui.ToolResult` 并存，粘合层靠 import alias 区分。
6. **未连真实 provider（事实）**：假 ChatModel 只验证协议形状、循环与命令映射，不验证任何真实模型的 tool-calling 行为或鉴权 / 限流 / 超时。
7. 假对象放在 gui 测试源集，core 的测试无法直接复用；若后续 core 的 agent 测试需要同一假对象，应上移到 core test 或改走 `testFixtures`（推断/建议）。

## 事实 vs 推断

**事实（本次实测，行号或命令输出可核对）**
- 上述所有接口签名、行号、命令与 `BUILD SUCCESSFUL` / `PASSED` 输出、测试数量、产物路径。
- :core:compileKotlin 零源码改动编译通过（复制文件仅加溯源注释），无需改 core 构建脚本。
- gui 的 `testCompileClasspath` 默认不含 `org.json`，需 `testImplementation(libs.json)`。
- 无 Robolectric 依赖；新源码无 Android import。
- 假 ChatModel 经真实 Agent 循环驱动 5 条 ToolInterface 命令并跑到 `RunStatus.SUCCESS`，`run.results.single().params["seed"] == "11"`，CLI command 含 `tool-built dawn`。

**来自工单 01、本次直接采信未复测的事实**
- JVM `org.json` 键序导致全字符串断言失配；协议按键名读取故不影响线上行为。

**推断 / 建议（尚未实现，需后续工单验证）**
- 桌面聊天模式应复用这套假对象与 `ToolInterfaceToolBox` 作为测试底座，而不是重造一套 ToolInterface。
- 真实模型可用性需补完整 tool schema、后台线程包装与真实 provider 的契约测试；本次未覆盖。
- 若把两个 `ToolResult` 统一为一个类型，粘合层可进一步缩到约 40 行。
