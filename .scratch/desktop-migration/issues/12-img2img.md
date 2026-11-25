# 12: img2img

**What to build:** CLI 图像到图像：输入图 → 编码 latent → 按强度去噪 → 输出 PNG。用户能对现有图片做改写与变化。

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] 不同去噪强度产出合理渐变结果（0≈原图、1≈重绘）
- [ ] 输入格式/尺寸校验错误路径有明确报错
