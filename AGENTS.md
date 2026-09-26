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

## Project Overview

A BitTorrent client in Clojure implementing crash-only design with OTP-style supervision, pure domain logic, and explicit effect boundaries. Uses core.async for concurrency.

## Commands

```bash
# Run all tests
lein test

# Run a single test namespace
lein test dev.cljtoc.core-test

# Run a single test
lein test :only dev.cljtoc.core-test/test-name

# Start REPL
lein repl

# Build uberjar
lein uberjar

# Run the application
lein run
java -jar target/uberjar/dev.cljtoc-0.1.0-SNAPSHOT-standalone.jar
```

## Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.

## Clojure Parenthesis Repair

When delimiters go unbalanced, in order:

1. First try `clj-paren-repair <files>` (also runs cljfmt).
2. If the compiler still disagrees, the repair likely closed the wrong scope (symptom: `recur` tail-position errors far from the real gap) — revert the file (`git checkout -- <file>`) and re-apply the edits one at a time.
3. After each edit, run a paren-depth scan and `clj-kondo --lint <file>`: kondo pinpoints the exact unclosed opener (`Found an opening ( with no matching )`). Counting closers by eye is unreliable — one extra `)` early silently shifts every scope below it.
4. The compiler is the final arbiter: full `lein test` green means the structure is right.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

The tool automatically formats files with cljfmt when it processes them.

## Architecture

Four-layer architecture with strict dependency rules (lower layers cannot depend on higher):

```
Supervisor Layer   → lifecycle management, restart policies
         ↓
Coordination Layer → core.async channels, message routing, backpressure
         ↓
Protocol Layer     → bencode parsing, peer wire protocol, tracker protocol
         ↓
Domain Layer       → pure torrent logic (piece selection, state transitions, verification)
```

### Layer Rules

- **Domain Layer**: Pure functions only. No I/O, no core.async, no time/randomness. State transitions as `(state, event) → state`.
- **Protocol Layer**: Pure parsing/encoding. No network sockets, no channels.
- **Coordination Layer**: core.async flows connecting domain to effects. No domain mutation or business rules.
- **Supervisor Layer**: Owns all go blocks and channel lifetimes. Workers crash on error; supervisors restart them.

### Effect Ports

All side effects isolated behind injectable protocols:
- Network I/O (peer/tracker communication)
- Disk I/O (piece storage)
- Time (current time, delays)
- Randomness (peer selection jitter)

## Constitutional Principles

See `.specify/memory/constitution.md` for authoritative rules. Key points:

1. **Pure Domain**: Domain functions must be deterministic with no side effects
2. **Explicit Effects**: Effects behind protocols, injected at construction
3. **Crash-Only**: Workers crash on errors; supervisors handle restarts
4. **No Hidden State**: No global atoms/vars for app state; all state explicitly passed
5. **I/O-Free Testing**: 90%+ coverage without actual I/O; inject test doubles

## Sub-Feature Structure

Implementation is split into independently deliverable features in `specs/`:
- `002-bencode-parser` - Foundation: .torrent file parsing
- `003-tracker-protocol` - Peer discovery
- `004-peer-wire-protocol` - BitTorrent peer messages
- `005-piece-management` - Pure domain logic for pieces
- `006-download-orchestration` - End-to-end download coordination

Each feature has: `spec.md`, `plan.md`, `tasks.md`, `data-model.md`, `contracts/`

## Project Conventions

- Torrent/bencode data is binary — do not apply UTF-8 encoding/decoding; treat all byte arrays as raw binary
- Persisted state must decode back to runtime values: byte arrays are stored as tagged `{:cljtoc/bytes hex}` maps (see `dev.cljtoc.ports.disk/encode-state` / `decode-state`) so a resumed download carries real bytes into handshake and verification. Test the decoded value, not the on-disk shape — asserting the encoded form passes while resume is broken
- Pause/resume must be covered by a cycle test through the real port (temp dirs), not just the mock: mocks drift from the real envelope contract (e.g. `load-state` returning the download instead of `{:ok download}`)
- Extract shared test helpers (e.g., `to-bytes`) to `test/dev/cljtoc/test_utils.clj` rather than duplicating across test namespaces
- Co-locate function specs: `(s/fdef NAME ...)` directly after its `defn`, never in a trailing section. Data `s/def` specs must be defined before any fdef referencing them (the spec registry resolves at load time)
- Edge effects must handle port result envelopes: after planning moved state forward, a failed send/write strands it — unwind (requeue + drop bookkeeping) or fail explicitly, never discard `{:error ...}`
- Test doubles must mirror the real ports' error envelopes (e.g., `MockDiskPort` `:write-error`) so edge failure paths stay drivable; success-only mocks leave failure handling untestable
- Filesystem writes must containment-check the canonical path: lexical component validation does not stop a pre-existing symlink inside the target dir from redirecting the write outside it — resolve, create parents, then require the canonical file to stay under the canonical dir before opening (see `DiskPortImpl/resolve-contained`)
- Every declared output path must be initialized on write, not just paths with data: zero-length files produce no spans and would otherwise be missing from a completed download (see `DiskPortImpl/write-layout!`)

## Common Errors to Avoid

- When capping a double before casting to long, apply `min` first: `(long (min double-val cap))` not `(min (long double-val) cap)` — the latter overflows if `double-val` exceeds `Long/MAX_VALUE` (e.g., exponential backoff with large attempt numbers)
- When consolidating duplicated logic into one function, search every namespace including `*_impl` before claiming it is single-sourced (the fourth copy of the size math lived in `network-impl`)
- When unwrapping a channel result, bind the envelope first: `(:ok (async/<!! ...))`, never `assoc` onto the `{:ok ...}` map itself

## Code Review Gates

All PRs must verify:
1. Domain namespaces contain no I/O imports
2. Side effects only in designated effect namespaces
3. All go blocks have explicit supervisor ownership
4. No new global state introduced
5. New code has corresponding tests; domain tests are pure
6. Changed files are `cljfmt`-clean (`cljfmt fix` before committing, `cljfmt check` after)

## Memory

After implementing an issue and opening its PR, file the key decision addressed in the palace (wing `torrent_client_clj`, room `decisions`): issue number, branch/PR, what changed and why, plus any deviation from the issue as written.

<!-- MANUAL ADDITIONS END -->

## Agent skills

### Issue tracker

Issues tracked in GitHub Issues for kevgathuku/cljtoc. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout (`CONTEXT.md` + `docs/adr/` at repo root). See `docs/agents/domain.md`.

### PR review threads

- Reply per thread via `gh api repos/<owner>/<repo>/pulls/<number>/comments --input` with numeric `in_reply_to` JSON; `-f in_reply_to=<id>` sends a string and is rejected.
- The Copilot reviewer identity doesn't resolve via `--add-reviewer`; pushing the branch retriggers its pass.
