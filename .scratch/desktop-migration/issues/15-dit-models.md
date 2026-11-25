# 15: DiT 系模型（Z-Image Turbo / FLUX.2 Klein / Qwen Image）

**What to build:** 应用 `dit/` 模型支持补丁，打通 DiT 架构的加载与推理，三种代表性模型端到端出图。顺带完成 deferred 的 `fused`/`graph-cut` 补丁定性（适用/排除及原因）。

**Blocked by:** 09, 11

**Status:** ready-for-agent

- [ ] 三种 DiT 模型各完成一次端到端生成
- [ ] fused/graph-cut 补丁定性结论补录进补丁集清单
