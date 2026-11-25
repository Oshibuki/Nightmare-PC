# 07 - 桌面补丁集清单文档

Status: ready-for-agent
Type: task
Blocked by: 03

## 背景

从移动版 `backend-patches/` 挑选桌面可用的补丁子集（桌面补丁集，见 `GLOSSARY.md`）。分类盘点已在探索中完成（`spec.md` 补丁资产盘点节）。

## 步骤

1. 建 `desktop/engine/patches/`（或文档先行的 `desktop/engine/PATCHES.md`）：逐个列入约 125 个可移植补丁（根级 42 个编号 + `dit/` 约 55 个），标注来源路径、主题、目标仓库（sdcpp/ggml/包装层）、桌面是否适用。
2. Hexagon/QNN 专属（约 47 个）与移动 GPU 专属（Adreno OpenCL / MNN 约 11 个）单列"排除清单"，注明排除原因。
3. 约 12–15 个 `fused`/`graph-cut` 补丁标记为 `deferred`（Phase 1 逐个读 diff 定性）。
4. 不在本工单实际应用补丁——那是 Phase 1 的事；本工单只产出可执行的清单与顺序建议。

## 验收

- 清单覆盖 `backend-patches/` 全部补丁且每个都有归属（桌面用 / 排除 / 待定性）。
