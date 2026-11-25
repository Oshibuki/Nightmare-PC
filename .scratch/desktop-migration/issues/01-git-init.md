# 01: git init 基线

**What to build:** 把当前工作区变成 git 仓库并留下 baseline 提交，使后续一切依赖 git 的机制（引擎 submodule、补丁 `git apply` 与哈希校验）成为可能。不配置远端。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `git status` 干净，`git log` 含 "baseline before desktop migration" 提交
- [ ] `.gitignore` 已覆盖现有与 `desktop/`（后续）的构建产物路径
