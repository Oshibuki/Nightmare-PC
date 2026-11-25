# Nightmare（跨平台迁移上下文）

把 Android 端 AI 图像/视频生成能力（nightmare-mobile）迁移到 Windows/Linux/macOS 的上下文。桌面能力由独立的 Nightmare Desktop 工程承载。

## Language

**Nightmare Desktop**:
面向 Windows/Linux/macOS 的独立版本，位于仓库顶层 `desktop/` 目录，构建与依赖同 Android 版完全解耦。
_Avoid_: 桌面骨架工程、desktop skeleton（口语可用，文档统一用本名）

**无头引擎（Headless Engine）**:
不带 UI 的推理交付形态，通过 CLI 与本地 HTTP API 对外提供服务，是 GUI 阶段之前的交付目标。
_Avoid_: 后端模式、server mode

**桌面补丁集（Desktop Patch Set）**:
从移动版 `backend-patches/` 中挑选出的、与 Hexagon/QNN 后端无关的引擎补丁子集，供桌面引擎构建使用。
_Avoid_: 把 `backend-patches/`（移动版全集）与桌面子集混用

**复制式复用（Copy Reuse）**:
从 Android 工程单向复制源文件到桌面工程、允许就地修改的复用方式；两版各自演进，无同步机制。
_Avoid_: 共享模块、代码共享

**纵向切片（Tracer Bullet）**:
一次交付即可打通端到端主链路的最小工单；验收覆盖真实输入到真实输出，而不是只完成一个孤立的横向技术层。
_Avoid_: 追踪弹（文档统一用本名，口语可说 tracer bullet）
