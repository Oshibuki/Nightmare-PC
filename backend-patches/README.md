# backend-patches

Every edit to the C++ backend, as a patch. `backend-src/` — the forked engine
tree the app is actually built from — is **gitignored** (CC BY-NC lineage, see
`.gitignore`), so it is never published and never committed. These patch files
are therefore the *only* record of what the backend does beyond upstream. If an
edit exists in `backend-src/` and not here, it will be lost the next time
somebody clones.

Two libraries live here, written against two different upstreams:

| | written against | carries |
|---|---|---|
| `*.patch` (root, `001`–`058`) | the local-dream **wrapper** app (`app/src/main/cpp/...`) | `main.cpp`, `Pipeline*.hpp`, `DitEngine`, HTTP endpoints, request parsing |
| `dit/*.patch` | the **engine** itself — the `stable-diffusion.cpp` and `ggml` submodules | model support, samplers, VAE tiling, fused ops, Hexagon kernels |

Upstream chain: local-dream `v3.0.0-alpha.4` → `happyyzy/stable-diffusion.cpp`
→ `happyyzy/ggml`.

## Contents

1. [Layout](#layout)
2. [Applying patches: `apply.sh`](#applying-patches-applysh)
3. [Application order](#application-order)
4. [Which repository a patch lands in](#which-repository-a-patch-lands-in)
5. [What is in here](#what-is-in-here)
6. [Who references this file](#who-references-this-file)
7. [Verifying against a real checkout](#verifying-against-a-real-checkout)
8. [Rules](#rules)

## Layout

```
backend-patches/
  README.md       this file (tracked despite the blanket *.md rule)
  apply.sh        the applier: suffix routing + anti-silent-no-op assertion
  apply_test.sh   self-test for apply.sh, on throwaway repos
  001-...patch    95 files, numbered 001–058 (021 is unused)
  dit/            99 files, numbered 001–100 (011, 015, 023, 026, 058, 059,
                  066, 077, 082–084, 096 are unused, and 033b sits beside 033)
                  plus 10 unnumbered spike-*.patch probes
```

`194` patch files in total. One number may span several files
(`011-dit-lora-DitEngine.h.patch`, `…-main.cpp.patch`, …) when one logical
change touches several sources.

## Applying patches: `apply.sh`

```sh
# from anywhere; --repo-root is the local-dream clone
backend-patches/apply.sh --repo-root ../LocalDream/local-dream \
    002-pipeline-op-surface.patch dit/004-unet-stride2-conv.sdcpp.patch

# see where each patch WOULD land, writing nothing
backend-patches/apply.sh --dry-run --repo-root ../LocalDream/local-dream dit/004-unet-stride2-conv.sdcpp.patch
```

```
usage: apply.sh [--dry-run] [--repo-root DIR] [--sdcpp DIR] [--ggml DIR] <patch>...
```

| option | default |
|---|---|
| `--repo-root DIR` | `$PWD` |
| `--sdcpp DIR` | `<repo-root>/app/src/main/cpp/3rdparty/stable-diffusion.cpp` |
| `--ggml DIR` | `<sdcpp>/ggml` |

Exit codes: `0` every patch changed something, `1` a patch failed to apply **or
applied nothing**, `2` bad usage / missing patch file / not a git repository.

### The assertion, and why it is per patch

`git apply` exiting `0` does **not** mean the tree changed. A patch whose only
targets are gitignored applies, prints `成功应用补丁 …`, and leaves
`git diff` byte-identical — measured on a real `v3.0.0-alpha.4` clone:

```
$ git -C $CLONE apply -v --directory=app/src/main/cpp probe.patch   # build/probe.txt is ignored
成功应用补丁 app/src/main/cpp/build/probe.txt。
$ echo $?                                                          # 0. "success".
$ backend-patches/apply.sh --repo-root $CLONE probe.patch
apply.sh: error: …/probe.patch applied nothing -- …/t03-clone is unchanged (e3b0c442…)
$ echo $?                                                          # 1.
```

So for every patch, in this order:

1. `git -C "$repo" add -A -N` — a file an **earlier** patch created is untracked
   and invisible to `git diff`; intent-to-add puts its content in the diff
   without staging it. Without this, a patch that edits only a freshly created
   file reads as "applied nothing".
2. `before=$(git -C "$repo" diff | sha256sum)`
3. `git -C "$repo" apply -v <args> <patch>`
4. `after=$(git -C "$repo" diff | sha256sum)`; equal ⇒ error, `exit 1`.

Per **patch**, not once at the end: `git diff` over the whole tree cannot tell
"003 did nothing" from "001 already changed something".

`--dry-run` uses `git apply --check`, writes nothing, and prints the repository
each patch would hit. ⚠ It cannot prove a patch is not a silent no-op — nothing
is written, so there is no diff to compare. That is what a real run is for.

## Application order

**The number is the order.** Root `001`→`058` ascending, then `dit/` ascending.
Apply in order or not at all: `002` raises the ABI that `001` introduced, and
applying them out of order or skipping one fails here rather than producing an
engine whose reported version is a surprise.

Same number, several files: those files are one logical change and touch
different sources, so they carry no order between themselves — file-name order
(`DitEngine.h` → `main.cpp` → `Pipeline*.hpp` → `RequestParser.hpp` →
`VideoEndpoint.hpp`) is fine. What *is* ordered is the number.

The two libraries are independent: they land in different git repositories and
neither needs the other. The mobile CI only ever applies `dit/*` (see
`local_dream_ref` / `sdcpp_ref` in `.github/workflows/dit-engine.yml`).

## Which repository a patch lands in

Routing is by suffix, and it is **copied verbatim** from the "Apply patch" step
of `.github/workflows/dit-engine.yml` (lines ~175–219). The two must not drift.

| suffix | repository | `git apply` arguments | default path |
|---|---|---|---|
| `*.ggml.patch` | the `ggml` submodule (a submodule *of a submodule*) | none | `<sdcpp>/ggml` |
| `*.sdcpp.patch` | the `stable-diffusion.cpp` submodule | none | `<repo-root>/app/src/main/cpp/3rdparty/stable-diffusion.cpp` |
| anything else | the local-dream **wrapper** root | `--directory=app/src/main/cpp` | `<repo-root>` |

Root patches are written against the developer's own clone, so their `---` side
carries an absolute-ish path that no longer exists:

```
--- ../LocalDream/local-dream/app/src/main/cpp/src/Pipeline.hpp
+++ backend-src/src/Pipeline.hpp
```

How `+++ backend-src/src/Pipeline.hpp` becomes
`app/src/main/cpp/src/Pipeline.hpp`:

1. `git apply` strips `-p1` by default — one leading path component.
2. For a patch **without** a `diff --git` header, git takes the name from the
   **new** (`+++`) side. The `---` side is only consulted when `+++` is
   `/dev/null`, i.e. a deletion. So the stale `../LocalDream/…` path in `---` is
   documentation, not a target.
3. `+++ backend-src/src/Pipeline.hpp` → `src/Pipeline.hpp`.
4. `--directory=app/src/main/cpp` prefixes it →
   `app/src/main/cpp/src/Pipeline.hpp`.

Which is why `+++ b/src/…` and `+++ backend-src/src/…` are the same thing to
`git apply`. In the current library: 81 root patches use `+++ b/…`, 14 use
`+++ backend-src/…`; on the `---` side, 80 use `a/…`, 9 `../LocalDream/…`, 5
`backend-src/…`, 1 `/dev/null`. All 95 land in the same place.

⚠ Never `cd app/src/main/cpp && git apply …`. `git apply` resolves a patch's
paths against the **repository root**, not the working directory — so it looks
for `local-dream/src/…`, finds nothing, and (on the paths that matter here) still
exits 0. That is the failure `apply.sh` exists to make impossible.

⚠ Same trap one and two levels down: applying a `*.sdcpp.patch` from
local-dream with `--directory` resolves against the *parent's* index, which does
not track anything inside a gitlink. Hence `git -C <the submodule itself>`.

## What is in here

Classification below is by **rule over file name and patch paths**, not a
per-hunk audit. The counts are what those rules select today.

| class | rule | files |
|---|---|---|
| portable | no hunk touches a platform-specific symbol (see below) | 123 |
| Hexagon / QNN only | name or patch path matches `hexagon\|htp\|qnn\|npu\|hmx\|hvx\|v69\|v73\|v85`, or targets `src/ggml-hexagon/`, `*Npu*`, `QnnModel*` | 41 |
| mobile GPU only | name or patch path matches `opencl\|adreno\|mnn`, or targets `PipelineSdxlMnn*` | 12 |
| deferred | `fused` / `graph-cut` / `spike` in the name | 18 |
| | | **194** |

**Portable** — platform-independent engine work: model loading and `--type`
dispatch, pipeline and sampler parameters, VAE tiling, request validation, the
HTTP surface, progress output, LoRA. These are the ones a desktop build wants.

**Hexagon / QNN only — excluded.** NPU kernels, HTP graphs, the `npu/` sources,
QNN session plumbing, Hexagon SDK build wiring. Desktop hardware has no
counterpart (ADR-0002) and the Hexagon backend is compiled out. Examples:
`dit/012-hexagon-conv2d-batch.ggml.patch`, `dit/030-hexagon-hmx-selftest.ggml.patch`,
`041-request-validation-QnnModel.hpp.patch`.

**Mobile GPU only (Adreno OpenCL / MNN) — also excluded.** A separate class from
Hexagon and worth keeping separate: these target the *GPU* paths the phone uses,
which exist on desktop in name only — the Adreno-tuned kernel is not what a
desktop Vulkan/Metal build runs, and MNN is not linked at all. Examples:
`036-sdxl-mnn-main.cpp.patch`, `048-sdxl-mnn-prompt-chunks-*.patch`,
`dit/085-opencl-adreno-gemm-dst-view-offs.ggml.patch`,
`dit/099-zimage-opencl-out-scale.sdcpp.patch`.

**Deferred** — `fused` / `graph-cut` operator patches and the `spike-*` probes.
Not excluded, not adopted: whether a fused op is worth carrying depends on
measurements on desktop hardware that Phase 1 has not made yet. Examples:
`dit/008-anima-fused-ops.sdcpp.patch`, `dit/055-flux3-graph-cut.sdcpp.patch`,
`dit/spike-nan-probe.sdcpp.patch`.

⚠ The rules are a file-name heuristic, so a few are borderline and the count
moves when they are read properly: `dit/094-lowres-spectrum-spike.sdcpp.patch`
says "spike" about a numerical spike, not an experiment; `dit/spike-sd-cli.patch`
is a throwaway CLI probe. `spec.md` estimates 125 / 47 / 11 / 12–15 for the same
four classes; the difference is exactly these borderline cases.

**Per-file attribution** — which of the 194 goes where, with source and topic
for each — is `desktop/engine/PATCHES.md`, produced by ticket 09
(`.scratch/desktop-migration/issues/09-desktop-patch-set-inventory.md`). That
file did not exist when this README was written; see ticket 09.

## Who references this file

| referrer | what it expects |
|---|---|
| `tools/op_smoke.sh` (line 9) | the staging layout under `/data/local/tmp/ldbase` (QNN libs, models) that the on-device op smoke needs |
| `.github/workflows/dit-engine.yml` (lines 14–15, and the "Apply patch" step ~175–219) | the suffix routing and the per-patch hash assertion `apply.sh` implements |
| `tools/doc_budget.sh` | a 1000-line budget for this file, and a `## Contents` list |
| `.gitignore` | negates `!/backend-patches/README.md` — this file is tracked even though `*.md` is ignored |
| Kotlin comments in `app/src/main/java/com/abrah/nightmare/` | `backend-patches/NNN` is cited by number as the justification for behaviour (`BackendProcess.kt`, `ModelCatalog.kt`, `HarnessOps.kt`, `CustomModels.kt`, `Fused.kt`, …) |

## Verifying against a real checkout

Measured on 2026-11-25 against a shallow `v3.0.0-alpha.4` clone with its
submodules at their pinned commits (`sdcpp 35a3d48b`, `ggml f8758628`):

| check | result |
|---|---|
| `apply.sh --dry-run` on `dit/004-unet-stride2-conv.sdcpp.patch` | passes `git apply --check`, routes to the sdcpp submodule |
| `apply.sh --dry-run` on `dit/012-hexagon-conv2d-batch.ggml.patch` | passes, routes to the ggml submodule |
| `apply.sh --dry-run` on `004-generationresult-latent.patch` | passes, routes to the wrapper with `--directory` |
| `apply.sh --dry-run` on `002-pipeline-op-surface.patch` | **fails** — context drift, see below |
| `git status --porcelain` in all three repos after every dry run | empty |
| root patches that `git apply --check` on a clean alpha.4 | 12 of 95 |
| root patches that apply cumulatively, in numeric order | 19 of 95; the sequence breaks at `001` |
| `dit/*.sdcpp.patch` on the pinned sdcpp | 37 of 57 |
| `dit/*.ggml.patch` on the pinned ggml | 19 of 26 |
| `dit/*.patch` routed to the wrapper | 2 of 16 |

⚠ **The root library has drifted from every published tag.** `001`, `002` and
`003` fail on `v3.0.0-alpha.4` *and* on `v3.0.0-alpha.8`. They are written
against the developer's private `../LocalDream/local-dream` tree, which is ahead
of both, so a clean public tag rejects most of `001`–`058`. `dit/*` is written
against the pinned submodules and largely applies. Re-basing the root library
onto a public tag (or recording the private tree's commit) is ticket 07/09 work,
not something to paper over by editing the patches.

The `apply.sh` self-test needs no network and no engine clone:

```sh
timeout 120 backend-patches/apply_test.sh     # 37 assertions, exit 0
```

## Rules

- **Do not edit an existing `.patch`.** It is the only record of the change; a
  hand-edit here is a change nobody can regenerate.
- To change engine behaviour: edit `backend-src/`, verify on device or in CI,
  then regenerate the patch and add a new number. Never renumber or reuse one.
- Never commit `backend-src/`, the built `.so`, or the QNN runtime libraries —
  redistribution hazard, see the comments in `.gitignore`.
- A new patch needs a class in the table above and, once ticket 09 has landed, a
  row in `desktop/engine/PATCHES.md`.
