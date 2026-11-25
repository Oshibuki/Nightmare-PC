# 25: 真实 A1111 客户端兼容性认证

**What to build:** 用未修改的现有 A1111 客户端脚本连到工单 17/24 的本地服务出图；若本机不具备该客户端，以契约测试覆盖协议子集并明确记录未覆盖项。

**Blocked by:** 24

**Status:** resolved

- [x] 兼容客户端不改代码即可出图；或记录环境限制与契约测试覆盖范围
- [x] 认证过程与结论写入 `desktop/docs/` 下的说明

## Comments

- 2026-11-25：本机存在未修改的真实 A1111 客户端 SillyTavern 1.19.1（`/home/chjlxxc/Applications/SillyTavern`，`git status --short` 无 tracked 改动）。以 `node server.js --port 9351 --disableCsrf` 启动，经其未修改的 `src/endpoints/stable-diffusion.js:297-350` 调用 `POST /api/sd/generate`（body 带 `url=http://127.0.0.1:1234` 和标准 A1111 字段），连到 `nightmare-desktop serve --model sd15.safetensors --port 1234 --engine <ticket-07>/desktop/engine/build/bin/sd-server`，HTTP 200、2.35 s，引擎 `generate_image completed in 2.34s`。响应含 `images`/`parameters`/`info`；`images[0]` 解码为 64×64 RGB PNG（9,990 字节，parameters chunk 含 seed 1234 与模型名）。客户端的 `/api/sd/samplers`、`/api/sd/models` 均 200；生成路径的 GET `/sdapi/v1/options` 200。日志 `/tmp/ticket25-sd-server.log`、`/tmp/ticket25-sillytavern.log`，产物 `/tmp/ticket25-client-response.json`、`/tmp/ticket25-client-image.png`。
- 2026-11-25：`desktop/docs/a1111-client-compat.md` 记录认证过程与未覆盖项：POST options、progress、interrupt、sd-vae、sd-modules 均 404；Basic auth 被忽略（错误凭据仍 200）；客户端的 `restore_faces`、`override_settings.*`、`hr_second_pass_steps`、ADetailer 等字段被引擎忽略；`/sd-models` 只返回当前单一模型。未跑浏览器 UI 与长时生成。README 已链接该页。
- 2026-11-25：`A1111ClientContractTest` 先因假服务缺少真实引擎的 `info` 字段变红（`/tmp/ticket25-contract-first.log`），补齐后转绿（`/tmp/ticket25-contract-green.log`）；评审后加强为解析 `info` JSON 字符串、断言 PNG parameters chunk 与紧凑 JSON，并修正与真实客户端认证矛盾的注释。提交 `5e700f2`、`587e3b7`；集成合并 `52a61f0`。合并后 `timeout 900 ./gradlew --no-daemon --no-build-cache build` → `BUILD SUCCESSFUL`，180 项测试通过（`/tmp/ticket25-merged-build.log`）。
