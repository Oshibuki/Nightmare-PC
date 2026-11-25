# A1111 client compatibility

This page records the Phase 1 certification of Nightmare Desktop's local HTTP
API against a **real, unmodified A1111 client**. The API itself is the vendored
engine's `sd-server`, started by `nightmare-desktop serve` with a forced
`--listen-ip 127.0.0.1`; the Kotlin CLI does not implement A1111 routes (tickets
17/24). A curl request is therefore not the certification — a client written
against A1111's API is.

## Certified path

| | |
| --- | --- |
| Client | SillyTavern 1.19.1 at `/home/chjlxxc/Applications/SillyTavern` |
| Unmodified | `git status --short` shows no tracked changes; the request path is `src/endpoints/stable-diffusion.js:297-350`, which POSTs to `/sdapi/v1/txt2img` |
| Client start | `node server.js --port 9351 --disableCsrf` (flags only, no source edits) |
| Local HTTP API | `nightmare-desktop serve --model ~/.cache/nightmare-desktop/models/sd15/v1-5-pruned-emaonly.safetensors --port 1234 --engine <ticket-07 worktree>/desktop/engine/build/bin/sd-server` |
| Client call | `POST http://127.0.0.1:9351/api/sd/generate` with `url=http://127.0.0.1:1234`, `prompt`, `negative_prompt`, `steps=1`, `cfg_scale=1.5`, `width=64`, `height=64`, `seed=1234`, `sampler_name=euler`, `scheduler=discrete`, `batch_size=1` |
| Result | HTTP 200 in 2.35 s; engine logged `generate_image completed in 2.34s` |

The response carried the three fields an A1111 client expects: `images`,
`parameters` (echoed request) and `info` (a JSON **string**, as the engine
writes it). `images[0]` decoded to a 64×64 RGB PNG, 9,990 bytes, with an
embedded parameters chunk naming seed 1234 and `v1-5-pruned-emaonly.safetensors`.
The client did not need source changes, a shim, or a different payload shape.

Discovery calls through the same unmodified client also worked:
`POST /api/sd/samplers` → 200 with the engine's sampler list, and
`POST /api/sd/models` → 200 with the single loaded checkpoint. The client's
generate path first GETs `/sdapi/v1/options`, which returned 200.

Evidence: `/tmp/ticket25-sd-server.log`, `/tmp/ticket25-sillytavern.log`,
`/tmp/ticket25-client-response.json`, `/tmp/ticket25-client-image.png`.

## Not covered

Certification is of the txt2img generation path, not of every A1111 feature the
client can send:

- `POST /sdapi/v1/options`, `GET /sdapi/v1/progress`, `POST /sdapi/v1/interrupt`,
  `GET /sdapi/v1/sd-vae` and `GET /sdapi/v1/sd-modules` all return 404 from this
  engine. Model switching, VAE discovery and interrupt are therefore not
  certified; only `GET /sdapi/v1/options` is available.
- The engine has no authentication: a deliberately wrong Basic `Authorization`
  header still got 200 from `/sdapi/v1/options`. An A1111 client configured
  with `--api-auth` credentials is not authenticated by this service.
- Client fields the engine does not read are silently ignored: `restore_faces`,
  `override_settings.*`, `forge_additional_modules`, `save_images`,
  `alwayson_scripts.ADetailer`, and `hr_second_pass_steps` (the engine reads
  `hr_steps`). They have no effect, rather than causing an error.
- `/sdapi/v1/sd-models` reports only the one checkpoint passed to `serve`; it
  is not a model catalogue. `/sdapi/v1/options` is read-only.
- Only the client's Node request path was exercised, not the browser UI, image
  post-processing, or a generation long enough to make progress/interrupt
  matter. No third-party client was modified to make the call succeed.

## Contract test guard

`cli/.../A1111ClientContractTest` covers the CLI-level subset with a fake
`sd-server`: an A1111-shaped POST reaches `/sdapi/v1/txt2img`, the response has
`images`/`parameters`/`info`, the base64 image decodes to a 64×64 PNG, the
request fields are forwarded, and the engine is started on loopback with the
selected port. All waits are bounded (JUnit 30 s, socket 5 s, ready-file 10 s,
fake server 12 s, future 20 s). It does not cover the gaps listed above; those
are documented here rather than asserted by a test that cannot see the real
engine's routes.
