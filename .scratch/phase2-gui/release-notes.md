# Nightmare Desktop — Phase 2 GUI（phase2-gui）

首个带图形界面的桌面交付：在 Phase 0/1 的无头 CLI 之上，新增独立的 `:gui` Compose Desktop 模块，提供**默认**与**高级**两种交互模式，共享同一份工作流，以子进程驱动 CLI 出图。移动版零改动。

## Summary

```text
desktop/gui/                      独立 JVM 模块（不引入 Android 插件/target）
├── Main.kt                       GUI 入口与模式切换（唯一 WorkflowSession）
├── DefaultModeScreen/State.kt    默认模式：链芯片 + 预览 + 参数抽屉（原型 C）
├── AdvancedModeScreen/State.kt   高级模式：节点库 + 自由画布 + 属性面板（原型 A）
├── WorkflowRunner/RunState.kt    CLI 子进程 + 句柄 + 进度事件 + 运行态
└── ToolInterface.kt              外部工具命令面（对话模式预留接缝）
desktop/core/
├── graph/                        最小图模型 / 节点类型 / JSON 持久化（format=1）
└── canvas/                       复制的画布几何与手势状态（就地换类型引用）
desktop/verify-gui.sh             一条命令的验收入口
```

**工作流、节点类型、交互模式、运行态、进度事件**等术语见 `GLOSSARY.md`；反直觉取舍见 ADR-0006～0011。

交付内容（工单 10–19 全部 resolved）：

| # | 内容 |
|---|---|
| 10 | Compose GUI 骨架与空窗口 |
| 11 | 纯 JVM 最小图模型与节点类型 |
| 12 | 复制几何/状态层并换类型引用（130 份画布测试迁移） |
| 13 | 默认模式：内置工作流 + 参数抽屉 |
| 14 | 运行：子进程、进度解析、成功产物 |
| 15 | 失败/取消/超时的运行态收口 |
| 16 | 工作流保存、载入与严格版本校验 |
| 17 | 外部工具驱动接口预留 |
| 18 | 高级模式自由画布（双模式共享同一工作流） |
| 19 | 一键验收脚本与三平台编译口径 |

## Evidence

- **验收闭环**：`desktop/verify-gui.sh`（无权重缓存路径）→ `verify-gui: PASS (all enabled steps)`，真实冒烟明确记录 `已跳过（无权重缓存）`，退出码 0。
- **全量构建**：`--no-build-cache cleanTest build` → `BUILD SUCCESSFUL`；**366 tests / 0 failures / 0 errors**（core + cli + gui，含 130 画布测试、持久化 12/12、运行态与工具接口测试）。
- **审查**：两轴 code-review（Standards + Spec）+ 对抗性复核，12 条发现确认 2 条真实问题并修复（连线坐标空间 bug、README 与实现不符）。
- **前后对比（关键 bug）**：
  ```diff
   # AdvancedModeScreen.kt 连线绘制
  -val to = deviceViewport.toScreen(wire.to)   // 算了未用
  -val curve = wirePath(wire.from, wire.to)     // 世界坐标
  -Offset(point.x, point.y)                     // 当屏幕像素画 → 缩放/平移时错位
  +wireScreenPath(wire.from, wire.to, deviceViewport)  // 每点经 toScreen 转换
  ```
  新增 `WireScreenPathTest` 以非单位缩放/平移的字面值断言坐标正确。

## Known limits（未验证项，如实记录）

- 真实权重下的 SD1.5 冒烟、GUI 弹窗/鼠标端到端、Windows/macOS 编译：属 ADR-0011 当前验收范围外，未实机验证。
- 高级模式未复刻移动版全功能编辑器（多选/裁剪/预览缩放）；滚轮缩放未接，以按钮与拖拽暴露缩放平移。
- 当前 shell 未暴露保存/载入按钮；持久化以 core API + JVM 测试为验收面（工单 05 决定）。

## Merge Danger

**Door:** 两步门（two-way）。本次交付为新增 `:gui` 模块 + core 新增 `graph`/`canvas` 包，不改动移动版、不改动已有 CLI 行为；回滚只需移除新增文件与提交，风险可逆。

**Blast Radius:** GUI only. CI（`.github/workflows/desktop.yml`）现调用 `verify-gui.sh`，Linux 构建与测试口径变更；无权重/无显示环境按 ADR-0011 跳过可选步骤，不阻塞。
