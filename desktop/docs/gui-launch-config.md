# Manual GUI launch configuration

A hand-started GUI/CLI run is described by three independent environment
variables. They are all optional; set only what you actually need.
`desktop/preflight-gui-launch.sh` validates whichever ones are present, and
`verify-gui.sh` calls it automatically as its `launch-config` step.

| Variable | What it really is | Example value |
| --- | --- | --- |
| `NIGHTMARE_GUI_CLI` | The **runnable CLI start script** built by `:cli:installDist` — the command the GUI launches as a child process. It is **not** the inference engine. | `<desktop>/cli/build/install/nightmare-desktop/bin/nightmare-desktop` |
| `NIGHTMARE_SD_BIN` | The C++ inference engine binary, or a Vulkan wrapper around it. The CLI resolves its engine from here. This one keeps its historical, unprefixed name. | `~/.cache/nightmare-desktop/engine/sd-cli` (CPU), or `$VERIFY_GUI_LOG_DIR/sd-cli-vulkan-wrapper.sh` (Vulkan) |
| `NIGHTMARE_GUI_MODEL` | The diffusion weights file — data, so no execute bit is needed. | `~/.cache/nightmare-desktop/models/sd15/v1-5-pruned-emaonly.safetensors` |

Pointing `NIGHTMARE_GUI_CLI` at `sd-cli` is the classic mistake: the GUI would
have no `txt2img` subcommand to run. If the three are confused, preflight names
the offending variable and prints the fix before any slow build starts.

## Vulkan wrapper

With no explicit engine, `verify-gui.sh` writes
`$VERIFY_GUI_LOG_DIR/sd-cli-vulkan-wrapper.sh` containing:

```bash
exec <vulkan-bin> --max-vram 0 --offload-to-cpu "$@"
```

- `--max-vram 0` — SD1.5 needs about 566 MB while the engine's default Vulkan
  budget is only about 530 MB. Without it, weight preparation fails with
  `model manager cannot make enough memory available on Vulkan0`.
- `--offload-to-cpu` — when a local LLM server shares the APU's device memory,
  the weights stay in RAM instead of fighting for VRAM; compute still runs on
  Vulkan.

Vulkan is the default for image runs: CPU sampling is much slower. An explicit
`NIGHTMARE_SD_BIN` is always used verbatim, with no wrapper and no injected
flags; if the Vulkan binary or the wrapper is unavailable, the script logs why
and falls back to the CPU engine.

## Local LLM, no key

The local OpenAI-compatible service (Lemonade) is reached with:

```bash
export NIGHTMARE_LLM_PROVIDER=CUSTOM
export NIGHTMARE_LLM_BASE_URL=http://127.0.0.1:13305/v1
export NIGHTMARE_LLM_MODEL=Qwen3.5-4B-GGUF
# deliberately no NIGHTMARE_LLM_KEY
```

`CUSTOM` has no built-in endpoint, so an empty `NIGHTMARE_LLM_BASE_URL` is a
preflight failure. The service accepts chat and tool calls without an
`Authorization` header, and its model list omits `supported_parameters`, so
name the model id explicitly. Preflight only checks that the base URL is
present — it never inspects or prints the key.

## Running preflight

```bash
timeout 30 desktop/preflight-gui-launch.sh
```

With none of the three variables set it prints
`未设置手动启动配置，preflight 跳过` and exits 0, so headless/CI runs stay
green. Otherwise each set variable is checked (executable script, executable
engine/wrapper, existing weights) plus the `CUSTOM` base-URL rule. Output is a
short `preflight: PASS` / `preflight: FAIL` with a fix hint; any failure exits
non-zero. It needs no Gradle, network or display.

In `verify-gui.sh` this is the `launch-config` step: a 30 s hard timeout, its
own `verify-gui-launch-config.log` in `VERIFY_GUI_LOG_DIR`, placed right after
`gui-classes`; a failure stops the run there. `VERIFY_GUI_PREFLIGHT` overrides
the launcher (default `./preflight-gui-launch.sh`) — `verify-gui.test.sh` uses
that seam for cases which deliberately inject bad paths to test other steps.

## Honest runs without cache

```bash
VERIFY_GUI_NO_CACHE=1 VERIFY_GUI_LOG_DIR=/tmp/nm-desktop/logs timeout 2400 ./verify-gui.sh
```

This appends `--no-build-cache --rerun-tasks` to every Gradle call, so a warm
cache cannot make the acceptance run look green without doing the work. The
default (`0`) leaves every command line exactly as before.
