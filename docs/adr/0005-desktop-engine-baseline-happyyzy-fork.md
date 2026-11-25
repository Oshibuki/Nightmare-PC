---
status: accepted
date: 2026-11-25
---

# 0005 - 桌面引擎基线选 happyyzy fork（Hexagon fork），编译期关闭 Hexagon 后端

桌面版 sdcpp submodule 指向 happyyzy/stable-diffusion.cpp（基于 leejet 上游的 Hexagon fork），而非干净的上游 leejet。理由：`backend-patches/dit/` 中约 55 个可移植补丁是以该 fork 的代码为上下文编写的，直接可打；干净基线几乎每个补丁都要手工移植。Hexagon 后端在桌面构建中用编译开关排除，死代码无运行时影响。将来如需干净化再单独评估。

**注意**：happyyzy fork 与 ggml 的 pinned commit SHA 不在本仓库内（只在 CI 构建输出中打印），Phase 0 必须先固化这两个 SHA。
