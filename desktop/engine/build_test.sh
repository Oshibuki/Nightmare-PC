#!/usr/bin/env bash
# Self-test for build.sh. Exercises only what an operator can see from the
# outside: exit codes, the messages, and -- via --dry-run -- the exact cmake
# command lines the script would run. No compilation, so this stays fast enough
# to run on every change.
#
# Run: ./desktop/engine/build_test.sh    (exit 0 = all passed)
#
# ⚠ Why a committed test rather than a one-off check: build.sh is the only
# place the "Hexagon off, CPU on" contract is written down. A typo there
# produces a binary that builds fine and carries the wrong backend, which no
# smoke test would notice. --dry-run is what makes that contract assertable
# without a ten-minute compile.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BUILD=$HERE/build.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

pass=0
fails=0
ok() { printf 'ok   %s\n' "$1"; pass=$((pass + 1)); return 0; }
bad() { printf 'FAIL %s\n       %s\n' "$1" "$2"; fails=$((fails + 1)); return 1; }

# Snapshot before any dry run: "a dry run writes nothing" is only assertable as
# a before/after comparison. Checking `[ -d build ]` would instead fail on a
# machine where a real build already happened -- which is the normal state of a
# developer's tree, so the test would be green only on a fresh clone.
before_tree=$(ls -a "$HERE" | sort)

RC=0
OUT=
run_build() {
  OUT=$("$BUILD" "$@" 2>&1)
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
expect_no_out() { # needle label
  case "$OUT" in *"$1"*) bad "$2" "output should not contain '$1'; got: $OUT" ;; esac
  ok "$2"
}
# The configure line, whatever else the script prints around it.
configure_line() {
  printf '%s\n' "$OUT" | grep -E 'configure:' | head -1
}
expect_configure_flag() { # flag label
  local line; line=$(configure_line)
  case "$line" in *"$1"*) ok "$2"; return 0 ;; esac
  bad "$2" "configure line lacks '$1'; got: $line"
}
# Exact comparison, not substring: `build` is a prefix of `build-vulkan`, so a
# substring check cannot tell the two directories apart.
expect_build_dir() { # expected label
  local got; got=$(printf '%s\n' "$OUT" | sed -n 's/^.*build dir: //p' | head -1)
  [ "$got" = "$1" ] && { ok "$2"; return 0; }
  bad "$2" "expected build dir '$1', got '$got'"
}

# ── 1. help is the cheapest contract: it must not need a toolchain ───────────
run_build --help
expect_rc 0 "--help exits 0"
expect_out "usage:" "--help prints usage"

# ── 2. an unknown option is an error, never a silently ignored flag ──────────
run_build --not-a-flag
expect_rc_nonzero "unknown option exits non-zero"
expect_out "unknown option" "unknown option names itself"

# ── 3. --jobs must reject a non-number instead of passing it to make ─────────
run_build --dry-run --jobs banana
expect_rc_nonzero "--jobs rejects a non-numeric value"
expect_out "--jobs" "--jobs error names the option"

run_build --dry-run --jobs 0
expect_rc_nonzero "--jobs rejects 0"

# ── 4. the CPU default: Hexagon off, Vulkan off, examples on ────────────────
run_build --dry-run
expect_rc 0 "dry run exits 0"
expect_configure_flag "-DGGML_HEXAGON=OFF" "CPU build configures GGML_HEXAGON=OFF"
expect_configure_flag "-DSD_VULKAN=OFF" "CPU build configures SD_VULKAN=OFF"
expect_configure_flag "-DSD_BUILD_EXAMPLES=ON" "CPU build configures SD_BUILD_EXAMPLES=ON"
expect_configure_flag "-DCMAKE_BUILD_TYPE=Release" "CPU build configures Release"
expect_out "build dir: $HERE/build" "CPU build dir is build/"
expect_out "cmake --build $HERE/build -j8" "CPU build defaults to -j8"
expect_no_out "-DSD_VULKAN=ON" "CPU dry run never turns Vulkan on"

# ── 5. --vulkan gets its own directory so the CPU tree survives ─────────────
run_build --dry-run --vulkan
expect_rc 0 "vulkan dry run exits 0"
expect_configure_flag "-DSD_VULKAN=ON" "vulkan build configures SD_VULKAN=ON"
expect_configure_flag "-DGGML_VULKAN=ON" "vulkan build configures GGML_VULKAN=ON"
expect_configure_flag "-DGGML_HEXAGON=OFF" "vulkan build still has Hexagon off"
expect_build_dir "$HERE/build-vulkan" "vulkan build dir is build-vulkan/"
expect_out "mode: vulkan" "vulkan dry run says which mode it is"

# ── 6. --jobs N reaches cmake --build ──────────────────────────────────────
run_build --dry-run --jobs 3
expect_rc 0 "--jobs 3 dry run exits 0"
expect_out "cmake --build $HERE/build -j3" "--jobs 3 becomes -j3"

# ── 7. --clean removes the build directory, and only in dry-run talks about it
run_build --dry-run --clean
expect_rc 0 "--clean dry run exits 0"
expect_out "clean: $HERE/build" "--clean names the directory it would remove"

# ── 7b. --cmake-arg reaches the configure step verbatim ────────────────────
# This is how the Vulkan backend gets its SPIRV-Headers include path on a box
# without root; if the passthrough silently dropped the argument, the build
# would fail with an error that points at ggml instead of at build.sh.
run_build --dry-run --vulkan --cmake-arg "-DCMAKE_PREFIX_PATH=/opt/spirv" \
                                  --cmake-arg "-DCMAKE_CXX_FLAGS=-I/opt/spirv/include"
expect_rc 0 "--cmake-arg dry run exits 0"
expect_configure_flag "-DCMAKE_PREFIX_PATH=/opt/spirv" "--cmake-arg passes the first argument through"
expect_configure_flag "-DCMAKE_CXX_FLAGS=-I/opt/spirv/include" "--cmake-arg passes the second argument through"
expect_configure_flag "-DSD_VULKAN=ON" "--cmake-arg does not displace the fixed flags"

run_build --dry-run --cmake-arg
expect_rc_nonzero "--cmake-arg without a value exits non-zero"

# ── 8. patches are opt-in, and a missing patch is an error ─────────────────
run_build --dry-run --patch "$WORK/nope.patch"
expect_rc_nonzero "--patch with a missing file exits non-zero"
expect_out "patch file not found" "--patch names the missing file"

# ── 9. a real patch is fingerprinted and routed through apply.sh ───────────
# The fingerprint is the point: a build log has to say WHICH bytes of a patch
# produced the binary, because the patch set moves and the SHA alone does not
# pin it.
printf 'diff --git a/x b/x\n' >"$WORK/001-toy.sdcpp.patch"
run_build --dry-run --patch "$WORK/001-toy.sdcpp.patch"
expect_rc 0 "--patch dry run exits 0"
expect_out "patch: $WORK/001-toy.sdcpp.patch" "--patch echoes the patch path"
expect_out "sha256: " "--patch prints the patch's sha256"
expect_out "apply.sh" "--patch routes through backend-patches/apply.sh"
expect_out "cmake -S" "a dry run still shows the configure step it would run"
[ -z "$(git -C "$HERE/sdcpp" status --porcelain 2>/dev/null)" ] \
  && ok "dry run leaves the engine checkout untouched" \
  || bad "dry run leaves the engine checkout untouched" "sdcpp is dirty after a dry run"

# ── 10. a submodule that was never initialised is a clear error, not a cmake
#         stack trace about a missing CMakeLists.txt ────────────────────────
if [ -f "$HERE/sdcpp/CMakeLists.txt" ]; then
  ok "engine checkout present (submodule init check not exercised)"
else
  run_build --dry-run
  expect_rc_nonzero "missing engine checkout exits non-zero"
  expect_out "git submodule update" "missing engine checkout says how to fix it"
fi

printf '\n%d passed, %d failed\n' "$pass" "$fails"

# ── 11. no dry run above may have written anything ─────────────────────────
after_tree=$(ls -a "$HERE" | sort)
[ "$before_tree" = "$after_tree" ] \
  && ok "no dry run changed the engine directory" \
  || bad "no dry run changed the engine directory" "before: $(printf '%s' "$before_tree" | tr '\n' ' ')
after:  $(printf '%s' "$after_tree" | tr '\n' ' ')"

[ "$fails" -eq 0 ]
