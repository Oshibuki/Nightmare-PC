# 12: img2img

**What to build:** CLI 图像到图像：输入图 → 编码 latent → 按强度去噪 → 输出 PNG。引擎已有 `--init-img`/`--strength`，无需新 ABI 或补丁。

**Blocked by:** 11

**Status:** resolved

- [x] 固定 seed 下断言：强度 0 输出与原图一致、强度 1 与原图像素差异超过写入测试的阈值；不依赖人工看图
- [x] 输入格式/尺寸校验错误路径有明确报错且非零退出
- [x] fake engine 端到端测试覆盖引擎参数传递与 PNG 输出
- [x] 真实 SD1.5 端到端在权重缓存存在时运行并记录结果，不作为硬前置

## Comments

- 2026-11-26：`ticket-12` 新增 `img2img` 命令，把 `--init-img`/`--strength` 传给 vendored `sd-cli`；`--model` 仍按单文件 safetensors 头部校验，源图必须是 PNG 且宽高为 8 的正倍数，元数据记录 `Strength`/`Init image` 以便复现。实现提交 `8e52aef`；合并集成 tip（`3df7333`，含 18/22）为 `61bd7bf`，仅 `Cli.kt` 的 Usage 行冲突，两行（img2img 与多文件 GGUF）均保留。
- 2026-11-26：假引擎断言见 `Img2ImgTest`（11 例）——完整 argv 顺序、PNG 可被 `ImageIO` 打开、元数据、引擎非零退出/错尺寸产物、坏输入路径；固定 seed 下强度 0 与原图像素完全一致（平均通道差 0）、强度 1 平均通道差 > 20（阈值写入测试），由 `strengthEngine` 复现引擎语义，不依赖人工看图。
- 2026-11-26：真实 SD1.5 E2E 在缓存存在时执行（`Img2ImgRealE2ETest`，`assumeTrue` 保护，非硬前置）：seed=42、steps=2、256×256，强度 0 用时 17907 ms、与原图平均通道差 5.73；强度 1 用时 31121 ms、平均通道差 90.10；两产物均可被 `ImageIO` 打开。日志 `/tmp/ticket12-real-e2e.log`，数字取自 JUnit XML 的 stdout。
- 2026-11-26：进程级复核（构建后的 `nightmare-desktop` 启动脚本）：`--help` 退出 0 且列出 `img2img`/`--strength`；`--strength 1.5` 退出 2 并报 `must be between 0 and 1`；非 PNG 的 `--init-img` 退出 1 并报 `is not a PNG`，无产物；真实 img2img（64×64、1 步、strength 0.5）退出 0，`PIL` 可打开 64×64 PNG（引擎 `generate_image` 用时 11.10 s）。日志 `/tmp/ticket12-smoke/`。
- 2026-11-26：合并后全量 `--no-build-cache cleanTest build` 通过（日志 `/tmp/ticket12-merged-build.log`），含 11/13/18/22 既有测试；单测红/绿轨迹见 `/tmp/ticket12-red1.log`、`/tmp/ticket12-green1.log`。
