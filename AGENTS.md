# torrent-client-clj Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-02-17

## Active Technologies
- Clojure 1.11+ (JVM-based) + core.async for coordination,clojure.spec.alpha for validation (006-download-orchestration)
- File system for downloaded pieces, JSON/EDN for state persistence (006-download-orchestration)

- Clojure 1.11+ (JVM-based) + None (pure functions only, core.async not needed at this layer) (004-peer-wire-protocol)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Clojure 1.11+ (JVM-based)

## Code Style

Clojure 1.11+ (JVM-based): Follow standard conventions

## Recent Changes
- 006-download-orchestration: Added Clojure 1.11+ (JVM-based) + core.async for coordination,clojure.spec.alpha for validation

- 004-peer-wire-protocol: Added Clojure 1.11+ (JVM-based) + None (pure functions only, core.async not needed at this layer)

<!-- MANUAL ADDITIONS START -->

## Naming

Prefer human-readable names everywhere. Avoid single-letter variables;
use full words that say what the value is (`address-str` not `s`,
`host` / `port-str` not `h` / `p`, `colon-index` not `i`).

<!-- MANUAL ADDITIONS END -->

## Agent skills

### Issue tracker

Issues tracked in GitHub Issues for kevgathuku/cljtoc. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout (`CONTEXT.md` + `docs/adr/` at repo root). See `docs/agents/domain.md`.
