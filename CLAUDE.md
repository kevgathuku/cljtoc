# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

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
- `005-piece-selection` - Pure domain logic for pieces
- `006-download-orchestration` - End-to-end download coordination

Each feature has: `spec.md`, `plan.md`, `tasks.md`, `data-model.md`, `contracts/`

## Code Review Gates

All PRs must verify:
1. Domain namespaces contain no I/O imports
2. Side effects only in designated effect namespaces
3. All go blocks have explicit supervisor ownership
4. No new global state introduced
5. New code has corresponding tests; domain tests are pure

## Active Technologies
- Clojure 1.11+ (JVM-based) (003-tracker-protocol)
- N/A (stateless protocol parsing) (003-tracker-protocol)

## Recent Changes
- 003-tracker-protocol: Added Clojure 1.11+ (JVM-based)
