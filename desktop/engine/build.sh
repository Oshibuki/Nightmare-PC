#!/usr/bin/env bash
# Build the vendored engine (stable-diffusion.cpp + ggml) for the desktop
# project. One command, no external CI, no Android NDK.
#
# usage: build.sh [--vulkan] [--jobs N] [--clean] [--patch FILE...]
#                 [--no-publish] [--dry-run]
#
#   (no flags)     CPU build into ./build          -> build/bin/sd-cli
#   --vulkan       Vulkan build into ./build-vulkan -> build-vulkan/bin/sd-cli
#                  (a SEPARATE directory: a Vulkan build reconfigures ggml's
#                  cache, and reusing ./build would silently invalidate the CPU
#                  tree that the desktop E2E run points at)
#   --jobs N       parallel jobs, default 8
#   --clean        rm -rf the build directory before configuring
#   --patch FILE   apply patches through backend-patches/apply.sh first.
#                  NOT applied by default: see "Patch hook" below.
#   --no-publish   do not copy the binary into the shared engine cache
#   --dry-run      print the plan, write nothing
#
# ⚠⚠ HEXAGON/QNN IS OFF AT CONFIGURE TIME, NOT AT RUN TIME (ADR-0005).
# `-DGGML_HEXAGON=OFF` is the only thing that keeps the QNN backend out of the
# build. The fork's hexagon sources stay in the tree on purpose -- they are dead
# code without the option -- so grepping the SOURCE for "hexagon" proves
# nothing. The evidence is `GGML_HEXAGON:BOOL=OFF` in CMakeCache.txt and the
# absence of any hexagon target in `cmake --build build --target help`.
#
# ⚠ Patch hook. The desktop patch set is not applied by default, and that is a
# finding rather than laziness: ticket 03 measured the root-level patches
# (001-058) against every public tag and found they are written for a tree that
# no longer exists (most do not even `--check` against alpha.4). A default
# build therefore has to be the clean baseline; anything else would silently
# ship a half-patched engine. When patches ARE requested, each file's sha256 is
# printed before it is applied -- a build log has to say which bytes of a patch
# produced the binary, because the patch set moves and the engine SHA alone does
# not pin it. apply.sh additionally refuses to report success for a patch that
# changed nothing.
#
# ⚠ Wrapper-routed patches (a bare `*.patch`, which apply.sh sends into
# `--repo-root`/app/src/main/cpp) do not apply to the desktop layout: the repo
# root is passed as desktop/engine, which has no app/src/main/cpp. That path is
# deliberate -- routing into the real app/src/main/cpp would edit Android
# sources from an engine build, which this project forbids. Such a patch fails
# loudly instead.
set -euo pipefail

ENGINE_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$ENGINE_DIR/../.." && pwd)
SDCPP=$ENGINE_DIR/sdcpp
APPLY_SH=$REPO_ROOT/backend-patches/apply.sh

# The three SHAs in ENGINES.md are the only versions that are "the mobile
# build". Printing the live ones next to the pinned ones is what makes a
# divergence visible in the build log instead of three tickets later.
PINNED_SDCPP=35a3d48b23581d2be7c3ad95e92f34662bb5f305
PINNED_GGML=f8758628270072da26ad44baf5e189d4695d9800

CACHE_ROOT=${NIGHTMARE_ENGINE_CACHE:-$HOME/.cache/nightmare-desktop/engine}

usage() {
  cat <<'EOF'
usage: build.sh [--vulkan] [--jobs N] [--clean] [--patch FILE...]
                [--no-publish] [--dry-run]

  (no flags)   CPU build into desktop/engine/build
               -> desktop/engine/build/bin/sd-cli
  --vulkan     Vulkan build into desktop/engine/build-vulkan (separate dir)
  --jobs N     parallel jobs (default 8)
  --clean      remove the build directory before configuring
  --patch F    apply patches through backend-patches/apply.sh first (opt-in)
  --no-publish skip copying the binary into the shared engine cache
  --cmake-arg A pass A through to the configure step (repeatable)
  --dry-run    print the plan; write nothing
  -h, --help   this help

The shared cache defaults to ~/.cache/nightmare-desktop/engine (override with
NIGHTMARE_ENGINE_CACHE); the CPU binary lands at <cache>/sd-cli, which is what
NIGHTMARE_SD_BIN points at in the desktop E2E run.

Vulkan needs SPIRV-Headers, and ggml's ggml-vulkan/CMakeLists.txt calls
find_package(SPIRV-Headers) without adding the imported target's include
directory to the compiler line. On a box with no root, install it to a local
prefix and pass both halves through --cmake-arg; see README.md.
EOF
}

die() {
  printf 'build.sh: error: %s\n' "$1" >&2
  exit 2
}

vulkan=0
jobs=8
clean=0
dry_run=0
publish=1
patches=()
cmake_args=()

while [ $# -gt 0 ]; do
  case "$1" in
    --vulkan) vulkan=1; shift ;;
    --jobs)
      [ $# -ge 2 ] || die "--jobs needs a number"
      case "$2" in
        ''|*[!0-9]*) die "--jobs needs a positive integer, got '$2'" ;;
      esac
      [ "$2" -ge 1 ] || die "--jobs needs a positive integer, got '$2'"
      jobs=$2; shift 2 ;;
    --clean) clean=1; shift ;;
    --no-publish) publish=0; shift ;;
    --dry-run) dry_run=1; shift ;;
    --cmake-arg)
      [ $# -ge 2 ] || die "--cmake-arg needs an argument"
      cmake_args+=("$2"); shift 2 ;;
    --patch)
      [ $# -ge 2 ] || die "--patch needs at least one file"
      shift
      before=${#patches[@]}
      while [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; do patches+=("$1"); shift; done
      [ ${#patches[@]} -gt "$before" ] || die "--patch needs at least one file (use -- FILE for a name starting with -)" ;;
    --) shift; while [ $# -gt 0 ]; do patches+=("$1"); shift; done ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option: $1 (try --help)" ;;
    *) die "unexpected argument: $1 (patches go after --patch; try --help)" ;;
  esac
done

if [ "$vulkan" -eq 1 ]; then
  mode=vulkan
  build_dir=$ENGINE_DIR/build-vulkan
else
  mode=cpu
  build_dir=$ENGINE_DIR/build
fi

# ⚠ Validate every patch BEFORE configuring anything: a typo in the last
# argument must not cost a ten-minute build that then fails on the patch step.
abs_patches=()
for p in ${patches[@]+"${patches[@]}"}; do
  [ -e "$p" ] || die "patch file not found: $p"
  [ -f "$p" ] || die "patch file is not a regular file: $p"
  case "$p" in
    /*) abs_patches+=("$p") ;;
    *) abs_patches+=("$(cd "$(dirname "$p")" && pwd)/$(basename "$p")") ;;
  esac
done

if [ "$dry_run" -eq 0 ]; then
  [ -f "$SDCPP/CMakeLists.txt" ] ||
    die "engine source missing at $SDCPP -- run: git submodule update --init --recursive"
fi

sha() { # file -> sha256
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# ── Plan ────────────────────────────────────────────────────────────────────
# Every step prints the same line whether it runs or is only planned, so a real
# build log and a --dry-run log can be diffed against each other.
step() { printf '[build.sh] %s\n' "$*"; }
run() { # command...
  step "run: $*"
  "$@"
}

step "mode: $mode"
if [ -d "$SDCPP/.git" ] || [ -f "$SDCPP/.git" ]; then
  live_sdcpp=$(git -C "$SDCPP" rev-parse HEAD 2>/dev/null || echo unknown)
  live_ggml=$(git -C "$SDCPP/ggml" rev-parse HEAD 2>/dev/null || echo unknown)
  step "engine: $SDCPP @ $live_sdcpp"
  step "ggml:   $SDCPP/ggml @ $live_ggml"
  [ "$live_sdcpp" = "$PINNED_SDCPP" ] ||
    step "WARNING: sdcpp is not the ENGINES.md pin ($PINNED_SDCPP) -- this binary is NOT the mobile-equivalent build"
  [ "$live_ggml" = "$PINNED_GGML" ] ||
    step "WARNING: ggml is not the ENGINES.md pin ($PINNED_GGML) -- this binary is NOT the mobile-equivalent build"
else
  step "engine: $SDCPP (not a git checkout; SHA not verified)"
fi
step "build dir: $build_dir"
[ "$clean" -eq 1 ] && step "clean: $build_dir"

for p in ${abs_patches[@]+"${abs_patches[@]}"}; do
  step "patch: $p"
  step "sha256: $(sha "$p")  $p"
done
if [ ${#abs_patches[@]} -gt 0 ]; then
  step "apply: $APPLY_SH --repo-root $ENGINE_DIR --sdcpp $SDCPP --ggml $SDCPP/ggml ${abs_patches[*]}"
fi

configure=(
  cmake -S "$SDCPP" -B "$build_dir"
  -DCMAKE_BUILD_TYPE=Release
  -DSD_BUILD_EXAMPLES=ON
  -DGGML_HEXAGON=OFF
)
if [ "$vulkan" -eq 1 ]; then
  configure+=(-DSD_VULKAN=ON -DGGML_VULKAN=ON)
else
  configure+=(-DSD_VULKAN=OFF)
fi
# Last, so a caller can override anything above -- that is the point of the
# passthrough: this box has no root and no Vulkan SDK, and the Vulkan backend
# needs its dependency include path injected by hand.
configure+=(${cmake_args[@]+"${cmake_args[@]}"})
step "configure: ${configure[*]}"
step "build: cmake --build $build_dir -j$jobs"

artifact=$build_dir/bin/sd-cli
if [ "$publish" -eq 1 ]; then
  # The CPU binary is what the desktop E2E run points at (NIGHTMARE_SD_BIN), so
  # it keeps the unqualified name. The Vulkan one is published beside it rather
  # than over it: overwriting `sd-cli` with a Vulkan build would change what
  # every other worktree's E2E run exercises, with no trace in the repo.
  if [ "$vulkan" -eq 1 ]; then
    published=$CACHE_ROOT/sd-cli-vulkan
  else
    published=$CACHE_ROOT/sd-cli
  fi
  step "publish: $artifact -> $published"
fi

if [ "$dry_run" -eq 1 ]; then
  step "dry run: nothing was written"
  exit 0
fi

# ── Execute ─────────────────────────────────────────────────────────────────
if [ "$clean" -eq 1 ]; then
  rm -rf "$build_dir"
fi

if [ ${#abs_patches[@]} -gt 0 ]; then
  # apply.sh asserts per patch that the tree actually changed, so a patch that
  # no longer applies to this SHA fails here instead of producing a binary that
  # looks right and is not.
  "$APPLY_SH" --repo-root "$ENGINE_DIR" --sdcpp "$SDCPP" --ggml "$SDCPP/ggml" \
    ${abs_patches[@]+"${abs_patches[@]}"}
fi

run "${configure[@]}"
run cmake --build "$build_dir" -j"$jobs"

[ -x "$artifact" ] || die "build reported success but $artifact is missing"

# ── Success signal ──────────────────────────────────────────────────────────
# The artifact path AND the binary answering for itself: a stale sd-cli left
# over from an earlier tree would satisfy a path-existence check alone.
step "artifact: $artifact"
step "version: $("$artifact" --version 2>&1 | head -1)"

if [ "$publish" -eq 1 ]; then
  mkdir -p "$CACHE_ROOT"
  cp -f "$artifact" "$published.tmp"
  mv -f "$published.tmp" "$published"
  step "published: $published"
fi

step "OK: $mode engine built"
