# 22: deferred 补丁定性

**What to build:** 对 `fused`/`graph-cut` 待定性补丁逐个给出桌面适用性结论，并更新 `desktop/engine/PATCHES.md` 与覆盖测试。

**Blocked by:** 09

**Status:** resolved

**Optional:** yes

- [x] 每个 deferred 补丁在清单中有唯一结论（适用/排除/条件适用及原因）
- [x] `PatchInventoryTest` 覆盖新增结论且全绿

## Comments

- 2026-11-25：原工单 15 拆分。本机性能有限，DiT 系模型可放弃时本工单也可关闭为 `wontfix`。
- 2026-11-25：已关闭为 `resolved` 而非 `wontfix`：结论可由 pinned 引擎与补丁正文判定，无需跑 DiT 推理。提交 `90eeaf4` 在 `desktop/engine/PATCHES.md` 新增 `Deferred patch verdicts` 表，18 个 deferred 补丁各有唯一 `applicable`/`excluded`/`conditional` 结论和独立原因（8 个条件适用、10 个排除、0 个无条件适用）。证据：桌面 `build.sh` 固定 `GGML_HEXAGON=OFF`；CPU 后端不支持 `GGML_OP_QKNORM_ROPE`，Vulkan 无对应 kernel；CPU 支持 F32 `MODULATE`/`GATED_RESIDUAL`；Phase 1 模型清单不含 Anima/Ideogram 4/MOSS/Iris/FLUX.3/Wan/MiniMax H3。未应用任何补丁，未改已有补丁与 `app/`。
- 验证：`export JAVA_HOME=/home/chjlxxc/Android/jdk-17 && cd desktop && timeout 600 ./gradlew --no-daemon --no-build-cache :core:test --tests 'com.abrah.nightmare.PatchInventoryTest'` → 6/6 通过（`/tmp/ticket22-green3.log`）；同一命令在新增文档前 3 个新测试失败（`/tmp/ticket22-red.log`）；`timeout 900 ./gradlew --no-daemon --no-build-cache build` → `BUILD SUCCESSFUL`（`/tmp/ticket22-full-build.log`）。
