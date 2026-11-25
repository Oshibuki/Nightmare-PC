---
status: accepted
date: 2026-11-25
---

# 0004 - 桌面工程自持（vendor）引擎源码， submodule + 桌面补丁集，本地/CI 自建

移动版的模式是：引擎源码不进仓库，由 CI 克隆上游 stable-diffusion.cpp/ggml、叠加 `backend-patches/` 后产出 release asset，App 运行时下载。桌面版反其道：在 `desktop/` 工程内以 git submodule 指向上游 sdcpp/ggml，并携带从移动版补丁集中挑选出的桌面补丁集，本地脚本或桌面 CI 直接构建。理由：依赖完全解耦（ADR-0001）应延伸到引擎——任何平台都能本地构建与调试，AI agent 能自己完成"改引擎 → 编译 → 冒烟"闭环，不依赖外部 CI。
