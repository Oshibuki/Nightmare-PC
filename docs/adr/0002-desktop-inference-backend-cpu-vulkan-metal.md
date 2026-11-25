---
status: accepted
date: 2026-11-25
---

# 0002 - 桌面版推理后端放弃 NPU，使用 sdcpp/ggml 的 CPU + Vulkan/Metal

移动版的性能依赖骁龙 NPU（Hexagon/QNN 后端），桌面硬件没有对应物。桌面版放弃 NPU 路线，改用上游 stable-diffusion.cpp / ggml 自带的后端：CPU + Vulkan（Windows/Linux）、CPU + Metal（macOS）。`backend-patches/` 中与后端无关的模型支持补丁（Qwen Image、Wan 2.2、FLUX 系等）在桌面侧沿用；Hexagon/QNN 专属补丁与 `npu/` 代码留在移动版不动。
