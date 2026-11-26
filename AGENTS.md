## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles map to `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

Desktop manual GUI launch variables, preflight and cache-free verification: [`desktop/docs/gui-launch-config.md`](desktop/docs/gui-launch-config.md).

### Local guardrail

Enable the lightweight pre-commit hook once per clone with `git config core.hooksPath .githooks` (it is local git config, not committed). It checks staged whitespace/conflict markers; the full build, JVM tests and fake CLI seams run in CI via `desktop/verify-gui.sh`.
