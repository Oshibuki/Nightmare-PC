# 08: 落地 agent 核心与假对象底座

**What to build:** 作为维护者，我能从干净检出直接用既有测试命令验证——从移动版复制的纯 JVM agent 核心与假 ChatModel 底座已在桌面构建里工作：假 ChatModel 经真实 agent 循环驱动五条工具命令搭出一条可运行工作流并跑到假 CLI 出图。这张工单是把已验证的证据从丢弃分支转成主分支代码，是后续所有切片的地基，不新增功能行为。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 纯 JVM 的 agent 核心两份源文件按复制式复用纪律落地桌面 JVM 模块；首行溯源注释与复制登记齐全；除测试侧既有 JSON 依赖外，桌面构建脚本无需为复制件新增依赖
- [ ] 假 ChatModel、假工具箱与约六十行粘合层落在桌面 JVM 测试源集，无 Android / Robolectric 依赖；五条工具名映射到既有五条工具命令，工具结果形状与移动版对齐
- [ ] 既有测试先例（假 CLI 端到端）在桌面全量通过：假 ChatModel 用五条工具命令搭出 `prompt→sampler→output` 并跑到成功产物；断言按键名比较字段，不依赖 JSON 键序
- [ ] 全量构建与测试通过，移动版零改动
