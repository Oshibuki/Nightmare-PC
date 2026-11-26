# 07: 对话模式经工具发起图生图

**What to build:** 作为桌面用户，我能在对话里说「用这张图按这句描述重画」并给出本地图片路径，AI 经既有工具面改同一份工作流——新增图片输入节点、把 path/strength 写进参数、连到采样节点、运行——图生图产出的图进入与文生图相同的结果面板，工作流收为顶部面包屑、图以气泡呈现。

**Blocked by:** 05 — 「重发上一句」按钮与可复现重放（它把 Agent 构造抽成工厂，是本工单改 `SYSTEM_PROMPT`/工具面的落点，先落可避免同处冲突）; 06 — 图片输入节点与运行器 img2img 投影

**Status:** resolved

- [x] 对话工具面能把「本地图片路径 + 描述」表达为对既有工作流的操作：新增图片输入节点、写入 path/strength（默认 0.75）、连到采样节点、运行；strength 由 AI 经工具参数设置，首切片不做用户侧控件或专门的 Ask 追问
- [x] Agent 的系统提示教会模型新节点的类型、参数与连线端口，使一句话能驱动一条可运行的图生图工作流；工具结果形状与既有五条命令一致
- [x] 生成的图按既有监听/快照路径进入结果面板、以 Picture 气泡呈现；运行日志仍走既有日志面板、不进气泡、不改运行态通知链
- [x] 假 ChatModel + 假 CLI 的状态层测试跑通「一句话 → 工具改工作流 → 图生图 → Picture 气泡 + 结果快照」全链路；断言按字段名比较
- [x] 含图片节点的工作流不改既有文生图对话行为；切模式/取消/追问等生命周期语义沿用既有实现，不为图生图开特例

## Comments

- 2026-11-26（isolated-implementer-1）：按 TDD 红-绿推进。红：`ChatSessionTest` 三条新测试失败（系统提示未含 core.image/initImage/path/strength/0.75/0..1；工具 schema 的 add_node enum 无 core.image；别名 "image" 被拒使端到端工具调用失败）；退出码 1，日志 `/tmp/nm-desktop/logs/retry-img2img-07-red.log`。绿：在 `ChatSession.SYSTEM_PROMPT` 补四种节点类型、path/strength 与 initImage 端口；在 `DesktopToolCommands.specs()` 补 core.image enum/别名/description，`TYPE_ALIASES` 接受 image/img/init_image/initImage/image_input；无第 6 条命令、无 runner/UI 改动。退出码 0，日志 `...-07-green.log`。
- 端到端证据：`one sentence drives an img2img turn through the five existing commands` 用 ScriptChatModel 十步脚本 + POSIX 假 CLI，断言 9 个工具全部 ok、工作流里图片节点 path/strength 按字段名取值、sampler.inputs["initImage"].node=="base"、argv 首参 `img2img` 且 `--init-img/--strength` 正确且无 `--batch`、Picture 气泡与 runner 快照均为同一 PNG、无 Log 气泡。`an image node added without strength...` 验证默认 0.75 同时进工作流与 argv。
- 回归证据：全量 `:core:test :gui:test --rerun-tasks` 退出码 0（120 tests，日志 `...-07-full-tests-rerun.log`）；`build` 退出码 0（`...-07-build.log`）；定点三件套 `ChatSessionTest`/`AskSessionTest`/`TurnLifecycleTest` 退出码 0（`...-07-targeted.log`），其中文生图 argv 仍为 `txt2img` + `--batch 1` 且无 `--init-img`，失败/取消后重试、追问、切模式、窗口关闭断言全绿。`DesktopToolCommandsTest` 的旧 enum/别名断言随契约更新（非绕过）。
- 验证边界：未接真实 provider 或真实模型 tool-calling（按 spec「不测」），strength 越界由 CLI 校验、本工单未新增 GUI 钳制或用户控件。
