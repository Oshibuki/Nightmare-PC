# 16: LoRA 支持（SD1.5 / sdcpp 原生）

**What to build:** CLI 支持 `--lora`（权重、倍率、多 LoRA 组合），使用 pinned sdcpp 原生 `--lora-model-dir` / prompt `<lora:...>` 能力；可复制的 Android LoRA helper 按复制式复用登记。DiT LoRA 的 ABI 补丁链归工单 23。

**Blocked by:** 09, 11

**Status:** resolved

- [x] 单 LoRA 与多 LoRA 参数组合经 fake engine 测试覆盖（`Txt2ImgLoraTest`，11 个用例：单/多、prompt 标签、倍率缺省与负值、缺权重/目录、默认目录）
- [x] LoRA 相关复制代码测试全绿（`LoraSpecTest` 7 + `PromptLorasTest` 5）；受 Android 符号阻塞的文件在 `copied-files.md` 记录跳过原因
- [x] 真实 LoRA 出图在基础模型与 LoRA 权重缓存存在时运行并记录结果，不作为硬前置

## Comments

- 2026-11-25：提交 `d9b4f83` 加入 `--lora` / `--lora-dir`、prompt `<lora:...>` 归一化和 PNG `Lora:` 元数据，并复制 `LoraSpec.kt`、`PromptLoras.kt` 的纯解析部分及其 12 个移动版断言。失败先行：红态 7/9 失败（`/tmp/ticket16-red.log`），实现后全绿（`/tmp/ticket16-green.log`）。
- 2026-11-25：合并 `a5b9b75` 纳入集成分支 `7ea8fbc`（工单 18 的多文件模型通路）；`Txt2Img.kt` 冲突已消解，保留 `ModelChoice.engineArgs()` 与 LoRA 参数。`--no-build-cache cleanTest build` 全绿（`/tmp/ticket16-merged-nocache-build.log`）。
- 2026-11-25：真实出图用缓存的 `v1-5-pruned-emaonly.safetensors` + `lora/lcm-sd15.safetensors`，512×512 / 4 步 / seed 42，退出码 0、耗时 125 s，引擎日志显示 `834/834 LoRA tensors applied`；产物 `/tmp/ticket16-real-lora-merged.png` 为可打开的 512×512 PNG，`parameters` 含 `Lora: lcm-sd15.safetensors@0.8`（日志 `/tmp/ticket16-real-lora-merged.log`）。真实出图是条件可用的额外验证，不是本单硬前置。
- 2026-11-25：DiT LoRA 的 ABI 补丁链不在本单范围（工单 23）。本单只使用 pinned sdcpp 原生的 `--lora-model-dir` / `<lora:...>` 能力。
- 2026-11-25：再次合并集成分支 `4aab3c4`（工单 17/22），合并提交 `6c7c64a` 无冲突；`--no-build-cache cleanTest build` 全绿，143 个测试通过（`/tmp/ticket16-final-build.log`）。
- 2026-11-25：最后一次合并集成分支 `8414319`（工单 24），合并提交 `ae1e699`；`copied-files.md` 的复制登记冲突按两张表合并（LoRA + API 两套登记并存）。`--no-build-cache cleanTest build` 全绿，156 个测试通过（`/tmp/ticket16-final-build2.log`）。
