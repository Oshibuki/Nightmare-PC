# 12: img2img

**What to build:** CLI 图像到图像：输入图 → 编码 latent → 按强度去噪 → 输出 PNG。引擎已有 `--init-img`/`--strength`，无需新 ABI 或补丁。

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] 固定 seed 下断言：强度 0 输出与原图一致、强度 1 与原图像素差异超过写入测试的阈值；不依赖人工看图
- [ ] 输入格式/尺寸校验错误路径有明确报错且非零退出
- [ ] fake engine 端到端测试覆盖引擎参数传递与 PNG 输出
- [ ] 真实 SD1.5 端到端在权重缓存存在时运行并记录结果，不作为硬前置
