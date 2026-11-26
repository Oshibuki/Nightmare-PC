# 应用内真 provider 验证记录

Type: verification evidence
Date: 2026-11-26
Status: passed (application chain verified with a local LLM; engine output via fake CLI by design)

## 验证了什么

第一次在**应用内**——而不是 curl 探针或测试里的假 ChatModel——验证「对话 → 真 LLM 工具调用 → 工作流 → 出图」这条主链路。此前 `verify-gui.sh` 的 `chat-turn` 步全程用脚本化假 ChatModel；历史 `local-llm-chat-*.json` 只证明 curl 级端点支持工具调用，都不覆盖应用接线。

## 配置（全部本地，无外部密钥）

```
NIGHTMARE_LLM_PROVIDER=CUSTOM
NIGHTMARE_LLM_BASE_URL=http://127.0.0.1:13305/v1
NIGHTMARE_LLM_MODEL=Qwen3.5-4B-GGUF
```

- 服务：lemond (Lemonade) 监听 `0.0.0.0:13305`（文档 CUSTOM 端点），pid 364228；端口经 `ss -ltnp` 只读确认在监听
- 模型：Qwen3.5-4B-GGUF，本地服务加载；无需密钥（CUSTOM 免 key 规则，ADR-0012）
- 三个 env 此前全 unset；本次为一验证临时注入，未写入任何配置或仓库文件

## 命令

```bash
cd desktop && export JAVA_HOME=/home/chjlxxc/Android/jdk-17
export NIGHTMARE_LLM_PROVIDER=CUSTOM NIGHTMARE_LLM_BASE_URL=http://127.0.0.1:13305/v1 NIGHTMARE_LLM_MODEL=Qwen3.5-4B-GGUF
timeout 300s bash ./gradlew --no-daemon -q :gui:verifyRealProvider
```

证据日志：`/tmp/nm-desktop/logs/real-provider-run-1.log`（首次真跑，退出码 0）；SKIP 路径：`real-provider-skip.log`（env unset，零网络，退出 0）；编译：`real-provider-compile.log`。

## 三条成功判据（全部满足）

1. **真服务返回 tool_calls**：8 条工具调用全部 ok——`new_workflow` → 3×`add_node`（core.prompt / sd.sample / core.output）→ `set_parameter`（prompt=「清晨薄雾的城市，柔和光线」）→ 2×`connect` → `run`。证明复制的 `Agent` 能驱动真模型按系统提示/schema 填对参数。
2. **工作流被工具改动**：`chat.workflow.nodes` = 3 个（core.prompt, sd.sample, core.output），连线成立。
3. **产物图 + Picture 气泡**：假 CLI argv = `txt2img --prompt … --sampler euler --scheduler discrete --steps 20 … --out … --batch 1`；PNG 真实落盘、`ChatItem.Picture` 气泡出现；agent 以中文「已成功生成一张…」收尾。

对话气泡共 11 个（User + 8 Tool + Picture + Assistant），无 Error、无 Ask、无拒绝工具。

## 结论与影响

-**通过，无需改产品代码**：Qwen3.5-4B 完全看懂了现有系统提示与工具 schema（四节点类型、字符串参数、端口名、steps 上限），没有触发 Q8 的"最小修复"。上一 effort 补的完整 schema/别名/提示词价值在此兑现。
-**渲染刻意用假 CLI**：本验证覆盖的是 agent→工具→工作流链，不是引擎图像质量；真实权重冒烟由 `verify-gui.sh` 的 real-smoke 步独立覆盖。
-**可复现**：脚本 `desktop/gui/src/test/kotlin/.../RealProviderVerificationMain.kt` + gradle task `:gui:verifyRealProvider`；缺配置 SKIP、不并入 ADR-0011 的单一脚本。

## 后续：16 工具对齐后的复验（2026-11-26）

工具面按 ADR-0014 新增 5 个映射工具（list_models / get_canvas / new_flow / save_flow / list_loras）后，复用 `:gui:verifyRealProvider` 复验。验证脚本加了 `NIGHTMARE_VERIFY_PROMPT` 话术注入，判据改为「本次调用 run 才要求 Picture」，避免只读/保存话术被误判失败。

-**新工具话术**（`先列出本地可用模型，再看看当前画布，然后把工作流保存到 /tmp/...`）：真 LLM 一次调用 `list_models` → `get_canvas` → `save_flow`，三个全 ok，保存文件真实落盘（合法 format=1 JSON）。证据：`/tmp/nm-desktop/logs/tool-align-real-provider-rerun.log`。
-**默认出图话术**：8 步原链路照常、Picture 气泡出现、无回归。证据：`/tmp/nm-desktop/logs/tool-align-real-provider-default-rerun.log`。

结论：真模型看懂了新增工具的 schema，一次选对并填对参数，无需再改提示词/schema。这是"按可行性分组对齐"决策（ADR-0014）在真 provider 下的兑现。

## 诚实边界

- 只验证了一句话、一个本地模型（Qwen3.5-4B）。ollama :11434、llama-server :8001（Bonsai-8B）未验证——它们是 Q2 的对照项，非本轮目标。
- 工具调用有随机性：单次成功不等于 100% 稳定；未做多次重复统计。真实托管 provider（OpenRouter 等）需密钥，未验证。
- GUI 窗口内的真实鼠标交互未测（无 UI 测试框架，既有裁决）。本次是 JVM 入口同步驱动 `ChatSession` 的端到端，非点击窗口。
