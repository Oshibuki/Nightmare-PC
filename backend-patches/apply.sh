#!/usr/bin/env bash
# Apply backend patches with the SAME routing the CI uses, and refuse to report
# success for a patch that changed nothing.
#
# usage: apply.sh [--dry-run] [--repo-root DIR] [--sdcpp DIR] [--ggml DIR] <patch>...
#
#   --repo-root DIR  local-dream clone root (default: $PWD)
#   --sdcpp DIR      stable-diffusion.cpp submodule root
#                    (default: <repo-root>/app/src/main/cpp/3rdparty/stable-diffusion.cpp)
#   --ggml DIR       ggml submodule root (default: <sdcpp>/ggml)
#   --dry-run        `git apply --check` only: writes nothing, prints where each
#                    patch WOULD land
#
# ⚠⚠⚠ THE ROUTING IS COPIED FROM .github/workflows/dit-engine.yml, STEP
# "Apply patch". It must stay identical -- a patch applied into the wrong
# repository is the failure mode this whole script exists to prevent.
#   *.ggml.patch  -> the ggml submodule, no --directory
#   *.sdcpp.patch -> the stable-diffusion.cpp submodule, no --directory
#   everything else -> local-dream, --directory=app/src/main/cpp
#
# ⚠ Why `git -C <repo>` and not `cd`: `git apply` resolves a patch's paths
# against the REPOSITORY root, not the working directory. Applying a
# `.sdcpp.patch` from local-dream with `--directory` resolves against the
# PARENT's index, which does not track anything inside a gitlink -- the patch
# is skipped and the command still exits 0.
#
# ⚠⚠ Why the hash assertion: `git apply` exiting 0 does not mean the tree
# changed. A patch whose paths are all filtered out, or whose only targets are
# gitignored, prints "applied" and leaves `git diff` identical. A patch step
# that can no-op silently is worse than one that fails, because the artifact it
# produces looks right and is not.
#
# ⚠ Per PATCH, not once at the end: `git diff` on the whole tree cannot tell
# "003 did nothing" from "001 already changed something".
#
# ⚠ `add -A -N` first: a file an earlier patch CREATED is untracked and
# invisible to `git diff`, so a patch that edits only it reads as "applied
# nothing". Intent-to-add puts its content in the diff without staging it.
set -euo pipefail

usage() {
  cat <<'EOF'
usage: apply.sh [--dry-run] [--repo-root DIR] [--sdcpp DIR] [--ggml DIR] <patch>...

Applies each patch in the order given, routing by suffix exactly as
.github/workflows/dit-engine.yml does:

  *.ggml.patch   -> <ggml>        (default <sdcpp>/ggml)
  *.sdcpp.patch  -> <sdcpp>       (default <repo-root>/app/src/main/cpp/3rdparty/stable-diffusion.cpp)
  anything else  -> <repo-root>   with --directory=app/src/main/cpp

Options:
  --dry-run       run `git apply --check` instead of applying; writes nothing
                  and prints the repository each patch would land in
  --repo-root DIR local-dream clone root (default: $PWD)
  --sdcpp DIR     stable-diffusion.cpp submodule root
  --ggml DIR      ggml submodule root
  -h, --help      print this help
EOF
}

die() {
  printf 'apply.sh: error: %s\n' "$1" >&2
  exit 2
}

dry_run=0
repo_root=
sdcpp=
ggml=
patches=()

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --repo-root)
      [ $# -ge 2 ] || die "--repo-root needs a directory argument"
      repo_root=$2; shift 2 ;;
    --sdcpp)
      [ $# -ge 2 ] || die "--sdcpp needs a directory argument"
      sdcpp=$2; shift 2 ;;
    --ggml)
      [ $# -ge 2 ] || die "--ggml needs a directory argument"
      ggml=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; while [ $# -gt 0 ]; do patches+=("$1"); shift; done ;;
    -*) die "unknown option: $1 (try --help)" ;;
    *) patches+=("$1"); shift ;;
  esac
done

if [ ${#patches[@]} -eq 0 ]; then
  usage >&2
  die "no patch files given"
fi

[ -n "$repo_root" ] || repo_root=$PWD
[ -n "$sdcpp" ] || sdcpp=$repo_root/app/src/main/cpp/3rdparty/stable-diffusion.cpp
[ -n "$ggml" ] || ggml=$sdcpp/ggml

# sha256sum is coreutils; macOS ships shasum. Either is fine -- only equality
# matters, and both sides of the comparison use the same tool.
if command -v sha256sum >/dev/null 2>&1; then
  sha() { sha256sum; }
else
  sha() { shasum -a 256; }
fi

abspath() { # path -> absolute, without requiring realpath
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$(cd "$(dirname "$1")" && pwd)" "$(basename "$1")" ;;
  esac
}

# ⚠ Validate every patch BEFORE applying any: a typo in the last argument must
# not leave the tree half-patched.
abs_patches=()
for p in "${patches[@]}"; do
  [ -e "$p" ] || die "patch file not found: $p"
  [ -f "$p" ] || die "patch file is not a regular file: $p"
  abs_patches+=("$(abspath "$p")")
done

require_repo() { # dir label
  [ -d "$1" ] || die "$2 is not a directory: $1 (pass --repo-root/--sdcpp/--ggml)"
  git -C "$1" rev-parse --git-dir >/dev/null 2>&1 ||
    die "$2 is not a git repository: $1 (pass --repo-root/--sdcpp/--ggml)"
}

touched=()
for p in "${abs_patches[@]}"; do
  case "$p" in
    *.ggml.patch)  repo=$ggml;      args=();                    label=ggml ;;
    *.sdcpp.patch) repo=$sdcpp;     args=();                    label=sdcpp ;;
    *)             repo=$repo_root; args=(--directory=app/src/main/cpp); label=wrapper ;;
  esac
  require_repo "$repo" "$label"

  printf -- '--- %s\n' "$p"
  printf '    -> %s %s\n' "$label" "$repo"

  if [ "$dry_run" -eq 1 ]; then
    # --check validates against the working tree without writing anything.
    # ⚠ It CANNOT prove the patch is not a silent no-op -- nothing is written,
    # so there is no diff to compare. That is what a real run's assertion is
    # for; see the README.
    git -C "$repo" apply --check -v ${args[@]+"${args[@]}"} "$p"
  else
    git -C "$repo" add -A -N
    before=$(git -C "$repo" diff | sha)
    git -C "$repo" apply -v ${args[@]+"${args[@]}"} "$p"
    after=$(git -C "$repo" diff | sha)
    if [ "$before" = "$after" ]; then
      printf 'apply.sh: error: %s applied nothing -- %s is unchanged (%s)\n' \
        "$p" "$repo" "${before%% *}" >&2
      exit 1
    fi
    touched+=("$repo")
  fi
done

if [ "$dry_run" -eq 1 ]; then
  printf '\ndry run: nothing written\n'
else
  for repo in $(printf '%s\n' ${touched[@]+"${touched[@]}"} | awk '!seen[$0]++'); do
    printf '\nfiles touched in %s:\n' "$repo"
    git -C "$repo" diff --stat
  done
fi
