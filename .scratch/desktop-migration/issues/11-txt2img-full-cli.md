# 11: txt2img 完整 CLI（Phase 1 首个纵向切片）

**What to build:** 从最小冒烟扩展为可日常使用的 txt2img 命令行：尺寸、步数、采样器（复制的采样器参数逻辑）、seed、负提示、批量等参数；进度输出 stdout，产物 PNG 带 A1111 风格参数元数据的桌面等价物（可简化）。本工单不再拆分；进行中的 `ticket-11` worktree 按现状收尾。

**Blocked by:** 05, 08

**Status:** resolved

- [x] 全部新参数经 CLI 端到端验证：fake engine 覆盖参数传递，错误参数非零退出且有明确报错
- [x] 采样器参数逻辑来自复制的 `Samplers.kt`/`SamplerWire.kt` 且有测试覆盖
- [x] 批量输出命名与 PNG `parameters` 元数据可复现
- [x] 进度输出 stdout：CLI 的 `[i/N] seed=<seed> -> <path>` 行与引擎转发日志都进入 stdout，批量逐张 flush
- [x] 真实 SD1.5 端到端在权重缓存存在时运行并记录结果，不作为 agent 关闭的硬前置

## Comments

- 2026-11-25：拆分评审确认不再拆；`ticket-11` worktree 已实现采样器复制层、CLI 参数/错误码、批量与 PNG 元数据，收尾时提交并合并即可。
- 2026-11-25：提交 `a641fb9` 已合并为 `e3706c7`；`desktop` 全量 build 通过（日志 `/tmp/ticket11-build.log`、`/tmp/integration-build-after-11-13.log`）。真实 SD1.5 E2E 沿用工单 08 的缓存权重冒烟记录。
- 2026-11-25：进程级复核 stdout：`Main.kt` 注入 `System.out`，`Txt2Img.kt` 写进度行并转发引擎合并输出，`Cli.kt` 的 help 明示该契约；假引擎 `--batch 2` 实测 stdout 为两条进度行 + 两条引擎输出，stderr 为空，`Txt2ImgFullCliTest.aBatchPrintsOneProgressLinePerImage` 覆盖同一行为。
