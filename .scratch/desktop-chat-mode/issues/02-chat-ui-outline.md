# 02: 对话模式的界面粗胚

Type: wayfinder:prototype
Status: resolved

## Question

第三种交互模式具体长什么样？做一份最粗的 Compose 草稿给人反应：对话气泡（User / Assistant / Tool 摘要 / Picture / Error / Ask 可点击选项）、文本输入框、provider 设置入口，以及它与工作流、结果面板并存时的布局关系。

要点是让人能指着它说「这里不对」，不是定稿视觉。把原型作为资产链接到本工单；`## Answer` 记录从反应里定下来的形态与仍开放的点。

## Prototype

- 资产：丢弃分支 `prototype/02-chat-ui-outline`（worktree `.codebuddy/worktrees/prototype-chat-ui`），提交 `5ebe600`，文件 `desktop/gui/prototype/chat-mode-prototype.html`。单文件、免构建，浏览器直接打开，用 `?variant=A|B|C` 或底部浮动切换条（← → 键也可）翻页。
- 三变体（结构迥异，非换色）：
  - **A 副驾控制台**：三栏常驻——对话｜当前工作流链｜结果大图。
  - **B 对话终端**：单列对话为主，结果图内联成 Picture 气泡，工作流收成顶部面包屑条。
  - **C 画布中心**：左侧结果大图 + 右侧对话栏，工作流放底部抽屉（本原型只读）。
- 每个变体都带：模式切换（默认/高级为占位）、六类气泡（User/Assistant/Tool 摘要/Picture/Error/Ask 可点选项）、输入框、provider 设置齿轮（弹窗演示密钥只走 Authorization 头、不回显）。Ask 选项与发送可点（桩，不真出图）。已用无头 Chrome 在 1280×800 实渲染三变体验证无重叠/空白。
- **HITL 待裁决**：选哪个变体（或要 A 的 X + C 的 Y）、气泡密度、工作流在对话模式里的可见度。裁决写入 `## Answer` 后本工单才 `resolved`。

## Answer

**裁决：选 B「对话终端」为主形态**，理由——它最贴合本图已锁定的「除 Log 外全部 `ChatItem` 进气泡」：结果图作为 Picture 气泡天然内联，不必像 A 那样维护第二套常驻结果展示、也不像 C 那样把对话挤成窄栏。具体形态：

- 单列对话为主体；六类气泡（User / Assistant / Tool 摘要 / Picture / Error / Ask 可点选项）按 B 的原型呈现，对话区占主视口。
- **工作流收为顶部面包屑条**（`提示词 → 采样器 → 输出`），保持"同一份工作流、不新增第二套展示"。
- **借 A 的一个细节**：面包屑节点可点开一个**只读**工作流抽屉，让用户看得见 agent 改了工作流哪一处；该抽屉只读、不可在对话模式编辑（编辑仍归默认/高级模式）。这条细节不是新增实现接缝，仅复用既有的工作流数据做只读呈现。
- provider 设置入口为对话区内的齿轮按钮（弹窗形态见原型），按已锁定的密钥纪律：只走 Authorization 头、键序按键名读取、界面不回显密钥。

**仍开放的点**（不阻塞本工单，交给后续工单裁决）：①气泡密度与 Ask 追问的视觉权重（工单 05）；②只读抽屉里是否展示参数 diff（工单 04/05 的边界）；③面包屑是否在 agent 改动节点时高亮（同上）。

**待办**：把 B 的获胜形态折进真实 `:gui` 是 `/to-spec` 之后的实现工单的事（按 prototype 技能，主分支只保留裁决，原型全套留在丢弃分支 `prototype/02-chat-ui-outline` commit `5ebe600`）。
