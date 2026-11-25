---
status: accepted
date: 2026-11-25
---

# 0003 - Nightmare Desktop 位于同一仓库顶层 `desktop/`，独立 Gradle 工程

桌面骨架工程不另建仓库，而是放在本仓库顶层 `desktop/` 目录：拥有自己的 `settings.gradle.kts`，与 Android 的 `app/` 互不引用、互不共享依赖。选择同仓库是因为可复用的 Kotlin 代码分散在 `app/src/main/java` 的十几个子包中，同一仓库让 AI agent 能在一个工作区内对照检索并复制源文件；解耦由独立构建脚本保证，不需要分仓库。
