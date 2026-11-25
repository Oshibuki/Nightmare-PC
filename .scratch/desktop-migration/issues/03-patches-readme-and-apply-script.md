# 03: 补丁库 README + 防静默应用脚本

**What to build:** 恢复补丁资产的可操作性：找回或重建移动版补丁库缺失的 README；提供按后缀路由（`.ggml.patch`→ggml、`.sdcpp.patch`→sdcpp、其余→包装层）的应用脚本，并带"应用前后 diff 哈希比对、相同即报错退出"的防静默跳过断言。使被 CI 与多处代码注释引用的路径重新可解析。

**Blocked by:** 01

**Status:** resolved

- [x] README 存在且包含补丁三类清单（可移植 / Hexagon 专属 / 待定性）与应用顺序约定
- [x] 应用脚本的防静默失败断言经 dry-run 验证

## Comments

- 2026-11-25：`backend-patches/README.md` 已恢复，按后缀路由与"应用前后 diff 相同即报错"的应用脚本就位（见 `desktop/engine/PATCHES.md`）；合并提交 `704a8cf`。
