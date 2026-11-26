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

assert_exit_code() {
  local what="$1" expected="$2" actual="$3"
  if [ "$expected" -eq "$actual" ]; then
    ok "$what (expected exit $expected)"
  else
    not_ok "$what: expected exit $expected, got $actual"
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
# Record the engine env the real-smoke JVM test would see, so selection can be
# asserted without a GPU or a real engine.
STUB_SMOKE_ENGINE="$TMP/smoke-engine.txt"
export STUB_SMOKE_ENGINE
: > "$STUB_SMOKE_ENGINE"
STUB="$TMP/gradlew"
cat > "$STUB" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_CALLS"
case "$*" in
  *Img2ImgRealE2ETest*)
    printf '%s\n' "${NIGHTMARE_SD_BIN:-<unset>}" >> "$STUB_SMOKE_ENGINE"
    ;;
esac
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

# Cases 1 and 4 deliberately inject a non-executable engine to exercise the
# smoke skip branch. Those cases are about the smoke step, not preflight
# validation, so they substitute a pass-through preflight. Cases that DO test
# preflight use the real script.
PASS_PREFLIGHT="$TMP/preflight-pass-stub"
printf '#!/usr/bin/env bash\nexit 0\n' > "$PASS_PREFLIGHT"
chmod +x "$PASS_PREFLIGHT"

# ---- case 1: no weight cache -> smoke skipped, everything else runs -------

CASE_LOGS="$TMP/logs-skip"
CASE_OUT="$TMP/out-skip"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
NIGHTMARE_MODELS="$TMP/no-such-models" \
NIGHTMARE_SD_BIN="$TMP/no-such-engine" \
VERIFY_GUI_PREFLIGHT="$PASS_PREFLIGHT" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "no-cache run exits 0" 0 "$rc"
assert_has_logs "$CASE_LOGS" \
  verify-gui-classes.log verify-gui-launch-config.log verify-gui-build.log \
  verify-gui-fake-runner.log verify-gui-chat-turn.log verify-gui-img2img.log \
  verify-gui-persistence.log verify-gui-real-smoke.log
assert_contains "launch-config runs by default" "$CASE_LOGS/verify-gui-launch-config.log" "verify-gui: launch-config OK"
assert_not_contains "default run does not force --no-build-cache" "$STUB_CALLS" "--no-build-cache"
assert_not_contains "default run does not force --rerun-tasks" "$STUB_CALLS" "--rerun-tasks"
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
assert_called_after "$STUB_CALLS" "TurnLifecycleTest" "DesktopToolCommandsTest"
assert_called_after "$STUB_CALLS" "DesktopToolCommandsTest" "WorkflowPersistenceTest"
assert_contains "chat turn step summary" "$CASE_LOGS/verify-gui-chat-turn.log" "verify-gui: chat-turn OK"
# The img2img step names the classes that assert the fake-path contract:
# the runner projection (--init-img/--strength, PNG into results), the
# one-sentence chat turn (Picture bubble + run snapshot), and the tool schema
# that carries the image node's path/strength. DesktopToolCommandsTest appears
# only in this step, so it also marks the order against chat-turn/persistence.
assert_contains "img2img: runner projection into --init-img/--strength" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.WorkflowRunnerTest"
assert_contains "img2img: one chat sentence drives Picture bubble and snapshot" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.ChatSessionTest"
assert_contains "img2img: tool schema carries path/strength" "$STUB_CALLS" "com.abrah.nightmare.desktop.gui.DesktopToolCommandsTest"
assert_contains "img2img step summary" "$CASE_LOGS/verify-gui-img2img.log" "verify-gui: img2img OK"
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
assert_has_logs "$CASE_LOGS" verify-gui-classes.log verify-gui-launch-config.log verify-gui-build.log
assert_contains "failure captured in log" "$CASE_LOGS/verify-gui-build.log" "stub forced failure"
assert_contains "failure summary on stdout" "$CASE_OUT" "verify-gui: FAILED"
assert_contains "log location on failure" "$CASE_OUT" "$CASE_LOGS"
assert_not_contains "no pass summary on failure" "$CASE_OUT" "verify-gui: PASS"
assert_not_contains "fail fast: no fake runner after build failure" "$STUB_CALLS" "WorkflowRunnerTest"
assert_not_contains "fail fast: no chat turn after build failure" "$STUB_CALLS" "FakeChatModelWorkflowTest"
assert_not_contains "fail fast: no img2img after build failure" "$STUB_CALLS" "DesktopToolCommandsTest"

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
VERIFY_GUI_PREFLIGHT="$PASS_PREFLIGHT" \
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
assert_not_contains "no img2img after chat-turn failure" "$STUB_CALLS" "DesktopToolCommandsTest"
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
assert_not_contains "no img2img after chat-turn timeout" "$STUB_CALLS" "DesktopToolCommandsTest"
assert_not_contains "no persistence after chat-turn timeout" "$STUB_CALLS" "WorkflowPersistenceTest"

# ---- case 7a: a failing img2img step stops before persistence -------------

CASE_LOGS="$TMP/logs-img2img-fail"
CASE_OUT="$TMP/out-img2img-fail"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
STUB_FAIL_ON="DesktopToolCommandsTest" \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "img2img failure exits with the stub code" 7 "$rc"
assert_has_logs "$CASE_LOGS" verify-gui-img2img.log
assert_contains "img2img failure captured in its log" "$CASE_LOGS/verify-gui-img2img.log" "stub forced failure"
assert_contains "img2img failure named on stdout" "$CASE_OUT" "verify-gui: FAILED at img2img"
assert_not_contains "no persistence after img2img failure" "$STUB_CALLS" "WorkflowPersistenceTest"
assert_not_contains "no pass summary after img2img failure" "$CASE_OUT" "verify-gui: PASS"

# ---- case 7b: img2img is bounded by its own hard timeout ------------------

CASE_LOGS="$TMP/logs-img2img-timeout"
CASE_OUT="$TMP/out-img2img-timeout"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
STUB_HANG_ON="DesktopToolCommandsTest" \
VERIFY_GUI_IMG2IMG_TIMEOUT=1 \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "a hung img2img is killed by its hard timeout" 124 "$rc"
assert_contains "timeout failure recorded in img2img log" "$CASE_LOGS/verify-gui-img2img.log" "verify-gui: FAILED at img2img (exit 124)"
assert_not_contains "no persistence after img2img timeout" "$STUB_CALLS" "WorkflowPersistenceTest"

# ---- case 8: no explicit engine -> real-smoke uses the Vulkan wrapper ------

FAKE_VULKAN="$TMP/fake-vulkan"
printf '#!/bin/sh\nexit 0\n' > "$FAKE_VULKAN"
chmod +x "$FAKE_VULKAN"

CASE_LOGS="$TMP/logs-vulkan"
CASE_OUT="$TMP/out-vulkan"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
: > "$STUB_SMOKE_ENGINE"
env -u NIGHTMARE_SD_BIN \
VERIFY_GUI_VULKAN_BIN="$FAKE_VULKAN" \
NIGHTMARE_MODELS="$MODELS" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "vulkan-default run exits 0" 0 "$rc"
assert_contains "real smoke runs under the Vulkan default" "$STUB_CALLS" "Img2ImgRealE2ETest"
assert_contains "Vulkan choice is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "默认走 Vulkan 加速"
assert_contains "VRAM override is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "--max-vram 0"
assert_contains "CPU offload is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "--offload-to-cpu"
WRAPPER="$CASE_LOGS/sd-cli-vulkan-wrapper.sh"
if [ -x "$WRAPPER" ]; then
  ok "Vulkan wrapper exists and is executable"
else
  not_ok "Vulkan wrapper missing or not executable: $WRAPPER"
fi
assert_contains "wrapper injects --max-vram 0" "$WRAPPER" "--max-vram 0"
assert_contains "wrapper injects --offload-to-cpu" "$WRAPPER" "--offload-to-cpu"
assert_contains "wrapper targets the injected Vulkan binary" "$WRAPPER" "$FAKE_VULKAN"
assert_contains "cli test receives the wrapper path" "$STUB_SMOKE_ENGINE" "$WRAPPER"
assert_contains "vulkan-default run passes" "$CASE_OUT" "verify-gui: PASS"

# ---- case 9: an explicit NIGHTMARE_SD_BIN is never overridden -------------

CASE_LOGS="$TMP/logs-explicit-engine"
CASE_OUT="$TMP/out-explicit-engine"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
: > "$STUB_SMOKE_ENGINE"
NIGHTMARE_SD_BIN="$ENGINE" \
VERIFY_GUI_VULKAN_BIN="$FAKE_VULKAN" \
NIGHTMARE_MODELS="$MODELS" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "explicit-engine run exits 0" 0 "$rc"
assert_contains "explicit choice is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "调用方显式指定的引擎"
assert_not_contains "no Vulkan selection with an explicit engine" "$CASE_LOGS/verify-gui-real-smoke.log" "Vulkan"
assert_contains "cli test receives the explicit engine" "$STUB_SMOKE_ENGINE" "$ENGINE"
assert_contains "real smoke still runs with an explicit engine" "$STUB_CALLS" "Img2ImgRealE2ETest"
if [ -e "$CASE_LOGS/sd-cli-vulkan-wrapper.sh" ]; then
  not_ok "no wrapper should be created for an explicit engine"
else
  ok "no wrapper created for an explicit engine"
fi

# ---- case 10: missing Vulkan binary falls back to the CPU engine ----------

CASE_LOGS="$TMP/logs-vulkan-fallback"
CASE_OUT="$TMP/out-vulkan-fallback"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
: > "$STUB_SMOKE_ENGINE"
env -u NIGHTMARE_SD_BIN \
VERIFY_GUI_VULKAN_BIN="$TMP/no-such-vulkan" \
NIGHTMARE_MODELS="$MODELS" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "vulkan-fallback run exits 0" 0 "$rc"
assert_contains "fallback reason is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "回退 CPU 引擎"
assert_not_contains "no Vulkan selection when the binary is absent" "$CASE_LOGS/verify-gui-real-smoke.log" "默认走 Vulkan 加速"
CPU_BIN="$HOME/.cache/nightmare-desktop/engine/sd-cli"
if [ -x "$CPU_BIN" ]; then
  assert_contains "cli test receives the CPU engine" "$STUB_SMOKE_ENGINE" "$CPU_BIN"
else
  assert_contains "CPU engine absent is a documented skip" "$CASE_LOGS/verify-gui-real-smoke.log" "已跳过（无引擎二进制）"
fi

# ---- case 11: wrapper creation failure falls back to the CPU engine -------

CASE_LOGS="$TMP/logs-wrapper-fail"
CASE_OUT="$TMP/out-wrapper-fail"
mkdir -p "$CASE_LOGS/sd-cli-vulkan-wrapper.sh" # a directory blocks the write
: > "$STUB_CALLS"
: > "$STUB_SMOKE_ENGINE"
env -u NIGHTMARE_SD_BIN \
VERIFY_GUI_VULKAN_BIN="$FAKE_VULKAN" \
NIGHTMARE_MODELS="$MODELS" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "wrapper-failure run exits 0" 0 "$rc"
assert_contains "wrapper failure is logged" "$CASE_LOGS/verify-gui-real-smoke.log" "wrapper 创建失败，回退 CPU 引擎"
assert_not_contains "no Vulkan selection when the wrapper fails" "$CASE_LOGS/verify-gui-real-smoke.log" "默认走 Vulkan 加速"

# ---- case 12: preflight-gui-launch.sh validates manual launch config -------

# The preflight seam is the script's own CLI: env vars in, exit code + text out.
# It must never need Gradle, the network, or a display. Wipe every relevant
# variable first so an exported caller environment cannot change a case.
PREFLIGHT="$ROOT/preflight-gui-launch.sh"
PF_UNSET=(env -u NIGHTMARE_GUI_CLI -u NIGHTMARE_GUI_MODEL -u NIGHTMARE_SD_BIN
  -u NIGHTMARE_LLM_PROVIDER -u NIGHTMARE_LLM_BASE_URL -u NIGHTMARE_LLM_KEY)

# 12a: no manual config -> documented skip, exit 0 so headless CI stays green.
PF_OUT="$TMP/preflight-none"
"${PF_UNSET[@]}" timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
assert_exit_code "preflight with no manual config exits 0" 0 "$rc"
assert_contains "preflight skip is documented" "$PF_OUT" "未设置手动启动配置，preflight 跳过"

# 12b: an injected GUI CLI path must be an executable installDist start script.
PF_OUT="$TMP/preflight-bad-cli"
"${PF_UNSET[@]}" NIGHTMARE_GUI_CLI="$TMP/no-such-cli" \
  timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  ok "bad NIGHTMARE_GUI_CLI exits non-zero (rc=$rc)"
else
  not_ok "bad NIGHTMARE_GUI_CLI must exit non-zero"
fi
assert_contains "preflight FAIL marker on bad CLI" "$PF_OUT" "preflight: FAIL"
assert_contains "preflight names the offending var" "$PF_OUT" "NIGHTMARE_GUI_CLI"
assert_contains "preflight points to the installDist script" "$PF_OUT" ":cli:installDist"
assert_contains "preflight warns the CLI is not the engine" "$PF_OUT" "不是引擎"

# 12c: a fully valid manual config (local CUSTOM LLM, no key) passes, and the
# key value is never echoed even when the caller exports one.
VALID_CLI="$TMP/valid-cli"
VALID_ENGINE="$TMP/valid-engine"
printf '#!/bin/sh\nexit 0\n' > "$VALID_CLI"; chmod +x "$VALID_CLI"
printf '#!/bin/sh\nexit 0\n' > "$VALID_ENGINE"; chmod +x "$VALID_ENGINE"
VALID_MODEL="$TMP/valid-model.safetensors"; : > "$VALID_MODEL"
PF_OUT="$TMP/preflight-ok"
"${PF_UNSET[@]}" \
  NIGHTMARE_GUI_CLI="$VALID_CLI" \
  NIGHTMARE_SD_BIN="$VALID_ENGINE" \
  NIGHTMARE_GUI_MODEL="$VALID_MODEL" \
  NIGHTMARE_LLM_PROVIDER=CUSTOM \
  NIGHTMARE_LLM_BASE_URL="http://127.0.0.1:13305/v1" \
  NIGHTMARE_LLM_KEY="SECRET_SENTINEL_DO_NOT_PRINT" \
  timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
assert_exit_code "preflight with a valid local config exits 0" 0 "$rc"
assert_contains "preflight PASS marker" "$PF_OUT" "preflight: PASS"
assert_not_contains "preflight never prints the key value" "$PF_OUT" "SECRET_SENTINEL_DO_NOT_PRINT"

# 12d: CUSTOM provider without a base URL is a clear, separate failure.
PF_OUT="$TMP/preflight-no-url"
"${PF_UNSET[@]}" NIGHTMARE_GUI_CLI="$VALID_CLI" \
  NIGHTMARE_LLM_PROVIDER=CUSTOM \
  timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  ok "CUSTOM without a base URL exits non-zero (rc=$rc)"
else
  not_ok "CUSTOM without a base URL must exit non-zero"
fi
assert_contains "preflight names the missing base URL" "$PF_OUT" "NIGHTMARE_LLM_BASE_URL"

# 12e: a non-executable engine and a missing weights file each fail with the
# exact variable named, so the caller can fix one thing at a time.
PF_OUT="$TMP/preflight-bad-engine"
"${PF_UNSET[@]}" NIGHTMARE_SD_BIN="$TMP/no-such-engine" \
  timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  ok "non-executable NIGHTMARE_SD_BIN exits non-zero (rc=$rc)"
else
  not_ok "non-executable NIGHTMARE_SD_BIN must exit non-zero"
fi
assert_contains "preflight names the bad engine var" "$PF_OUT" "NIGHTMARE_SD_BIN"

PF_OUT="$TMP/preflight-bad-model"
"${PF_UNSET[@]}" NIGHTMARE_GUI_MODEL="$TMP/no-such-model.safetensors" \
  timeout 30 bash "$PREFLIGHT" > "$PF_OUT" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  ok "missing NIGHTMARE_GUI_MODEL exits non-zero (rc=$rc)"
else
  not_ok "missing NIGHTMARE_GUI_MODEL must exit non-zero"
fi
assert_contains "preflight names the bad model var" "$PF_OUT" "NIGHTMARE_GUI_MODEL"

# ---- case 13: VERIFY_GUI_NO_CACHE=1 forces every build step to re-run ------

CASE_LOGS="$TMP/logs-no-cache"
CASE_OUT="$TMP/out-no-cache"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
VERIFY_GUI_NO_CACHE=1 \
NIGHTMARE_MODELS="$TMP/no-such-models" \
VERIFY_GUI_GRADLE="$STUB" \
VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

assert_eq "VERIFY_GUI_NO_CACHE=1 run exits 0" 0 "$rc"
assert_contains "no-cache appends --no-build-cache" "$STUB_CALLS" "--no-build-cache"
assert_contains "no-cache appends --rerun-tasks" "$STUB_CALLS" "--rerun-tasks"
assert_contains "no-cache run still passes" "$CASE_OUT" "verify-gui: PASS"

# ---- case 14: an injected bad GUI CLI fails at launch-config, then stops --

CASE_LOGS="$TMP/logs-launch-config-fail"
CASE_OUT="$TMP/out-launch-config-fail"
mkdir -p "$CASE_LOGS"
: > "$STUB_CALLS"
"${PF_UNSET[@]}" NIGHTMARE_GUI_CLI="$TMP/no-such-cli" \
  NIGHTMARE_MODELS="$TMP/no-such-models" \
  VERIFY_GUI_GRADLE="$STUB" \
  VERIFY_GUI_LOG_DIR="$CASE_LOGS" \
  timeout 30 bash "$SCRIPT" > "$CASE_OUT" 2>&1
rc=$?

if [ "$rc" -ne 0 ]; then
  ok "bad injected GUI CLI makes the script exit non-zero (rc=$rc)"
else
  not_ok "bad injected GUI CLI must make the script exit non-zero"
fi
assert_contains "failure is named as launch-config" "$CASE_OUT" "verify-gui: FAILED at launch-config"
assert_contains "preflight detail is in its own log" "$CASE_LOGS/verify-gui-launch-config.log" "NIGHTMARE_GUI_CLI"
assert_not_contains "fail fast: no build after launch-config failure" "$STUB_CALLS" " build"
assert_not_contains "no pass summary after launch-config failure" "$CASE_OUT" "verify-gui: PASS"

# ---- summary --------------------------------------------------------------

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
