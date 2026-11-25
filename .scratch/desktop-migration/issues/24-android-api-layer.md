# 24: Android API 层移植

**What to build:** 把移动版 `MiniHttp`/`A1111` 等可解耦的 JVM API 代码复制到桌面并替换 Android 依赖，服务层测试迁移到 JVM 源集；引擎 server 暴露由工单 17 提供。

**Blocked by:** 11, 13

**Status:** ready-for-agent

- [ ] 可解耦的 API 文件及其测试在 `desktop/` 全绿
- [ ] 仍被 `Graph`/`NodeType` 或 `ModelCatalog` 阻塞的部分在 `copied-files.md` 记录原因
- [ ] 服务仅绑定本机回环
