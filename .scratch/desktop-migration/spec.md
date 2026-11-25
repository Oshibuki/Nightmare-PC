# Spec: nightmare-mobile 跨平台迁移（Windows / Linux / macOS）

Status: ready-for-agent
Type: spec

> 领域术语见根 `GLOSSARY.md`（Nightmare Desktop、无头引擎、桌面补丁集、复制式复用）；决策依据见 `docs/adr/0001`–`0006`。工单见 `issues/01–10`。

## Problem Statement

项目的 AI 图像/视频生成能力目前只能运行在骁龙手机上：推理依赖高通 NPU，UI 依赖 Android，用户离开手机就无法使用自己的模型与工作流；维护者也没有一个能让 AI coding agent 自主完成"改代码 → 构建 → 测试 → 冒烟"闭环的桌面开发环境。

## Solution

建立 **Nightmare Desktop**：一个与 Android 版完全解耦的桌面版本，位于仓库顶层 `desktop/`，分三阶段交付——Phase 0 工程准备（可复现构建 + 测试基线 + agent 导航文档）、Phase 1 无头引擎（CLI 先行，后接 A1111 兼容本地 HTTP API）、Phase 2 GUI（Compose Multiplatform）。推理走 CPU + Vulkan（Windows/Linux）/ Metal（macOS），引擎源码以 vendor 方式自持，复用 Android 版代码采用复制式复用。平台顺序：Linux 为 CI/agent 基线先行，随后 Windows、macOS。

## User Stories

**桌面用户**

1. 作为桌面用户，我想在 Windows/Linux/macOS 上用本地硬件生成图像，所以不需要云端服务或骁龙手机。
2. 作为桌面用户，我想用一条 CLI 命令完成文本生成图像（txt2img），所以可以把生成嵌进脚本与自动化。
3. 作为桌面用户，我想用 CLI 完成图像到图像（img2img），所以能对现有图片做改写。
4. 作为桌面用户，我想加载自己的 `.safetensors` / GGUF 模型文件，所以能使用自己微调的模型而无需转换。
5. 作为桌面用户，我想在无 NPU 的普通电脑上获得可用速度（CPU + Vulkan/Metal），所以不必购置特定硬件。
6. 作为桌面用户，我想在 Phase 1 之后获得本地 A1111 兼容 HTTP API，所以能让其他桌面软件把本机当作生成后端。
7. 作为桌面用户，我想在 Phase 2 获得节点图 GUI，所以能以可视方式搭建与复用生成流程。
8. 作为桌面用户，我想用 LoRA 与 ControlNet 类附加能力，所以能控制风格与构图。

**AI coding agent**

9. 作为 AI agent，我想在 Linux 上一条命令完成 `desktop/` 构建，所以能自主验证每次改动。
10. 作为 AI agent，我想一条命令跑全部测试并得到明确通过/失败结果，所以能自检不引入回归。
11. 作为 AI agent，我想通过 CLI 冒烟（SD1.5 出一张图）做端到端验证，所以能确认"改引擎 → 编译 → 冒烟"闭环成立。
12. 作为 AI agent，我想有 `desktop/` 内的导航文档说明架构、构建命令与禁区，所以不需要每次向人询问上下文。
13. 作为 AI agent，我想在本地构建引擎（源码自持，不依赖外部 CI 产物），所以能调试引擎层问题。
14. 作为 AI agent，我想复制的源文件头部标明来源，所以能判断一段逻辑的出处与原始语义。

**维护者**

15. 作为维护者，我想桌面版与 Android 版构建、依赖完全解耦，所以一边的改动不会破坏另一边。
16. 作为维护者，我想引擎版本（local-dream / sdcpp / ggml）以精确 SHA 固化并记录在案，所以任何构建都可复现、可追溯。
17. 作为维护者，我想全部引擎补丁有一份归属清单（桌面用 / 排除 / 待定性），所以补丁资产不会随人员变动而失传。
18. 作为维护者，我想复制的代码有追踪文档且无自动同步义务，所以两个版本可以独立演进而无需维护同步机制。
19. 作为维护者，我想 Linux CI 在 `desktop/` 变更时自动构建并测试，所以回归在合并前暴露。
20. 作为维护者，我想 NPU 相关代码留在移动版不动，所以移动版行为零风险。
21. 作为维护者，我想 Phase 0 有明确的五条验收标准，所以准备工作的完成与否可以客观判定。

## Implementation Decisions

- **工程形态**：Nightmare Desktop 位于同一仓库顶层 `desktop/`，拥有独立的 Gradle 构建脚本与锁定的依赖版本，与 Android 工程互不引用（ADR-0003）。模块划分为 CLI 入口、核心逻辑（复制的 JVM 代码）、引擎（vendor）与桌面专属文档四个职责区。
- **复用策略**：复制式复用——从 Android 工程单向复制源文件，允许就地修改，两版各自演进，无同步机制；每个复制文件在追踪文档中记录来源（ADR-0001）。首批候选：模型目录、GGUF 导入、执行调度、采样器参数、节点图纯几何层。
- **推理后端**：CPU + Vulkan（Windows/Linux）/ Metal（macOS），放弃 NPU；Hexagon/QNN 专属代码不进桌面构建（ADR-0002）。
- **引擎管理**：git submodule 自持引擎源码，基线为 happyyzy fork（模型支持补丁的代码上下文），编译期关闭 Hexagon 后端；本地脚本或桌面 CI 自建，不走 release asset 下载（ADR-0004/0005）。三个上游版本（local-dream 参考 / sdcpp / ggml）以精确 SHA 固化。
- **补丁策略**：桌面补丁集从移动版补丁库中挑选平台无关部分（约 125 个文件）并逐个登记归属；Hexagon/NPU 专属（约 47 个）与移动 GPU 专属（约 11 个）明确排除；应用方式沿用"按后缀路由 + 应用前后 diff 哈希比对防静默跳过"。
- **无头接口**：Phase 1 先交付最小 CLI（txt2img/img2img，模型加载→推理→保存 PNG 主链路），随后移植 A1111 兼容本地 HTTP API；进度输出到 stdout 且失败必须非静默。
- **模型顺序**：SD1.5（冒烟基线）→ SDXL → DiT 系（Z-Image Turbo / FLUX.2 Klein / Qwen Image）→ LoRA/ControlNet。
- **GUI（Phase 2）**：Compose Multiplatform，复用节点图纯几何层，自绘渲染层按桌面重写（ADR-0006）。

## Testing Decisions

- **只测外部行为**：断言"给定输入产生什么输出/退出码"，不断言实现细节（内部函数调用、数据结构布局）。
- **接缝 1 —— CLI 端到端**（最高接缝）：冒烟 = 提供模型与提示词 → 断言产出可打开的 PNG 且退出码为 0；错误场景断言非零退出码与明确错误信息。这是 Phase 0 验收第 2 条。
- **接缝 2 —— Gradle 测试源集**：复制到核心逻辑的代码沿用 Android 版已有的 JVM JUnit 测试先例（原工程约 201 个测试文件的同风格），覆盖模型目录解析、执行调度、采样器参数、节点图几何命中。
- **引擎构建不设测试接缝**：以构建脚本退出码 + 产物存在性验证，归入引擎工单验收。
- **基线意义**：复制的测试即迁移回归基线——Phase 0 验收第 3 条要求其全绿；后续任何对复制代码的就地修改都不得使其转红。

## Out of Scope

- 视频模型（Wan 2.2、MiniMax H3）与运动迁移——Phase 2+ 单独评估。
- NPU/QNN 路径的任何桌面适配；移动版任何功能或代码改动。
- 与 Android 版的自动同步机制或共享模块。
- Windows/macOS 的端到端冒烟（各平台到达时独立做；Phase 0 只要求可编译）。
- 干净基线（leejet 上游）引擎化重构——happyyzy fork 死代码的清理将来单独评估。
- 12–15 个 `fused`/`graph-cut` 补丁的实际应用——Phase 1 逐个定性后再议。
- GUI（Phase 2）的具体交互设计。

## Further Notes

- **Phase 0 验收五条**：① Linux 一条命令构建（依赖锁版本）；② CLI SD1.5 CPU 出图冒烟；③ 复制代码测试全绿；④ Windows/macOS 至少编译通过；⑤ `desktop/` 内有自己的 agent 导航文档。
- **已知缺口**（工单内处理，不阻塞）：移动版补丁库的 README 缺失需找回/重建；引擎 pinned SHA 只存在于 CI 构建输出需固化；根级补丁无仓库内应用脚本需补。
- **执行入口**：`issues/01–17`，编号即依赖序，依赖关系见各工单 `Blocked by` 行。
  - Phase 0（01–10）：01 git init → 02 固化引擎 SHA → 03 补丁 README/应用脚本 → 04 desktop 骨架 → 05 复制 JVM 代码与测试 → 06 agent 导航文档 → 07 引擎 vendor 构建 → 08 CLI SD1.5 冒烟 → 09 补丁集清单 → 10 Linux CI。
  - Phase 1（11–17）：11 txt2img 完整 CLI → 12 img2img → 13 模型目录与 GGUF/safetensors 导入 → 14 SDXL → 15 DiT 系 → 16 LoRA → 17 A1111 兼容 API。
  - 当前前沿（无阻塞，可立即开始）：**01、03、04**。
- **补丁资产盘点**：可移植约 125 个文件（根级 42 个编号 + dit 子目录约 55 个）；Hexagon/QNN 专属约 47 个；移动 GPU（Adreno OpenCL / MNN）专属约 11 个；待定性 12–15 个。上游链：local-dream `v3.0.0-alpha.4` → happyyzy/stable-diffusion.cpp → ggml-org/ggml。
