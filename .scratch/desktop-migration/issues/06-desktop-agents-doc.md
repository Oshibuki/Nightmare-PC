# 06: desktop/ agent 导航文档

**What to build:** `desktop/` 内面向 AI agent 的导航文档：架构地图（四个模块职责与复制来源关系）、已验证的构建/测试/冒烟命令、引擎重建说明、禁区（不动移动版 NPU 代码、不回移、不改既有补丁）。一个不熟悉仓库的 agent 仅凭此文档能完成构建→测试→冒烟循环。

**Blocked by:** 04, 05

**Status:** resolved

- [x] 文档中的每条命令都实际验证过
- [x] 禁区清单明确（含"桌面改动不得反向同步回移动版"）

## Comments

- 2026-11-25：逐条验证 `desktop/README.md` 的 Gradle build/test/projects/dependencies/cli run/installDist 与安装版 `--help`/`--version`，引擎 `build.sh --help`/`--dry-run` 和 `build_test.sh`（39 passed），SD1.5 冒烟产出 512×512 PNG；证据日志 `/tmp/nm-verify-*.log`、冒烟图 `/tmp/nm-doc-verify.png`。禁区见 README 的 Boundaries 段。状态改为 resolved。
