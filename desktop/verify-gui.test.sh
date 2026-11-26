#!/usr/bin/env bash
# Bounded shell assertions for desktop/verify-gui.sh.
#
# The seam under test is the script's *external behaviour*: exit code, the log
# files it leaves behind, what it writes into them, and which Gradle commands
# it issues. A stub gradlew replaces the real build so every case finishes in
# seconds and no test depends on Gradle, the network or the reference machine's
# caches. Run with: timeout 120 desktop/verify-gui.test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$ROOT/verify-gui.sh"
TMP="$(mktemp -d /tmp/verify-gui-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

ok() {
  printf 'ok - %s\n' "$1"
  pass=$((pass + 1))
}

not_ok() {
  printf 'not ok - %s\n' "$1" >&2
  fail=$((fail + 1))
}

assert_eq() {
  local what="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    ok "$what (expected $expected)"
  else
    not_ok "$what: expected [$expected], got [$actual]"
  fi
}

assert_contains() {
  local what="$1" file="$2" needle="$3"
  if [ -f "$file" ] && grep -Fq -- "$needle" "$file"; then
    ok "$what contains: $needle"
  else
    not_ok "$what missing: $needle (file: $file)"
  fi
}

assert_not_contains() {
  local what="$1" file="$2" needle="$3"
  if [ ! -f "$file" ]; then
    not_ok "$what: missing file $file"
  elif ! grep -Fq -- "$needle" "$file"; then
    ok "$what does not contain: $needle"
  else
    not_ok "$what unexpectedly contains: $needle (file: $file)"
  fi
}

assert_has_logs() {
  local dir="$1"
  shift
  local name
  for name in "$@"; do
    if [ -s "$dir/$name" ]; then
      ok "log exists and is non-empty: $name"
    else
      not_ok "missing or empty log: $dir/$name"
    fi
  done
}

# The order of Gradle calls is externally observable in the stub call log: it
# proves chat-turn is inserted after fake-runner and before persistence.
assert_called_after() {
  local file="$1" earlier="$2" later="$3"
  local earlier_line later_line
  earlier_line=$(grep -Fn -- "$earlier" "$file" | head -n 1 | cut -d: -f1)
  later_line=$(grep -Fn -- "$later" "$file" | head -n 1 | cut -d: -f1)
  if [ -n "$earlier_line" ] && [ -n "$later_line" ] && [ "$later_line" -gt "$earlier_line" ]; then
    ok "called in order: $earlier before $later"
  else
    not_ok "expected $earlier before $later (lines: ${earlier_line:-missing}/${later_line:-missing})"
  fi
}

# ---- stub gradlew ----------------------------------------------------------

STUB_CALLS="$TMP/gradle-calls.txt"
export STUB_CALLS
: > "$STUB_CALLS"
STUB="$TMP/gradlew"
cat > "$STUB" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_CALLS"
printf 'stub gradle invoked: %s\n' "$*"
if [ -n "${STUB_FAIL_ON:-}" ]; then
  case "$*" in
    *"$STUB_FAIL_ON"*)
      printf 'stub forced failure on %s\n' "$STUB_FAIL_ON" >&2
      exit 7
      ;;
  esac
fi
if [ -n "${STUB_HANG_ON:-}" ]; then
  case "$*" in
    *"$STUB_HANG_ON"*) sleep 30 ;;
  esac
fi
exit 0
EOF
chmod +x "$STUB"

# ---- case 1: no weight cache -> smoke skipped, everything else runs -------

CASE_LOGS="$TMP/logs-skip"
CASE_OUT="$TMP/out-skip"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
NIGHTMARE_MODELS="$TMP/no-such-models" \
NIGHTMARE_SD_BIN="$TMP/no-such-engine" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "no-cache run exits 0" 0 "$rc"
assert_has_logs "$CASE_LOGS" \
  verify-gui-classes.log verify-gui-build.log verify-gui-fake-runner.log \
  verify-gui-chat-turn.log verify-gui-persistence.log verify-gui-real-smoke.log
assert_contains "skip reason" "$CASE_LOGS/verify-gui-real-smoke.log" "已跳过（无权重缓存）"
assert_contains "classes step" "$STUB_CALLS" ":gui:classes"
assert_contains "build step" "$STUB_CALLS" " build"
assert_contains "fake runner: WorkflowRunnerTest" "$STUB_CALLS" "WorkflowRunnerTest"
assert_contains "fake runner: ToolInterfaceTest" "$STUB_CALLS" "ToolInterfaceTest"
assert_contains "fake runner: DefaultModeStateTest" "$STUB_CALLS" "DefaultModeStateTest"
assert_contains "fake runner: AdvancedModeStateTest" "$STUB_CALLS" "AdvancedModeStateTest"
assert_contains "chat turn: fake ChatModel full workflow" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.fake.FakeChatModelWorkflowTest"
assert_contains "chat turn: one chat turn" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.ChatSessionTest"
assert_contains "chat turn: Ask answer path" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.AskSessionTest"
assert_contains "chat turn: cancel/window-close path" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.TurnLifecycleTest"
assert_called_after "$STUB_CALLS" "WorkflowRunnerTest" "FakeChatModelWorkflowTest"
assert_called_after "$STUB_CALLS" "TurnLifecycleTest" "WorkflowPersistenceTest"
assert_contains "chat turn step summary" "$CASE_LOGS/verify-gui-chat-turn.log" "verify-gui: chat-turn OK"
assert_contains "persistence test" "$STUB_CALLS" "WorkflowPersistenceTest"
assert_not_contains "real smoke must not run without weights" "$STUB_CALLS" "Img2ImgRealE2ETest"
assert_contains "pass summary" "$CASE_OUT" "verify-gui: PASS"

# ---- case 2: a failing step -> non-zero, logs retained --------------------

CASE_LOGS="$TMP/logs-fail"
CASE_OUT="$TMP/out-fail"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
STUB_FAIL_ON=" build" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

if [ "$rc" -ne 0 ]; then
  ok "failing step makes the script exit non-zero (rc=$rc)"
else
  not_ok "failing step must make the script exit non-zero"
fi
assert_has_logs "$CASE_LOGS" verify-gui-classes.log verify-gui-build.log
assert_contains "failure captured in log" "$CASE_LOGS/verify-gui-build.log" "stub forced failure"
assert_contains "failure summary on stdout" "$CASE_OUT" "verify-gui: FAILED"
assert_contains "log location on failure" "$CASE_OUT" "$CASE_LOGS"
assert_not_contains "no pass summary on failure" "$CASE_OUT" "verify-gui: PASS"
assert_not_contains "fail fast: no fake runner after build failure" "$STUB_CALLS" "WorkflowRunnerTest"
assert_not_contains "fail fast: no chat turn after build failure" "$STUB_CALLS" "FakeChatModelWorkflowTest"

# ---- case 3: weights + engine present -> real smoke runs ------------------

MODELS="$TMP/models"
mkdir -p "$MODELS/sd15"
: > "$MODELS/sd15/v1-5-pruned-emaonly.safetensors"
ENGINE="$TMP/engine/sd-cli"
mkdir -p "$TMP/engine"
printf '#!/bin/sh\nexit 0\n' > "$ENGINE"
chmod +x "$ENGINE"

CASE_LOGS="$TMP/logs-smoke"
CASE_OUT="$TMP/out-smoke"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
NIGHTMARE_MODELS="$MODELS" \
NIGHTMARE_SD_BIN="$ENGINE" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "with-cache run exits 0" 0 "$rc"
assert_contains "real smoke runs with weights" "$STUB_CALLS" "Img2ImgRealE2ETest"
assert_not_contains "no skip reason with weights" "$CASE_LOGS/verify-gui-real-smoke.log" "已跳过（无权重缓存）"
assert_contains "smoke log exists" "$CASE_LOGS/verify-gui-real-smoke.log" "verify-gui: real-smoke"

# ---- case 4: weights present but no engine -> explicit skip ---------------

CASE_LOGS="$TMP/logs-no-engine"
CASE_OUT="$TMP/out-no-engine"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
NIGHTMARE_MODELS="$MODELS" \
NIGHTMARE_SD_BIN="$TMP/no-such-engine" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "no-engine run exits 0" 0 "$rc"
assert_contains "engine skip reason" "$CASE_LOGS/verify-gui-real-smoke.log" "已跳过（无引擎二进制）"
assert_not_contains "real smoke must not run without engine" "$STUB_CALLS" "Img2ImgRealE2ETest"

# ---- case 5: display handling for the opt-in GUI launch step --------------

# 5a: an existing DISPLAY is used directly.
CASE_LOGS="$TMP/logs-display"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
DISPLAY=:0 \
VERIFY_GUI_LAUNCH=1 \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
timeout 30 bash "$SCRIPT" > "$TMP/out-display" 2>&1
rc=$?
assert_eq "launch step with DISPLAY exits 0" 0 "$rc"
assert_contains "launch log created" "$CASE_LOGS/verify-gui-launch.log" "DISPLAY=:0"
assert_contains "gui:run issued" "$STUB_CALLS" ":gui:run"

# 5b: no DISPLAY but xvfb-run available -> wrap with xvfb-run -a.
FAKE_BIN="$TMP/bin"
mkdir -p "$FAKE_BIN"
printf '#!/usr/bin/env bash\nif [ "${1:-}" = "-a" ]; then shift; fi\nexec "$@"\n' > "$FAKE_BIN/xvfb-run"
chmod +x "$FAKE_BIN/xvfb-run"
CASE_LOGS="$TMP/logs-xvfb"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
env -u DISPLAY \
PATH="$FAKE_BIN:$PATH" \
VERIFY_GUI_LAUNCH=1 \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
timeout 30 bash "$SCRIPT" > "$TMP/out-xvfb" 2>&1
rc=$?
assert_eq "launch step with xvfb-run exits 0" 0 "$rc"
assert_contains "xvfb wrapper announced" "$CASE_LOGS/verify-gui-launch.log" "xvfb-run -a"

# 5c: neither DISPLAY nor xvfb-run -> clear skip, still exit 0.
CASE_LOGS="$TMP/logs-no-display"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
env -u DISPLAY \
PATH="/usr/bin:/bin" \
VERIFY_GUI_LAUNCH=1 \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
timeout 30 bash "$SCRIPT" > "$TMP/out-no-display" 2>&1
rc=$?
assert_eq "launch step with no display exits 0" 0 "$rc"
assert_contains "no-display skip reason" "$CASE_LOGS/verify-gui-launch.log" "已跳过（无显示且无 xvfb-run）"
assert_not_contains "no gui:run without a display" "$STUB_CALLS" ":gui:run"

# ---- case 6: a failing chat-turn step stops before persistence -----------

CASE_LOGS="$TMP/logs-chat-turn-fail"
CASE_OUT="$TMP/out-chat-turn-fail"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
STUB_FAIL_ON="FakeChatModelWorkflowTest" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "chat-turn failure exits with the stub code" 7 "$rc"
assert_has_logs "$CASE_LOGS" verify-gui-chat-turn.log
assert_contains "chat-turn failure captured in its log" "$CASE_LOGS/verify-gui-chat-turn.log" "stub forced failure"
assert_contains "chat-turn failure named on stdout" "$CASE_OUT" "verify-gui: FAILED at chat-turn"
assert_not_contains "no persistence after chat-turn failure" "$STUB_CALLS" "WorkflowPersistenceTest"
assert_not_contains "no pass summary after chat-turn failure" "$CASE_OUT" "verify-gui: PASS"

# ---- case 7: chat-turn is bounded by a hard timeout -----------------------

CASE_LOGS="$TMP/logs-chat-turn-timeout"
CASE_OUT="$TMP/out-chat-turn-timeout"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
STUB_HANG_ON="FakeChatModelWorkflowTest" \
VERIFY_GUI_CHAT_TURN_TIMEOUT=1 \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "a hung chat-turn is killed by its hard timeout" 124 "$rc"
assert_contains "timeout failure recorded in chat-turn log" "$CASE_LOGS/verify-gui-chat-turn.log" "verify-gui: FAILED at chat-turn (exit 124)"
assert_not_contains "no persistence after chat-turn timeout" "$STUB_CALLS" "WorkflowPersistenceTest"

# ---- summary --------------------------------------------------------------

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
