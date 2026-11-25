# 16: LoRA 支持（SD1.5 / sdcpp 原生）

**What to build:** CLI 支持 `--lora`（权重、倍率、多 LoRA 组合），使用 pinned sdcpp 原生 `--lora-model-dir` / prompt `<lora:...>` 能力；可复制的 Android LoRA helper 按复制式复用登记。DiT LoRA 的 ABI 补丁链归工单 23。

**Blocked by:** 09, 11

**Status:** ready-for-agent

- [ ] 单 LoRA 与多 LoRA 参数组合经 fake engine 测试覆盖
- [ ] LoRA 相关复制代码测试全绿；受 Android 符号阻塞的文件在 `copied-files.md` 记录跳过原因
- [ ] 真实 LoRA 出图在基础模型与 LoRA 权重缓存存在时运行并记录结果，不作为硬前置
