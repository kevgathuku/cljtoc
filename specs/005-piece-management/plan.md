# Implementation Plan: Piece Management

**Branch**: `005-piece-management` | **Date**: 2026-02-21 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/005-piece-management/spec.md`

## Summary

Implement pure domain functions for BitTorrent piece management: immutable piece-status state machine, rarest-first piece selection, 16 KiB block decomposition, SHA-1 integrity verification, and endgame mode detection. All functions live in `dev.cljtoc.domain.pieces` with no I/O dependencies. Reuses `dev.cljtoc.domain.bencode/sha1-hash` for verification. Full clojure.spec coverage with generative tests.

## Technical Context

**Language/Version**: Clojure 1.11+ (JVM-based)
**Primary Dependencies**: None (pure domain — no core.async, no network). Reuses `dev.cljtoc.domain.bencode/sha1-hash` (within same layer).
**Storage**: N/A — all in-memory, no disk I/O
**Testing**: clojure.test + clojure.spec.alpha + test.check (same as existing test suite)
**Target Platform**: JVM (cross-platform)
**Project Type**: Single project (domain layer library component)
**Performance Goals**: All operations < 1ms for torrents up to 100,000 pieces (SC-001)
**Constraints**:
  - All functions MUST be pure (no side effects, no I/O imports)
  - State transitions MUST return new state without mutating input
  - Block size fixed at 16,384 bytes per BitTorrent convention
  - Must follow `{:ok value} / {:error :keyword :message string}` result pattern
  - Must include `s/def` specs and `s/fdef` with `:fn` invariants
**Scale/Scope**: Handle torrents with 1 to 100,000+ pieces; rarest-first over up to 200 peers

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Research Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ✅ PASS | All functions are pure transformations of in-memory data. No I/O imports. Lives in `dev.cljtoc.domain.*`. SHA-1 is a pure bytes→bytes computation. |
| **II. Explicit Effect Boundaries** | ✅ PASS | No effects in this feature. No network, disk, or time operations. The domain layer makes no assumptions about where data comes from. |
| **III. Crash-Only Supervision** | ⚪ N/A | Pure functions cannot crash in the supervision sense. No go blocks or concurrent processes. |
| **IV. No Hidden State** | ✅ PASS | All state transitions are `(state, event) → state` pure functions. No atoms, refs, or global vars. |
| **V. Testability Without I/O** | ✅ PASS | 100% testable with in-memory data. No network, disk, or timer access required at any point. |

**Pre-Research Gate Result**: ✅ ALL GATES PASS — Proceed to Phase 0 research

### Post-Design Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ✅ PASS | `pieces.clj` uses only `[clojure.spec.alpha]` and `[dev.cljtoc.domain.bencode]` — both domain-safe. No I/O imports. |
| **II. Explicit Effect Boundaries** | ✅ PASS | SHA-1 is computed via `bencode/sha1-hash` (pure JVM computation, not I/O). Coordination layer owns bitfield-to-set conversion. |
| **III. Crash-Only Supervision** | ⚪ N/A | Still N/A at Domain Layer. |
| **IV. No Hidden State** | ✅ PASS | `PieceState` record uses persistent sets. All transitions via `assoc`/`conj`/`disj`. No mutation. |
| **V. Testability Without I/O** | ✅ PASS | All test inputs are literal Clojure values. Generative tests use `gen/choose`, `gen/nat`, `gen/bytes`. Zero I/O required. |

**Post-Design Gate Result**: ✅ ALL GATES PASS — Design compliant with constitution

## Project Structure

### Documentation (this feature)

```text
specs/005-piece-management/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── api.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/
└── dev/
    └── cljtoc/
        └── domain/
            ├── bencode.clj       # existing — provides sha1-hash
            ├── torrent.clj       # existing — provides parsed piece hashes
            └── pieces.clj        # NEW — piece state machine, selection, blocks, verification

test/
└── dev/
    └── cljtoc/
        └── domain/
            ├── bencode_test.clj  # existing
            ├── torrent_test.clj  # existing
            └── pieces_test.clj   # NEW — comprehensive domain tests
```

**Structure Decision**: Single Clojure source file in the existing `dev.cljtoc.domain` namespace following the established domain layer pattern (bencode.clj, torrent.clj). No new namespaces or directories required beyond the source and test files.

## Planning Complete

**Status**: ✅ Phase 0 (Research) and Phase 1 (Design) Complete

### Generated Artifacts

| Artifact | Path | Description |
|----------|------|-------------|
| Implementation Plan | `plan.md` | This document — technical context, constitution check, structure |
| Research | `research.md` | Phase 0 — SHA-1 reuse, PieceState representation, rarest-first algorithm, error handling |
| Data Model | `data-model.md` | Phase 1 — PieceState, Block, VerificationResult, state machine diagram |
| API Contracts | `contracts/api.md` | Phase 1 — All function signatures, invariants, error handling, usage example |
| Quickstart | `quickstart.md` | Phase 1 — Developer usage guide with REPL examples and test patterns |

### Key Design Decisions

| Concern | Decision | Rationale |
|---------|----------|-----------|
| SHA-1 | Reuse `bencode/sha1-hash` | Already implemented; avoids duplication |
| PieceState sets | Clojure persistent sets | Immutable, no clone-on-mutate; fully serializable |
| Peer availability input | `#{piece-index}` sets | Keeps domain free of Java types; coordination converts |
| Rarest-first | Linear frequency count | O(C×P) — fast enough for typical swarm sizes |
| Block size | Fixed 16,384 constant | BEP 3 standard; not configurable |
| Endgame | Caller-supplied threshold | Default 20 recommended; separation of policy from mechanism |

### Dependencies

- **Depends On**: `002-bencode-parser` (`bencode/sha1-hash`, parsed torrent providing `piece-length`, `pieces`, `length`)
- **Required By**: `006-download-orchestration` (calls all functions; drives state transitions on wire events)

### Next Step: Task Breakdown

Run `/speckit.tasks` to generate the implementation task list:

```
/speckit.tasks
```

This will create `tasks.md` with actionable work items for implementation.

### Architecture Compliance

✅ **All constitution principles verified**:
- Pure functions only — no I/O, no side effects
- Domain layer placement — `dev.cljtoc.domain.pieces`
- Immutable state transitions — persistent sets, `assoc`/`conj`/`disj`
- 100% testable without I/O — all tests use in-memory Clojure values
