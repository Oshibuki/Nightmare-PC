# 04: 图生图的验收边界与文档落盘

Type: wayfinder:grilling
Status: resolved

## Question

裁决图生图首切片的验收到哪一层，以及术语/ADR 何时落盘。

## Answer

- **验收边界**：在既有 `verify-gui.sh` 里**新增一个独立 `img2img` 步**（复用 `run_step`/独立日志/硬超时，ADR-0011 单一入口），假 CLI 断言工作流投影带了 `--init-img`/`--strength`、产物 PNG 进结果面板；真权重冒烟可选跳过、Windows/macOS 只要求 `:gui:classes` 编译通过。不新建脚本。与 chat-turn 步同构，接缝现成。
- **文档落盘**：「图生图（Img2Img）」已写入 `GLOSSARY.md`（定义＝给定初始图与重绘强度、按文字描述改写画面；与「参考图输入」并列区分）；ADR-0013 就已落盘（图片输入节点 + 运行器按节点切换子命令，附三个备选方案取舍）。两份在写规格前落盘，作为 wayfinding 既定前提。
- **重试的验收**：重发按钮的可复现语义（复位工作流 + 干净上下文 + UI 历史保留）作为对话模式状态层测试断言，不另开验收步。
