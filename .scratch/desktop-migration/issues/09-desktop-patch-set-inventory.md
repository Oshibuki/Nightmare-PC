# 09: 桌面补丁集清单

**What to build:** 覆盖移动版补丁库全部补丁的归属清单：约 125 个可移植补丁逐个登记（来源、主题、目标仓库、桌面适用性）；约 47 个 Hexagon/QNN 专属与约 11 个移动 GPU 专属单列排除清单；12–15 个 `fused`/`graph-cut` 补丁标记 deferred（Phase 1 逐个定性）。本工单不实际应用补丁，只产出可执行的清单与顺序建议。

**Blocked by:** 03

**Status:** resolved

- [x] 清单覆盖全部补丁且每个都有归属（桌面用 / 排除 / 待定性）
- [x] 桌面补丁的应用顺序建议已给出

## Comments

- 2026-11-25：`desktop/engine/PATCHES.md` 给出逐补丁归属与应用顺序，`PatchInventoryTest` 锁定"一文件一行、一行一判定、无幽灵行"；合并提交 `2abaf5c`。
