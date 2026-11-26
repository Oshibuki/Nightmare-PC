# 08: 落地 agent 核心与假对象底座

**What to build:** 作为维护者，我能从干净检出直接用既有测试命令验证——从移动版复制的纯 JVM agent 核心与假 ChatModel 底座已在桌面构建里工作：假 ChatModel 经真实 agent 循环驱动五条工具命令搭出一条可运行工作流并跑到假 CLI 出图。这张工单是把已验证的证据从丢弃分支转成主分支代码，是后续所有切片的地基，不新增功能行为。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] 纯 JVM 的 agent 核心两份源文件按复制式复用纪律落地桌面 JVM 模块；首行溯源注释与复制登记齐全；除测试侧既有 JSON 依赖外，桌面构建脚本无需为复制件新增依赖
- [x] 假 ChatModel、假工具箱与约六十行粘合层落在桌面 JVM 测试源集，无 Android / Robolectric 依赖；五条工具名映射到既有五条工具命令，工具结果形状与移动版对齐
- [x] 既有测试先例（假 CLI 端到端）在桌面全量通过：假 ChatModel 用五条工具命令搭出 `prompt→sampler→output` 并跑到成功产物；断言按键名比较字段，不依赖 JSON 键序
- [x] 全量构建与测试通过，移动版零改动

## Comments

Implemented from the read-only evidence branch `task/03-fake-chatmodel@496c7e9` (not merged). TDD: the new `FakeChatModelWorkflowTest` failed to compile on `:gui:compileTestKotlin` first (`/tmp/nm-desktop/logs/ticket-08-red.log`, exit 1); it passed after the copies, fakes, glue and the single `testImplementation(libs.json)` line landed (`ticket-08-green-targeted.log`, exit 0, 2 tests PASSED). Post-merge, `:core:test :gui:test --rerun-tasks` passed uncached (`ticket-08-post-merge-test.log`, exit 0; core 222 + gui 41 tests), and `build` passed (`ticket-08-build.log`, exit 0; cli 105 tests). Full `:core:test :gui:test` before merge: `ticket-08-full-test.log`, exit 0. `:gui:dependencies --configuration testRuntimeClasspath` has no Robolectric (`ticket-08-gui-deps.log`); new sources have no Android imports (the only "Robolectric" mentions are comments).

`Agent.kt` / `LlmClient.kt` match the mobile originals byte-for-byte after skipping their `2026-11-26` provenance line; both are registered in `desktop/docs/copied-files.md` together with the trimmed `AgentTest` fakes. The evidence task note is landed at `.scratch/desktop-chat-mode/task-notes/03-fake-chatmodel.md`. `git merge desktop-chat-mode` was already up to date (integration tip `181ccbf`); no conflicts. Code commit `0f65eac`; worktree `.codebuddy/worktrees/chat-ticket-08-impl`, report branch `chat-ticket-08`. No changes under `app/`; no new Android/Robolectric/coroutine dependency; `WorkflowRunner` and `format=1` untouched.

Coverage boundary: all nine tool calls in the end-to-end test are accepted (`GuiToolResult.Ok` → mobile `{"ok": summary}`); the rejected branch of the glue (`GuiToolResult.Rejected` → `{"error": reason}`) is implemented but has no dedicated test in this ticket. Ask handling, provider wiring and the `verify-gui.sh` chat-turn step remain later tickets (09–13).
