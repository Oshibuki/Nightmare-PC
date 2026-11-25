# 10: Compose GUI 骨架与空窗口

**What to build:** 作为 AI coding agent，我能从干净检出用一条命令编译并启动 Nightmare Desktop 的 GUI 骨架空窗口；该模块按锁定版本独立构建，不引入 Android 依赖，也不要求移动版任何改动。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 新增独立的 `:gui` JVM 模块，保留 JVM 插件，不切换多平台插件
- [ ] 版本沿用 Kotlin 2.4.21、Compose compiler 2.4.21、Compose Multiplatform 1.12.2、Gradle 8.14.4 与 JDK 17
- [ ] 集中依赖仓库允许解析 Compose 所需的通用构件，并保持「模块不得自行声明仓库」的严格模式
- [ ] 空窗口能从明确入口启动；无头环境借助虚拟显示可运行
- [ ] 编译与启动命令都有超时和日志，失败留下证据
- [ ] 导航文档说明新增模块、构建命令与定位 CLI 可执行文件的方式
- [ ] 未引入 Android 插件或 target，移动版零改动
