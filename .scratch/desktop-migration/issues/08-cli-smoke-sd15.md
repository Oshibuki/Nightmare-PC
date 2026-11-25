# 08: CLI SD1.5 出图冒烟（Phase 0 端到端验收）

**What to build:** 最小 CLI：`txt2img --model <path> --prompt ... --out <path>`，通过子进程或链接方式调用工单 07 的引擎库，SD1.5 走 CPU 路径成功生成可打开的 PNG；失败时非零退出码 + 明确错误输出。这是"agent 改引擎→编译→冒烟"闭环的端到端证明，冒烟命令写进工单 06 的文档。

**Blocked by:** 07

**Status:** ready-for-agent

- [ ] Linux 一条命令从零到 PNG 成功（512x512、20 步内）
- [ ] 失败路径有明确错误输出
- [ ] SD1.5 权重获取方式与放置目录约定写入文档（权重不入库）
