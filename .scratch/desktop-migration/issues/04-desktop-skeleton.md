# 04: desktop/ 骨架与一条命令构建

**What to build:** Nightmare Desktop 的可构建骨架：仓库顶层 `desktop/`，独立 Gradle 构建与锁定版本的依赖，与 Android 工程零引用。Linux 上一条命令完成构建+测试，这是 agent 自主验证循环的地基。

**Blocked by:** 01

**Status:** resolved

- [x] Linux 一条命令构建+测试成功，产物路径明确
- [x] `desktop/` 与 `app/` 零依赖（依赖树抽查通过）
- [x] 依赖版本锁定（版本目录/锁文件）

## Comments

- 2026-11-25：独立 Gradle 工程落地于 `desktop/`，版本集中在 `gradle/libs.versions.toml`，构建/测试命令与产物路径见 `desktop/README.md`；合并提交 `a8dd227`。
