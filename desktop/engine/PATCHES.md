# Desktop patch set — per-file inventory

All **194** `.patch` files under `backend-patches/` have exactly one row in the
inventory below, each with the repository it lands in, its desktop verdict, and
whether it `git apply --check`s against the pinned engine tree.
[`backend-patches/README.md`](../../backend-patches/README.md) is the rulebook —
suffix routing, the four classes, the silent-no-op assertion. This file is the
per-file application of those rules, which the README left to this ticket.

Produced by ticket 09 (`.scratch/desktop-migration/issues/09-desktop-patch-set-inventory.md`),
measured 2026-11-25 against `local-dream v3.0.0-alpha.4` (`18be9aa6`),
`stable-diffusion.cpp 35a3d48b` and `ggml f8758628` — the pins in
[`ENGINES.md`](ENGINES.md). No patch was applied: this file is an inventory, not
a build recipe.

## Contents

1. [Verdicts and baseline availability](#verdicts-and-baseline-availability)
2. [How each row was produced](#how-each-row-was-produced)
3. [Counts](#counts)
4. [The inventory](#the-inventory)
5. [Application order](#application-order)
6. [The patch set is not a prerequisite for Phase 1 models](#the-patch-set-is-not-a-prerequisite-for-phase-1-models)
7. [Coverage guard](#coverage-guard)
8. [Borderline rows and risks](#borderline-rows-and-risks)
9. [Deferred patch verdicts](#deferred-patch-verdicts)

## Verdicts and baseline availability

`Verdict` is one of four, and every file gets exactly one:

| verdict | meaning |
|---|---|
| `portable` | platform-independent engine or wrapper work — model loading, `--type` dispatch, pipeline and sampler parameters, VAE tiling, request validation, the HTTP surface, progress output, LoRA. A desktop build wants these. |
| `excluded-hexagon` | Hexagon / QNN only: NPU kernels, HTP graphs, `src/ggml-hexagon/`, QNN session plumbing. No desktop counterpart, and `GGML_HEXAGON=OFF` (ADR-0002). |
| `excluded-mobile-gpu` | Adreno OpenCL / MNN only: the GPU paths a phone runs. A desktop Vulkan/Metal build does not run these kernels and MNN is never linked. |
| `deferred` | `fused` / `graph-cut` / `spike` experiments. Ticket 09's classification only; [ticket 22](#deferred-patch-verdicts) gives each of these rows its own desktop conclusion and reason. |

`Baseline` is the answer to "does it apply to the pinned tree as it stands":

| baseline | meaning |
|---|---|
| `applies` | `git apply --check` exits 0 against the pinned tree, on its own, from a clean checkout. |
| `does-not-apply` | `git apply --check` exits non-zero. The remark says which of three reasons: the change is **already upstream**, the **target file is absent**, or plain **context drift**. |
| `n-a` | Not measured: the patch is excluded on hardware grounds, so applicability to a desktop tree says nothing. See the note under [Counts](#counts) — half of them would in fact apply. |

⚠ `applies` means "the text still fits", nothing more. It does not mean the
patch was built, linked, or run — no patch in this set has been. And it does not
mean it should be adopted: several `applies` rows are excluded on hardware
grounds, and every root-level row that applies still sits behind the rebase
described in [Application order](#application-order).

## How each row was produced

No row was typed by hand. A throwaway script walked
`backend-patches/**/*.patch`, applied the three rules below, ran the
`git apply --check` per patch and emitted the rows. The script is **not
committed** (ticket 09 allows it to live in `/tmp`), so what is reproducible
is the method — documented here in enough detail to repeat — rather than a
command. Adding a patch therefore means re-doing these three steps, not
re-running a tool; the coverage test under [Coverage guard](#coverage-guard) goes
red until the rows are back in step.

**Target repository** — by suffix, copied verbatim from `backend-patches/apply.sh`
and the CI routing it mirrors, so the two cannot disagree:

| suffix | repository |
|---|---|
| `*.ggml.patch` | `ggml` (the submodule of a submodule) |
| `*.sdcpp.patch` | `sdcpp` (`stable-diffusion.cpp`) |
| anything else | `local-dream wrapper` (`--directory=app/src/main/cpp`) |

**Verdict** — the README's rules over *file name and touched paths*, applied
first-match-wins in the order below, so a name that matches two rules lands in
the earlier one. The touched paths come from the diff header (`+++` side,
`/dev/null` skipped), stripped of `b/`, `backend-src/` and `app/src/main/cpp/`:

| rule | verdict | why this rule |
|---|---|---|
| name or touched path matches `hexagon`, `htp`, `qnn`, `npu`, `hmx`, `hvx`, `v69`, `v73`, `v85`; or a path is under `src/ggml-hexagon/` or names `*Npu*` / `QnnModel*` | `excluded-hexagon` | Those are the Qualcomm NPU vocabulary and file names. The path half of the rule is load-bearing: three root patches (`012`, `018`, `026`) never say "hexagon" but only touch `QnnModel.hpp` / `PipelineSd15Npu.hpp`, and a desktop build has no QNN pipeline to edit. |
| name or touched path matches `opencl`, `adreno`, `mnn`; or a path names `PipelineSdxlMnn*` | `excluded-mobile-gpu` | A separate class from Hexagon on purpose: these are the phone's *GPU* paths, which exist on desktop in name only. |
| name matches `fused`, `graph-cut`, `spike` | `deferred` | The decision these patches encode is a performance one, and it has to be re-made on desktop hardware. |
| otherwise | `portable` | No hunk touches a platform-specific symbol. |

The rules are a file-name heuristic, exactly as the README says. They are
reproduced here because the counts are what they select *today*; a patch that is
misfiled is a row to fix, not a rule to bend. Borderline rows are listed at the
end.

**Baseline** — a real `git apply --check` per patch, never a guess:

```sh
# root + dit/*.patch  -> the wrapper, same --directory CI uses
LC_ALL=C git -C /tmp/t03-clone apply --check \
  --directory=app/src/main/cpp backend-patches/002-pipeline-op-surface.patch

# dit/*.sdcpp.patch   -> the sdcpp submodule itself, never from its parent
LC_ALL=C git -C /tmp/t03-clone/app/src/main/cpp/3rdparty/stable-diffusion.cpp \
  apply --check backend-patches/dit/004-unet-stride2-conv.sdcpp.patch

# dit/*.ggml.patch    -> the ggml submodule
LC_ALL=C git -C /tmp/t03-clone/app/src/main/cpp/3rdparty/stable-diffusion.cpp/ggml \
  apply --check backend-patches/dit/012-hexagon-conv2d-batch.ggml.patch
```

`--check` writes nothing, and `git status --porcelain` in all three
repositories was empty afterwards, so the checkout used for the inventory is
byte-identical to the one the pins describe. Each patch is checked on its own
from that clean tree, which is why "does-not-apply" is a statement about the
patch, not about the order it was tried in. (`apply.sh` additionally hashes the
diff before and after, to catch a patch that applies and changes nothing; a
`--check` cannot see that, so `applies` here is a necessary condition only.)

When a patch fails, the remark says why, from the tree rather than from the file
name:

* **already upstream** — every non-comment line the patch *adds* is already
  present in each of its target files. The change landed upstream; the patch
  is history, not work.
* **target file absent** — a file the patch *modifies* does not exist in the
  pinned tree. The remark says whether some patch in the library creates it
  (then the row only makes sense after that one) or nothing does (then only the
  private tree ever had it). A file the patch *creates* is not "absent";
  creating it is the patch's job.
* **context drift** — everything else. The remark quotes git's first `error:`
  line, which names the file and line that moved.

## Counts

| verdict | files | by target repository | baseline |
|---|---|---|---|
| `portable` | 123 | local-dream wrapper 90, sdcpp 33 | `applies` 33, `does-not-apply` 90 |
| `excluded-hexagon` | 41 | local-dream wrapper 12, sdcpp 7, ggml 22 | `n-a` (excluded) |
| `excluded-mobile-gpu` | 12 | local-dream wrapper 7, sdcpp 1, ggml 4 | `n-a` (excluded) |
| `deferred` | 18 | local-dream wrapper 2, sdcpp 16 | `applies` 12, `does-not-apply` 6 |
| **total** | **194** | wrapper 111, sdcpp 57, ggml 26 | `applies` 45, `does-not-apply` 96, `n-a` 53 |

The verdict counts match `backend-patches/README.md` (123 / 41 / 12 / 18) and the
`applies` counts match its per-repository measurements exactly — 12 of 95 root,
37 of 57 `sdcpp`, 19 of 26 `ggml`, 2 of 16 `dit/` wrapper. Two independent
runs of the same measurement agreeing is the only reason to trust either.

Why 96 portable and
deferred patches fail, split by the reason above:

| reason | files |
|---|---|
| already upstream | 0 |
| target file absent | 19 |
| context drift | 77 |

`already upstream` reads **0** here, and that is a measurement, not a missing
row: the check fired on 2 files in the whole set, every one of them excluded (`excluded-mobile-gpu`), so none of the rows whose baseline is reported is upstreamed. It was worth running
because the opposite mistake — calling real porting work "already upstream"
because a version number happens to match — is the one this inventory is most
likely to make. See the `DitEngine` ABI note under
[Application order](#application-order).

⚠ Excluded rows are `n-a`, but they were measured anyway, and the answer is
worth knowing: 21 of the 41 Hexagon patches and 4 of the 12 mobile-GPU patches would `git apply --check` cleanly.
They are excluded because a desktop build has no target for them, not because
the text is stale — so `applies` must not be read as a verdict. This is exactly
the trap the `n-a` value exists to avoid.

## The inventory

Sorted by source path: root `001`–`058` first, then `dit/` in number order, then
the unnumbered `spike-*` probes. `Topic` comes from the file name; the paths
after the dash are the files the diff header says it touches (`+N` = more).

| source path | topic | target repository | verdict | baseline | remark |
|---|---|---|---|---|---|
| `001-sampleapp-patch-via-patch1.patch` | sampleapp patch via patch1 — CMakeLists.txt | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/CMakeLists.txt:9. |
| `002-pipeline-op-surface.patch` | pipeline op surface — src/Pipeline.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Pipeline.hpp:197. |
| `003-op-endpoints.patch` | op endpoints — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:41. |
| `004-generationresult-latent.patch` | generationresult latent — src/SDUtils.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `005-sdxl-npuforge-231-masked.patch` | sdxl npuforge 231 masked — src/PipelineSdxl.hpp, src/QnnModel.hpp (+1) | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `006-sd15-inpaint-full-frame.patch` | sd15 inpaint full frame — src/PipelineSd15Inpaint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/PipelineSd15Inpaint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `007-dit-engine.patch` | dit engine — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:234. |
| `008-upscale-without-a-qnn-pipeline.patch` | upscale without a qnn pipeline — src/QnnRuntime.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `009-dit-reference-edit-wire.patch` | dit reference edit wire — src/RequestParser.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `010-dit-shared-parts.patch` | dit shared parts — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:374. |
| `011-dit-lora-DitEngine.h.patch` | dit lora — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `011-dit-lora-Pipeline.hpp.patch` | dit lora — src/Pipeline.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Pipeline.hpp:69. |
| `011-dit-lora-PipelineDit.hpp.patch` | dit lora — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:27. |
| `011-dit-lora-RequestParser.hpp.patch` | dit lora — src/RequestParser.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `011-dit-lora-main.cpp.patch` | dit lora — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:3. |
| `012-sd15-float32-vae.patch` | sd15 float32 vae — src/QnnModel.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `013-qwen-image-2-1-DitEngine.h.patch` | qwen image 2 1 — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `013-qwen-image-2-1-PipelineDit.hpp.patch` | qwen image 2 1 — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:11. |
| `013-qwen-image-2-1-SDUtils.hpp.patch` | qwen image 2 1 — src/SDUtils.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/SDUtils.hpp:392. |
| `013-qwen-image-2-1-main.cpp.patch` | qwen image 2 1 — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:80. |
| `014-gguf-dit-krea2-main.cpp.patch` | gguf dit krea2 — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:81. |
| `015-template-unet-extra-inputs.patch` | template unet extra inputs — src/main.cpp, src/PipelineControlNet.hpp (+6) | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `016-ip-adapter-inputs.patch` | ip adapter inputs — src/main.cpp, src/Pipeline.hpp (+3) | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `017-swap-v3-inpaint-inputs.patch` | swap v3 inpaint inputs — src/PipelineSd15Inpaint.hpp, src/PipelineSd15Npu.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `018-sdxl-swap-template-io.patch` | sdxl swap template io — src/PipelineSdxl.hpp, src/QnnModel.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `019-sdxl-n-chunk-context.patch` | sdxl n chunk context — src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineSdxl.hpp:33. |
| `020-sdxl-swap-inpaint-inputs.patch` | sdxl swap inpaint inputs — src/Pipeline.hpp, src/PipelineSd15Npu.hpp (+1) | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `022-dit-params-backend-override.patch` | dit params backend override — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:461. |
| `023-sdxl-swap-controlnet-PipelineSdxl.patch` | sdxl swap controlnet — src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineSdxl.hpp:35. |
| `023-sdxl-swap-controlnet-QnnModel.patch` | sdxl swap controlnet — src/QnnModel.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `023-sdxl-swap-controlnet-RequestParser.patch` | sdxl swap controlnet — src/RequestParser.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/RequestParser.hpp:98. |
| `024-sdxl-swap-ip-adapter-PipelineSdxl.patch` | sdxl swap ip adapter — src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineSdxl.hpp:228. |
| `025-line-buffered-stdout.patch` | line buffered stdout — src/main.cpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `026-sd15-controlnet-evicts-vaes.patch` | sd15 controlnet evicts vaes — src/PipelineSd15Npu.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `027-lowram-unet-evicts-vae-decoder.patch` | lowram unet evicts vae decoder — src/PipelineAnima.hpp, src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineSdxl.hpp:226. |
| `028-wan-video-DitEngine.h.patch` | wan video — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `028-wan-video-PipelineDit.hpp.patch` | wan video — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:28. |
| `028-wan-video-VideoEndpoint.hpp.patch` | wan video — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `028-wan-video-main.cpp.patch` | wan video — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:32. |
| `029-samplers-3m-exponential-dit.patch` | samplers 3m exponential dit — src/DPMSolverMultistepScheduler.hpp, src/EulerAncestralDiscreteScheduler.hpp (+4) | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:48. |
| `030-qwen-vae-tiles-by-side-PipelineDit.hpp.patch` | qwen vae tiles by side — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:394. |
| `031-wan-vae-spatial-tiles-PipelineDit.hpp.patch` | wan vae spatial tiles — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:31. |
| `031-wan-vae-spatial-tiles-VideoEndpoint.hpp.patch` | wan vae spatial tiles — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `032-lowram-new-prompt-releases-Pipeline.hpp.patch` | lowram new prompt releases — src/Pipeline.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Pipeline.hpp:369. |
| `032-lowram-new-prompt-releases-PipelineAnima.hpp.patch` | lowram new prompt releases — src/PipelineAnima.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `032-lowram-new-prompt-releases-PipelineSdxl.hpp.patch` | lowram new prompt releases — src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `033-wan-fun-control-DitEngine.h.patch` | wan fun control — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `033-wan-fun-control-PipelineDit.hpp.patch` | wan fun control — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:38. |
| `033-wan-fun-control-VideoEndpoint.hpp.patch` | wan fun control — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `033-wan-fun-control-main.cpp.patch` | wan fun control — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:114. |
| `034-qwen-tile-ref-cap-PipelineDit.hpp.patch` | qwen tile ref cap — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:5. |
| `035-iris-type-PipelineDit.hpp.patch` | iris type — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:141. |
| `035-iris-type-main.cpp.patch` | iris type — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:84. |
| `036-sdxl-mnn-PipelineSdxl.hpp.patch` | sdxl mnn — src/PipelineSdxl.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `036-sdxl-mnn-PipelineSdxlMnn.hpp.patch` | sdxl mnn — src/PipelineSdxlMnn.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `036-sdxl-mnn-main.cpp.patch` | sdxl mnn — src/main.cpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `037-variation-seed-Pipeline.hpp.patch` | variation seed — src/Pipeline.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Pipeline.hpp:494. |
| `037-variation-seed-RequestParser.hpp.patch` | variation seed — src/RequestParser.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `038-dit-schedule-DitEngine.h.patch` | dit schedule — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `038-dit-schedule-PipelineDit.hpp.patch` | dit schedule — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:6. |
| `039-dit-more-schedules-PipelineDit.hpp.patch` | dit more schedules — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:69. |
| `040-dit-donor-parts-main.cpp.patch` | dit donor parts — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:505. |
| `041-request-validation-Pipeline.hpp.patch` | request validation — src/Pipeline.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Pipeline.hpp:472. |
| `041-request-validation-PipelineSd15Npu.hpp.patch` | request validation — src/PipelineSd15Npu.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `041-request-validation-PipelineSdxl.hpp.patch` | request validation — src/PipelineSdxl.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineSdxl.hpp:645. |
| `041-request-validation-PipelineSdxlMnn.hpp.patch` | request validation — src/PipelineSdxlMnn.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `041-request-validation-QnnModel.hpp.patch` | request validation — src/QnnModel.hpp | local-dream wrapper | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `041-request-validation-RequestParser.hpp.patch` | request validation — src/RequestParser.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `041-request-validation-VideoEndpoint.hpp.patch` | request validation — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `041-request-validation-main.cpp.patch` | request validation — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:5. |
| `042-backend-token-main.cpp.patch` | backend token — src/main.cpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `043-dit-simple-beta-PipelineDit.hpp.patch` | dit simple beta — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:71. |
| `044-dit-progress-sampler-only-PipelineDit.hpp.patch` | dit progress sampler only — src/PipelineDit.hpp | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `045-dit-vae-tiles-low-ram-PipelineDit.hpp.patch` | dit vae tiles low ram — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:2. |
| `046-dit-native-edit-env-PipelineDit.hpp.patch` | dit native edit env — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:610. |
| `047-wan14-720-VideoEndpoint.hpp.patch` | wan14 720 — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `048-sdxl-mnn-prompt-chunks-Pipeline.hpp.patch` | sdxl mnn prompt chunks — src/Pipeline.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `048-sdxl-mnn-prompt-chunks-PipelineSdxl.hpp.patch` | sdxl mnn prompt chunks — src/PipelineSdxl.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `048-sdxl-mnn-prompt-chunks-PipelineSdxlMnn.hpp.patch` | sdxl mnn prompt chunks — src/PipelineSdxlMnn.hpp | local-dream wrapper | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `049-krea2-llm-vision-PipelineDit.hpp.patch` | krea2 llm vision — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:618. |
| `049-krea2-llm-vision-main.cpp.patch` | krea2 llm vision — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:517. |
| `050-h3-video-audio-DitEngine.h.patch` | h3 video audio — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `050-h3-video-audio-PipelineDit.hpp.patch` | h3 video audio — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:58. |
| `050-h3-video-audio-VideoEndpoint.hpp.patch` | h3 video audio — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `050-h3-video-audio-main.cpp.patch` | h3 video audio — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:89. |
| `051-video-pause-VideoEndpoint.hpp.patch` | video pause — src/VideoEndpoint.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/VideoEndpoint.hpp` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `052-dit-taesd-PipelineDit.hpp.patch` | dit taesd — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:198. |
| `052-dit-taesd-main.cpp.patch` | dit taesd — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:629. |
| `053-vae-tile-env-PipelineDit.hpp.patch` | vae tile env — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:506. |
| `054-esrgan-upscale-DitEngine.h.patch` | esrgan upscale — src/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: `src/DitEngine.h` is not in the pinned tree at all — no patch in the library creates it, so only the private tree has it. |
| `054-esrgan-upscale-main.cpp.patch` | esrgan upscale — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:799. |
| `055-h3-ref-parts-main.cpp.patch` | h3 ref parts — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:472. |
| `056-h3-parts-chain-main.cpp.patch` | h3 parts chain — src/main.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/main.cpp:10. |
| `057-dit-schedules-097-PipelineDit.hpp.patch` | dit schedules 097 — src/PipelineDit.hpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/PipelineDit.hpp:79. |
| `058-dpmpp-sde-sampler.patch` | dpmpp sde sampler — src/DPMSolverSDEScheduler.hpp, src/Pipeline.hpp (+1) | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/src/Scheduler.hpp:42. |
| `dit/001-lora-abi4.patch` | lora abi4 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:192. |
| `dit/002-lora-apply-mode-abi5.patch` | lora apply mode abi5 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:105. |
| `dit/003-lora-on-alpha3-abi105.patch` | lora on alpha3 abi105 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/004-unet-stride2-conv.sdcpp.patch` | unet stride2 conv — src/core/ggml_extend.cpp, src/core/ggml_extend.h (+1) | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/005-controlnet-htp.sdcpp.patch` | controlnet htp — src/model/diffusion/unet.hpp, src/pipeline/diffusion_engine.cpp (+1) | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/006-max-vram-env.patch` | max vram env — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:162. |
| `dit/007-qwen21-graph-cut.sdcpp.patch` | qwen21 graph cut — src/model/diffusion/qwen_image_2_1.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/008-anima-fused-ops.sdcpp.patch` | anima fused ops — src/model/diffusion/anima.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/009-wan-fused-ops.sdcpp.patch` | wan fused ops — src/model/diffusion/wan.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/010-wan-te-unpadded.sdcpp.patch` | wan te unpadded — src/conditioning/conditioner.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/012-hexagon-conv2d-batch.ggml.patch` | hexagon conv2d batch — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/013-wan22-t2v-one-timestep.sdcpp.patch` | wan22 t2v one timestep — src/pipeline/diffusion_engine.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/014-dump-video-latent.sdcpp.patch` | dump video latent — src/pipeline/video.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/016-wan22-i2v-split-modulation.sdcpp.patch` | wan22 i2v split modulation — src/model/diffusion/wan.hpp, src/pipeline/diffusion_engine.cpp | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/diffusion/wan.hpp:323. |
| `dit/017-video-abi106.patch` | video abi106 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/include/DitEngine.h:26. |
| `dit/018-vae-temporal-tile-cut.sdcpp.patch` | vae temporal tile cut — src/model/vae/vae_tiling.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/019-wan22-fun-control.sdcpp.patch` | wan22 fun control — src/model/diffusion/model.hpp, src/model/diffusion/wan.hpp (+2) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/diffusion/wan.hpp:855. |
| `dit/020-video-abi107-fun-control.patch` | video abi107 fun control — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:422. |
| `dit/021-ref-max-pixels-env.patch` | ref max pixels env — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:270. |
| `dit/022-iris-3b.sdcpp.patch` | iris 3b — src/conditioning/conditioner.hpp, src/model/diffusion/iris.hpp (+6) | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/024-taehv-chunked-encode.sdcpp.patch` | taehv chunked encode — src/model/vae/tae.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/025-zimage-htp-out-scale.sdcpp.patch` | zimage htp out scale — src/model/common/ggml_block.hpp, src/model/diffusion/z_image.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/027-lokr-factored-forward.sdcpp.patch` | lokr factored forward — src/model/adapter/lora_ops.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/028-moss-image.sdcpp.patch` | moss image — src/conditioning/conditioner.hpp, src/model/diffusion/moss_image.hpp (+8) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model.h:60. |
| `dit/029-ideogram4-fused-rope.sdcpp.patch` | ideogram4 fused rope — src/model/diffusion/ideogram4.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/030-hexagon-hmx-selftest.ggml.patch` | hexagon hmx selftest — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/031-hexagon-concat-contig.ggml.patch` | hexagon concat contig — src/ggml-hexagon/htp/concat-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/032-dit-scheduler-abi108.patch` | dit scheduler abi108 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/include/DitEngine.h:33. |
| `dit/033-hexagon-v85.ggml.patch` | hexagon v85 — src/ggml-hexagon/CMakeLists.txt, src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/033b-hexagon-v85-skel-only.ggml.patch` | hexagon v85 skel only — src/ggml-hexagon/CMakeLists.txt | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/034-hexagon-v69-skel.ggml.patch` | hexagon v69 skel — src/ggml-hexagon/CMakeLists.txt, src/ggml-hexagon/ggml-hexagon.cpp (+1) | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/035-moss-fused-rope.sdcpp.patch` | moss fused rope — src/model/diffusion/moss_image.hpp | sdcpp | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/036-hexagon-cpy-strided-hvx.ggml.patch` | hexagon cpy strided hvx — src/ggml-hexagon/htp/cpy-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/037-iris-fused-rope.sdcpp.patch` | iris fused rope — src/model/diffusion/iris.hpp | sdcpp | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/038-wan-patch-embed-matmul.sdcpp.patch` | wan patch embed matmul — src/model/common/ggml_block.hpp, src/model/diffusion/wan.hpp | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/diffusion/wan.hpp:784. |
| `dit/039-hexagon-fa-prefetch-early.ggml.patch` | hexagon fa prefetch early — src/ggml-hexagon/htp/flash-attn-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/040-hexagon-fa-softmax-cubic-exp.ggml.patch` | hexagon fa softmax cubic exp — src/ggml-hexagon/htp/flash-attn-ops.c, src/ggml-hexagon/htp/hvx-exp.h | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/041-hexagon-fa-chunk-env.ggml.patch` | hexagon fa chunk env — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/042-flux3-klein.sdcpp.patch` | flux3 klein — src/conditioning/conditioner.hpp, src/model/diffusion/flux3.hpp (+7) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/conditioning/conditioner.hpp:2010. |
| `dit/043-flux3-fused-rope.sdcpp.patch` | flux3 fused rope — src/model/diffusion/flux3.hpp | sdcpp | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/044-wan-a14b-moe-env.patch` | wan a14b moe env — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:179. |
| `dit/045-flux3-htp-matmul-scale.sdcpp.patch` | flux3 htp matmul scale — src/model/diffusion/flux3.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/046-wan-token-chunked-activations.sdcpp.patch` | wan token chunked activations — src/model/diffusion/wan.hpp | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/diffusion/wan.hpp:170. |
| `dit/047-moss-image-edit-refs.sdcpp.patch` | moss image edit refs — src/model/diffusion/model.hpp, src/model/diffusion/moss_image.hpp (+1) | sdcpp | `portable` | `does-not-apply` | Does not apply: `src/model/diffusion/moss_image.hpp` is not in the pinned tree — `dit/028` creates it, so this row only makes sense after that one. |
| `dit/048-dit-ref-preset-per-model.patch` | dit ref preset per model — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:227. |
| `dit/049-wan-high-noise-lora-prefix.sdcpp.patch` | wan high noise lora prefix — src/name_conversion.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/050-segment-weight-preload.sdcpp.patch` | segment weight preload — src/core/ggml_runner.cpp, src/core/segment_weight_pipeline.cpp (+1) | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/051-hexagon-buffer-cpy-tensor.ggml.patch` | hexagon buffer cpy tensor — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/052-taehv-encode-small-chunks.sdcpp.patch` | taehv encode small chunks — src/model/vae/tae.hpp | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/vae/tae.hpp:909. |
| `dit/053-vae-tiles-never-seam.sdcpp.patch` | vae tiles never seam — src/runtime/tiling.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/054-wan-animate.sdcpp.patch` | wan animate — examples/cli/main.cpp, examples/common/common.cpp (+6) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model/diffusion/model.hpp:192. |
| `dit/055-flux3-graph-cut.sdcpp.patch` | flux3 graph cut — src/model/diffusion/flux3.hpp | sdcpp | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/056-fibo2-edit.sdcpp.patch` | fibo2 edit — src/conditioning/conditioner.hpp, src/conditioning/fibo2.hpp (+10) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/conditioning/conditioner.hpp:1822. |
| `dit/057-fibo2-htp.sdcpp.patch` | fibo2 htp — src/model/diffusion/fibo2.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/060-graph-cut-use-counts.sdcpp.patch` | graph cut use counts — src/core/ggml_graph_cut.cpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/061-hexagon-repack-threaded.ggml.patch` | hexagon repack threaded — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/062-loader-pread-chunks.sdcpp.patch` | loader pread chunks — src/model_loader.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/063-hexagon-qknorm-rope-blocks.ggml.patch` | hexagon qknorm rope blocks — src/ggml-hexagon/htp/qknorm-rope-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/064-cache-buffer-reuse.sdcpp.patch` | cache buffer reuse — src/core/runner_cache.cpp, src/core/runner_cache.h | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/065-hexagon-qknorm-rope-l2fetch.ggml.patch` | hexagon qknorm rope l2fetch — src/ggml-hexagon/htp/qknorm-rope-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/067-opencl-mali-old-drivers.ggml.patch` | opencl mali old drivers — src/ggml-opencl/ggml-opencl.cpp | ggml | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `dit/068-wan-lora-expert-prefix.patch` | wan lora expert prefix — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:440. |
| `dit/069-hexagon-binary-wide-rows.ggml.patch` | hexagon binary wide rows — src/ggml-hexagon/ggml-hexagon.cpp | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/070-minimax-h3-audio-vae-on-te-backend.sdcpp.patch` | minimax h3 audio vae on te backend — src/pipeline/diffusion_engine.cpp, src/pipeline/model_builders.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/071-minimax-h3-fused-partial-rope.sdcpp.patch` | minimax h3 fused partial rope — src/model/diffusion/minimax_h3.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/072-minimax-h3-curve-knots.sdcpp.patch` | minimax h3 curve knots — src/model/diffusion/minimax_h3.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/073-minimax-h3-htp-matmul-scale.sdcpp.patch` | minimax h3 htp matmul scale — src/model/diffusion/minimax_h3.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/074-minimax-h3-vae-fused-rope.sdcpp.patch` | minimax h3 vae fused rope — src/model/vae/minimax_h3_vae.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/075-minimax-h3-vae-flash-attn.sdcpp.patch` | minimax h3 vae flash attn — src/model/vae/minimax_h3_vae.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/076-minimax-h3-clipproj.sdcpp.patch` | minimax h3 clipproj — src/conditioning/conditioner.hpp | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/conditioning/conditioner.hpp:2236. |
| `dit/078-h3-audio-abi110.patch` | h3 audio abi110 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:160. |
| `dit/079-minimax-h3-vae-encode-one-frame.sdcpp.patch` | minimax h3 vae encode one frame — src/model/vae/minimax_h3_vae.hpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/080-opencl-yield-to-screen.ggml.patch` | opencl yield to screen — src/ggml-opencl/ggml-opencl.cpp | ggml | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `dit/081-opencl-qkv-preprocess-pr28446.ggml.patch` | opencl qkv preprocess pr28446 — src/ggml.c, src/ggml-opencl/CMakeLists.txt (+2) | ggml | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `dit/085-opencl-adreno-gemm-dst-view-offs.ggml.patch` | opencl adreno gemm dst view offs — src/ggml-opencl/ggml-opencl.cpp | ggml | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `dit/086-esrgan-upscale-abi111.patch` | esrgan upscale abi111 — dit/DitEngine.cpp, include/DitEngine.h | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:622. |
| `dit/087-esrgan-pixel-unshuffle.sdcpp.patch` | esrgan pixel unshuffle — src/model/upscaler/esrgan.hpp, src/name_conversion.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/088-hexagon-cpy-interleave2.ggml.patch` | hexagon cpy interleave2 — src/ggml-hexagon/htp/cpy-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/089-loader-poll-1ms.sdcpp.patch` | loader poll 1ms — src/model_loader.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/090-moss-rapid-sampler.sdcpp.patch` | moss rapid sampler — src/pipeline/diffusion_engine.cpp | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/091-hexagon-cpy-interleave2-4d.ggml.patch` | hexagon cpy interleave2 4d — src/ggml-hexagon/htp/cpy-ops.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/092-flux1-env.patch` | flux1 env — dit/DitEngine.cpp | local-dream wrapper | `portable` | `does-not-apply` | Does not apply: patch failed: app/src/main/cpp/dit/DitEngine.cpp:177. |
| `dit/093-moss-lora-fused-qkv.sdcpp.patch` | moss lora fused qkv — src/model/adapter/lora.hpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/094-lowres-spectrum-spike.sdcpp.patch` | lowres spectrum spike — src/pipeline/diffusion_engine.cpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/095-keep-runners-spike.sdcpp.patch` | keep runners spike — src/core/ggml_runner.cpp, src/pipeline/diffusion_engine.cpp | sdcpp | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/097-schedules-lq-beta57-radiance.sdcpp.patch` | schedules lq beta57 radiance — include/stable-diffusion.h, src/runtime/denoiser.hpp (+1) | sdcpp | `portable` | `applies` | Applies cleanly to the pinned tree. |
| `dit/098-hexagon-v69-stacks.ggml.patch` | hexagon v69 stacks — src/ggml-hexagon/htp/main.c | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/099-zimage-opencl-out-scale.sdcpp.patch` | zimage opencl out scale — src/model/diffusion/z_image.hpp | sdcpp | `excluded-mobile-gpu` | `n-a` | Excluded: phone-GPU path (Adreno OpenCL / MNN); a desktop Vulkan/Metal build does not run these kernels and MNN is not linked. |
| `dit/100-minimax-h3-image.sdcpp.patch` | minimax h3 image — src/conditioning/conditioner.hpp, src/model/diffusion/minimax_h3.hpp (+9) | sdcpp | `portable` | `does-not-apply` | Does not apply: patch failed: src/model.h:63. |
| `dit/spike-dit-no-fa.patch` | spike dit no fa — dit/DitEngine.cpp | local-dream wrapper | `deferred` | `does-not-apply` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/spike-flux1-htp-kv-scale.sdcpp.patch` | spike flux1 htp kv scale — src/model/diffusion/flux.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/spike-flux1-htp-matmul-scale.sdcpp.patch` | spike flux1 htp matmul scale — src/model/diffusion/flux.hpp | sdcpp | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/spike-nan-probe.sdcpp.patch` | spike nan probe — src/core/ggml_runner.cpp, src/core/util.cpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/spike-sd-cli.patch` | spike sd cli — dit/CMakeLists.txt | local-dream wrapper | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/spike-segment-timing.sdcpp.patch` | spike segment timing — src/core/ggml_runner.cpp | sdcpp | `deferred` | `applies` | Deferred to Phase 1: fused-op / graph-cut / spike experiment, adopted only after it is measured on desktop hardware. |
| `dit/spike-v69-away.ggml.patch` | spike v69 away — src/ggml-hexagon/htp/hvx-base.h | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/spike-v69-rne.ggml.patch` | spike v69 rne — src/ggml-hexagon/htp/hvx-base.h | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/spike-v73emu-rne.ggml.patch` | spike v73emu rne — src/ggml-hexagon/htp/hvx-base.h | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |
| `dit/spike-v73emu-trunc.ggml.patch` | spike v73emu trunc — src/ggml-hexagon/htp/hvx-base.h | ggml | `excluded-hexagon` | `n-a` | Excluded: Hexagon/QNN kernel or QNN session plumbing; every desktop build sets `GGML_HEXAGON=OFF`, so nothing here has a target (ADR-0002). |

## Application order

### The one rule

**The number is the order.** Root `001`→`058` ascending, then `dit/` ascending,
as `backend-patches/README.md` already states. Same number, several files = one
logical change across several sources, no order between them; file-name order is
fine. What is ordered is the number, and skipping one fails here rather than
producing an engine whose reported version is a surprise.

### Root library `001`–`058`: blocked on a rebase, not on order

**Do not apply the root library to a public tag, and do not claim it is
applicable.** Measured: 12 of the 95 root files `--check` on `alpha.4`, and the
sequence breaks at `001` — its first hunk wants
`app/src/main/cpp/CMakeLists.txt:9`, which is not what `alpha.4` has there. The
library was written against the maintainer's private `local-dream` tree, which is
ahead of every published tag. Consequences for the desktop build:

* A root-level `applies` is not usable advice. Eleven portable root files do
  `--check` in isolation, but the sequence they belong to does not run: `002`
  alone fails, and `002` is what raises the ABI `001` introduces. Each row
  reports what its file does against a clean tree; it cannot promise the set.
* The rebase is its own ticket. Re-basing means regenerating against a public
  tag and re-verifying each patch, which is a change to the patch library —
  never a hand-edit of a `.patch` (README, Rules).
* Until then the desktop build uses the **clean baseline**, which is what
  `desktop/engine/build.sh` already does by default.

### `dit/` on the engine: usable now, in this order

`dit/*.sdcpp.patch` and `dit/*.ggml.patch` are written against the pinned
submodules and largely still fit (37/57 and 19/26). They land in two different
git repositories and neither needs the other, so they are two independent
sequences:

1. **`sdcpp`**, ascending by number, taking only the `portable` rows that
   `applies`. `dit/004` (`unet stride2 conv`) is the natural first one: it is
   the patch `apply.sh --dry-run` is documented against, and its three targets
   (`src/core/ggml_extend.{cpp,h}`, `src/model/common/ggml_block.hpp`) are all
   engine internals, not model support.
2. **`ggml`**, ascending by number — and, for a desktop build, this sequence is
   empty. All 26 files land in `src/ggml-hexagon/` or the OpenCL backend (22
   Hexagon, 4 mobile-GPU), so 19 of them `--check` cleanly and not one is
   `portable`: there is nothing here to adopt, only things to skip. It stays
   listed because the routing is by suffix and a future patch could be neither.
3. **`dit/*.patch` (bare, routed to the wrapper)**, ascending. These are the
   `DitEngine` ABI bumps and the `*-env` knobs, and 14 of the 16 do not apply.
   The ABI ones are a single chain, and the numbers tell you where the pin
   sits: `alpha.4` ships `DIT_ENGINE_ABI_VERSION 5`; `dit/003` takes it **5 →
   105** and applies, which is why its name says `abi105`; `dit/017` then takes
   105→106, `020` 106→107, `032` 107→108, `078` 108→110 and `086` 110→111.
   So the order is `003`, `017`, `020`, `032`, `078`, `086`, and each needs the
   one before it — a later ABI patch's `-#define` line is the previous one's
   `+#define` line.
   `dit/001` (3→4) and `dit/002` (4→5) are the same LoRA change written against
   an older private ABI; `003` is that change re-based onto the ABI the pin
   ships. **Do not port `001`/`002`** — `003` supersedes them, and its pre-image
   blob is byte-for-byte the `dit/DitEngine.cpp` the tag ships, which is exactly
   why it is one of only two `dit/` wrapper patches that applies.
   The lesson generalises: an `*-abiNNN` file name is a name, not a version
   comparison. `alpha.4` declares ABI 5 and has no `lora` token anywhere in
   `include/DitEngine.h` or `dit/DitEngine.cpp`, so "the pin already has the
   LoRA ABI because both say 5" would be wrong — the pin's 5 predates `003`.
   The blob check and the `already upstream` rule are the reliable answers;
   both were run for every row.

### What to apply before building

**Nothing, for Phase 1.** `desktop/engine/build.sh` builds the clean baseline
with `--patch` off by default, and that is the configuration the pins in
`ENGINES.md` describe. Turn patches on only when a specific model needs one:

```sh
./desktop/engine/build.sh --dry-run --patch backend-patches/dit/004-unet-stride2-conv.sdcpp.patch
./desktop/engine/build.sh --patch backend-patches/dit/004-unet-stride2-conv.sdcpp.patch
```

Two things to know before doing that: `apply.sh` fails the build when a patch
changes nothing (the silent-no-op assertion), and `--repo-root` is
`desktop/engine`, so a bare `*.patch` targets `desktop/engine/app/src/main/cpp`
and fails loudly — deliberate, because routing it at the real `app/src/main/cpp`
would let an engine build edit Android sources.

## The patch set is not a prerequisite for Phase 1 models

The DiT models Phase 1 targets are **native in the pinned `sdcpp`**; no patch
from this library is needed to get them, and no patch in the library is what
makes them work. Verified in the pinned tree rather than assumed:

| model | evidence in `35a3d48b` |
|---|---|
| Z-Image (Turbo) | `src/model/diffusion/z_image.hpp`; `sd_version_is_z_image()` branches in `src/pipeline/model_builders.cpp` |
| FLUX.2 Klein 4B / 9B | `VERSION_FLUX2_KLEIN` in `src/model.h`; `"Flux.2 klein"` in `src/pipeline/diffusion_engine.cpp`; `docs/flux2.md` |
| Qwen Image 2.1 | `src/model/diffusion/qwen_image_2_1.hpp` |

The engine's own changelog dates Z-Image Turbo and FLUX.2 Klein 4B to
2026-09-17 and Klein 9B to 2026-09-20 — all before the 2026-10-02 pinned
commit — and `docs/edit.md` lists the reference presets for them. SD1.5 and
SDXL predate the fork entirely.

So the `dit/` model patches that do apply are *ahead* of the pin, not a
prerequisite for it: `dit/042` creates a `src/model/diffusion/flux3.hpp` that
the pin does not have, for a model the pin does not name at all. Treating the
patch set as the way to "enable DiT" would be wrong in both directions —
unnecessary for the models Phase 1 ships, and insufficient for the ones it
does not.

## Coverage guard

A table of 194 rows is only an inventory if every file is in it. The guard is
`desktop/core/src/test/kotlin/com/abrah/nightmare/PatchInventoryTest.kt`, run by
`./gradlew :core:test` with the rest of the JVM suite:

* it walks `backend-patches/**/*.patch` and asserts each patch has **exactly one
  inventory row** — a missing row fails, and so does a duplicated one. The
  assertion is row-scoped rather than a raw substring count, so the prose above
  is free to quote a path in a runnable command without failing the guard;
* it asserts the line carrying a patch names **exactly one** of the four
  verdicts, so a row cannot be added without a verdict;
* it asserts the counts add up to the number of files on disk, so a row cannot
  be quietly dropped from a section header either;
* it asserts [the ticket-22 section](#deferred-patch-verdicts) has exactly one
  conclusion row per `deferred` patch, one conclusion and a reason per row, no
  duplicate reasons, and the conclusion recorded for each patch.

The repository root reaches the test as the `nightmare.repoRoot` system
property, set from `desktop/core/build.gradle.kts`, so the test works from a
fresh clone and in CI without a hard-coded path. Add a patch and forget the
row: the test goes red. That is the whole point of it.

## Borderline rows and risks

* `012-sd15-float32-vae` is `excluded-hexagon` because the only file it touches
  is `src/QnnModel.hpp`. The *change* (a float32 VAE) is not NPU-specific; the
  file is. A desktop build has no `QnnModel.hpp`, so there is nowhere to put it
  — but if Phase 1 wants float32 VAE it is a new patch, not this one.
* `018-sdxl-swap-template-io` touches `src/PipelineSdxl.hpp` *and*
  `src/QnnModel.hpp`, so the rule excludes the whole file even though part of
  it is portable; `015` and `016` are the same shape. One hunk in a QNN file is
  enough. Splitting a patch is a new number, never an edit (README, Rules) —
  and nothing on the desktop side wants the portable half today.
* The rule reads names and paths, and it is deliberately blunt. `dit/005`
  (`controlnet htp`) is excluded by the `htp` in its name, yet one of its
  hunks is plainly portable — it enables flash attention on the ControlNet
  copy of the UNet so an SDXL ControlNet at 1024² does not materialise
  4096×4096 score matrices. `dit/025` (`zimage htp out scale`) is HTP-only: its
  new branch is gated on `sd_backend_is(ctx->backend, "HTP")`. The first is a
  candidate for a split into a portable patch; the second is not. A rule that
  needed a per-hunk audit to tell them apart is a rule nobody would re-run,
  which is why the table inherits the blunt answer and this paragraph records
  the disagreement.
* `dit/067-opencl-mali-old-drivers` is `excluded-mobile-gpu` although it names
  Mali, not Adreno. The rule matches the class, not the vendor: it is a phone
  GPU workaround and desktop runs neither.
* `dit/094-lowres-spectrum-spike` says "spike" about a numerical spike, not an
  experiment, and `dit/spike-sd-cli` is a throwaway CLI probe. Both are
  `deferred` by the rule. The README already flags these; the rows keep the
  rule's answer so the table and the README cannot drift, and this paragraph is
  where the disagreement is recorded.
* `spike-sd-cli` and `dit/003` are the two `dit/` wrapper patches that apply.
  `spike-sd-cli` touches `dit/CMakeLists.txt` only, so "applies" says nothing
  about whether the probe is still meaningful.
* A row can go stale without anyone editing it: adding a patch, or moving a
  pin, changes `applies`. The coverage test catches a missing row; nothing
  catches a stale verdict except re-running the measurement. Re-run it when
  `ENGINES.md` changes — the README's own rule is that applicability is bound
  to the `sdcpp` SHA.

## Deferred patch verdicts

Ticket 09 gave the 18 `fused` / `graph-cut` / `spike` rows one shared
`deferred` verdict. Ticket 22 settles them one by one; the `[verdict]` column
above is unchanged so the 09 counts stay reproducible. "Conclusion" here means
**whether a desktop build should carry this patch**, not whether its text
`--check`s (that is `Baseline`):

| conclusion | meaning |
|---|---|
| `applicable` | carry it in the desktop build as-is, no further condition |
| `excluded` | no desktop target or no desktop workload; do not carry it |
| `conditional` | carry it only under the stated condition, and re-check it against the pin |

Evidence used for these rows, read from the pinned tree rather than inferred
from patch names: `desktop/engine/build.sh` sets `-DGGML_HEXAGON=OFF` and
`-DSD_BUILD_EXAMPLES=ON` (Vulkan builds add `SD_VULKAN=ON`/`GGML_VULKAN=ON`);
the CPU backend's `ggml_backend_supports_op` returns false for
`GGML_OP_QKNORM_ROPE` and the pinned Vulkan backend has no such kernel, so an
HTP `qknorm_rope` fused path always falls back on desktop; CPU *does* support
F32 `GGML_OP_MODULATE` and `GGML_OP_GATED_RESIDUAL`; and the Phase 1 model list
in [the patch-set section](#the-patch-set-is-not-a-prerequisite-for-phase-1-models)
does not include Anima, Ideogram 4, MOSS-Image, Iris, FLUX.3, Wan or MiniMax H3.
The mobile-only cache regressions quoted inside the patches are **not** desktop
measurements. No patch was applied to produce this section.

| source path | conclusion | reason / condition |
|---|---|---|
| `dit/007-qwen21-graph-cut.sdcpp.patch` | `conditional` | Qwen Image 2.1 is a Phase 1 DiT and graph cutting is compiled on desktop, but cuts only run under a memory budget such as `--max-vram`, which the desktop CLI does not expose; adopt for direct `sd-cli` use only after an A/B of peak memory and output. |
| `dit/008-anima-fused-ops.sdcpp.patch` | `conditional` | `Flux::modulate`/`gated_residual` F32 ops are supported by the desktop CPU backend, so this really changes the graph there; its `qknorm_rope` half stays HTP-only. Adopt only for Anima workloads after a desktop numeric and timing A/B. |
| `dit/009-wan-fused-ops.sdcpp.patch` | `excluded` | Wan video is out of scope (`spec.md` Out of Scope); the fused rope needs HTP `qknorm_rope`, which desktop CPU/Vulkan reject, and the CPU modulate paths have no Phase 1 workload to justify the change. |
| `dit/029-ideogram4-fused-rope.sdcpp.patch` | `excluded` | Ideogram 4's fused `qknorm_rope` is HTP-only; CPU reports `GGML_OP_QKNORM_ROPE` unsupported and Vulkan has no kernel, so desktop always takes the unchanged composed path, for a model Phase 1 does not ship. |
| `dit/035-moss-fused-rope.sdcpp.patch` | `excluded` | Same HTP-only fused rope as `dit/029` on MOSS-Image, and this row already fails `--check`; desktop CPU/Vulkan take the composed fallback, so there is nothing to adopt. |
| `dit/037-iris-fused-rope.sdcpp.patch` | `excluded` | Iris's rope table is already interleaved, but the fused kernel is still HTP-only; desktop CPU/Vulkan report it unsupported and keep the composed path unchanged. |
| `dit/043-flux3-fused-rope.sdcpp.patch` | `excluded` | Flux3 fused rope targets a model the pinned engine does not ship (`dit/042` would create it and does not apply); desktop CPU/Vulkan reject the fused op in any case. |
| `dit/055-flux3-graph-cut.sdcpp.patch` | `excluded` | Its cut marks target that same absent Flux3 model and depend on `dit/042`; FLUX.2 Klein is native in the pin and is the Phase 1 target, so this cut has no desktop subject. |
| `dit/060-graph-cut-use-counts.sdcpp.patch` | `conditional` | Repairs `use_counts` inside cut segments; Hexagon fusion (ADR-0002) is the stated consumer, while Vulkan reads it only for QSA top-k fusion that no Phase 1 model uses. Revisit only if a desktop Vulkan graph-cut workload needs that fusion, with an A/B. |
| `dit/071-minimax-h3-fused-partial-rope.sdcpp.patch` | `excluded` | MiniMax H3 video is out of scope; the fused rope is HTP-only and the added table padding only makes the composed path view the same first pairs. No desktop workload and no desktop gain. |
| `dit/074-minimax-h3-vae-fused-rope.sdcpp.patch` | `excluded` | H3 VAE fused rope is HTP-only, and its padded table likewise only preserves the composed path; with H3 out of scope there is no desktop model to justify it. |
| `dit/093-moss-lora-fused-qkv.sdcpp.patch` | `conditional` | A platform-independent LoRA rename for MOSS-Image weights with fused `proj.qkv`, and it applies; adopt only when such a LoRA is actually imported on desktop, after checking that the renamed tensors bind. |
| `dit/094-lowres-spectrum-spike.sdcpp.patch` | `conditional` | `NM_LOWRES`/`NM_SPECTRUM` sampling experiment that trades image fidelity for speed and is off by default; adopt only as an opt-in desktop experiment after a quality-and-time A/B. |
| `dit/095-keep-runners-spike.sdcpp.patch` | `conditional` | Env-gated cross-render weight retention that also fails `--check`; adopt only if desktop needs it, after rebasing and re-verifying LoRA/model invalidation. |
| `dit/spike-dit-no-fa.patch` | `excluded` | Android-wrapper diagnostic for an HTP fp16 `q.k` overflow, marked test only; its `dit/DitEngine.cpp` target is absent from the desktop tree and there is no desktop flash-attention equivalent to toggle. |
| `dit/spike-nan-probe.sdcpp.patch` | `conditional` | Applies and can locate NaNs on desktop, but it also changes scheduler eval-callback registration; adopt only as a temporary debugging aid after that path is verified. |
| `dit/spike-sd-cli.patch` | `excluded` | It only flips `SD_BUILD_EXAMPLES` to ON in the Android wrapper's `dit/CMakeLists.txt`; `build.sh` already sets `-DSD_BUILD_EXAMPLES=ON` for desktop and the target file is absent, so it is redundant. |
| `dit/spike-segment-timing.sdcpp.patch` | `conditional` | Applies and adds a `LOG_INFO` timing line only when a graph is cut into several segments; adopt for desktop profiling of segmented runs, then drop it before shipping. |

The HTP-fused rows are not `excluded-hexagon` by the ticket-09 name rule, and
this section does not change that classification: they are deferred by name but
excluded here for a measured backend reason. Re-run the checks above when
`ENGINES.md` changes, as with every other row.

---

Add a patch: regenerate this table. Change a pin: re-measure it. Neither is
optional — the alternative is a 194-row list that says whatever it said the
last time somebody cared.
