# 10: Linux CI 基线

**What to build:** 新增 workflow：Linux runner 在 `desktop/` 变更时自动构建并跑全部测试。引擎构建耗时长，CI 先只编译 Kotlin 层+测试，引擎库作为可选 job 或缓存产物。仓库尚无远端时把 workflow 准备好，远端接入即生效。

**Blocked by:** 04, 05

**Status:** resolved

- [x] workflow 语法有效（dry-run/act 验证），触发路径与命令正确

## Comments

- 2026-11-25：workflow 落在 `.github/workflows/desktop.yml`，`desktop/` 变更触发 Kotlin 层构建与全部测试；远端接入前本地 dry-run 验证；合并提交 `3a208b5`。
