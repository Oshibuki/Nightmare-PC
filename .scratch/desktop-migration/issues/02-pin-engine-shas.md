# 02: 固化引擎版本（local-dream / sdcpp / ggml 精确 SHA）

**What to build:** 一份引擎版本档案：happyyzy/stable-diffusion.cpp 与 ggml-org/ggml 的精确 commit SHA（优先取自 GitHub Actions dit-engine 历史构建输出的 SDCPP_SHA / GGML_SHA），外加 local-dream 参考版本（v3.0.0-alpha.4）。任何构建可复现、可追溯。

**Blocked by:** 01

**Status:** resolved

- [x] 档案文件存在且含三个精确版本号与来源说明
- [x] 历史 run 不可得时的回退值已记录并注明"非移动版同款构建"

## Comments

- 2026-11-25：`desktop/engine/ENGINES.md` 固化 local-dream `18be9aa6`、sdcpp `35a3d48b`、ggml `f8758628`，含取得方式、CI 复核命令与覆盖 ref 的回退说明；合并提交 `55131c9`。
