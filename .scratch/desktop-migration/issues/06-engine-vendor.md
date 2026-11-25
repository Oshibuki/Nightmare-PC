# 06 - 引擎 vendor：submodule + 编译关 Hexagon + 本地构建脚本

Status: ready-for-agent
Type: task
Blocked by: 02, 04

## 背景

桌面自持引擎源码（ADR-0004），基线为 happyyzy/stable-diffusion.cpp fork，编译期排除 Hexagon 后端（ADR-0005）。

## 步骤

1. `desktop/engine/` 下以 git submodule 引入 happyyzy/stable-diffusion.cpp（SHA 见 02 产出）；在其内再以 submodule 引入 ggml-org/ggml（同样固化 SHA）。
2. 盘点 fork 中 Hexagon/QNN 的编译开关（如 `GGML_HEXAGON` 类宏 / CMake 选项），桌面构建显式关闭；启用 CPU 后端，Linux 上另验 Vulkan 可编译。
3. 写 `desktop/engine/build.sh`：本地一条命令构建引擎库；预留与移动版 `dit-engine.yml` 等价的"补丁应用 + 哈希校验"钩子（实际补丁集由 Phase 1 工单填充）。
4. 记录构建产物路径与最小依赖（编译器版本、Vulkan SDK 等）。

## 验收

- Linux 上本地构建出 sdcpp/ggml 引擎库，且构建配置中无 Hexagon/QNN 目标。
- `ENGINES.md` 含三个精确版本（local-dream 参考 / sdcpp SHA / ggml SHA）。
