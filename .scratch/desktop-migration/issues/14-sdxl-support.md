# 14: SDXL 支持

**What to build:** 应用桌面补丁集中 SDXL 相关补丁（含 VAE 分块/低内存），CLI 能以 SDXL 权重完成 1024x1024 生成。首个真正考验"补丁应用 + 哈希校验"基础设施的工单。

**Blocked by:** 09, 11

**Status:** ready-for-agent

- [ ] SDXL 1024x1024 端到端出图
- [ ] 所用补丁逐个登记进应用记录（成功/跳过及原因）
