#!/usr/bin/env bash
# Phase 2 GUI acceptance in one bounded command (ADR-0011).
#
# Steps, in order: :gui:classes -> full build (all JVM tests) -> the fake CLI
# runner end-to-end tests -> chat turns (fake ChatModel + fake CLI, including
# Ask answer/cancel) -> workflow persistence -> optional real SD1.5 smoke
# -> optional GUI launch. Every step has a hard timeout and an appended log in
# VERIFY_GUI_LOG_DIR (default /tmp); any non-zero exit stops the run and leaves
# all logs in place. Missing weights skip the real smoke without failing.
#
# Overrides (mostly for CI/test harnesses; see desktop/README.md):
#   VERIFY_GUI_GRADLE      gradle launcher (default ./gradlew)
#   VERIFY_GUI_LOG_DIR     log directory (default /tmp)
#   VERIFY_GUI_CHAT_TURN_TIMEOUT  seconds for chat-turn (default 600)
#   VERIFY_GUI_SMOKE_TIMEOUT  seconds for the real smoke (default 1800)
#   VERIFY_GUI_LAUNCH=1    also launch the GUI once (uses DISPLAY or xvfb-run)
#   NIGHTMARE_MODELS       model cache root (default ~/.cache/nightmare-desktop/models)
#   NIGHTMARE_SD_BIN       engine binary (default ~/.cache/nightmare-desktop/engine/sd-cli)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE" || exit 1

# JAVA_HOME is not on PATH on the reference machine; Gradle only needs the
# variable. Do not overwrite an explicit setting from the caller.
if [ -z "${JAVA_HOME:-}" ] && [ -d /home/chjlxxc/Android/jdk-17 ]; then
  export JAVA_HOME=/home/chjlxxc/Android/jdk-17
fi

GRADLE="${VERIFY_GUI_GRADLE:-./gradlew}"
LOG_DIR="${VERIFY_GUI_LOG_DIR:-/tmp}"
CHAT_TURN_TIMEOUT="${VERIFY_GUI_CHAT_TURN_TIMEOUT:-600}"
SMOKE_TIMEOUT="${VERIFY_GUI_SMOKE_TIMEOUT:-1800}"
MODELS_ROOT="${NIGHTMARE_MODELS:-$HOME/.cache/nightmare-desktop/models}"
MODEL_FILE="$MODELS_ROOT/sd15/v1-5-pruned-emaonly.safetensors"
ENGINE_BIN="${NIGHTMARE_SD_BIN:-$HOME/.cache/nightmare-desktop/engine/sd-cli}"

mkdir -p "$LOG_DIR" || exit 1

# Run one step under a hard timeout, mirroring stdout+stderr into its log.
# PIPESTATUS[0] is the command's status, not tee's. ALLOW_TIMEOUT_RC=1 lets the
# optional GUI launch treat "still up when the clock ran out" as success.
run_step() {
  local label="$1" timeout_s="$2" log="$3"
  shift 3
  printf 'verify-gui: step %s (timeout %ss) -> %s\n' "$label" "$timeout_s" "$log" | tee -a "$log"
  timeout "$timeout_s" "$@" 2>&1 | tee -a "$log"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 124 ] && [ "${ALLOW_TIMEOUT_RC:-0}" = "1" ]; then
    printf 'verify-gui: %s OK (window stayed up until the timeout)\n' "$label" | tee -a "$log"
    return 0
  fi
  if [ "$rc" -ne 0 ]; then
    printf 'verify-gui: FAILED at %s (exit %s), log: %s\n' "$label" "$rc" "$log" | tee -a "$log"
    return "$rc"
  fi
  printf 'verify-gui: %s OK\n' "$label" | tee -a "$log"
  return 0
}

fail_step() {
  local rc=$?
  printf 'verify-gui: FAILED (exit %s). Logs kept in %s\n' "$rc" "$LOG_DIR" >&2
  exit "$rc"
}

printf 'verify-gui: starting in %s; logs in %s\n' "$HERE" "$LOG_DIR"

# 1. GUI sources compile without needing a display.
run_step gui-classes 900 "$LOG_DIR/verify-gui-classes.log" \
  "$GRADLE" --no-daemon :gui:classes || fail_step

# 2. Everything else: :core, :cli and :gui plus every JVM test.
run_step build 900 "$LOG_DIR/verify-gui-build.log" \
  "$GRADLE" --no-daemon build || fail_step

# 3. The fake CLI runner end-to-end seam, re-run explicitly so its result has
#    its own log. Each named class is asserted by Gradle: --tests fails when a
#    filter matches nothing, and any failed test gives a non-zero exit.
run_step fake-runner 600 "$LOG_DIR/verify-gui-fake-runner.log" \
  "$GRADLE" --no-daemon :gui:test \
  --tests com.abrah.nightmare.desktop.gui.WorkflowRunnerTest \
  --tests com.abrah.nightmare.desktop.gui.ToolInterfaceTest \
  --tests com.abrah.nightmare.desktop.gui.DefaultModeStateTest \
  --tests com.abrah.nightmare.desktop.gui.AdvancedModeStateTest || fail_step

# 4. 对话回合 full path: a scripted fake ChatModel drives the real agent loop
#    through the five ToolInterface commands into a POSIX fake CLI, then the
#    existing listener/snapshot path exposes the PNG. AskSessionTest covers
#    answer(id, option) continuing the same turn and CANCEL on cancel/close;
#    TurnLifecycleTest covers the single cancel/window-close entry point. All
#    filters are named so an empty match fails the step; no key or network is
#    needed and no real provider is contacted.
run_step chat-turn "$CHAT_TURN_TIMEOUT" "$LOG_DIR/verify-gui-chat-turn.log" \
  "$GRADLE" --no-daemon :gui:test \
  --tests com.abrah.nightmare.desktop.gui.fake.FakeChatModelWorkflowTest \
  --tests com.abrah.nightmare.desktop.gui.ChatSessionTest \
  --tests com.abrah.nightmare.desktop.gui.AskSessionTest \
  --tests com.abrah.nightmare.desktop.gui.TurnLifecycleTest || fail_step

# 5. Workflow save/load/version-compat seam, also part of `build` above but
#    kept visible here as a separate, bounded assertion.
run_step persistence 600 "$LOG_DIR/verify-gui-persistence.log" \
  "$GRADLE" --no-daemon :core:test \
  --tests com.abrah.nightmare.desktop.graph.WorkflowPersistenceTest || fail_step

# A real LLM provider smoke stays a manual, optional check: it needs
# NIGHTMARE_LLM_* credentials and network, while every automated chat-turn
# assertion above uses a fake ChatModel and a fake CLI. No key is required
# here and a missing provider/key/network never blocks acceptance.
#
# 6. Real-weight smoke is optional: no cache means a documented skip, not a
#    failure. A missing engine binary is skipped for the same reason.
SMOKE_LOG="$LOG_DIR/verify-gui-real-smoke.log"
if [ -f "$MODEL_FILE" ]; then
  if [ -x "$ENGINE_BIN" ]; then
    run_step real-smoke "$SMOKE_TIMEOUT" "$SMOKE_LOG" \
      "$GRADLE" --no-daemon :cli:test \
      --tests com.abrah.nightmare.desktop.cli.Img2ImgRealE2ETest || fail_step
  else
    printf 'verify-gui: real-smoke 已跳过（无引擎二进制）: %s\n' "$ENGINE_BIN" | tee -a "$SMOKE_LOG"
  fi
else
  printf 'verify-gui: real-smoke 已跳过（无权重缓存）: %s\n' "$MODEL_FILE" | tee -a "$SMOKE_LOG"
fi

# 7. Opt-in graphical sanity check. Use the display that is already there;
#    otherwise wrap with xvfb-run when installed; otherwise say so and carry
#    on. `:gui:run` is a window that stays open, so the timeout is expected.
if [ "${VERIFY_GUI_LAUNCH:-0}" = "1" ]; then
  LAUNCH_LOG="$LOG_DIR/verify-gui-launch.log"
  prefix=()
  can_launch=0
  if [ -n "${DISPLAY:-}" ]; then
    printf 'verify-gui: gui-launch uses existing DISPLAY=%s\n' "$DISPLAY" | tee -a "$LAUNCH_LOG"
    can_launch=1
  elif command -v xvfb-run >/dev/null 2>&1; then
    prefix=(xvfb-run -a)
    printf 'verify-gui: gui-launch wraps the run with xvfb-run -a\n' | tee -a "$LAUNCH_LOG"
    can_launch=1
  else
    printf 'verify-gui: gui-launch 已跳过（无显示且无 xvfb-run）；设置 DISPLAY 或安装 xvfb-run\n' | tee -a "$LAUNCH_LOG"
  fi
  if [ "$can_launch" -eq 1 ]; then
    ALLOW_TIMEOUT_RC=1 run_step gui-launch 60 "$LAUNCH_LOG" \
      "${prefix[@]}" "$GRADLE" --no-daemon :gui:run || fail_step
  fi
fi

printf 'verify-gui: PASS (all enabled steps). Logs in %s\n' "$LOG_DIR"
