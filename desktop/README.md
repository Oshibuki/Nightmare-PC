# Nightmare Desktop

Headless desktop build of Nightmare: Kotlin/JVM code plus a vendored C++ engine,
targeting Linux first, then Windows and macOS. This directory is a **Gradle
build of its own** (ADR-0003). Gradle run from here never reads the Android
build in the repository root, and nothing here may reference `app/` or apply an
Android plugin.

## Requirements

- JDK 17. It is not on `PATH` on the reference machine, so export it first:

  ```bash
  export JAVA_HOME=/home/chjlxxc/Android/jdk-17
  ```

- Gradle comes from the wrapper (`gradle-8.14.4`), no separate install.
- Running the GUI needs a graphical display. On a headless machine install Xvfb
  and prefix the run command with `xvfb-run -a`; the reference machine has
  `DISPLAY=:0` and no Xvfb binary. Compilation needs no display.

## Build and test in one command

```bash
export JAVA_HOME=/home/chjlxxc/Android/jdk-17
cd desktop
timeout 900 ./gradlew --no-daemon build 2>&1 | tee /tmp/desktop-build.log
```

`build` compiles `:core`, `:cli` and `:gui` and runs every JVM test. A green run
ends with `BUILD SUCCESSFUL`; test results are also written to
`core/build/reports/tests/test/index.html` and `cli/build/reports/tests/test/index.html`.

Useful narrower commands:

| Goal | Command |
| --- | --- |
| tests only | `./gradlew --no-daemon test` |
| list projects | `./gradlew --no-daemon projects` (expects `:core`, `:cli` and `:gui`) |
| dependency tree | `./gradlew --no-daemon :cli:dependencies --configuration runtimeClasspath` |
| run the CLI without packaging | `./gradlew --no-daemon :cli:run --args='--help'` |
| compile the GUI | `timeout 900 ./gradlew --no-daemon :gui:classes 2>&1 \| tee /tmp/gui-classes.log` |
| launch the GUI window | `timeout 300 xvfb-run -a ./gradlew --no-daemon :gui:run 2>&1 \| tee /tmp/gui-run.log` |

## Phase 2 acceptance: one command (Linux)

`verify-gui.sh` is the single acceptance entry point (ADR-0011). It runs every
step under a hard timeout and appends each step's stdout+stderr to its own log;
the first non-zero exit stops the run, keeps all logs, and makes the script exit
non-zero. A green run ends with `verify-gui: PASS`.

```bash
export JAVA_HOME=/home/chjlxxc/Android/jdk-17
cd desktop
timeout 2400 ./verify-gui.sh 2>&1 | tee /tmp/verify-gui-run.log
```

What it checks, in order:

| Step | Command | Log in `/tmp` | Timeout |
| --- | --- | --- | --- |
| GUI compile | `:gui:classes` | `verify-gui-classes.log` | 900 s |
| Full build | `build` (all JVM tests) | `verify-gui-build.log` | 900 s |
| Fake CLI runner E2E | `:gui:test` with `WorkflowRunnerTest`, `ToolInterfaceTest`, `DefaultModeStateTest`, `AdvancedModeStateTest` | `verify-gui-fake-runner.log` | 600 s |
| Workflow persistence | `:core:test` with `WorkflowPersistenceTest` | `verify-gui-persistence.log` | 600 s |
| Real SD1.5 smoke | `:cli:test` with `Img2ImgRealE2ETest` | `verify-gui-real-smoke.log` | 1800 s |
| GUI launch (opt-in) | `:gui:run` | `verify-gui-launch.log` | 60 s |

The `--tests` filters are assertions too: Gradle fails a filter that matches no
test, and any failed test gives a non-zero exit. Logs live in
`VERIFY_GUI_LOG_DIR` (default `/tmp`). Overrides:

| Variable | Meaning |
| --- | --- |
| `VERIFY_GUI_GRADLE` | Gradle launcher, default `./gradlew` |
| `VERIFY_GUI_LOG_DIR` | log directory, default `/tmp` |
| `VERIFY_GUI_SMOKE_TIMEOUT` | real-smoke seconds, default 1800 |
| `NIGHTMARE_MODELS` | model cache root, default `~/.cache/nightmare-desktop/models` |
| `NIGHTMARE_SD_BIN` | engine binary, default `~/.cache/nightmare-desktop/engine/sd-cli` |

The real smoke is optional. If
`$NIGHTMARE_MODELS/sd15/v1-5-pruned-emaonly.safetensors` is absent the script
writes `已跳过（无权重缓存）` to the smoke log and exits 0; a missing engine gives
`已跳过（无引擎二进制）`. Neither blocks acceptance — the fake runner carries the
contract without external downloads. With both present the CLI test really runs
the cached weights and reports its timings.

Graphics are opt-in: `VERIFY_GUI_LAUNCH=1` adds the `:gui:run` step. It uses an
existing `DISPLAY` as-is, wraps with `xvfb-run -a` on a headless machine that
has Xvfb, and logs `已跳过（无显示且无 xvfb-run）` when neither exists. The reference
machine has `DISPLAY=:0` and no Xvfb binary, so no wrapper is needed there:

```bash
DISPLAY=:0 VERIFY_GUI_LAUNCH=1 timeout 2400 ./verify-gui.sh 2>&1 | tee /tmp/verify-gui-launch-run.log
```

The script's own shell assertions are bounded and need no Gradle:

```bash
timeout 120 bash verify-gui.test.sh 2>&1 | tee /tmp/verify-gui-test.log
```

### Windows and macOS: compile only

Only the GUI module's local compile is required on those platforms (spec User
Story 40, ADR-0011). Compilation needs no display and no weights:

```bat
:: Windows (PowerShell or cmd, from desktop\)
.\gradlew.bat --no-daemon :gui:classes
```

```bash
# macOS (and Linux), from desktop/
./gradlew --no-daemon :gui:classes
```

That is the whole cross-platform boundary for this phase: no smoke, no GUI
launch and no Windows/macOS CI job. The fake CLI runner tests use a POSIX shell
script and are skipped on Windows by `Assume`; they are not a substitute for a
Windows GUI run. The existing `.github/workflows/desktop.yml` keeps its single
Linux `build` job and its failure-time upload of `desktop/*/build/reports/tests/`;
the verification script is a local/agent entry point and CI was deliberately
left unchanged.

## Desktop GUI (Phase 2 skeleton)

`:gui` is an independent Compose Desktop application that stays a JVM project:
it applies `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.compose`
(version locked to Kotlin 2.4.21) and `org.jetbrains.compose` (1.12.2), with no
Android plugin or target. Versions live in `gradle/libs.versions.toml`; the
central repository list includes Google Maven only for Compose's generic
`androidx.*` artifacts, while module scripts still may not declare repositories.

With `JAVA_HOME` exported as above and the working directory `desktop/`:

```bash
timeout 900 ./gradlew --no-daemon :gui:classes 2>&1 | tee /tmp/gui-classes.log
timeout 300 xvfb-run -a ./gradlew --no-daemon :gui:run 2>&1 | tee /tmp/gui-run.log
```

The entry point is `com.abrah.nightmare.desktop.gui.MainKt`; `:gui:run` opens an
empty window titled `Nightmare Desktop`. Both commands are bounded and keep a
log on failure. Drop `xvfb-run -a` when a display is already available; on a
headless machine without Xvfb, startup cannot be verified here.

### Locating the CLI executable

The GUI will drive the CLI as a child process, not call it in-process. Build the
runnable script with `./gradlew --no-daemon :cli:installDist`; the executable is
`cli/build/install/nightmare-desktop/bin/nightmare-desktop` (absolute path:
`<desktop>/cli/build/install/nightmare-desktop/bin/nightmare-desktop`). This
skeleton does not launch it yet. Do not confuse it with `--engine` /
`NIGHTMARE_SD_BIN`, which locate the C++ inference binary instead.

## Smoke test: one command to a PNG

The Phase 0 acceptance test is "one command from a clean checkout to an image".
It needs two things that are not in the repository — the engine binary and an
SD1.5 checkpoint — so build and fetch them once, then:

```bash
export JAVA_HOME=/home/chjlxxc/Android/jdk-17
./desktop/engine/build.sh                     # -> ~/.cache/nightmare-desktop/engine/sd-cli
cd desktop && ./gradlew --no-daemon :cli:installDist && cd ..

NIGHTMARE_SD_BIN="$HOME/.cache/nightmare-desktop/engine/sd-cli" \
timeout 1800 ./desktop/cli/build/install/nightmare-desktop/bin/nightmare-desktop \
  txt2img \
  --model "$HOME/.cache/nightmare-desktop/models/sd15/v1-5-pruned-emaonly.safetensors" \
  --prompt "a red fox in an autumn forest, photograph" \
  --out /tmp/nm-smoke-sd15.png \
  --width 512 --height 512 --steps 20 --seed 42
```

Where the weights come from, and where they go, is
[`docs/models.md`](docs/models.md). Exit code `0` means the PNG exists, carries
the PNG signature and the requested 512×512 IHDR — the CLI checks that before
it reports success, so a truncated or log-redirected file cannot pass. The
engine prints its progress (model load, one line per step) to stdout, which is
why the command is worth watching rather than backgrounding silently.

On the reference machine (16 cores, CPU only) the whole command takes about
4½ minutes, measured: 247 s of sampling at ~12 s per step, 19 s to decode the
VAE, and a couple of seconds to load the model. `timeout` is the caller's job —
the CLI deliberately sets no internal deadline, because a slow run is a
legitimate run.

## Running the CLI

```bash
./gradlew --no-daemon installDist
./cli/build/install/nightmare-desktop/bin/nightmare-desktop --help
./cli/build/install/nightmare-desktop/bin/nightmare-desktop --version
```

Artifacts:

| Path | What it is |
| --- | --- |
| `cli/build/install/nightmare-desktop/bin/nightmare-desktop` | start script, the runnable command |
| `cli/build/install/nightmare-desktop/lib/` | `cli-<version>.jar`, `core-<version>.jar`, `kotlin-stdlib` |
| `cli/build/distributions/*.zip`, `*.tar` | same tree, packaged |
| `core/build/libs/core-<version>.jar` | core logic library |
| `core/build/generated/build-info/.../BuildInfo.kt` | generated version constant |

Exit codes are part of the contract: `0` did what was asked, `1` the request was
understood but could not be completed (missing model, engine failure, an output
that is not the PNG it claims to be), `2` the command line itself was wrong
(message on stderr, nothing on stdout). `--help` documents all three.

`txt2img` takes `--model`, `--prompt` and `--out` (all required) plus:

| Option | Default | Accepted |
| --- | --- | --- |
| `--negative-prompt` | empty | any text |
| `--width` / `--height` | 512 | positive, a multiple of 8 |
| `--steps` | 20 | 1–150 |
| `--sampler` | `euler` | `euler`, `euler_a`, `heun`, `dpm++2m`, `dpm++2m_sde`, `er_sde`, `res_multistep`, `ipndm`, `lcm` |
| `--scheduler` | `discrete` | `discrete`, `karras`, `exponential`, `sgm_uniform`, `bong_tangent`, `simple`, `beta` |
| `--cfg-scale` | 7 | any number > 0 |
| `--seed` | 42 | any integer; `-1` draws one and reports it |
| `--batch` | 1 | 1–16 |
| `--engine` | `$NIGHTMARE_SD_BIN`, then `desktop/engine/build/bin/sd-cli` | a path |
| `--lora` | unset | `file[@mult]`; repeat the flag or comma-separate several |
| `--lora-dir` | `$NIGHTMARE_MODELS/lora`, then `~/.cache/nightmare-desktop/models/lora` | folder holding the LoRA weights |
| `--vae` | unset (required with `--diffusion-model`) | path to readable safetensors/GGUF VAE weights; standalone SDXL VAE or multi-file decoder |
| `--vae-tiling` | off | bare flag on; `--vae-tiling=false` off |
| `--vae-tile-size` | engine default `32x32` | positive `WxH` |
| `--vae-tile-overlap` | engine default `0.5` | number strictly between 0 and 1 |

### Multi-file DiT / GGUF models

Z-Image, FLUX.2 Klein and Qwen Image ship as a diffusion model plus a separate
text encoder and VAE: the engine's `-m` cannot load that set as one checkpoint.
Use the multi-file pathway instead (`--model` with a `.gguf` is refused with a
message pointing here):

```bash
M="${NIGHTMARE_MODELS:-$HOME/.cache/nightmare-desktop/models}"
nightmare-desktop txt2img \
  --diffusion-model "$M/z-image/diffusion_model.gguf" \
  --llm "$M/z-image/llm.gguf" \
  --vae "$M/flux/ae.safetensors" \
  --prompt "a red fox" --out /tmp/nm-zimage.png \
  --cfg-scale 1 --steps 20 --width 1024 --height 1024 --scheduler sgm_uniform
```

Qwen Image 2.1 additionally needs the vision encoder:

```bash
nightmare-desktop txt2img \
  --diffusion-model "$M/qwen-image/diffusion_model.gguf" \
  --llm "$M/qwen-image/llm.gguf" \
  --vae "$M/qwen-image/vae.safetensors" \
  --llm_vision "$M/qwen-image/mmproj.gguf" \
  --prompt "a red fox" --out /tmp/nm-qwen.png --cfg-scale 1 --steps 20
```

`--diffusion-model`, `--llm` and `--vae` are required together and mutually
exclusive with `--model`; `--llm_vision` is optional. All four paths are checked
for existence and a valid header before the engine starts, and an error names
the option whose file is wrong. Everything else — seed, batch, samplers,
progress lines and the `parameters` chunk — behaves exactly as in single-file
mode. The real, weight-backed smoke tests for these families are tickets
19–21; they build on this pathway.

The engine is resolved from `--engine`, then `NIGHTMARE_SD_BIN`, then
`desktop/engine/build/bin/sd-cli`; it runs as a child process with its output
forwarded, so a crash cannot take the CLI down with it.

⚠ **The two vocabularies are not the same.** The sampler names above are the
**engine's** (`sample_method_to_str[]` in `engine/sdcpp/src/stable-diffusion.cpp`),
not the mobile app's backend ids — `dpm` and `dpm_sde` are the QNN backend's
spelling and are rejected with exit `2`, as is `lcm_karras` (the app's suffix
form; the engine takes the schedule in its own flag). The accepted values come
from `Samplers.kt`, the copied mobile table, narrowed by
`SamplerWire.kt` — the CLI has no list of its own, so a name it offers is a name
the engine knows.

### Batches

`--batch 3` runs the engine three times, one image at a time, with the seed
incrementing from `--seed`. The first image is written exactly where `--out`
points; the rest go beside it as `<stem>-<i>.png` (`out.png` → `out-2.png`,
`out-3.png`). One `[i/N] seed=<seed> -> <path>` line per image goes to stdout
before the engine's own output, so a long batch says where it is.

### The `parameters` chunk

Every PNG carries an A1111-style `parameters` chunk — `tEXt` for Latin-1 text,
`iTXt` when the prompt is not (a Japanese or Chinese prompt would otherwise be
mangled to `?`), always with the keyword `parameters`, always written **before
IEND** with a correct CRC32:

```
a red fox in an autumn forest, photograph
Negative prompt: blurry, low quality
Steps: 8, Sampler: Euler A, Schedule type: Karras, CFG scale: 5.5, Seed: 42, Size: 640x448, Model: v1-5-pruned-emaonly.safetensors
```

The names in it are the human ones (`Euler A`, not `euler_a`) because this
string is what a person reads out of an old image; the exact flags are in your
shell history.

⚠ **The engine writes one of these too** (`Sampler: euler_a karras`,
`CFG scale: 5.500000`, plus an `SDCPP: {...}` JSON blob), so the CLI **replaces**
that chunk rather than adding a second: two chunks under one keyword is
ambiguous, and readers disagree about which wins (PIL keeps the last, a walker
that stops at the first match keeps the first). What is lost in the swap is the
`SDCPP` JSON; what is kept is every field above plus `Model:`, so the image still
names the weights that made it. If the engine writes a file with no IEND, the
run fails with exit `1` rather than adding a chunk to a file that was already
broken.

### Local HTTP API (opt-in)

The HTTP service is **off** unless you explicitly start it:

```bash
./cli/build/install/nightmare-desktop/bin/nightmare-desktop \
  serve --model "$HOME/.cache/nightmare-desktop/models/sd15/v1-5-pruned-emaonly.safetensors" \
  --port 1234
```

`serve` runs the engine's `sd-server` in the foreground and always passes
`--listen-ip 127.0.0.1`; the CLI has no host flag, so a typo cannot expose an
unauthenticated generation endpoint to the LAN. `--port` defaults to `1234`
(1–65535). The binary is resolved from `--engine`, then
`NIGHTMARE_SD_SERVER_BIN`, then `desktop/engine/build/bin/sd-server`;
`engine/build.sh` already builds it alongside `sd-cli`. The server's stdout and
stderr are forwarded, and a non-zero engine exit becomes a non-zero CLI exit.

The engine's own A1111-style routes are then available on the loopback port.
Their request fields and response shape are documented in
[`engine/sdcpp/examples/server/api.md`](engine/sdcpp/examples/server/api.md),
with the route implementations in `routes_sdapi.cpp` beside it:

```bash
curl --max-time 300 -sS -H 'Content-Type: application/json' \
  -d '{"prompt":"a red fox","steps":4,"width":64,"height":64}' \
  http://127.0.0.1:1234/sdapi/v1/txt2img
```

`images` in the response is an array of base64-encoded PNGs. This exposes the
vendored engine as-is. The result of testing that surface with an unmodified
A1111 client, including the endpoints this engine does not implement, is in
[`docs/a1111-client-compat.md`](docs/a1111-client-compat.md).

## Versions

Every version lives in `gradle/libs.versions.toml` and nowhere else. The product
version there is single-sourced: `:core` generates `BuildInfo.VERSION` from it,
so `--version` cannot disagree with the catalog. Bumping the version is a
one-line catalog edit — and it fails `ProductIdentityTest`, which pins the
string users see.

## Layout

| Path | Responsibility |
| --- | --- |
| `cli/` | `:cli` — headless entry point, argument handling, exit codes |
| `core/` | `:core` — platform-independent logic copied from the Android app |
| `gui/` | `:gui` — Compose Desktop shell, currently an empty window |
| `engine/` | vendored C++ inference engine, built outside Gradle |
| `docs/` | desktop-only documentation |

The engine is not built by Gradle: `engine/` has its own script (see its README)
and Gradle only depends on the resulting binary path. The GUI is a normal Gradle
module (`:gui`) with a Compose Desktop entry point.

## Boundaries

- Do not edit `app/` from here, and do not sync desktop changes back into the
  mobile app (ADR-0001: copy once, then evolve independently).
- Build output (`build/`, `.gradle/`) is ignored; never commit it.
- Model weights are never committed.
