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

Exit codes are part of the contract: `0` did what was asked, `2` the command
line itself was wrong (message on stderr, nothing on stdout).

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

The engine and GUI phases are not built by Gradle yet. `engine/` holds no source
until the vendoring ticket lands; see its README.

## Boundaries

- Do not edit `app/` from here, and do not sync desktop changes back into the
  mobile app (ADR-0001: copy once, then evolve independently).
- Build output (`build/`, `.gradle/`) is ignored; never commit it.
- Model weights are never committed.
