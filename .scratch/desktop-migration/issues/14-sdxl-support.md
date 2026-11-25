# 14: SDXL 支持

**What to build:** 登记 SDXL 相关桌面补丁的适用性，CLI 具备 SDXL 权重参数与 VAE 分块/低内存开关；用 fake engine 验证参数传递。1024×1024 真实权重出图只在缓存存在时运行记录。

**Blocked by:** 09, 11

**Status:** resolved

- [x] SDXL 相关补丁逐个登记进应用记录（成功/跳过及原因）
- [x] CLI 具备 SDXL 权重与 VAE tiling 参数，fake engine 测试覆盖
- [x] 真实 SDXL 1024×1024 出图：权重缓存存在时运行并记录结果，否则在 Comments 标注未执行原因

## Comments

- 2026-11-25：`desktop/engine/SDXL.md` 新增逐文件应用记录：补丁库中 18 个 SDXL 相关文件各有唯一一行、`skipped` 判定和具体原因（QNN/Hexagon、MNN/Adreno、mobile wrapper 目标缺失；`032-lowram` 虽可 apply 但目标仍在移动 wrapper）。`SdxlPatchRecordTest` 三项守护总数、唯一性和判定/理由，未新增或改动任何补丁文件，清基线不变。
- 2026-11-25：CLI 新增 `--vae <path>`（单文件 SDXL 的独立 VAE，仍支持多文件通路的同名必填参数）、`--vae-tiling`、`--vae-tile-size <WxH>`、`--vae-tile-overlap <float>`；非法值退出 2，缺失/损坏 VAE 在引擎启动前退出 1；默认 SD1.5 命令行不变。真实 `sd-cli 35a3d48 --help` 确认四个引擎开关均存在。`SdxlCliTest` 7 例覆盖 argv 透传、默认关闭、非法值与帮助文档。
- 2026-11-25：真实 1024×1024 出图（权重缓存存在）：`nightmare-desktop txt2img --model ~/.cache/nightmare-desktop/models/sdxl/sd_xl_base_1.0.safetensors --prompt 'a red fox in an autumn forest, photograph' --out /tmp/ticket14-sdxl-1024.png --width 1024 --height 1024 --steps 2 --seed 42 --engine ~/.cache/nightmare-desktop/engine/sd-cli --vae-tiling` 退出 0；引擎 `generate_image completed in 298.89s`（采样 127.67 s、分块 VAE 解码 168.56 s），PIL 验证产物 1024×1024 RGB、parameters 含 seed 42 与模型名。日志 `/tmp/ticket14-real-e2e.log`，图像 `/tmp/ticket14-sdxl-1024.png`。只跑 2 步：验证通路与显存开关，不评价画质。
- 2026-11-25：实现 `173293a`，评审修复 `9656a8b`（README 选项表同步、重复扩展名校验合并）；集成合并 `47e3ea2` 解决 `Txt2Img.kt` 冲突：`ModelChoice.SingleFile` 承载可选独立 VAE，保留 18 的多文件与 16 的 LoRA 参数。合并后 `timeout 900 ./gradlew --no-daemon --no-build-cache build` → `BUILD SUCCESSFUL`，179 项测试通过（`/tmp/ticket14-merged-build.log`）。
- 2026-11-25：最终并入集成分支 `desktop-migration` 的合并提交 `a07e16d`。
