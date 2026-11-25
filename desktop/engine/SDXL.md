# SDXL on Nightmare Desktop

This page is the **application record** for the SDXL-related patches in the
mobile patch library, together with the CLI surface SDXL adds. It follows the
conventions of [`PATCHES.md`](PATCHES.md): `Verdict` and `Baseline` below are
copied from that inventory, not re-measured, and `Decision` is the desktop
build's answer per file — `applied` or `skipped`, always with a reason. A row
does not exist just to say "we looked at the family": it says what would happen
if that patch were offered to a desktop engine build.

Guard: `desktop/core/src/test/kotlin/com/abrah/nightmare/SdxlPatchRecordTest.kt`
fails if a patch is missing from the table, duplicated, or lacks a decision or
a reason. It follows the same seam as `PatchInventoryTest` (one row per file,
one verdict per row), so adding an SDXL patch without registering it here is a
red test.

## Why every row is `skipped`

SDXL is **native in the pinned engine** (`35a3d48b`): `sd_version_is_sdxl()`
and the SDXL UNet/VAE paths are in the vendored `stable-diffusion.cpp` source,
and `sd-cli -m sd_xl_base_1.0.safetensors -W 1024 -H 1024` loads the cached
checkpoint with no patch applied. `PATCHES.md` already records the same fact
for the other Phase 1 families: the patch set is not a prerequisite. The
engine build therefore uses the clean baseline (`--patch` off), which is what
[`ENGINES.md`](ENGINES.md) pins.

The mobile patches below describe the *phone's* SDXL paths — QNN/HTP kernels,
the SDXL Swap template UNet, MNN and Adreno OpenCL — or ask the `local-dream`
wrapper for edits the desktop build does not compile. A skip is a decision
about a target, not a claim that the patch is stale: some of these files still
`--check` cleanly against the pinned tree, exactly as `PATCHES.md` warns.

## Application record

`Verdict` and `Baseline` come from the 2026-11-25 inventory in `PATCHES.md`.
`Decision` is the desktop answer. The reason names the missing target or the
reason a portable patch is not adopted.

| source path | verdict | baseline | decision | reason |
|---|---|---|---|---|
| `005-sdxl-npuforge-231-masked.patch` | `excluded-hexagon` | `n-a` | `skipped` | NPUforge SDXL Swap / QNN session; desktop sets `GGML_HEXAGON=OFF` and has no QNN pipeline (ADR-0002). |
| `018-sdxl-swap-template-io.patch` | `excluded-hexagon` | `n-a` | `skipped` | Wires SDXL Swap template IO through `QnnModel.hpp`, absent from a desktop build. |
| `019-sdxl-n-chunk-context.patch` | `portable` | `does-not-apply` | `skipped` | Wrapper-only SDXL prompt chunking; host file `PipelineSdxl.hpp` is absent and the pinned engine already handles long SDXL context. |
| `020-sdxl-swap-inpaint-inputs.patch` | `excluded-hexagon` | `n-a` | `skipped` | SDXL Swap inpaint on `PipelineSd15Npu.hpp`; NPU path, no desktop counterpart. |
| `023-sdxl-swap-controlnet-PipelineSdxl.patch` | `portable` | `does-not-apply` | `skipped` | SDXL Swap ControlNet inputs for the QNN pipeline; context drift against the private wrapper tree, not the vendored engine. |
| `023-sdxl-swap-controlnet-QnnModel.patch` | `excluded-hexagon` | `n-a` | `skipped` | QNN session plumbing; excluded by `GGML_HEXAGON=OFF`. |
| `023-sdxl-swap-controlnet-RequestParser.patch` | `portable` | `does-not-apply` | `skipped` | Wrapper request parser for Swap ControlNet; desktop's request surface is the CLI, which has no such input. |
| `024-sdxl-swap-ip-adapter-PipelineSdxl.patch` | `portable` | `does-not-apply` | `skipped` | SDXL Swap IP-Adapter inputs for the QNN pipeline; context drift, and no desktop IP-Adapter path. |
| `027-lowram-unet-evicts-vae-decoder.patch` | `portable` | `does-not-apply` | `skipped` | Low-RAM UNet/VAE swap inside the wrapper's `PipelineSdxl.hpp`; desktop VAE memory is handled by `--vae-tiling` instead. |
| `032-lowram-new-prompt-releases-PipelineSdxl.hpp.patch` | `portable` | `applies` | `skipped` | The one SDXL row that still fits, but it edits `app/src/main/cpp/src/PipelineSdxl.hpp` in the mobile wrapper; routing it at `desktop/engine` would fail by design, and clean-baseline SDXL needs it. |
| `036-sdxl-mnn-main.cpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | MNN backend routing on the phone; MNN is never linked into a desktop build. |
| `036-sdxl-mnn-PipelineSdxl.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | MNN SDXL pipeline; desktop runs CPU/Vulkan/Metal only (ADR-0002). |
| `036-sdxl-mnn-PipelineSdxlMnn.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | Creates the phone-only `PipelineSdxlMnn.hpp`; no desktop consumer. |
| `041-request-validation-PipelineSdxl.hpp.patch` | `portable` | `does-not-apply` | `skipped` | Wrapper-side request validation for SDXL dimensions; the CLI validates sizes itself (multiple of 8) before the engine starts. |
| `041-request-validation-PipelineSdxlMnn.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | Validation on the phone-only MNN SDXL pipeline. |
| `048-sdxl-mnn-prompt-chunks-Pipeline.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | MNN prompt chunking on the phone; not linked on desktop. |
| `048-sdxl-mnn-prompt-chunks-PipelineSdxl.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | MNN prompt chunking in the SDXL wrapper; not linked on desktop. |
| `048-sdxl-mnn-prompt-chunks-PipelineSdxlMnn.hpp.patch` | `excluded-mobile-gpu` | `n-a` | `skipped` | MNN prompt chunking in the phone-only pipeline; not linked on desktop. |

Totals: 18 rows — 4 `excluded-hexagon`, 7 `excluded-mobile-gpu`,
7 `portable`; 1 `applies`, 6 `does-not-apply`, 11 `n-a`. All 18 skipped, zero
applied. No patch file was edited, and the clean engine baseline is unchanged.

## CLI surface

`nightmare-desktop txt2img --model <sdxl.safetensors> -W 1024 -H 1024` needs
no `--type` or model-mode flag: the engine reads SDXL version from the
checkpoint. The optional knobs for large SDXL runs are:

| CLI flag | Engine flag | Purpose |
|---|---|---|
| `--vae <path>` | `--vae <path>` | Standalone VAE weights (e.g. an SDXL VAE). Must be an existing, readable safetensors/GGUF file; checked before the checkpoint loads. |
| `--vae-tiling` | `--vae-tiling` | Decode the VAE in tiles to cut peak memory at 1024² and above. Bare flag = on; `--vae-tiling=false` = off. |
| `--vae-tile-size <WxH>` | `--vae-tile-size <WxH>` | Tile shape, e.g. `64x96`; the engine default is `32x32`. |
| `--vae-tile-overlap <float>` | `--vae-tile-overlap <float>` | Overlap as a fraction in (0,1); the engine default is `0.5`. |

`SdxlCliTest` covers the forwarding, the default-off behaviour and every
illegal value at the CLI end-to-end seam (fake engine + real PNG + recorded
argv). SDXL weights go in `~/.cache/nightmare-desktop/models/sdxl/`; see
[`../docs/models.md`](../docs/models.md).

## Real 1024×1024 run

Run on this machine (16-core CPU, cached engine `35a3d48`, no patch applied):

```bash
./cli/build/install/nightmare-desktop/bin/nightmare-desktop txt2img \
  --model ~/.cache/nightmare-desktop/models/sdxl/sd_xl_base_1.0.safetensors \
  --prompt 'a red fox in an autumn forest, photograph' \
  --out /tmp/ticket14-sdxl-1024.png \
  --width 1024 --height 1024 --steps 2 --seed 42 \
  --engine ~/.cache/nightmare-desktop/engine/sd-cli --vae-tiling
```

Exit 0. The engine reported `generate_image completed in 298.89s` (127.67 s
sampling, 168.56 s tiled VAE decode) and wrote a 460,633-byte PNG. Verified
independently with PIL: `size (1024, 1024)`, mode `RGB`, `parameters` chunk
recording seed 42, size 1024x1024 and `Model: sd_xl_base_1.0.safetensors`.
Log: `/tmp/ticket14-real-e2e.log`; image: `/tmp/ticket14-sdxl-1024.png`.
Only two steps were sampled: this proves the 1024² SDXL load/sample/decode path
and the VAE-tiling flag, not image quality.
