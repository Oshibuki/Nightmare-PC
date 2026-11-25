#!/usr/bin/env bash
# Self-test for apply.sh. Builds throwaway git repositories in a temp dir, runs
# apply.sh against them, and asserts what an operator sees: the exit code, the
# message, and which repository the tree changed in.
#
# Run: ./backend-patches/apply_test.sh    (exit 0 = all passed)
#
# ⚠ Why a committed test rather than a one-off check: the whole point of
# apply.sh is a guard against a patch step that reports success and does
# nothing. A guard nobody tests is a guard that rots. The fixtures below are
# SYNTHETIC patches -- they exercise routing and the assertion, not whether a
# real patch still applies to a real engine checkout (that needs a network
# clone and belongs to the release check, see README "Verifying against a real
# checkout").
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
APPLY=$HERE/apply.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

pass=0
fails=0
ok() { printf 'ok   %s\n' "$1"; pass=$((pass + 1)); return 0; }
bad() { printf 'FAIL %s\n       %s\n' "$1" "$2"; fails=$((fails + 1)); return 1; }

# Run apply.sh, capturing combined output. Deliberately not `set -e`: a
# non-zero exit is the thing under test half the time.
RC=0
OUT=
run_apply() {
  OUT=$("$APPLY" "$@" 2>&1)
  RC=$?
}
expect_rc() { # expected label
  [ "$RC" -eq "$1" ] && { ok "$2"; return 0; }
  bad "$2" "expected exit $1, got $RC; output: $OUT"
}
expect_rc_nonzero() { # label
  [ "$RC" -ne 0 ] && { ok "$1"; return 0; }
  bad "$1" "expected non-zero exit, got 0; output: $OUT"
}
expect_out() { # needle label
  case "$OUT" in *"$1"*) ok "$2"; return 0 ;; esac
  bad "$2" "output lacks '$1'; got: $OUT"
}
expect_contains() { # file needle label
  grep -qF "$2" "$1" 2>/dev/null && { ok "$3"; return 0; }
  bad "$3" "$1 lacks '$2' (content: $(cat "$1" 2>/dev/null))"
}
expect_clean() { # repo label
  local st; st=$(git -C "$1" status --porcelain 2>&1)
  [ -z "$st" ] && { ok "$2"; return 0; }
  bad "$2" "expected a clean tree in $1, got: $st"
}
expect_fixture() { # name
  fixture "$1" && { ok "fixture $1"; return 0; }
  bad "fixture $1" "could not build the throwaway repos"
}

mkrepo() { # dir
  git init -q "$1" || return 1
  git -C "$1" config user.email apply-test@example.com
  git -C "$1" config user.name apply-test
  git -C "$1" config commit.gpgsign false
}

# The real layout: local-dream/ with sdcpp at
# app/src/main/cpp/3rdparty/stable-diffusion.cpp, and ggml nested in THAT.
# The nested repos are excluded from their parents' indexes because a real
# submodule is a gitlink -- its contents are neither tracked nor diffed by the
# parent.
fixture() { # name -> ROOT SDCPP GGML
  ROOT=$WORK/$1
  SDCPP=$ROOT/app/src/main/cpp/3rdparty/stable-diffusion.cpp
  GGML=$SDCPP/ggml
  rm -rf "$ROOT"
  mkrepo "$ROOT" || return 1
  mkdir -p "$ROOT/app/src/main/cpp/src"
  printf 'wrapper-one\n' > "$ROOT/app/src/main/cpp/src/Pipeline.hpp"
  # ⚠ Ignored on purpose: this is the file the "applied nothing" test patches.
  printf 'app/src/main/cpp/scratch.log\n' > "$ROOT/.gitignore"
  printf 'scratch-old\n' > "$ROOT/app/src/main/cpp/scratch.log"

  mkrepo "$SDCPP" || return 1
  mkdir -p "$SDCPP/src/core"
  printf 'sdcpp-one\n' > "$SDCPP/src/core/ggml_extend.cpp"

  mkrepo "$GGML" || return 1
  mkdir -p "$GGML/src"
  printf 'ggml-one\n' > "$GGML/src/ggml-hexagon.cpp"

  printf 'app/src/main/cpp/3rdparty/\n' > "$ROOT/.git/info/exclude"
  printf 'ggml/\n' > "$SDCPP/.git/info/exclude"

  git -C "$ROOT" add -A && git -C "$ROOT" commit -qm init || return 1
  git -C "$SDCPP" add -A && git -C "$SDCPP" commit -qm init || return 1
  git -C "$GGML" add -A && git -C "$GGML" commit -qm init || return 1
  return 0
}

# One patch per routing class. Paths are what p1 leaves behind, exactly as the
# real library does: `+++ b/src/Pipeline.hpp` for the wrapper (which
# --directory then prefixes), `+++ b/src/...` for both submodules.
wpatch=$WORK/wrapper.patch
printf -- '--- a/src/Pipeline.hpp\n+++ b/src/Pipeline.hpp\n@@ -1 +1,2 @@\n-wrapper-one\n+wrapper-one\n+wrapper-two\n' > "$wpatch"
spatch=$WORK/sdcpp.sdcpp.patch
printf -- '--- a/src/core/ggml_extend.cpp\n+++ b/src/core/ggml_extend.cpp\n@@ -1 +1,2 @@\n-sdcpp-one\n+sdcpp-one\n+sdcpp-two\n' > "$spatch"
gpatch=$WORK/ggml.ggml.patch
printf -- '--- a/src/ggml-hexagon.cpp\n+++ b/src/ggml-hexagon.cpp\n@@ -1 +1,2 @@\n-ggml-one\n+ggml-one\n+ggml-two\n' > "$gpatch"
# Applies cleanly, changes a file `git diff` cannot see (gitignored).
nopatch=$WORK/silent.patch
printf -- '--- a/scratch.log\n+++ b/scratch.log\n@@ -1 +1,2 @@\n-scratch-old\n+scratch-old\n+scratch-new\n' > "$nopatch"

if [ ! -x "$APPLY" ]; then
  printf 'FAIL apply.sh is missing or not executable: %s\n' "$APPLY"
  exit 1
fi

# ── Argument handling ───────────────────────────────────────────────────────
run_apply
expect_rc_nonzero "no arguments is a usage error"
expect_out "usage" "no arguments prints usage"

run_apply --sdcpp
expect_rc_nonzero "--sdcpp without a value is a usage error"
expect_out "--sdcpp" "--sdcpp without a value names the option"

run_apply "$WORK/does-not-exist.patch"
expect_rc_nonzero "a missing patch file is rejected"
expect_out "does-not-exist.patch" "a missing patch file is named in the error"

# ── Routing ─────────────────────────────────────────────────────────────────
if expect_fixture wrapper; then
  run_apply --repo-root "$ROOT" "$wpatch"
  if expect_rc 0 "a plain .patch applies"; then
    expect_contains "$ROOT/app/src/main/cpp/src/Pipeline.hpp" "wrapper-two" \
      "a plain .patch lands under app/src/main/cpp"
    expect_clean "$SDCPP" "a plain .patch leaves the sdcpp submodule alone"
  fi
fi

if expect_fixture sdcpp; then
  run_apply --repo-root "$ROOT" "$spatch"
  if expect_rc 0 "a .sdcpp.patch applies"; then
    expect_contains "$SDCPP/src/core/ggml_extend.cpp" "sdcpp-two" \
      "a .sdcpp.patch lands in the sdcpp submodule"
    expect_clean "$ROOT" "a .sdcpp.patch leaves the wrapper alone"
  fi
fi

if expect_fixture ggml; then
  run_apply --repo-root "$ROOT" "$gpatch"
  if expect_rc 0 "a .ggml.patch applies"; then
    expect_contains "$GGML/src/ggml-hexagon.cpp" "ggml-two" \
      "a .ggml.patch lands in the ggml submodule"
    expect_clean "$SDCPP" "a .ggml.patch leaves sdcpp alone"
    expect_clean "$ROOT" "a .ggml.patch leaves the wrapper alone"
  fi
fi

# Explicit --sdcpp/--ggml win over the derived defaults.
if expect_fixture explicit; then
  alt=$WORK/alt
  mkdir -p "$alt/sdcpp/src/core" "$alt/sdcpp/ggml/src"
  mkrepo "$alt/sdcpp" && printf 'sdcpp-one\n' > "$alt/sdcpp/src/core/ggml_extend.cpp" &&
    printf 'ggml-one\n' > "$alt/sdcpp/ggml/src/ggml-hexagon.cpp" &&
    printf 'ggml/\n' > "$alt/sdcpp/.git/info/exclude" &&
    git -C "$alt/sdcpp" add -A && git -C "$alt/sdcpp" commit -qm init &&
    mkrepo "$alt/sdcpp/ggml" &&
    git -C "$alt/sdcpp/ggml" add -A && git -C "$alt/sdcpp/ggml" commit -qm init
  run_apply --repo-root "$ROOT" --sdcpp "$alt/sdcpp" --ggml "$alt/sdcpp/ggml" "$spatch"
  if expect_rc 0 "--sdcpp redirects a .sdcpp.patch"; then
    expect_contains "$alt/sdcpp/src/core/ggml_extend.cpp" "sdcpp-two" \
      "--sdcpp decides which repo a .sdcpp.patch hits"
    expect_clean "$SDCPP" "--sdcpp keeps the default sdcpp untouched"
  fi
fi

# ── Dry run ─────────────────────────────────────────────────────────────────
if expect_fixture dryrun; then
  run_apply --dry-run --repo-root "$ROOT" "$wpatch" "$spatch" "$gpatch"
  if expect_rc 0 "--dry-run succeeds on patches that would apply"; then
    expect_clean "$ROOT" "--dry-run writes nothing to the wrapper"
    expect_clean "$SDCPP" "--dry-run writes nothing to sdcpp"
    expect_clean "$GGML" "--dry-run writes nothing to ggml"
    expect_contains "$ROOT/app/src/main/cpp/src/Pipeline.hpp" "wrapper-one" \
      "--dry-run leaves the wrapper file untouched"
    expect_out "$SDCPP" "--dry-run prints the sdcpp repo it would use"
    expect_out "$GGML" "--dry-run prints the ggml repo it would use"
  fi
fi

# ── The anti-silent-noop assertion (the reason this script exists) ──────────
if expect_fixture silent; then
  run_apply --repo-root "$ROOT" "$nopatch"
  expect_rc_nonzero "a patch that changes nothing fails"
  expect_out "unchanged" "a patch that changes nothing says the repo is unchanged"
fi

# Applying the same patch twice must fail, not quietly do nothing.
if expect_fixture twice; then
  run_apply --repo-root "$ROOT" "$wpatch"
  expect_rc 0 "the first apply succeeds"
  run_apply --repo-root "$ROOT" "$wpatch"
  expect_rc_nonzero "the second apply fails instead of silently no-opping"
fi

printf '\n%d assertions passed, %d failed\n' "$pass" "$fails"
[ "$fails" -eq 0 ]
