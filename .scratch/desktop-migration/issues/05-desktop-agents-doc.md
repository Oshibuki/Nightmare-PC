# 05 - desktop/ 内的 AGENTS.md（agent 导航文档）

Status: ready-for-agent
Type: task
Blocked by: 04

## 背景

Phase 0 验收第 5 条：`desktop/` 需要有面向 AI agent 的导航文档。根 `AGENTS.md` 已有工程级配置（issue tracker / triage labels / domain docs），桌面文档不重复它。

## 内容要求

1. 架构地图：`desktop/` 各目录职责、与 `app/` 的关系（复制来源、允许就地修改、不回移，ADR-0001）。
2. 构建与验证命令：一条命令构建、跑测试、CLI 冒烟的具体命令。
3. 引擎说明：submodule 结构、如何重建、Hexagon 编译开关位置（ADR-0004/0005）。
4. 禁区：不改 `app/` 下 NPU 相关代码（`npu/`、`DeviceProbe` 等）；不改移动版 `backend-patches/` 既有补丁；桌面改动不得反向同步回移动版。
5. 领域文档指引：术语查根 `GLOSSARY.md`，决策查 `docs/adr/`。

## 验收

- 一个不熟悉本仓库的 agent 仅凭此文档能完成构建→测试→冒烟循环。
