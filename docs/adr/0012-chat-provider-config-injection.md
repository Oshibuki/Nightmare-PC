---
status: accepted
date: 2026-11-26
---

# 0012 - 桌面对话模式的 provider 配置与密钥只经注入、不落盘

移动版对话模式的 provider / base URL / 密钥由 `AgentConfig.kt` 经 Android `Context` 与 Keystore（`KeyGenParameterSpec`）管理，桌面无法复用。ADR-0008 已约定密钥只经环境变量或本地配置注入、只放 `Authorization` 头。本 ADR 进一步锁定桌面 GUI 对话模式的配置来源、校验与掩码纪律。

**配置四件**：provider（5 家 + 自填 base URL 的 Custom）、base URL、密钥、模型名。

**来源与优先级**：持久来源只认环境变量 `NIGHTMARE_LLM_PROVIDER` / `NIGHTMARE_LLM_BASE_URL` / `NIGHTMARE_LLM_KEY` / `NIGHTMARE_LLM_MODEL`（沿用桌面既有 `NIGHTMARE_*` 纪律，如 `NIGHTMARE_MODELS` / `NIGHTMARE_SD_BIN`）。设置弹窗的输入只存本次进程内存、覆盖 env；重启即回退 env。**不写任何配置文件、不引入新持久化接缝。**

**校验**：保存时用独立短超时探针（连接 3s / 读取 8s）在后台线程做 GET /models 连通性检查；失败仍允许保存，真实错误留到首次发送。不调用 `LlmClient.models()`（connect 20s / read 180s 硬编码会卡 3 分钟），不改 `LlmClient`。模型由 `fitsChat` 自动挑一个可用者作默认，设置入口的可选「模型」输入框可覆盖。

**错误与掩码**：无配置 / 连不上分两类文案，错误气泡带「打开设置」按钮、不自动重试、不改变工作流。密钥框为 password 类型；关闭后界面只显示「已配置 / 未配置」，不回显、不显示尾四位；密钥只进 `Authorization: Bearer` 头（仅非空白时发送），绝不进工作流 JSON、日志或截图（截图捕获 GUI 可见文本，故必须掩码）。

**Considered Options**：
1. 环境变量持久 + GUI 输入仅内存（采纳）——复用既有 env 纪律，密钥不落盘，零新接缝；代价是重启后 GUI 输入需重填（env 仍在）。
2. 引入本地配置文件持久化 GUI 输入——重启不必重填，但桌面无配置文件先例，需新定义格式、位置与文件权限，且明文密钥落盘有泄露风险。
3. 只用内存、不兼容环境变量——与 ADR-0008「环境变量或本地配置注入」冲突，无头/CI 场景无法注入。

**Consequences**：对话模式的配置可测性提升——假 provider 用 env 注入、假 ChatModel 走同一路径，无需网络或密钥即可验收；用户须自行管理环境变量以持久化配置；任何复制来的 provider 代码不得把密钥写进日志或工作流 JSON。
