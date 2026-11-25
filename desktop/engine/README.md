# engine/ — vendored C++ inference engine

Placeholder. Nothing here yet, and that is deliberate: the skeleton ticket only
reserves the responsibility area so the layout is settled before code arrives.

What will live here, per ADR-0002/0004/0005:

- the `stable-diffusion.cpp` fork and its `ggml` submodule, pinned to exact SHAs;
- a build script that compiles with the Hexagon/QNN backend **off** and the CPU
  backend on, plus Vulkan on Linux/Windows and Metal on macOS;
- the desktop patch set applied to that source, not to `app/`.

Rules this area inherits:

- Not a Gradle project. The engine is built by its own script; Gradle only
  depends on the resulting binary path.
- Engine source and build output are never committed (weights and binaries are
  too large, and the fork's licence has redistribution limits).
- The shared build cache for engine binaries lives outside the repository so
  worktrees do not each rebuild it.

Until then, `desktop/README.md` covers the JVM side, which is what
`./gradlew build` exercises.
