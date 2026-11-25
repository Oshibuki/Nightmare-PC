# 18: GGUF/多文件加载 CLI 通路

**What to build:** 把多文件 GGUF 的加载参数（`--diffusion-model`、`--llm`、`--vae`）接入桌面 CLI，以 fake engine 验证参数路由与错误路径；为 19–21、23 提供可复用通路。

**Blocked by:** 13

**Status:** ready-for-agent

**Optional:** yes

- [ ] fake engine 测试覆盖三种参数组合与缺文件报错
- [ ] `--model` 遇 GGUF 时给出指向本通路的明确提示
- [ ] 真实 GGUF 加载在权重缓存存在时运行并记录结果，不作为硬前置

## Comments

- 2026-11-25：从原 13/15 拆出。受本机性能限制，依赖本工单的 19–21、23 均可放弃，不阻塞 Phase 1 关闭。
