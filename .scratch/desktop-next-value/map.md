# Map: 下一批收益最大的工作（B 验证 / A 用户功能 / C 技术债）

Type: wayfinder:map
Status: charted

<!-- 事后追认的精简地图：本轮无预写地图/规格，决策直接来自 8 轮 grilling，已落地实现并合并。本图只记决策可追溯性，不重复实现细节。 -->

## Destination

按「先确认交付物真能用，再补用户缺口，最后还债」的优先级，交付三块独立工作：B 配置诊断工具、A GUI 保存/载入按钮 + 图片节点画布展示、C LlmClient 短超时。全部已合并到 `main`（合并提交 `7554ba1`）。

## Notes

- 无规格、无实现工单：本轮决策充分收敛后直接派隔离实现子代理（TDD + 有界验证），未走地图→规格→工单流水线。本图是事后追认的决策索引。
- 领域术语见 `GLOSSARY.md`；约束见 ADR-0001（复制式复用）、0009（单运行）、0011（单一验收入口）。

## Decisions so far

- [优先级 B→A→C](docs/adr/0009-gui-owns-cli-process-handle.md)：先补验证闭环（真 provider 跑不起来风险最高），再补用户功能，最后还技术债；理由＝主链路建立在「真 LLM 按 schema 调工具」的未验证假设上。
- [真 provider 先做配置诊断而非验证 effort](desktop/verify-gui.sh)：四个 `NIGHTMARE_LLM_*` env 全 unset、无密钥，诊断工具复用 `ProviderProbe` 的 3s/8s 探针，缺配置 SKIP 退出 0；`verify-gui.sh` 的真 provider 冒烟本就标为手工可选（:149-152）。
- [保存/载入按钮只进默认+高级模式](desktop/gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/DefaultModeScreen.kt)：对话模式共享同一工作流但不加文件条（面包屑空间有限、对话用户不手动管文件）；文本路径输入而非 JFileChooser（为 JVM 可测性）。
- [LlmClient.models() 超时改 3s/8s](docs/adr/0012-chat-provider-config-injection.md)：`complete()` 推理长超时不变；只改复制的桌面副本（ADR-0001），设置弹窗探针继续 3s/8s。
- [过时注释随 C 修正](desktop/gui/src/main/kotlin/com/abrah/nightmare/desktop/gui/ProviderProbe.kt)：C 改 models() 超时后，探针注释里「20s/180s」对比失效，一并更新。

## Not yet specified

- 真 provider 联网验证当时未做（env 全 unset）；后来单独成 effort，见 `.scratch/real-provider-verification/`。

## Out of scope

- 新建总进度表、改真 provider 持久化配置（ADR-0012 不动）、其他技术债（77 处 context drift 等）。
