# 23: DiT LoRA ABI 补丁链

**What to build:** 在工单 18 的通路上应用 DiT LoRA ABI 补丁链并验证 fake engine ABI 参数；真实 DiT LoRA 出图仅在缓存存在时记录。

**Blocked by:** 18

**Status:** ready-for-agent

**Optional:** yes

- [ ] DiT LoRA 相关补丁逐个登记进应用记录
- [ ] fake engine 测试覆盖 LoRA 参数与 ABI 名称
- [ ] 真实权重缓存存在时运行并记录结果，否则可关闭为 `wontfix`

## Comments

- 2026-11-25：从原 16 的 DiT 部分拆出；DiT 系模型可放弃时本工单也可放弃。
