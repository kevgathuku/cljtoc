# CLI persistence through IDiskPort

All CLI byte movement (`save-state`/`load-state`/`delete-state` call sites in `core.clj`: pause, resume, status, stop, start) goes through the `IDiskPort` built in `make-ports`, so the CLI and orchestration share one transport, one filename scheme, and one error story. `cli.state` keeps only `default-state-dir`, `state-file-path`, and `load-most-recent` — dir-scanning by recency has no port equivalent, so the keeper scans while the port reads. Commands map a corrupt or missing record to nil (the long-standing missing-state UX); save/delete failures print and exit 1. Refines ADR-0009's two-adapter arrangement (issue #52, Slice B; Slice A deleted the dead surface in PR #51).

## Considered Options

- **Keep the cli.state transport alongside the port**: rejected — the filename scheme and error semantics (`nil`-swallow vs `:load-error`) drift per caller.
- **Move recency scanning into the port**: rejected — a new protocol method for one CLI fallback; the keeper scans, the port reads.
- **One shared literal for the filename scheme**: rejected — the port cannot depend on the CLI layer; the keeper names the scheme and a test pins the port's agreement with it.
