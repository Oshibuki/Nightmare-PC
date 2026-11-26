#!/usr/bin/env bash
# Preflight for the manual GUI launch configuration (no Gradle, no network, no
# display). Every check is a local file test, so this script is bounded by
# construction. It is invoked by verify-gui.sh as its `launch-config` step and
# can also be run directly before starting the GUI by hand.
#
# Manual launch has three independent knobs. Each is optional on its own, but
# once set it must point at the right kind of file:
#   NIGHTMARE_GUI_CLI   the runnable CLI start script, NOT the inference engine
#   NIGHTMARE_SD_BIN    the C++ engine binary, or a Vulkan wrapper around it
#   NIGHTMARE_GUI_MODEL the diffusion weights file
# With none of the three set there is nothing to validate: print the documented
# skip and exit 0 so headless/CI runs stay green.
#
# NIGHTMARE_LLM_PROVIDER=CUSTOM additionally needs an explicit base URL. The
# key is never inspected or echoed — only its presence/absence is managed by
# the GUI, never by this script.
set -uo pipefail

fail() {
  printf 'preflight: FAIL - %s\n' "$1"
  printf 'preflight: 修复指引: %s\n' "$2"
  exit 1
}

pass() {
  printf 'preflight: PASS - %s\n' "$1"
}

CLI="${NIGHTMARE_GUI_CLI:-}"
ENGINE="${NIGHTMARE_SD_BIN:-}"
MODEL="${NIGHTMARE_GUI_MODEL:-}"

if [ -z "$CLI" ] && [ -z "$ENGINE" ] && [ -z "$MODEL" ]; then
  printf 'preflight: 未设置手动启动配置，preflight 跳过\n'
  exit 0
fi

# 1. The GUI runs a child process: this is the installDist start script, not
#    the engine the CLI later execs. Confusing the two is the common mistake.
if [ -n "$CLI" ]; then
  if [ ! -f "$CLI" ] || [ ! -x "$CLI" ]; then
    fail "NIGHTMARE_GUI_CLI 不是可执行文件: $CLI" \
      "运行 :cli:installDist，并指向 desktop/cli/build/install/nightmare-desktop/bin/nightmare-desktop；它不是引擎"
  fi
  pass "NIGHTMARE_GUI_CLI 可执行: $CLI"
fi

# 2. The engine (or its Vulkan wrapper) must also be executable.
if [ -n "$ENGINE" ]; then
  if [ ! -f "$ENGINE" ] || [ ! -x "$ENGINE" ]; then
    fail "NIGHTMARE_SD_BIN 不是可执行文件: $ENGINE" \
      "指向已构建的引擎二进制，或含 --max-vram 0 --offload-to-cpu 的 Vulkan wrapper"
  fi
  pass "NIGHTMARE_SD_BIN 可执行: $ENGINE"
fi

# 3. Weights are data, not a program: existence is enough, no exec bit needed.
if [ -n "$MODEL" ]; then
  if [ ! -f "$MODEL" ]; then
    fail "NIGHTMARE_GUI_MODEL 不是存在的文件: $MODEL" \
      "指向已下载的权重，例如 ~/.cache/nightmare-desktop/models/sd15/v1-5-pruned-emaonly.safetensors"
  fi
  pass "NIGHTMARE_GUI_MODEL 文件存在: $MODEL"
fi

# 4. CUSTOM is the only provider with no built-in endpoint. Never read or echo
#    NIGHTMARE_LLM_KEY here: a missing key is a runtime concern, not a path.
if [ "${NIGHTMARE_LLM_PROVIDER:-}" = "CUSTOM" ]; then
  if [ -z "${NIGHTMARE_LLM_BASE_URL:-}" ]; then
    fail "NIGHTMARE_LLM_PROVIDER=CUSTOM 但 NIGHTMARE_LLM_BASE_URL 为空" \
      "设置 NIGHTMARE_LLM_BASE_URL=http://127.0.0.1:13305/v1 与 NIGHTMARE_LLM_MODEL=Qwen3.5-4B-GGUF；无需密钥"
  fi
  pass "CUSTOM provider 的 base URL 已设置"
fi

printf 'preflight: PASS - 手动启动配置检查通过\n'
exit 0
