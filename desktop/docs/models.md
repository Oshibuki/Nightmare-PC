# Models

Model weights are the one thing this repository never stores. An SD1.5
checkpoint is 4 GB, every family has its own licence, and a `git status` that
has to explain a binary blob is a `git status` people stop reading. The
`.gitignore` already refuses `models/` anywhere in the tree, including
`desktop/models/`.

This page is the contract for **where weights go** and **how to get the SD1.5
baseline** that the end-to-end smoke test uses.

## Where weights live

| Location | Role |
| --- | --- |
| `~/.cache/nightmare-desktop/models` | default shared cache, outside the repo |
| `$NIGHTMARE_MODELS` | overrides the cache root when set |
| `desktop/models/` | repo-local scratch space, gitignored, for when you want weights next to the build |

The cache is shared on purpose: several git worktrees and the CI baseline all
read the same 4 GB file instead of each downloading their own. It mirrors the
engine cache (`~/.cache/nightmare-desktop/engine`, see
[`../engine/README.md`](../engine/README.md)).

Layout by family, so a path says what a file is:

```
~/.cache/nightmare-desktop/models/
├── sd15/v1-5-pruned-emaonly.safetensors
├── sdxl/…
├── flux/…
├── z-image/…
└── lora/…
```

## How the CLI finds a model

`txt2img` takes `--model <path>` and **nothing else**: there is no implicit
search, no default, and no environment lookup in Phase 0. A path that does not
exist fails immediately with exit code 1 before the engine is started, so a
typo costs a second rather than a model load.

`NIGHTMARE_MODELS` is the documented root for scripts and for the Phase 1 model
catalogue (ticket 13). The Phase 0 CLI does not read it — scripts should pass
`"$NIGHTMARE_MODELS/sd15/…"` explicitly rather than assume the CLI resolves it.

## SD1.5 baseline (the smoke model)

| | |
| --- | --- |
| file | `v1-5-pruned-emaonly.safetensors` |
| source | <https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5> |
| download | `https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors` |
| size | 4,265,146,304 bytes (≈ 4.0 GiB) |
| sha256 | `6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa` |
| licence | CreativeML Open RAIL-M — read it before redistributing anything generated from it |

```bash
MODELS="${NIGHTMARE_MODELS:-$HOME/.cache/nightmare-desktop/models}"
mkdir -p "$MODELS/sd15"
curl -L --fail --continue-at - \
  -o "$MODELS/sd15/v1-5-pruned-emaonly.safetensors" \
  https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors
```

`--continue-at -` resumes an interrupted download instead of restarting 4 GB;
`--fail` makes an HTTP error an error instead of an HTML file with a
`.safetensors` name. Check the result before blaming the engine:

```bash
ls -l "$MODELS/sd15/v1-5-pruned-emaonly.safetensors"   # expect 4265146304 bytes
sha256sum "$MODELS/sd15/v1-5-pruned-emaonly.safetensors"
# 6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa
```

The checksum is the one measured on the copy this machine's smoke run used; a
mismatch means a different revision, not a corrupt engine.

The file is a plain single-file SD1.5 checkpoint — the `.safetensors` the engine
reads directly. Quantised GGUF checkpoints and the SDXL / DiT families (which
need a separate text encoder and VAE) are later tickets; they go in their own
subdirectory of the same cache.

## Why this is the smoke model

Phase 0 acceptance requires one command from a clean checkout to a PNG on CPU.
SD1.5 is the smallest single-file family the engine supports, so it is the
cheapest honest proof that "edit engine → build → smoke" closes. See
[`../README.md`](../README.md#smoke-test-one-command-to-a-png) for the command.
