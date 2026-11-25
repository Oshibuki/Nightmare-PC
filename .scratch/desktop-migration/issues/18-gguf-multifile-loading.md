# 18: GGUF/多文件加载 CLI 通路

**What to build:** 把多文件 GGUF 的加载参数（`--diffusion-model`、`--llm`、`--vae`）接入桌面 CLI，以 fake engine 验证参数路由与错误路径；为 19–21、23 提供可复用通路。

**Blocked by:** 13

**Status:** resolved

**Optional:** yes

- [x] fake engine 测试覆盖三种参数组合与缺文件报错
- [x] `--model` 遇 GGUF 时给出指向本通路的明确提示
- [ ] 真实 GGUF 加载在权重缓存存在时运行并记录结果，不作为硬前置（本机可用内存 2.4 GiB 小于 z-image 权重约 5.7 GiB，且并行 agent 正跑真实 SD1.5；未启动以避免 OOM。真实模型 E2E 属 19–21，本项按工单约定非关闭前置）

## Comments

- 2026-11-25：从原 13/15 拆出。受本机性能限制，依赖本工单的 19–21、23 均可放弃，不阻塞 Phase 1 关闭。
- 2026-11-25：由 `ticket-18` worktree 实现并提交 `b663550`（英文提交信息，含 why）。`desktop-migration` 合并检查为 “已经是最新的”（tip 仍为 `e21fef2`，无冲突）。
- 2026-11-25：`txt2img` 现支持两种互斥的模型选择：`--model`（单文件）或 `--diffusion-model` + `--llm` + `--vae`（Qwen Image 另加可选 `--llm_vision`）。四个路径都会在引擎启动前检查存在性、可读性与文件头；缺文件报错点名具体选项。`--model` 收到 GGUF 时的提示改为指向这条已实现的通路。帮助文本、`desktop/README.md`、`desktop/docs/models.md` 同步更新。
- 2026-11-25：验证——`timeout 900 ./gradlew --no-daemon --no-build-cache cleanTest build`（日志 `/tmp/nm-t18-merged-build.log`）退出 0，109 个测试全绿、0 failures/errors；新增 `GgufMultiFileCliTest` 10 个测试覆盖三种模型选择（单文件 / 三文件 / 四文件含 vision）、`--name=value`、逐项缺文件、坏文件头、不支持扩展名与互斥/半组参数。红-绿证据见 `/tmp/nm-t18-red1.log` → `/tmp/nm-t18-green1.log`、`/tmp/nm-t18-red2.log` → `/tmp/nm-t18-green2.log`。
- 2026-11-25：真实引擎 `/home/chjlxxc/.cache/nightmare-desktop/engine/sd-cli --help`（`timeout 60`，日志 `/tmp/nm-t18-sdcli-help.txt`，退出 0）确认转发的所有四个 flag（`--diffusion-model`、`--llm`、`--vae`、`--llm_vision`）都是该二进制原生支持的选项。未做真实权重推理，理由见上一条勾选说明。
