# 06: provider 设置入口与密钥存储边界

Type: wayfinder:grilling
Status: resolved
Blocked by: 01, 02

## Question

对话模式内设置入口的具体形态：provider 选择、自填 base URL、密钥输入放在哪、何时校验？密钥在内存还是本地配置、与环境变量的优先级如何？错误气泡的文案与是否允许重试？如何保证密钥不进工作流 JSON、日志或截图（截图包含 GUI 可见文本，需明确掩码口径）？

provider 契约的事实来自工单 01，本工单不重新调查。

## Answer

两轮 grilling 裁决完毕（人逐项确认），结论如下。已立 ADR-0012（provider 配置与密钥只经注入、不落盘）。

**1. 配置项与存储/优先级**
- 配置项四件：provider（5 家 + Custom 自填 base URL）、base URL、密钥、**模型名**（见第 5 条）。
- **密钥与配置只经注入，不落盘**：环境变量作持久来源，命名 `NIGHTMARE_LLM_PROVIDER` / `NIGHTMARE_LLM_BASE_URL` / `NIGHTMARE_LLM_KEY` / `NIGHTMARE_LLM_MODEL`（沿用桌面既有 `NIGHTMARE_*` 纪律）。
- 设置弹窗（工单 02 已定 B 的齿轮入口）的输入**只存本次进程内存、覆盖 env**；重启后回退 env，不写任何配置文件。桌面无用户配置文件先例，`ApiSettings.kt` 也已明确把 Android `SharedPreferences` 访问器故意不复制——不为此新开配置文件接缝。
- 校验/读取顺序：GUI 内存值 > 环境变量 > 内置默认（provider 默认 OPENROUTER，base URL 由 provider 常量给出，Custom 为空需注入）。

**2. 校验时机**
- 点「保存」时用**独立短超时探针**（连接 3s / 读取 8s）在后台线程做一次 GET /models 连通性检查；失败在弹窗内提示但**仍允许保存**，真实错误留到首次发送。不调用 `LlmClient.models()`（其 connect 20s/read 180s 硬编码会卡 3 分钟），不改 `LlmClient`。

**3. 错误路径**（沿用 charting 轮⑧）
- 两类文案：「未配置 provider / 密钥」（引导打开设置）与「无法连接 provider（附 HTTP 状态或错误摘要）」。错误气泡带「打开设置」按钮，**不自动重试**。不崩溃、不改变工作流。

**4. 掩码口径**
- 设置弹窗密钥框为 password 类型；关闭后界面任何位置**只显示「已配置 / 未配置」**，不回显密钥、不显示尾四位。截图与日志零泄露（截图会捕获 GUI 可见文本，故必须掩码）。密钥只进 `Authorization: Bearer` 头，仅 key 非空白时发送（来自工单 01 实读）。

**5. 模型入口**（本轮新裁决的缺口）
- 保存探针拉 `GET /models` 后，用 `fitsChat` 七条否决自动挑一个可用模型作默认；设置入口另给一个**可选「模型」输入框**，填了就覆盖自动结果。既开箱即用又能指定特定模型。

**术语纪律**：GLOSSARY 现有「对话回合」「结构化追问」不含配置项用语；provider/base URL/密钥/模型均为通用技术词，不新增领域术语条目。

**事实 vs 决策**：provider 表、请求头纪律、超时硬编码、`fitsChat` 规则、桌面无配置文件先例为实读事实（工单 01 / 源码）；上述 1–5 的取舍为人的决策。
