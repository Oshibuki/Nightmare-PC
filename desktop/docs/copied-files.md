# Copied files — where the desktop code came from

The desktop build does not share a module with the Android app (ADR-0001): code
that both need is **copied** from `app/src/` into `core/src/` and then lives its
own life. There is no sync job and no back-porting. **Edit the desktop copy**;
the mobile original is the historical source, not the upstream.

Every copied file carries this as its first line, so provenance survives being
read out of context:

```
// Copied from app/src/main/java/<path> on 2026-11-25 (ADR-0001). Edit the desktop copy; never sync back.
```

This file is the only place that records the pairs. Keep it in step with the
tree: a copied file missing from here is a file whose origin nobody can trace.

## Copied

| App path (`app/src/main/java/…`) | Desktop path (`desktop/core/src/…`) | Its test | Notes |
| --- | --- | --- | --- |
| `com/abrah/nightmare/GgufHeader.kt` | `main/kotlin/com/abrah/nightmare/GgufHeader.kt` | `test/kotlin/com/abrah/nightmare/GgufCompleteTest.kt`, `test/kotlin/com/abrah/nightmare/GgufCpuTypesTest.kt` | Byte-for-byte copy (JDK only: `java.io`, `java.nio`). GGUF header parse, tensor table, `dataEnd`/`isComplete` for resume, `cpuTypes` naming. |
| `com/abrah/nightmare/ModelCatalog.kt` (sampler section only) | `main/kotlin/com/abrah/nightmare/Samplers.kt` | `test/kotlin/com/abrah/nightmare/SamplersTest.kt` | **Excerpt**, not the file: `SAMPLERS`, `SCHEDULES`, `DIT_SCHEDULES`, `DIT_SAMPLERS`, `DIT_SAMPLER_IDS`, `DIT_COMMON_SAMPLER_IDS`, `H3_SPECTRAL`, `DEFAULT_SCHEDULER`, `DEFAULT_STEPS`/`DEFAULT_CFG`/`STEPS_RANGE`/`CFG_RANGE`, `SCHEDULERS`, `DIT_SCHEDULERS`, `schedulesSupported`/`karrasSupported`/`splitScheduler`/`joinScheduler`×2/`samplerName`/`schedulerLabel`. Receiver renamed `ModelCatalog` → `Samplers`; every declaration is otherwise verbatim. |
| `app/src/test/java/…/SamplerNamingTest.kt` | — (test, not source) | `test/kotlin/com/abrah/nightmare/SamplersTest.kt` | The mobile assertions, unchanged, against `Samplers`. Two tests were **trimmed**, not relaxed — see below. |
| `com/abrah/nightmare/api/MiniHttp.kt` | `main/kotlin/com/abrah/nightmare/api/MiniHttp.kt` | `test/kotlin/com/abrah/nightmare/api/MiniHttpTest.kt`, `ApiCorsTest.kt` | The JVM `java.net` server. **Edited for desktop**: binds `127.0.0.1` (mobile bound every interface), default peer gate is `isLoopbackPeer` (mobile admitted LAN/tailnet), and the mobile-only "Settings → API on the phone" hint was reworded. `err()` still uses `org.json`, now the pinned JVM artifact in `libs.versions.toml`. |
| `com/abrah/nightmare/api/ApiSettings.kt` (excerpt) | `main/kotlin/com/abrah/nightmare/api/ApiSettings.kt` | `test/kotlin/com/abrah/nightmare/api/ApiSettingsTest.kt` | **Excerpt**: `PORT`, `CORS_HEADERS`, `matches`, `BEARER` verbatim. The `Context`/`SharedPreferences` accessors (`enabled`, `token`, `regenerate`, `cors`) stayed behind. |
| `com/abrah/nightmare/api/NightmareApi.kt` (`corsPreflight` only) | `main/kotlin/com/abrah/nightmare/api/ApiCors.kt` | `test/kotlin/com/abrah/nightmare/api/ApiCorsTest.kt` | **Excerpt**, promoted from the `NightmareApi` companion to a top-level function; body otherwise verbatim. |
| `com/abrah/nightmare/api/A1111.kt` (path helpers only) | `main/kotlin/com/abrah/nightmare/api/A1111.kt` | `test/kotlin/com/abrah/nightmare/api/A1111Test.kt` | **Excerpt**: `handles`, `TXT2IMG`, `MAX_IMAGES`, `nearestAspect` verbatim; `ASPECTS`/`DEFAULT_ASPECT`/`ratioValue` are an excerpt of `ModelCatalog.kt` (also verbatim). Everything that speaks in `ModelSpec` stayed behind — see the skip table. |
| `com/abrah/nightmare/api/FlowRequest.kt` (`ApiError` + `RunRequest.parse`) | `main/kotlin/com/abrah/nightmare/api/RunRequest.kt` | `test/kotlin/com/abrah/nightmare/api/RunRequestTest.kt` | **Excerpt**: the request body is verbatim; `applyOverrides`, `frameShaped`, `apiRange`, `checked` are not copied. |
| `app/src/test/java/…/api/ApiTest.kt` (server/token/CORS/body halves) | — (test, not source) | `test/kotlin/com/abrah/nightmare/api/{MiniHttpTest,ApiSettingsTest,ApiCorsTest,RunRequestTest,A1111Test}.kt` | The socket, token, CORS, SSE, body-parse and path-helper assertions were migrated unchanged in meaning. `@RunWith(RobolectricTestRunner::class)` is gone: `org.json` is a JVM artifact here. Two body-parse inputs were made unambiguous (they now include a `flow`) so the named-error assertion reaches the check it names. The LAN allow-list test became the loopback-only test below. |

Four `GgufHeader` tests and ten `Samplers` tests, all passing on the desktop build:

- `GgufCompleteTest` — 3 (`theHeaderSaysWhereTheDataEnds`, `aWholeFileOfAnySizeIsComplete`, `aHalfDownloadIsNot`)
- `GgufCpuTypesTest` — 1 (`aKQuantModelIsNamedAndAStrayHeadIsNot`)
- `SamplersTest` — 10 (`everyWireValueRoundTrips`, `thePickerCanOnlyProduceKnownWireValues`,
  `lcmNeverGainsAScheduleSuffix`, `everyOtherSamplerHasEverySchedule`, `theOldIdsStillParse`,
  `anUnknownSchedulerFallsBackToTheDefault`, `labelsAreTheFamiliarNames`,
  `theDitModelsGetSgmUniformAndBongTangent`, `theSpectralSamplerIsItsOwnWireValue`,
  `ditSamplersJoinAndSplitTheSameWay`)

Thirteen more JVM tests cover the copied API layer, all passing on the desktop build:

- `MiniHttpTest` — 6 (`aRequestReachesTheHandlerWithItsPathQueryAndBody`, `aHandlerThatThrowsIsA500NotAHang`,
  `eventsStreamInOrder`, `onlyLoopbackPeersMayConnect`, `theServerIsNotReachableThroughANonLoopbackAddress`,
  `anUnadmittedBodyIsRefusedBeforeItIsRead`)
- `ApiSettingsTest` — 1 (`theTokenMustMatchExactly`)
- `ApiCorsTest` — 2 (`withBrowserPagesAllowedAPreflightIsAnsweredBeforeTheTokenAndEveryAnswerCarriesTheHeaders`,
  `withBrowserPagesOffNothingChanges`)
- `A1111Test` — 2 (`onlySdApiV1PathsAreHandled`, `theNearestAspectIsTheOneAClientAskedFor`)
- `RunRequestTest` — 2 (`aBodyIsParsedIntoItsFields`, `everyMistakeIsNamedBeforeAnythingRuns`)

Every event-driven API test carries a JUnit timeout; the LAN-bind test uses `Assume` when the host has no
non-loopback IPv4 address, so it cannot hang or fail on an interface-less runner.

⚠ What was **not** copied from `ModelCatalog.kt`, and why:

| Mobile declaration | Why it stayed behind |
| --- | --- |
| `schedulersFor(family: Family)` | Its parameter is `Family`, an enum owned by the Android-coupled part of `ModelCatalog.kt` (and `Family.dit` decides the engine). Porting it means porting `Family`, `ModelSpec` and `SelectedModel` — ticket 13's work, not this one. |
| the two mobile tests that call it | `theSpectralSamplerIsH3ImagesAlone` and `ditFamiliesOfferTheEnginesSamplers` assert through `schedulersFor`. Their **wire-level** assertions were kept as `theSpectralSamplerIsItsOwnWireValue` and `ditSamplersJoinAndSplitTheSameWay`; the per-family ones are trimmed, and come back with `Family`. |

⚠ Not copied, and deliberately not in the table above: `desktop/core/src/main/kotlin/com/abrah/nightmare/desktop/core/SamplerWire.kt`.
It is desktop-only glue that *narrows* `Samplers` to the names the vendored
engine's `sample_method_to_str[]` / `scheduler_to_str[]` actually contain (the
mobile ids `dpm`/`dpm_sde`/… are the QNN backend's spelling, not
stable-diffusion.cpp's). It has no mobile original, so there is no pair to
record — `SamplerWireTest` pins it against the engine's arrays instead.

The `GgufHeader` tests were copied unchanged apart from the provenance line: same package,
same class names, same assertions. Nothing was relaxed to make them compile —
they compile because `GgufHeader` is genuinely platform-free.

## Desktop-authored (not copied)

| Desktop path (`desktop/core/src/…`) | Why it is not a copy |
| --- | --- |
| `main/kotlin/com/abrah/nightmare/desktop/core/ModelFile.kt` | New desktop code: judges `.safetensors`/`.gguf` by header bytes; reuses the copied `GgufHeader` instead of porting `SafetensorsGguf.kt` (blocked by `android.util.Half`/`org.json`). |
| `main/kotlin/com/abrah/nightmare/desktop/core/ModelLibrary.kt` | New desktop code: directory scan with `--dir`/`NIGHTMARE_MODELS`/shared-cache precedence; Android `ModelCatalog.kt` is blocked by `Context` and is not copied. |

These two files deliberately have no "Copied from" line: inventing one would
lie about provenance.

## Skipped, and why

Nothing below is "hard to port". Each one is blocked by a **single symbol that
lives in an Android-coupled file**, and the block is transitive: the file
compiles only if that symbol comes too, and that symbol drags the Android
platform in. Splitting the symbol out is a code change to the mobile file's
shape, not a copy, so it is left to the ticket that owns that file.

| App path | Blocking symbol (where it lives) | Left to |
| --- | --- | --- |
| `com/abrah/nightmare/Graph.kt` | `NodeType` (`Executor.kt:411`) and `NODE_TYPES` (`Executor.kt:2722`) — used as default arguments at `Graph.kt:582,618,645,660` | Phase 2 GUI (ticket for `CanvasModel`/`CanvasState`), after the registry is separated |
| `com/abrah/nightmare/canvas/CanvasModel.kt` | imports `NodeType` (`CanvasModel.kt:5`); needs `Graph` first | Phase 2 GUI |
| `com/abrah/nightmare/canvas/CanvasState.kt` | imports `NodeType` (`CanvasState.kt:4`); needs `Graph` first | Phase 2 GUI |
| `com/abrah/nightmare/canvas/Workflows.kt` | imports `ModelCatalog`, `SelectedModel`, **and the generated `R`** (`canvas/Workflows.kt:4,6,7`) — resource ids, no desktop equivalent | Phase 2 GUI |
| `com/abrah/nightmare/Batch.kt` | `terminalImageNodes(graph: Graph, types: Map<String, NodeType>)` (`Batch.kt:223`) | Phase 1 (11/12, batch runs) |
| `com/abrah/nightmare/BatchParams.kt` | `axesOf(graph: Graph)` / `runCount(graph: Graph)` (`BatchParams.kt:177,232`) | Phase 1 (11/12) |
| `com/abrah/nightmare/npu/Scheduler.kt` | `AppAssets.config` — `npu/AppAssets.kt:3-4` imports `android.content.Context` + `org.json` | Phase 1 (14/15, samplers), after a desktop config loader exists |
| `com/abrah/nightmare/GgufWiden.kt` | `Family` (`ModelCatalog.kt:43`); also `ModelInstaller.Cancelled` and `TemplateLora.halfToFloat` | Phase 1 (13, model catalog + import) |
| `com/abrah/nightmare/ModelCatalog.kt` | `android.content.Context` (`ModelCatalog.kt:3`) at file level; also `Family`, `ModelSpec`, `SelectedModel` | Phase 1 (13) — **except** its sampler section, which is platform-free and was copied to `Samplers.kt` (see above) |
| `com/abrah/nightmare/SafetensorsGguf.kt` | `android.util.Half` + `org.json` (`SafetensorsGguf.kt:3-4`) | Phase 1 (13) |
| `com/abrah/nightmare/Executor.kt` | `androidx.annotation`, `android.graphics.Bitmap`/`BitmapFactory`, `kotlinx.coroutines`; 3273 lines, the whole backend host | Phase 1 (17, API) — only after the node registry is separated |
| `com/abrah/nightmare/Streams.kt` | nothing — pure, but its only consumers on the copy list (`GgufWiden`, the importers) are skipped | copy it with the importers (13) |
| `com/abrah/nightmare/api/A1111.kt` (`route`, `txt2img`, `knobsFor`, `samplerFor`, `sdModels`, `options`, `setOptions`, `modelNamed`) | `ModelSpec`, `Family`, `SelectedModel` (`ModelCatalog.kt`) and `ModelCatalog.schedulersFor`/`keptSize`; also `ApiCore` (`Graph`/`NodeType`, `android.util.Base64`, `BitmapFactory`) | Phase 1 API: ticket 17 exposes the engine's own A1111 service; the path/aspect helpers above are the part that needs no catalog |
| `com/abrah/nightmare/api/ApiCore.kt` | `Graph`, `NodeType`, `SdSampler`, `Workflow` (the node registry), plus `android.content.Context`, `android.graphics.BitmapFactory`, `kotlinx.coroutines` | Phase 1 executor, after the node registry is separated |
| `com/abrah/nightmare/api/NightmareApi.kt` (`route`, `run`, `info`) | `ApiCore` and `RunLock`, `BuildConfig`, `Graph`/`NodeType`; the only moved piece is the pure `corsPreflight` | tickets 17 (engine server) and 25 (real client authentication) |
| `com/abrah/nightmare/api/ApiService.kt` | `android.app.Service`/`Notification`/`PendingIntent` and generated `R.string` ids — a foreground service has no desktop equivalent | not applicable; the desktop server is an explicit CLI entry point (17) |
| `com/abrah/nightmare/api/FlowRequest.kt` (`applyOverrides`, `frameShaped`, `apiRange`, `checked`) | `Graph`, `NodeType`, `Widget`, `CropRect`, `ModelSpec` from the executor/graph layer | Phase 1 executor / Phase 2 GUI, after the registry is separated |

Tests that were evaluated and not copied, because they test a skipped source:

| Test | Why |
| --- | --- |
| `app/src/test/…/canvas/CanvasGeometryTest.kt` | needs `NODE_TYPES` (`CanvasGeometryTest.kt:4`) to lay out real node types |
| `app/src/test/…/canvas/CanvasStateTest.kt` | needs `NODE_TYPES` (`CanvasStateTest.kt:4`) |
| `app/src/test/…/canvas/WorkflowIoTest.kt` | `RobolectricTestRunner` + `NODE_TYPES` |
| `app/src/test/…/SchedulerTest.kt` | tests `scheduleByKey`/`launchKeyFor` from `Executor.kt`, not `npu/Scheduler.kt` |
| `app/src/test/…/BatchTest.kt`, `BatchParamsTest.kt`, `BatchChoicesTest.kt` | import `Graph`/`canvas.inpaintWorkflow` |
| `app/src/test/…/GgufWidenTest.kt` | uses `Family.FLUX2`/`Family.ZIMAGE` |
| `app/src/test/…/GgufImportTest.kt`, `Int8GgufTest.kt`, `Fp8Q8GgufTest.kt`, `SafetensorsGgufTest.kt` | `RobolectricTestRunner`, `androidx.test`, `org.json` |
| `app/src/test/…/api/ApiTest.kt` — `anA1111BodyMapsOntoTheModel` | needs `ModelCatalog.byId("sdxl_dreamshaper")`, `ModelSpec.fixedCanvas`/`isDit`, `ModelCatalog.schedulersFor` (`Family`) and `ApiCore` |
| `app/src/test/…/api/ApiTest.kt` — `theShortcutsReachThePromptAndTheSampler`, `aPictureBecomesTheImageNodesFile`, `aClipBecomesTheVideoNodesFile`, `aFrameMayHangOffThePhoto`, `anOutpaintFrameSetAloneTakesTheRenderShapeWithIt`, graph-level `everyMistakeIsNamedBeforeAnythingRuns` | need `Graph`, `NODE_TYPES`, `SdSampler`, `canvas.motionTransferWorkflow` — the node registry is not separated yet |
| `app/src/test/…/api/ApiTest.kt` — `onlyLocalPeersMayConnect` (mobile LAN/tailnet allow-list) | **superseded**, not trimmed: the desktop server binds loopback only, so the assertion is inverted in `MiniHttpTest.onlyLoopbackPeersMayConnect` and backed by a real socket bind test |

## Desktop service exposure

The copied HTTP shell is **off by default** and binds `127.0.0.1` only; `MiniHttp.isLoopbackPeer`
refuses every non-loopback peer as a second gate. This is a deliberate divergence from the mobile
allow-list, recorded above. The CLI entry point that starts the engine's A1111-compatible server,
the endpoint smoke test and real third-party client authentication are tickets 17 and 25 — they are
not implemented here.

## The finding: two files are the gate

Everything on the skip list funnels through two Android-coupled files:

1. **`Executor.kt`** owns `NodeType` (`:411`), `NODE_TYPES` (`:2722`) and `NodeCtx`
   (`:319`). The graph layer, the batch layer and the run scheduler are all typed
   in terms of them, so `Graph.kt`, `canvas/CanvasModel.kt`,
   `canvas/CanvasState.kt`, `Batch.kt` and `BatchParams.kt` — and the **157 test
   methods** that come with them (`CanvasStateTest` 83, `CanvasGeometryTest` 29,
   `BatchParamsTest` 15, `BatchTest` 12, `SchedulerTest` 10, `BatchChoicesTest`
   8) — cannot move until the *node registry* is separated from the *node
   implementations*. That is a real design step (the metadata and the `run`
   bodies currently live in the same `NodeType`), not a copy step, and it is the
   prerequisite for both Phase 1's executor and Phase 2's GUI.
2. **`ModelCatalog.kt`** owns `Family`, `ModelSpec` and `SelectedModel`. It is the
   second gate: it blocks `GgufWiden` (+5 tests), the family importers, and the
   model-catalog tests. It is why the model-catalog and import work is one
   ticket (13) rather than a file-at-a-time copy.

`GgufHeader.kt` is the one part of the import path that clears both gates, which
is why it is the whole of this first slice.

## Verifying a copy is still a copy

```bash
export JAVA_HOME=/home/chjlxxc/Android/jdk-17
cd desktop
timeout 900 ./gradlew --no-daemon :core:test --rerun-tasks 2>&1 | tee /tmp/desktop-core-test.log
```

Compare a copy against its origin (the first line is the provenance comment, so
skip it):

```bash
diff <(tail -n +2 desktop/core/src/main/kotlin/com/abrah/nightmare/GgufHeader.kt) \
     app/src/main/java/com/abrah/nightmare/GgufHeader.kt
```

An empty diff means the desktop copy is still the mobile file. A non-empty one
is expected once the desktop copy has been edited — that is the point of ADR-0001.
