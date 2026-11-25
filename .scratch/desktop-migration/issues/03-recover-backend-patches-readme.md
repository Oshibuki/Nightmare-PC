# 03 - 找回或重建 backend-patches/README.md，并补根级补丁应用脚本

Status: ready-for-agent
Type: task

## 背景

`backend-patches/README.md` 被 CI（`tools/op_smoke.sh`）与多处 Kotlin 注释引用但文件缺失；根级补丁 001–058 的 diff 头指向开发者本地克隆（`../LocalDream/local-dream/app/src/main/cpp/...`），仓库内没有应用脚本。

## 步骤

1. 从上游 local-dream 仓库或 git 历史尝试找回原 README；找不到则按补丁编号与主题重建（补丁分类盘点见 `spec.md`）。
2. 写一个 `backend-patches/apply.sh`（或等效脚本）：按后缀路由（`.ggml.patch`→ggml、`.sdcpp.patch`→sdcpp、其余→包装层 `--directory=app/src/main/cpp`），并带"应用前后 diff sha256 比对、相同即报错退出"的防静默跳过断言（参照 `dit-engine.yml` 175–219 行逻辑）。
3. 在 README 中记录：补丁三类清单（可移植 / Hexagon 专属 / 待定性）、应用顺序约定、根级补丁的目标路径映射表。

## 验收

- `backend-patches/README.md` 存在且被引用路径可解析。
- `apply.sh` 的防静默失败断言经 dry-run 验证。
