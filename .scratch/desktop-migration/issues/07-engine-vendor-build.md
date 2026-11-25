# 07: 引擎 vendor 本地构建（编译期关闭 Hexagon）

**What to build:** `desktop/` 自持引擎源码：submodule 引入 happyyzy fork 与 ggml（SHA 见工单 02），本地一条命令在 Linux 构建出引擎库，构建配置显式排除 Hexagon/QNN 目标、启用 CPU 后端（Linux 另验 Vulkan 可编译）。agent 不依赖外部 CI 即可调试引擎层。

**Blocked by:** 02, 04

**Status:** resolved

- [x] Linux 本地构建成功，产物路径与最小依赖（编译器/Vulkan SDK 版本）已记录
- [x] 构建配置中无 Hexagon/QNN 目标
- [x] 构建脚本预留"补丁应用 + 哈希校验"钩子

## Comments

- 2026-11-25：引擎源码 vendor 于 `desktop/engine/sdcpp`，`build.sh` 默认 clean baseline、`--patch` 默认关闭，构建记录与依赖见 `ENGINES.md`/`README.md`；合并提交 `ac11533`。
