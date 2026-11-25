# 13: 模型目录与 GGUF/safetensors 导入

**What to build:** 复制的模型目录与导入逻辑接入 CLI：`--model` 接受本地任意 `.safetensors` / GGUF 文件（CivitAI 微调即插即用），可扫描与列出本地模型库。用户无需转换即可使用自己的微调模型。

**Blocked by:** 05, 08

**Status:** ready-for-agent

- [ ] 导入的测试随复制代码在桌面全绿
- [ ] CLI 能列出本地模型并按路径加载任一受支持格式
