# 10: Compose GUI 骨架与空窗口

**What to build:** 作为 AI coding agent，我能从干净检出用一条命令编译并启动 Nightmare Desktop 的 GUI 骨架空窗口；该模块按锁定版本独立构建，不引入 Android 依赖，也不要求移动版任何改动。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] 新增独立的 `:gui` JVM 模块，保留 JVM 插件，不切换多平台插件
- [x] 版本沿用 Kotlin 2.4.21、Compose compiler 2.4.21、Compose Multiplatform 1.12.2、Gradle 8.14.4 与 JDK 17
- [x] 集中依赖仓库允许解析 Compose 所需的通用构件，并保持「模块不得自行声明仓库」的严格模式
- [x] 空窗口能从明确入口启动；无头环境借助虚拟显示可运行
- [x] 编译与启动命令都有超时和日志，失败留下证据
- [x] 导航文档说明新增模块、构建命令与定位 CLI 可执行文件的方式
- [x] 未引入 Android 插件或 target，移动版零改动

## Comments

- 2026-11-26：分支 `gui-ticket-10`，提交 `099c7e5`「Add Compose Desktop GUI skeleton with empty window」；以 `--no-ff` 合并进集成分支 `desktop-migration`，合并提交 `c0e397c`。仅 6 个 `desktop/` 文件改动（新增 `:gui` 模块、集中仓库 `google()`、版本别名、README 导航），移动版零改动，无 Android 插件或 target。
- 2026-11-26：TDD 红绿验证——红测 `/tmp/gui10-red.log` 为 `FAIL: projects output has no :gui module`；绿测 `/tmp/gui10-green.log` 中 `:gui` 出现在 projects、`:gui:classes` 编译成功、xdotool 检测到标题窗口、`VERIFY_OK`。全量 `./gradlew --no-daemon --no-build-cache cleanTest build` → `BUILD SUCCESSFUL in 44s`（`/tmp/gui10-build.log`）；合并后复跑 `/tmp/gui10-final.log` 三项 PASS、`verify_exit=0`、窗口 ID `192937991`、无遗留 `MainKt` 进程；`:gui:tasks --all` 无 Android 任务命中（`/tmp/gui10-tasks.log`）。
- 2026-11-26：无头虚拟显示路径**未实测**——本机无 `xvfb-run`/`Xvfb`；空窗口已在既有 `DISPLAY=:0` 上验证可启动，虚拟显示命令写入 `desktop/README.md`，留待工单 19 的验收脚本覆盖。不声称该环境路径已验证。
- 2026-11-26：未添加 `desktop/verify-gui.sh`（按 ADR-0011 归后续工单），本工单只在 README 记录有界命令，验证脚本留在 `/tmp`。集成分支合并 10、11 后复跑全量构建 → `BUILD SUCCESSFUL in 40s`（`/tmp/gui-merge-verify.log`）。
