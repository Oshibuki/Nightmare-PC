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

## Build and test in one command

```bash
export JAVA_HOME=/home/chjlxxc/Android/jdk-17
cd desktop
timeout 900 ./gradlew --no-daemon build 2>&1 | tee /tmp/desktop-build.log
```

`build` compiles both modules and runs every JVM test. A green run ends with
`BUILD SUCCESSFUL`; test results are also written to
`core/build/reports/tests/test/index.html` and `cli/build/reports/tests/test/index.html`.

Useful narrower commands:

| Goal | Command |
| --- | --- |
| tests only | `./gradlew --no-daemon test` |
| list projects | `./gradlew --no-daemon projects` (expects only `:core` and `:cli`) |
| dependency tree | `./gradlew --no-daemon :cli:dependencies --configuration runtimeClasspath` |
| run the CLI without packaging | `./gradlew --no-daemon :cli:run --args='--help'` |

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

`txt2img` takes `--model`, `--prompt` and `--out` (all required) plus
`--negative-prompt`, `--width`/`--height` (512), `--steps` (20), `--seed` (42)
and `--engine`. The engine is resolved from `--engine`, then `NIGHTMARE_SD_BIN`,
then `desktop/engine/build/bin/sd-cli`; it runs as a child process with its
output forwarded, so a crash cannot take the CLI down with it.

## Versions

Every version lives in `gradle/libs.versions.toml` and nowhere else. The product
version there is single-sourced: `:core` generates `BuildInfo.VERSION` from it,
so `--version` cannot disagree with the catalog. Bumping the version is a
one-line catalog edit — and it fails `ProductIdentityTest`, which pins the
string users see.

## Layout

| Path | Responsibility |
| --- | --- |
| `cli/` | `:cli` — entry point, argument handling, exit codes |
| `core/` | `:core` — platform-independent logic copied from the Android app |
| `engine/` | vendored C++ inference engine, built outside Gradle |
| `docs/` | desktop-only documentation |

The engine and GUI phases are not built by Gradle. `engine/` is built by its own
script (see its README) and Gradle only depends on the resulting binary path;
the GUI arrives in Phase 2.

## Boundaries

- Do not edit `app/` from here, and do not sync desktop changes back into the
  mobile app (ADR-0001: copy once, then evolve independently).
- Build output (`build/`, `.gradle/`) is ignored; never commit it.
- Model weights are never committed.
