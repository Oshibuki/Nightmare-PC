# 13: 模型目录与单文件 safetensors 导入

**What to build:** 扫描本地模型目录、解析单文件 `.safetensors` 头部，CLI 提供 `models list [--dir]` 并让 `--model` 接受单文件 checkpoint。多文件 GGUF 加载不在本工单范围内。

**Blocked by:** 05, 08

**Status:** resolved

- [x] 模型文件识别与目录扫描测试在 `desktop/` 全绿
- [x] CLI 能列出本地模型，并按单文件 safetensors 路径加载
- [x] 坏文件与不支持格式（含 GGUF）有明确报错且非零退出
- [x] `desktop/docs/copied-files.md` 登记新增文件：复制来的写来源，桌面自写的注明非复制

## Comments

- 2026-11-25：多文件 GGUF（`--diffusion-model/--llm/--vae`）拆到工单 18；本工单只提交 `ticket-13` worktree 中已完成的目录列表与单文件校验部分。
- 2026-11-25：提交 `1c62d09`、集成合并 `cd42f2f` 已合为 `1d3f020`；合并时解决与工单 11 的 `Cli.kt`/`Txt2Img.kt` 冲突，保留全部 CLI 参数与模型校验，`desktop` 全量 build 通过（日志 `/tmp/ticket13-merged-build3.log`、`/tmp/integration-build-after-11-13.log`）。
