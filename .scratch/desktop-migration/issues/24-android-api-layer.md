# 24: Android API 层移植

**What to build:** 把移动版 `MiniHttp`/`A1111` 等可解耦的 JVM API 代码复制到桌面并替换 Android 依赖，服务层测试迁移到 JVM 源集；引擎 server 暴露由工单 17 提供。

**Blocked by:** 11, 13

**Status:** resolved

- [x] 可解耦的 API 文件及其测试在 `desktop/` 全绿
- [x] 仍被 `Graph`/`NodeType` 或 `ModelCatalog` 阻塞的部分在 `copied-files.md` 记录原因
- [x] 服务仅绑定本机回环

## Comments

- 2026-11-25：复制 `MiniHttp`、纯 JVM 的 `ApiSettings` 摘录、`NightmareApi.corsPreflight`、`A1111` 路径/宽高比助手、`ApiError`/`RunRequest.parse` 到 `desktop/core`；新增 `org.json:json:20250517`（JVM 制品）替换 Android 内置 `org.json`。13 个新增 JVM 测试覆盖真实回环 socket、SSE、401 预读拦截、token、CORS、路径与请求体解析。
- 2026-11-25：桌面服务显式绑定 `127.0.0.1` 且默认只接受 loopback peer；`MiniHttpTest.theServerIsNotReachableThroughANonLoopbackAddress` 从真实非回环网卡连接并断言被拒绝。`ApiCore`、`NightmareApi.route`、`A1111` 的模型相关方法、graph 覆盖逻辑及对应移动测试未迁移，原因逐条登记在 `desktop/docs/copied-files.md`。
- 2026-11-25：`timeout 900 ./gradlew --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx1536m cleanTest build` 成功（`BUILD SUCCESSFUL`，core + cli 全部测试通过；日志 `/tmp/ticket24-full-build.log`）。引擎 server 入口（17）与真实客户端认证（25）不在本单范围。
- 2026-11-25：实现提交 `0eea375`；合并集成分支 `3df7333` 为 `efadd70`（无冲突），合并后同一 `--no-build-cache cleanTest build` 再次 `BUILD SUCCESSFUL`（日志 `/tmp/ticket24-merged-build.log`）。
