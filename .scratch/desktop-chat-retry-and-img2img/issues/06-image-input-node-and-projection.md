# 06: 图片输入节点与运行器 img2img 投影

**What to build:** 作为维护者，桌面工作流能在节点注册表里表达一个最小的图片输入节点（携带 path/strength、连到采样节点），运行器按工作流里是否存在该节点把工作流投影成文生图或图生图 CLI 子命令——图生图带上 `--init-img`/`--strength`，文生图路径与既有行为完全一致。这是一条可独立演示的纵向切片：搭一条含图片节点的工作流 → 经运行器投影 → 假 CLI 出图。本工单不接对话工具面（那是 07），但把地基与命令面打通。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] 节点注册表新增一个最小图片输入节点，携带 path/strength 参数、可连到采样节点；受桌面自持纪律约束（ADR-0007），既有三种节点行为不变
- [x] 既有工作流 JSON 格式的字符串参数映射可直接承载图片路径，不改格式版本；保存/加载往返不丢图片节点及其参数
- [x] 运行器按工作流里是否存在图片输入节点决定投影：含则投影图生图子命令并带 `--init-img`/`--strength`（strength 默认 0.75、范围 0..1），不含则仍投影文生图；单运行、单一 CLI 子进程、取消/关窗清进程语义不变（ADR-0009）
- [x] 假 CLI 端到端断言：含图片节点的工作流投影出正确参数并产出图、不含的工作流行为与既有完全一致；断言按参数名比较、不做字符串全等
- [x] ADR-0013 的取舍在代码与文档中一致，不引入全局运行参数或直调引擎入口的独立路径

## Comments

- 端口语义：图片节点输出 `image:IMAGE`；采样节点保留原 `prompt:PROMPT`，另加可选 `initImage:IMAGE` 输入。选它而不是把图片伪装成 `PROMPT`，是因为 `Workflow.connect` 要求端口 type 相等，而原 prompt 线必须继续承载文字提示；新增独立输入不改既有三种节点的连线语义。运行器只按节点是否存在切换子命令。ADR-0013 记录了同一端口约定。
- strength 默认由节点 widget 的 `"0.75"` 与投影中的兜底共同保证；GUI 不钳制越界值，`1.4` 原样传给 CLI，范围校验由 CLI 的 usage error 负责（`Img2ImgTest > aStrengthOutsideZeroToOneIsAUsageErrorAndTheEngineNeverRuns` 在全量 build 中 PASSED）。img2img 分支跳过 `--batch`，其余参数与 txt2img 共用。
- TDD 红-绿：先写图节点/连线测试（编译红，`retry-img2img-06-red-graph.log`），再写投影测试（2 failed，`retry-img2img-06-red-runner.log`），逐片实现后转绿。持久化测试是既有 `format=1` 字符串映射的表征测试，无需改持久化代码即可通过（`retry-img2img-06-persistence.log`）。
- 有界验证（均在 `desktop/`，`JAVA_HOME=/home/chjlxxc/Android/jdk-17`，日志在 `/tmp/nm-desktop/logs/`）：定向 `timeout 180s bash ./gradlew --no-daemon :core:test :gui:test --tests '...WorkflowRunnerTest' --tests '...MinimalGraphTest'` exit 0（`retry-img2img-06-green-runner.log`）；全量 `timeout 600s bash ./gradlew --no-daemon :core:test :gui:test --rerun-tasks` exit 0（`retry-img2img-06-full-tests.log`）；`timeout 600s bash ./gradlew --no-daemon build` exit 0（`retry-img2img-06-build.log`）。
- 单运行、取消、关窗清进程的既有断言在 `WorkflowRunnerTest` 全量运行中保持 PASSED；没有新增运行路径，也没有直调 `Img2Img.run` 或引擎入口。
- 未做（明确属其他工单/后续 effort）：对话工具面（07）、验收脚本 img2img 步（08）、默认/高级模式画布展示、真实模型冒烟。
- 集成分支检查：第一次 `git merge desktop-retry-img2img` 时集成分支仍是基线 `6e4ef34`（already up to date，`retry-img2img-06-post-merge.log` 定向复跑 exit 0）；集成分支随后推进到 `520243f`（合入工单 05），已再次 `git merge desktop-retry-img2img`，无冲突，合并提交 `b906431`。合并后 `timeout 600s :core:test :gui:test --rerun-tasks` exit 0（`retry-img2img-06-post-merge-full-tests.log`），`timeout 600s build` exit 0（`retry-img2img-06-post-merge-build.log`）。
