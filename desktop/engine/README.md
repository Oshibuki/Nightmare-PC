# engine/ — vendored C++ inference engine

The desktop build owns its engine source (ADR-0004): `stable-diffusion.cpp` and
its `ggml` submodule are pinned to the exact SHAs the mobile build ships
(ADR-0005, catalogued in [`ENGINES.md`](ENGINES.md)), and
[`build.sh`](build.sh) turns them into a `sd-cli` binary with **no Android NDK
and no external CI**. That is the whole point: an agent can do
"edit engine → build → smoke" locally.

## Layout

```
desktop/engine/
├── sdcpp/                 git submodule → happyyzy/stable-diffusion.cpp @ 35a3d48b
│   ├── ggml/              git submodule → happyyzy/ggml @ f8758628 (pinned by sdcpp)
│   ├── thirdparty/libwebp/  ┐ pinned by sdcpp, needed because SD_WEBP defaults ON
│   ├── thirdparty/libwebm/  ┘
│   └── examples/server/frontend/   pinned by sdcpp; SD_BUILD_EXAMPLES builds sd-server
├── build.sh               the build script (this is the entry point)
├── build_test.sh          self-test for build.sh (no compilation, ~2 s)
├── build/                 CPU build tree          — gitignored
├── build-vulkan/          Vulkan build tree       — gitignored
├── ENGINES.md             the three pinned SHAs (source of truth)
└── README.md              this file
```

Nothing under `build*/` or inside `sdcpp/` is ever committed. Only the gitlink
and `.gitmodules` are; the licence terms of the fork forbid redistributing the
engine source.

## First checkout

```bash
git submodule update --init --recursive
```

`--recursive` is required: `sdcpp`'s own submodules are what the build actually
compiles, and a plain `--init` leaves `ggml/` empty and `cmake` fails on a
missing `CMakeLists.txt`. Verify the pins came out right:

```bash
git -C desktop/engine/sdcpp rev-parse HEAD         # 35a3d48b23581d2be7c3ad95e92f34662bb5f305
git -C desktop/engine/sdcpp/ggml rev-parse HEAD    # f8758628270072da26ad44baf5e189d4695d9800
```

## Build

```bash
./desktop/engine/build.sh                 # CPU    -> desktop/engine/build/bin/sd-cli
./desktop/engine/build.sh --vulkan        # Vulkan -> desktop/engine/build-vulkan/bin/sd-cli
./desktop/engine/build.sh --jobs 4        # fewer parallel jobs (default 8)
./desktop/engine/build.sh --clean         # drop the build tree first
./desktop/engine/build.sh --dry-run       # print the plan, write nothing
```

`--dry-run` is not a debugging leftover: it is what lets `build_test.sh` assert
the "Hexagon off, CPU on" contract in two seconds instead of a three-minute
compile.

Both modes print the artifact's absolute path and let the binary answer for
itself (`sd-cli --version`), then copy it into the shared cache:

| mode | build tree | binary | shared cache |
|---|---|---|---|
| CPU | `desktop/engine/build` | `build/bin/sd-cli` | `~/.cache/nightmare-desktop/engine/sd-cli` |
| Vulkan | `desktop/engine/build-vulkan` | `build-vulkan/bin/sd-cli` | `~/.cache/nightmare-desktop/engine/sd-cli-vulkan` |

The cache root is `$NIGHTMARE_ENGINE_CACHE` (default
`~/.cache/nightmare-desktop/engine`); `--no-publish` skips the copy. The CPU
binary keeps the unqualified `sd-cli` name because that is what
`NIGHTMARE_SD_BIN` points at in the desktop E2E run; a Vulkan build publishes
beside it rather than over it, so switching backends cannot silently change
what another worktree's E2E run exercises.

The two modes get separate trees on purpose: reconfiguring one directory for a
different backend invalidates its cache and leaves a binary whose provenance no
longer matches its path.

A failed configure or compile exits non-zero with cmake's own output intact —
`build.sh` never swallows a cmake error.

## Minimum dependencies (Linux, measured 2026-11-25)

| need | version here | note |
|---|---|---|
| cmake | 3.31.4 | `ggml-vulkan` requires ≥ 3.19 |
| C/C++ compiler | g++ 12.3.0 (C++17, OpenMP) | no clang, no NDK |
| make | 4.4.1 | Unix Makefiles; `ninja` is not installed here |
| Vulkan loader + headers | 1.3.296 (`libvulkan-dev`) | Vulkan mode only |
| `glslc` | shaderc 2025.2 | Vulkan mode only; compiles the compute shaders |
| SPIRV-Headers | `vulkan-sdk-1.3.296.0` | Vulkan mode only, see below |
| RAM | ~4 GB free with `-j8` | 16 cores / 12 GB here; `-j8` is the safe default |

### Vulkan needs one extra `--cmake-arg` on a box without root

`ggml/src/ggml-vulkan/CMakeLists.txt` calls `find_package(SPIRV-Headers CONFIG
REQUIRED)` but never puts the imported target's include directory on the
compile line. With the LunarG Vulkan SDK installed system-wide this goes
unnoticed; without it, `ggml-vulkan.cpp` fails with `'spv' has not been
declared` (the `__has_include` chain at the top of the file finds nothing).

There is no root on this machine, so install SPIRV-Headers to a local prefix and
pass both halves through:

```bash
PREFIX=~/.cache/nightmare-desktop/vendor/spirv-headers-install
git clone --depth 1 --branch vulkan-sdk-1.3.296.0 \
  https://github.com/KhronosGroup/SPIRV-Headers.git /tmp/SPIRV-Headers
cmake -S /tmp/SPIRV-Headers -B /tmp/SPIRV-Headers/build \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" -DSPIRV_HEADERS_SKIP_EXAMPLES=ON
cmake --install /tmp/SPIRV-Headers/build

./desktop/engine/build.sh --vulkan \
  --cmake-arg "-DCMAKE_PREFIX_PATH=$PREFIX" \
  --cmake-arg "-DCMAKE_CXX_FLAGS=-I$PREFIX/include"
```

If you have root or a full Vulkan SDK, plain `--vulkan` works and none of this
applies.

## Hexagon / QNN is off, and how to prove it

`-DGGML_HEXAGON=OFF` (ADR-0005) is the only thing keeping the QNN backend out.
The fork's hexagon sources stay in the tree deliberately — they are dead code
without the option — so **grepping the source for "hexagon" proves nothing**.
The evidence is in the build configuration and the target list:

```bash
grep -i hexagon desktop/engine/build/CMakeCache.txt
# GGML_HEXAGON:BOOL=OFF        (the option's comment line mentions Hexagon too)

cmake --build desktop/engine/build --target help | grep -i hexagon
# (no output)

grep -i hexagon desktop/engine/build/CMakeFiles/TargetDirectories.txt
# (no output) -- and no *hexagon* object file exists anywhere under build/
```

The Vulkan tree reports `GGML_HEXAGON:BOOL=OFF` as well; `build.sh --vulkan`
never relaxes it.

One cosmetic detail worth knowing before someone "fixes" it: in the Vulkan tree
the cache shows `GGML_VULKAN:UNINITIALIZED=ON`, not `:BOOL=ON`. That is cmake's
normal bookkeeping — `sdcpp`'s `CMakeLists.txt` sets `GGML_VULKAN` as a *normal*
variable derived from `SD_VULKAN` before `ggml`'s `option()` runs, so the
command-line `-D` keeps the `UNINITIALIZED` type. The value is ON and
`ggml-vulkan` is built, which is what matters.

## Patch hook

Patches are **opt-in and off by default**, and that is a finding rather than
caution: ticket 03 measured the root-level set (`001`–`058`) against every
public tag and found it written for a tree that no longer exists — most patches
do not even `git apply --check` against `alpha.4`. A default build therefore has
to be the clean baseline; anything else would ship a half-patched engine that
looks fine.

```bash
./desktop/engine/build.sh --patch backend-patches/dit/007-dit-engine.sdcpp.patch
./desktop/engine/build.sh --dry-run --patch backend-patches/dit/*.sdcpp.patch   # plan only
```

What the hook does:

1. Validates every patch file exists **before** configuring anything, so a typo
   in the last argument does not cost a three-minute build that then fails.
2. Prints the sha256 of each patch. A build log has to say which bytes of a
   patch produced the binary: the patch set moves, and the engine SHA alone does
   not pin it.
3. Calls `backend-patches/apply.sh` with the same routing CI uses, so
   `*.ggml.patch` lands in `sdcpp/ggml`, `*.sdcpp.patch` in `sdcpp`, and a bare
   `*.patch` in `--repo-root`.
4. `apply.sh` asserts per patch that the tree actually changed. A patch that
   applies cleanly and changes nothing fails the build instead of being
   reported as success.

`--repo-root` is `desktop/engine`, not the repository root. A bare `*.patch`
therefore targets `desktop/engine/app/src/main/cpp`, which does not exist, and
fails loudly. That is deliberate: routing it at the real `app/src/main/cpp`
would let an engine build edit Android sources, which this project forbids.

## Self-test

```bash
./desktop/engine/build_test.sh      # exit 0 = all passed
```

It exercises the script's contract only — exit codes, messages, and (through
`--dry-run`) the exact cmake command lines. No compilation, so it is cheap
enough to run on every change. It does not prove the engine compiles; that is
`build.sh`'s job, and `build.sh` failing is the signal.

## Not a Gradle project

The engine is built by its own script; Gradle only depends on the resulting
binary path. `desktop/README.md` covers the JVM side, which is what
`./gradlew build` exercises.
