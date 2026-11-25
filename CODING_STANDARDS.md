# Coding standards

Read during review, not implementation. This file holds the judgement calls a
guardrail cannot substitute for; mechanical checks live in the build and CI
(`.github/workflows/desktop.yml`, `desktop/verify-gui.sh`).

## GUI drawing: convert world coordinates at the DrawScope seam

**Trigger:** a diff draws geometry obtained from `desktop/core/.../canvas/` —
`Viewport`, `wirePath(from, to)`, or the `Pt` values they return — onto a Compose
`DrawScope`.

The canvas geometry layer is Compose-free and speaks **world coordinates**
(ADR-0006). `DrawScope` speaks **screen pixels**. The seam between them is
`deviceViewport.toScreen(...)`. Convert **every** point drawn — endpoints, curve
samples, hit markers alike — through that seam before using it as an `Offset`.

A world point used directly as an `Offset` looks correct only at scale=1 and
offset=0. Under zoom or pan the drawing diverges from both the node positions
and the `CanvasState.wireAt` hit geometry, so the wire appears detached from its
ports. Converting only the first point and reusing world points for the rest is
the same error.

**Review check:** confirm the conversion is applied to each drawn point, not
just the segment start; a computed-but-unused `toScreen` value beside a raw world
`Offset` is the tell (this exact bug shipped once and was caught only in review).

**Verification:** assertions cover a non-unit scale and a non-zero offset, not
just the identity viewport — see `WireScreenPathTest`.

Keep this a judgement rule: no linter can tell which space a float came from.
