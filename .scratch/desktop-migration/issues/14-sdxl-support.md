# 14: SDXL 支持

**What to build:** 登记 SDXL 相关桌面补丁的适用性，CLI 具备 SDXL 权重参数与 VAE 分块/低内存开关；用 fake engine 验证参数传递。1024×1024 真实权重出图只在缓存存在时运行记录。

**Blocked by:** 09, 11

**Status:** ready-for-agent

- [ ] SDXL 相关补丁逐个登记进应用记录（成功/跳过及原因）
- [ ] CLI 具备 SDXL 权重与 VAE tiling 参数，fake engine 测试覆盖
- [ ] 真实 SDXL 1024×1024 出图：权重缓存存在时运行并记录结果，否则在 Comments 标注未执行原因
