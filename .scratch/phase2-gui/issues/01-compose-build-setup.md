# 01: 确认 Compose Multiplatform 的落地方式

Type: wayfinder:research
Status: open
Unblocks: 07

## Question

在只应用 JVM 插件、`desktop/settings.gradle.kts` 仅 include `core`/`cli`、Kotlin JVM 2.4.21、Gradle 8.14 的独立构建里，Compose Multiplatform 桌面 target 应如何接入，才能既不引入 Android 依赖又能构建并启动一个空窗口？

请给出可直接写进 spec 的答案：可信的一手版本组合、`settings.gradle.kts` 与各 `build.gradle.kts` 的必要改动点、一条能验证「空窗口构建并启动」的命令，以及已知的版本兼容风险。以 Compose Multiplatform 官方与 Kotlin/Gradle 官方文档、以及仓库内已锁定的版本为准。
