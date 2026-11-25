# 01: 确认 Compose Multiplatform 的落地方式

Type: wayfinder:research
Status: resolved
Unblocks: 07

## Question

在只应用 JVM 插件、`desktop/settings.gradle.kts` 仅 include `core`/`cli`、Kotlin JVM 2.4.21、Gradle 8.14 的独立构建里，Compose Multiplatform 桌面 target 应如何接入，才能既不引入 Android 依赖又能构建并启动一个空窗口？

请给出可直接写进 spec 的答案：可信的一手版本组合、`settings.gradle.kts` 与各 `build.gradle.kts` 的必要改动点、一条能验证「空窗口构建并启动」的命令，以及已知的版本兼容风险。以 Compose Multiplatform 官方与 Kotlin/Gradle 官方文档、以及仓库内已锁定的版本为准。

## Answer

保持 `org.jetbrains.kotlin.jvm` 插件，不切 KMP、不加 Android 插件/target：新增独立 `:gui` JVM 模块，按 Kotlin 2.4.21 + Compose compiler 2.4.21 + CMP 1.12.2 + Gradle 8.14.4 + JDK 17 组合配置。`libs.versions.toml` 加 `compose-plugin = "1.12.2"` 与两个插件别名（compiler 版本 `version.ref = "kotlin"`）；根 `build.gradle.kts` 两个插件 `apply false`；`settings.gradle.kts` 在 `dependencyResolutionManagement.repositories` 增 `google()`（保持 `FAIL_ON_PROJECT_REPOS`）并 include `:gui`；`:gui` 用 `implementation(compose.desktop.currentOs)` + `compose.desktop.application.mainClass`，入口 `singleWindowApplication`。验证命令 `cd desktop && ./gradlew :gui:run`（无头环境套 `xvfb-run -a`，仅构建校验用 `:gui:classes`）。

本机已在 `/tmp` 等价工程实测：`mavenCentral()` 单独会报 `androidx.collection:collection:1.5.0` 等找不到；加 `google()` 后 `classes` 成功、`run` 真弹出窗口（`SMOKE_WINDOW_DISPLAYED_OK`）。关键风险：官方允许 JVM 插件路径，Gradle `application` 插件从 8.7 起不兼容 KMP 插件，故不要切 KMP；`desktop/settings.gradle.kts:8-9` 现有「No google()」注释需随实现改写——加仓库不等于引入 Android 依赖。完整一手引用与实测记录见 research 分支 `research/compose-multiplatform-setup` 提交 `beedd0e` 的 `.scratch/phase2-gui/research/01-compose-build-setup.md`（本地保留，不合并、不开 PR）。
